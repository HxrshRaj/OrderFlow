package com.orderflow.order.domain;

import com.orderflow.order.domain.event.OrderShipped;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Direct, no-mocks tests of the {@link Order} aggregate's own invariants: no repository, no
 * service, no Spring context — just the aggregate, so a failure here can only mean the
 * aggregate itself is wrong.
 */
class OrderTest {

    private Order confirmedOrder() {
        Order order = Order.place("ORD-TEST-1", UUID.randomUUID(), "cust-1");
        order.addItem("SKU-A", 1, BigDecimal.TEN);
        order.confirm();
        return order;
    }

    @Test
    void adding_an_item_while_placed_is_allowed() {
        Order order = Order.place("ORD-TEST-2", UUID.randomUUID(), "cust-1");

        order.addItem("SKU-A", 2, new BigDecimal("9.99"));

        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("19.98");
    }

    @Test
    void cannot_add_a_line_item_once_the_order_has_shipped() {
        Order order = confirmedOrder();
        order.ship();

        assertThatExceptionOfType(OrderItemsLockedException.class)
                .isThrownBy(() -> order.addItem("SKU-B", 1, BigDecimal.ONE))
                .withMessageContaining("ORD-TEST-1")
                .withMessageContaining("SHIPPED");

        assertThat(order.getItems())
                .as("the rejected item must not have been appended")
                .hasSize(1);
        assertThat(order.getItems().get(0).getSku()).isEqualTo("SKU-A");
    }

    @Test
    void cannot_add_a_line_item_to_a_confirmed_order_either() {
        Order order = confirmedOrder(); // status CONFIRMED, not yet shipped

        assertThatExceptionOfType(OrderItemsLockedException.class)
                .isThrownBy(() -> order.addItem("SKU-B", 1, BigDecimal.ONE));

        assertThat(order.getItems()).hasSize(1);
    }

    @Test
    void cannot_add_a_line_item_to_a_rejected_or_cancelled_order() {
        Order rejected = Order.place("ORD-TEST-3", UUID.randomUUID(), "cust-1");
        rejected.addItem("SKU-A", 1, BigDecimal.ONE);
        rejected.reject("no stock");

        assertThatExceptionOfType(OrderItemsLockedException.class)
                .isThrownBy(() -> rejected.addItem("SKU-B", 1, BigDecimal.ONE));

        Order cancelled = Order.place("ORD-TEST-4", UUID.randomUUID(), "cust-1");
        cancelled.addItem("SKU-A", 1, BigDecimal.ONE);
        cancelled.cancel();

        assertThatExceptionOfType(OrderItemsLockedException.class)
                .isThrownBy(() -> cancelled.addItem("SKU-B", 1, BigDecimal.ONE));
    }

    @Test
    void shipping_records_exactly_one_OrderShipped_event_and_draining_it_clears_it() {
        Order order = confirmedOrder();

        order.ship();

        List<Object> events = order.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(OrderShipped.class);
        OrderShipped event = (OrderShipped) events.get(0);
        assertThat(event.orderNumber()).isEqualTo("ORD-TEST-1");
        assertThat(event.customerId()).isEqualTo("cust-1");
        assertThat(event.reservationId()).isEqualTo(order.getReservationId());

        assertThat(order.pullDomainEvents())
                .as("events must be drained, not re-delivered on a second pull")
                .isEmpty();
    }

    @Test
    void confirming_or_rejecting_does_not_record_a_shipped_event() {
        Order confirmed = confirmedOrder();
        assertThat(confirmed.pullDomainEvents()).isEmpty();

        Order rejected = Order.place("ORD-TEST-5", UUID.randomUUID(), "cust-1");
        rejected.addItem("SKU-A", 1, BigDecimal.ONE);
        rejected.reject("no stock");
        assertThat(rejected.pullDomainEvents()).isEmpty();
    }
}
