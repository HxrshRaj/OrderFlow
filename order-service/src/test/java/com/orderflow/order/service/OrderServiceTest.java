package com.orderflow.order.service;

import com.orderflow.order.client.InsufficientStockException;
import com.orderflow.order.client.InventoryClient;
import com.orderflow.order.client.InventoryUnavailableException;
import com.orderflow.order.client.Shortfall;
import com.orderflow.order.domain.IllegalOrderStateException;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    OrderRepository orderRepository;
    @Mock
    InventoryClient inventoryClient;
    @Mock
    PlatformTransactionManager transactionManager;

    OrderService service;
    private final AtomicReference<Order> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OrderService(orderRepository, inventoryClient, transactionManager,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(orderRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
    }

    private PlaceOrderCommand oneLine() {
        return new PlaceOrderCommand("cust-1",
                List.of(new OrderLineCommand("SKU-A", 2, new BigDecimal("9.99"))));
    }

    @Test
    void placeOrder_confirms_when_inventory_reserves() {
        when(orderRepository.nextOrderSequence()).thenReturn(1000L);

        Order order = service.placeOrder(oneLine());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getOrderNumber()).isEqualTo("ORD-1000");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("19.98");
        verify(inventoryClient).reserve(any(), anyList());
    }

    @Test
    void placeOrder_rejects_when_inventory_reports_insufficient_stock() {
        when(orderRepository.nextOrderSequence()).thenReturn(1001L);
        doThrow(new InsufficientStockException(UUID.randomUUID(),
                List.of(new Shortfall("SKU-A", 2, 0, "INSUFFICIENT_STOCK"))))
                .when(inventoryClient).reserve(any(), anyList());

        Order order = service.placeOrder(oneLine());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectionReason()).contains("SKU-A").contains("available 0");
    }

    @Test
    void placeOrder_stays_placed_when_inventory_is_unreachable() {
        when(orderRepository.nextOrderSequence()).thenReturn(1002L);
        doThrow(new InventoryUnavailableException("boom", new RuntimeException()))
                .when(inventoryClient).reserve(any(), anyList());

        Order order = service.placeOrder(oneLine());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void placeOrder_stays_placed_when_circuit_breaker_is_open() {
        when(orderRepository.nextOrderSequence()).thenReturn(1003L);
        doThrow(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("inventory")))
                .when(inventoryClient).reserve(any(), anyList());

        Order order = service.placeOrder(oneLine());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void ship_commits_the_reservation_for_a_confirmed_order() {
        Order confirmed = confirmedOrder("ORD-2000");
        when(orderRepository.findByOrderNumber("ORD-2000")).thenReturn(Optional.of(confirmed));

        Order shipped = service.ship("ORD-2000");

        assertThat(shipped.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(inventoryClient).commit(confirmed.getReservationId());
    }

    @Test
    void ship_is_rejected_for_a_non_confirmed_order_without_calling_inventory() {
        Order placed = Order.place("ORD-2001", UUID.randomUUID(), "cust-1");
        placed.addItem("SKU-A", 1, BigDecimal.ONE);
        when(orderRepository.findByOrderNumber("ORD-2001")).thenReturn(Optional.of(placed));

        assertThatExceptionOfType(IllegalOrderStateException.class).isThrownBy(() -> service.ship("ORD-2001"));
        verify(inventoryClient, never()).commit(any());
    }

    @Test
    void cancel_releases_the_hold_for_a_confirmed_order() {
        Order confirmed = confirmedOrder("ORD-2002");
        when(orderRepository.findByOrderNumber("ORD-2002")).thenReturn(Optional.of(confirmed));

        Order cancelled = service.cancel("ORD-2002");

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryClient).release(confirmed.getReservationId());
    }

    @Test
    void cancel_is_rejected_for_a_shipped_order() {
        Order shipped = confirmedOrder("ORD-2003");
        shipped.ship();
        when(orderRepository.findByOrderNumber("ORD-2003")).thenReturn(Optional.of(shipped));

        assertThatExceptionOfType(IllegalOrderStateException.class).isThrownBy(() -> service.cancel("ORD-2003"));
        verify(inventoryClient, never()).release(any());
    }

    @Test
    void reconcile_confirms_a_previously_stuck_order_once_inventory_recovers() {
        Order stuck = Order.place("ORD-3000", UUID.randomUUID(), "cust-1");
        stuck.addItem("SKU-A", 1, BigDecimal.ONE);
        stored.set(stuck);
        when(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PLACED), any()))
                .thenReturn(List.of(stuck));

        int resolved = service.reconcileStaleOrders(Duration.ZERO);

        assertThat(resolved).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    private Order confirmedOrder(String number) {
        Order order = Order.place(number, UUID.randomUUID(), "cust-1");
        order.addItem("SKU-A", 1, new BigDecimal("5.00"));
        order.confirm();
        stored.set(order);
        return order;
    }
}
