package com.orderflow.selenium.support;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for OrderFlow UI tests: owns the WebDriver lifecycle and the handful of
 * page interactions every flow needs. Every wait here is an explicit, condition-based
 * {@link WebDriverWait} &mdash; there is no {@code Thread.sleep} anywhere in this suite.
 *
 * <p>The target instance is configurable, not hard-coded, so the same suite runs against a
 * local {@code docker compose} stack or a deployed environment:
 * <ul>
 *   <li>{@code -Dorderflow.baseUrl} / env {@code ORDERFLOW_BASE_URL} &mdash; the storefront
 *       (default {@code http://localhost:8088}, the compose gateway)</li>
 *   <li>{@code -Dorderflow.orderServiceUrl} / env {@code ORDER_SERVICE_URL} &mdash; the Order
 *       Service's own API, used only for the independent backend-state check in
 *       {@code OrderStatusUiTest} (default {@code http://localhost:8080})</li>
 *   <li>{@code -Dselenium.headless=false} to watch the browser locally</li>
 * </ul>
 */
public abstract class OrderFlowUiTest {

    protected static final String BASE_URL = resolve("orderflow.baseUrl", "ORDERFLOW_BASE_URL", "http://localhost:8088");
    protected static final String ORDER_SERVICE_URL =
            resolve("orderflow.orderServiceUrl", "ORDER_SERVICE_URL", "http://localhost:8080");
    private static final boolean HEADLESS =
            Boolean.parseBoolean(resolve("selenium.headless", "SELENIUM_HEADLESS", "true"));
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

    private static final AtomicLong SEQUENCE = new AtomicLong();

    protected WebDriver driver;
    protected WebDriverWait wait;

    @BeforeAll
    static void resolveDriver() {
        WebDriverManager.chromedriver().setup();
    }

    @BeforeEach
    void openStorefront() {
        ChromeOptions options = new ChromeOptions();
        if (HEADLESS) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
                "--window-size=1440,1000",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu");
        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, WAIT_TIMEOUT);

        driver.get(BASE_URL);
        wait.until(ExpectedConditions.presenceOfElementLocated(testId("customer-id-input")));
    }

    @AfterEach
    void closeBrowser() {
        if (driver != null) {
            driver.quit();
        }
    }

    // --- page interactions -----------------------------------------------------------

    /** A fresh id per test invocation, so each test's order list starts empty and isolated. */
    protected static String uniqueCustomerId(String label) {
        return "selenium-%s-%d-%d".formatted(label, System.currentTimeMillis(), SEQUENCE.incrementAndGet());
    }

    protected void setCustomerId(String customerId) {
        WebElement input = driver.findElement(testId("customer-id-input"));
        input.clear();
        input.sendKeys(customerId);
        waitForOrderCount(0); // this id is brand new: the order list must settle to empty
    }

    protected void setQuantity(String sku, int quantity) {
        WebElement qtyInput = wait.until(ExpectedConditions.elementToBeClickable(testId("qty-input-" + sku)));
        qtyInput.clear();
        qtyInput.sendKeys(String.valueOf(quantity));
    }

    protected void clickPlaceOrder() {
        WebElement button = wait.until(ExpectedConditions.elementToBeClickable(testId("place-order-btn")));
        button.click();
    }

    protected void waitForOrderCount(int expected) {
        wait.until(d -> orderRows().size() == expected);
    }

    /** Waits until the customer has exactly one order, then returns that row's element. */
    protected WebElement waitForSoleOrder() {
        waitForOrderCount(1);
        return orderRows().get(0);
    }

    protected String orderNumberOf(WebElement orderRow) {
        return orderRow.getAttribute("data-testid").replaceFirst("^order-", "");
    }

    /**
     * Waits until the sole order's status is one of {@code anyOf}, polling the live DOM
     * (never a cached element reference, so a React re-render can't cause staleness) and
     * returns whichever status was reached.
     */
    protected String waitForSoleOrderStatusIn(Set<String> anyOf) {
        return wait.until(d -> {
            List<WebElement> rows = orderRows();
            if (rows.size() != 1) {
                return null;
            }
            String status = rows.get(0).findElement(testId("order-status")).getText().trim();
            return anyOf.contains(status) ? status : null;
        });
    }

    protected void clickShipOnSoleOrder() {
        WebElement button = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-testid=orders-list] li [data-testid=ship-btn]")));
        button.click();
    }

    private List<WebElement> orderRows() {
        return driver.findElements(By.cssSelector("[data-testid=orders-list] li"));
    }

    private static By testId(String value) {
        return By.cssSelector("[data-testid='" + value + "']");
    }

    private static String resolve(String systemProperty, String envVar, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
