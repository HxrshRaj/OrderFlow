package com.orderflow.selenium;

import com.orderflow.selenium.support.OrderFlowUiTest;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real user flow #1: browse the catalogue, place an order for an in-stock item, and see it
 * confirmed. Drives the actual rendered storefront end to end (React -&gt; nginx gateway -&gt;
 * Order Service -&gt; Inventory Service -&gt; Postgres) &mdash; nothing here is mocked.
 */
class PlaceOrderUiTest extends OrderFlowUiTest {

    @Test
    void placing_an_order_for_an_in_stock_item_confirms_it() {
        setCustomerId(uniqueCustomerId("place"));

        // SKU-CABLE is seeded with 100 units; one unit should always be confirmable.
        setQuantity("SKU-CABLE", 1);
        clickPlaceOrder();

        String status = waitForSoleOrderStatusIn(Set.of("CONFIRMED", "REJECTED"));
        assertThat(status).as("a 1-unit order for a well-stocked item should be confirmed").isEqualTo("CONFIRMED");

        WebElement order = waitForSoleOrder();
        assertThat(order.getText()).contains("SKU-CABLE");
        assertThat(orderNumberOf(order)).startsWith("ORD-");
    }
}
