package com.orderflow.order.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Item-to-item recommendations from order history ("customers who bought X also bought Y").
 *
 * <p>For the SKUs in the cart (the <em>seeds</em>), candidates are ranked by how strongly their
 * order set overlaps each seed's order set (Jaccard similarity, summed across seeds), ignoring
 * pairs seen in fewer than {@code minSupport} orders because one coincidence is not a pattern.
 * If that yields fewer than {@code limit} results (new catalogue, empty cart), the list is
 * topped up with the most-ordered SKUs and labelled {@code POPULAR} so callers can tell the
 * difference.
 *
 * <p>This service has no stock knowledge: stock belongs to the Inventory Service, so the
 * storefront filters out sold-out suggestions itself.
 */
@Service
public class RecommendationService {

    static final int MAX_LIMIT = 20;
    /** Never an order-item SKU; lets {@code NOT IN (...)} be valid when nothing is excluded. */
    private static final String NO_SKU = "\u0000";

    private final RecommendationRepository repository;
    private final int minSupport;

    public RecommendationService(RecommendationRepository repository,
                                 @Value("${recommendations.min-support:2}") int minSupport) {
        this.repository = repository;
        this.minSupport = Math.max(1, minSupport);
    }

    @Transactional(readOnly = true)
    public List<Recommendation> recommend(List<String> skus, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Set<String> seeds = new LinkedHashSet<>();
        if (skus != null) {
            skus.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).forEach(seeds::add);
        }

        List<Recommendation> result = new ArrayList<>();
        if (!seeds.isEmpty()) {
            result.addAll(repository.boughtTogether(seeds, minSupport, bounded));
        }
        if (result.size() < bounded) {
            Set<String> excluded = new LinkedHashSet<>(seeds);
            result.stream().map(Recommendation::sku).forEach(excluded::add);
            if (excluded.isEmpty()) {
                excluded.add(NO_SKU);
            }
            result.addAll(repository.popular(excluded, bounded - result.size()));
        }
        return result;
    }
}
