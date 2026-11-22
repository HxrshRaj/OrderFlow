package com.orderflow.order.storage;

import com.orderflow.order.domain.OrderItem;
import com.orderflow.order.domain.event.OrderConfirmed;

import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

/**
 * Renders a receipt body as JSON, as a pure function with no Spring/AWS dependency — kept
 * separate from {@link ReceiptStorageListener} specifically so the JSON shape can be unit-tested
 * ({@code ReceiptJsonTest}) without a Spring context, a database, or a mocked S3Client.
 */
final class ReceiptJson {

    private ReceiptJson() {
    }

    static String render(OrderConfirmed event, List<OrderItem> items, Instant confirmedAt) {
        StringJoiner lineItems = new StringJoiner(",", "[", "]");
        for (OrderItem item : items) {
            lineItems.add("""
                    {"sku":"%s","quantity":%d,"unitPrice":%s}""".formatted(
                    escape(item.getSku()), item.getQuantity(), item.getUnitPrice().toPlainString()));
        }
        return """
                {
                  "orderNumber": "%s",
                  "reservationId": "%s",
                  "customerId": "%s",
                  "totalAmount": %s,
                  "confirmedAt": "%s",
                  "items": %s
                }""".formatted(
                escape(event.orderNumber()),
                event.reservationId(),
                escape(event.customerId()),
                event.totalAmount().toPlainString(),
                confirmedAt,
                lineItems);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
