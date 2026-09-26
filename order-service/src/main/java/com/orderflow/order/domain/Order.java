package com.orderflow.order.domain;

import com.orderflow.order.domain.event.OrderShipped;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The order aggregate — the consistency boundary for the Order Management bounded context (see
 * {@code docs/DDD.md}). State transitions and the invariants around them are enforced here
 * rather than in the service, so an illegal move (e.g. shipping a REJECTED order, or adding a
 * line item after the order has shipped) fails the same way regardless of which caller — today
 * or in the future — attempts it.
 *
 * <pre>
 *   PLACED --reserve ok--> CONFIRMED --ship--> SHIPPED
 *     |                        |
 *     | reserve 409            | cancel
 *     v                        v
 *   REJECTED               CANCELLED
 * </pre>
 *
 * {@link OrderItem} is not its own aggregate: it has no repository, no identity outside this
 * order, and is only ever constructed through {@link #addItem}. {@code ship()} additionally
 * records an {@link OrderShipped} domain event, drained by the application layer via
 * {@link #pullDomainEvents()} once the transition is durably persisted.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, updatable = false)
    private String orderNumber;

    @Column(name = "reservation_id", nullable = false, unique = true, updatable = false)
    private UUID reservationId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "rejection_reason", length = 512)
    private String rejectionReason;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private BigDecimal totalAmount;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderItem> items = new ArrayList<>();

    /**
     * Domain events recorded by this aggregate but not yet handed to the application layer.
     * Never persisted (JPA would have no idea how to map a heterogeneous event list) and never
     * exposed directly — only {@link #pullDomainEvents()} can drain it.
     */
    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
        // for JPA
    }

    private Order(String orderNumber, UUID reservationId, String customerId) {
        this.orderNumber = orderNumber;
        this.reservationId = reservationId;
        this.customerId = customerId;
        this.status = OrderStatus.PLACED;
        this.totalAmount = BigDecimal.ZERO;
    }

    /** Start a new order in PLACED. Lines are added with {@link #addItem}. */
    public static Order place(String orderNumber, UUID reservationId, String customerId) {
        return new Order(orderNumber, reservationId, customerId);
    }

    /**
     * Add a line item while the order is still being composed.
     *
     * @throws OrderItemsLockedException if the order has moved past {@code PLACED} — items are
     *                                    locked in the instant reservation is attempted, so this
     *                                    can never happen "after the order has shipped" (or been
     *                                    confirmed, rejected, or cancelled) no matter who calls it
     */
    public void addItem(String sku, int quantity, BigDecimal unitPrice) {
        if (status != OrderStatus.PLACED) {
            throw new OrderItemsLockedException(orderNumber, status);
        }
        OrderItem item = new OrderItem(this, sku, quantity, unitPrice);
        this.items.add(item);
        this.totalAmount = this.totalAmount.add(item.lineTotal());
    }

    public void confirm() {
        requireStatus(OrderStatus.PLACED, "confirm");
        this.status = OrderStatus.CONFIRMED;
        this.rejectionReason = null;
    }

    public void reject(String reason) {
        requireStatus(OrderStatus.PLACED, "reject");
        this.status = OrderStatus.REJECTED;
        this.rejectionReason = truncate(reason);
    }

    public void ship() {
        requireStatus(OrderStatus.CONFIRMED, "ship");
        this.status = OrderStatus.SHIPPED;
        domainEvents.add(new OrderShipped(orderNumber, reservationId, customerId));
    }

    public void cancel() {
        if (status != OrderStatus.PLACED && status != OrderStatus.CONFIRMED) {
            throw new IllegalOrderStateException(orderNumber, status, "cancel");
        }
        this.status = OrderStatus.CANCELLED;
    }

    public boolean isAwaitingInventory() {
        return status == OrderStatus.PLACED;
    }

    /**
     * Drains and returns every domain event recorded since the last call. The application
     * layer calls this after a mutation has been durably persisted, then publishes whatever
     * comes back — so a listener only ever hears about facts that actually happened.
     */
    public List<Object> pullDomainEvents() {
        List<Object> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    private void requireStatus(OrderStatus expected, String action) {
        if (this.status != expected) {
            throw new IllegalOrderStateException(orderNumber, status, action);
        }
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public long getVersion() {
        return version;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
