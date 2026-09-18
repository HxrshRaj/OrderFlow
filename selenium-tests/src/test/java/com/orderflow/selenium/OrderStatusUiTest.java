package com.orderflow.selenium;

import com.orderflow.selenium.support.ApiProbe;
import com.orderflow.selenium.support.OrderFlowUiTest;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real user flow #3: place an order, ship it from the UI, and confirm the status shown on
 * screen agrees with the Order Service's own record &mdash; queried directly over HTTP, not
 * read back out of the same DOM. The browser and {@link ApiProbe} are two independent paths
 * to the server, so their agreement is a genuine end-to-end check, not a tautology.
 */
class OrderStatusUiTest extends OrderFlowUiTest {

    @Test
    void shipping_an_order_updates_the_ui_and_matches_the_backend_record() {
        setCustomerId(uniqueCustomerId("status"));

        setQuantity("SKU-CABLE", 1);
        clickPlaceOrder();

        String placedStatus = waitForSoleOrderStatusIn(Set.of("CONFIRMED", "REJECTED"));
        assertThat(placedStatus).isEqualTo("CONFIRMED");

        WebElement order = waitForSoleOrder();
        String orderNumber = orderNumberOf(order);

        clickShipOnSoleOrder();

        String shippedStatus = waitForSoleOrderStatusIn(Set.of("SHIPPED"));
        assertThat(shippedStatus).isEqualTo("SHIPPED");

        String backendStatus = ApiProbe.fetchOrderStatus(ORDER_SERVICE_URL, orderNumber);
        assertThat(backendStatus)
                .as("the Order Service's own record for %s must agree with what the UI renders", orderNumber)
                .isEqualTo("SHIPPED");
    }
}
