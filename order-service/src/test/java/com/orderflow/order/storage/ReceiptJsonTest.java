package com.orderflow.order.storage;

import com.orderflow.order.domain.event.OrderConfirmed;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReceiptJson is package-private on purpose (a pure rendering helper, no reason to be public
 * API) — this test lives in the same package specifically so it can call it directly, same as
 * any other same-package test in this codebase.
 */
class ReceiptJsonTest {

    @Test
    void renders_order_fields_and_an_empty_item_list() {
        OrderConfirmed event = new OrderConfirmed("ORD-1", UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "cust-7", new BigDecimal("149.98"));
        Instant confirmedAt = Instant.parse("2026-01-01T00:00:00Z");

        String json = ReceiptJson.render(event, List.of(), confirmedAt);

        assertThat(json).contains("\"orderNumber\": \"ORD-1\"")
                .contains("\"customerId\": \"cust-7\"")
                .contains("\"totalAmount\": 149.98")
                .contains("\"confirmedAt\": \"2026-01-01T00:00:00Z\"")
                .contains("\"items\": []");
    }

    @Test
    void escapes_quotes_and_backslashes_in_customer_id() {
        OrderConfirmed event = new OrderConfirmed("ORD-2", UUID.randomUUID(), "cust-\"weird\"\\name",
                BigDecimal.ZERO);

        String json = ReceiptJson.render(event, List.of(), Instant.EPOCH);

        assertThat(json).contains("cust-\\\"weird\\\"\\\\name");
    }
}
