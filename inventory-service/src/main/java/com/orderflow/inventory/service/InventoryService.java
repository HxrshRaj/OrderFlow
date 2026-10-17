package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.repository.InventoryItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Read side of the catalogue plus the <em>cold</em> write path: admin create and restock/adjust.
 *
 * <p>{@link #adjustStock(String, int)} is the one place we do a genuine read-modify-write on
 * the {@link InventoryItem} entity, so it is protected by JPA optimistic locking
 * ({@code @Version}). A version clash throws {@link ObjectOptimisticLockingFailureException};
 * we retry the whole transaction a bounded number of times with jittered backoff before
 * giving up with {@link ConcurrentAdjustmentException}.
 *
 * <p>The hot path (reserve / commit / release) does not live here — see
 * {@link ReservationService}, which never loads the entity to change quantities.
 */
@Service
public class InventoryService {

    static final int MAX_SEARCH_RESULTS = 50;
    /** Below this, trigram word similarity is noise ("xyz" would otherwise match everything). */
    static final double MIN_WORD_SIMILARITY = 0.55;

    private final InventoryItemRepository repository;
    private final TransactionTemplate txTemplate;
    private final int adjustMaxRetries;

    public InventoryService(InventoryItemRepository repository,
                            PlatformTransactionManager transactionManager,
                            @Value("${inventory.reservation.adjust-max-retries:3}") int adjustMaxRetries) {
        this.repository = repository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.adjustMaxRetries = Math.max(1, adjustMaxRetries);
    }

    public List<InventoryItem> listAll() {
        return txTemplate.execute(status -> repository.findAll(Sort.by("sku")));
    }

    /**
     * Search the catalogue by name or SKU, tolerating prefixes and small typos. An empty query
     * returns the first {@code limit} items by name. {@code limit} is clamped to 1..50.
     */
    public List<InventoryItem> search(String rawQuery, boolean inStockOnly, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_SEARCH_RESULTS);
        SearchQuery query = SearchQuery.parse(rawQuery);
        return txTemplate.execute(status -> {
            if (query.isEmpty()) {
                return repository.findAll(Sort.by("name")).stream()
                        .filter(item -> !inStockOnly || item.getAvailableQuantity() > 0)
                        .limit(bounded)
                        .toList();
            }
            return repository.search(query.text(), query.tsQuery(), MIN_WORD_SIMILARITY, inStockOnly, bounded);
        });
    }

    public InventoryItem getBySku(String sku) {
        return txTemplate.execute(status -> repository.findBySku(sku)
                .orElseThrow(() -> new InventoryItemNotFoundException(sku)));
    }

    public InventoryItem create(String sku, String name, int quantity) {
        return txTemplate.execute(status -> {
            repository.findBySku(sku).ifPresent(existing -> {
                throw new DuplicateSkuException(sku);
            });
            return repository.save(new InventoryItem(sku, name, quantity));
        });
    }

    /**
     * Change on-hand stock by {@code delta}. Positive restocks; negative corrects downward
     * (never below zero). Retries transparently on optimistic-lock conflicts.
     */
    public InventoryItem adjustStock(String sku, int delta) {
        for (int attempt = 1; ; attempt++) {
            try {
                return txTemplate.execute(status -> {
                    InventoryItem item = repository.findBySku(sku)
                            .orElseThrow(() -> new InventoryItemNotFoundException(sku));
                    item.applyDelta(delta);
                    // Flush inside the transaction so a stale @Version surfaces here as a
                    // retryable failure rather than at commit time.
                    return repository.saveAndFlush(item);
                });
            } catch (ObjectOptimisticLockingFailureException conflict) {
                if (attempt >= adjustMaxRetries) {
                    throw new ConcurrentAdjustmentException(sku, attempt);
                }
                backoff(attempt);
            }
        }
    }

    private static void backoff(int attempt) {
        long base = 10L * attempt;
        long jitter = ThreadLocalRandom.current().nextLong(base + 1);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off a stock adjustment retry", e);
        }
    }
}
