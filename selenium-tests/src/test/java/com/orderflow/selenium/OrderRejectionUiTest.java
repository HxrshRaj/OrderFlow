package com.orderflow.selenium;

import com.orderflow.selenium.support.OrderFlowUiTest;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real user flow #2: request more units than are in stock and confirm the UI surfaces a
 * real, specific error &mdash; not a generic failure. Ordering a huge quantity guarantees
 * rejection regardless of how much of {@code SKU-LASTUNIT} earlier test runs left in stock,
 * which is what actually happens server-side: the Inventory Service's atomic reserve check
 * rejects it and reports exactly why (see OrderFlow's reservation concurrency design).
 *
 * <p><b>Known issue (filed in JIRA, see docs/jira-integration/README.md):</b> {@code App.jsx}
 * sets the top-of-page banner to the rejection message, then immediately calls
 * {@code refresh()}, whose success path unconditionally does {@code setError(null)} -
 * wiping the banner before a user can read it (and again on every 3s poll after). That is a
 * real defect this suite found, not a flaky wait: no amount of waiting makes the banner
 * text stick around, because the app clears it a moment after setting it. Fixing it is a
 * frontend behaviour change outside this addition's scope, so this test asserts on the
 * signal that IS reliable - the rejection reason persisted on the order card itself, which
 * is driven by the polled order list rather than the racy {@code error} state - and leaves
 * the banner assertion out rather than pin a flaky wait on a known-broken indicator.
 */
class OrderRejectionUiTest extends OrderFlowUiTest {

    private static final int QUANTITY_GUARANTEED_TO_EXCEED_STOCK = 1_000_000;

    @Test
    void ordering_far_more_than_available_stock_shows_a_specific_rejection_on_the_order_card() {
        setCustomerId(uniqueCustomerId("reject"));

        setQuantity("SKU-LASTUNIT", QUANTITY_GUARANTEED_TO_EXCEED_STOCK);
        clickPlaceOrder();

        String status = waitForSoleOrderStatusIn(Set.of("CONFIRMED", "REJECTED"));
        assertThat(status).as("a 1,000,000-unit order can never be satisfied").isEqualTo("REJECTED");

        WebElement reason = wait.until(d -> {
            WebElement el = d.findElement(By.cssSelector("[data-testid=orders-list] li [data-testid=rejection-reason]"));
            return el.isDisplayed() ? el : null;
        });
        assertThat(reason.getText())
                .as("the order card should name the SKU and requested quantity that could not be held")
                .contains("SKU-LASTUNIT")
                .contains(String.valueOf(QUANTITY_GUARANTEED_TO_EXCEED_STOCK));
    }
}
