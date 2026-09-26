package com.orderflow.order.service;

import com.orderflow.order.client.InsufficientStockException;
import com.orderflow.order.client.InventoryClient;
import com.orderflow.order.client.InventoryUnavailableException;
import com.orderflow.order.client.dto.ReservationLinePayload;
import com.orderflow.order.domain.IllegalOrderStateException;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderItem;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owns the order lifecycle and its coordination with the Inventory Service.
 *
 * <h2>Why the network call sits outside the transaction</h2>
 * The order is persisted as {@code PLACED} in one short transaction. The reservation call to
 * Inventory then happens with <em>no</em> transaction (and no database connection) held open,
 * and the result is applied in a second short transaction. A slow Inventory Service therefore
 * never pins a database connection.
 *
 * <h2>Failure handling</h2>
 * <ul>
 *   <li>Reserved &rarr; {@code CONFIRMED}.</li>
 *   <li>HTTP 409 &rarr; {@code REJECTED} with the shortfall summary. Final.</li>
 *   <li>Timeout / 5xx / open circuit &rarr; the order stays {@code PLACED}; the
 *       {@code PendingOrderReconciler} retries later. The retry is safe because it reuses the
 *       order's {@code reservationId}, which the Inventory Service deduplicates.</li>
 * </ul>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orderRepository,
                        InventoryClient inventoryClient,
                        PlatformTransactionManager transactionManager,
                        Clock clock,
                        ApplicationEventPublisher events) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.events = events;
    }

    public Order placeOrder(PlaceOrderCommand command) {
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }

        Order order = txTemplate.execute(status -> {
            long sequence = orderRepository.nextOrderSequence();
            Order draft = Order.place("ORD-" + sequence, UUID.randomUUID(), command.customerId());
            command.lines().forEach(line -> draft.addItem(line.sku(), line.quantity(), line.unitPrice()));
            return orderRepository.save(draft);
        });

        return resolveReservation(order);
    }

    public Order ship(String orderNumber) {
        Order order = requireOrder(orderNumber);
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new IllegalOrderStateException(orderNumber, order.getStatus(), "ship");
        }
        inventoryClient.commit(order.getReservationId());
        return transition(order.getId(), Order::ship);
    }

    public Order cancel(String orderNumber) {
        Order order = requireOrder(orderNumber);
        if (order.getStatus() != OrderStatus.PLACED && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new IllegalOrderStateException(orderNumber, order.getStatus(), "cancel");
        }
        // Safe whether or not a hold exists: the Inventory Service treats release of an
        // unknown reservation as a no-op.
        inventoryClient.release(order.getReservationId());
        return transition(order.getId(), Order::cancel);
    }

    public Order getByNumber(String orderNumber) {
        return requireOrder(orderNumber);
    }

    public List<Order> listByCustomer(String customerId) {
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    /**
     * Retry the reservation for every order still stuck in {@code PLACED} past {@code staleAfter}.
     *
     * @return how many stale orders reached a terminal-ish state (CONFIRMED or REJECTED)
     */
    public int reconcileStaleOrders(Duration staleAfter) {
        Instant cutoff = clock.instant().minus(staleAfter);
        List<Order> stale = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PLACED, cutoff);
        int resolved = 0;
        for (Order order : stale) {
            try {
                inventoryClient.reserve(order.getReservationId(), toPayload(order));
                transition(order.getId(), Order::confirm);
                resolved++;
                log.info("reconciled order {} -> CONFIRMED", order.getOrderNumber());
            } catch (InsufficientStockException rejected) {
                transition(order.getId(), o -> o.reject(rejected.summary()));
                resolved++;
                log.info("reconciled order {} -> REJECTED ({})", order.getOrderNumber(), rejected.summary());
            } catch (InventoryUnavailableException | CallNotPermittedException stillDown) {
                log.debug("order {} still awaiting inventory: {}", order.getOrderNumber(), stillDown.toString());
            }
        }
        return resolved;
    }

    private Order resolveReservation(Order order) {
        try {
            inventoryClient.reserve(order.getReservationId(), toPayload(order));
            return transition(order.getId(), Order::confirm);
        } catch (InsufficientStockException rejected) {
            log.info("order {} rejected: {}", order.getOrderNumber(), rejected.summary());
            return transition(order.getId(), o -> o.reject(rejected.summary()));
        } catch (InventoryUnavailableException | CallNotPermittedException unavailable) {
            log.warn("order {} left PLACED; inventory unavailable: {}",
                    order.getOrderNumber(), unavailable.toString());
            return order;
        }
    }

    /**
     * Applies a state-transition method to the order and persists it, then publishes whatever
     * domain events that transition recorded (see {@link Order#pullDomainEvents()}). Publishing
     * happens from inside this same transaction, so a {@code @TransactionalEventListener}
     * bound to {@code AFTER_COMMIT} only ever fires once the transition is durably saved.
     */
    private Order transition(Long orderId, Consumer<Order> mutation) {
        return txTemplate.execute(status -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
            mutation.accept(order);
            Order saved = orderRepository.save(order);
            order.pullDomainEvents().forEach(events::publishEvent);
            return saved;
        });
    }

    private Order requireOrder(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));
    }

    private static List<ReservationLinePayload> toPayload(Order order) {
        return order.getItems().stream()
                .map(item -> new ReservationLinePayload(item.getSku(), item.getQuantity()))
                .toList();
    }
}
