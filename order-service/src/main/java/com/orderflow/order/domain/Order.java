package com.orderflow.order.domain;

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
 * The order aggregate. State transitions are enforced here rather than in the service, so an
 * illegal move (e.g. shipping a REJECTED order) fails the same way from any caller.
 *
 * <pre>
 *   PLACED --reserve ok--> CONFIRMED --ship--> SHIPPED
 *     |                        |
 *     | reserve 409            | cancel
 *     v                        v
 *   REJECTED               CANCELLED
 * </pre>
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

    public void addItem(String sku, int quantity, BigDecimal unitPrice) {
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
