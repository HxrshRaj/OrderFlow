package com.orderflow.api;

import org.testng.annotations.Test;

import static com.orderflow.api.TestData.available;
import static com.orderflow.api.TestData.createItem;
import static com.orderflow.api.TestData.orderBody;
import static com.orderflow.api.TestData.orders;
import static com.orderflow.api.TestData.reserved;
import static com.orderflow.api.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

public class OrderApiTest {

    private static String place(String customer, String sku, int qty, double price) {
        return orders().body(orderBody(customer, sku, qty, price)).post("/api/v1/orders")
                .then().statusCode(201).extract().path("orderNumber");
    }

    @Test(description = "A fillable order is CONFIRMED, totalled correctly, and holds the stock")
    public void confirmedOrderReservesStock() {
        String sku = createItem("Order Probe", 10);
        orders().body(orderBody("api-cust-1", sku, 3, 19.99)).post("/api/v1/orders")
                .then().statusCode(201)
                .body("status", equalTo("CONFIRMED"))
                .body("reservationId", notNullValue())
                .body("totalAmount", equalTo(59.97f))
                .body("items.size()", equalTo(1));
        assertThat(available(sku)).isEqualTo(7);
        assertThat(reserved(sku)).isEqualTo(3);
    }

    @Test(description = "An order larger than stock is REJECTED with a reason and stock is unchanged")
    public void oversizedOrderIsRejectedWithoutTouchingStock() {
        String sku = createItem("Scarce Probe", 2);
        orders().body(orderBody("api-cust-2", sku, 5, 10.0)).post("/api/v1/orders")
                .then().statusCode(201)
                .body("status", equalTo("REJECTED"))
                .body("rejectionReason", notNullValue());
        assertThat(available(sku)).isEqualTo(2);
        assertThat(reserved(sku)).isZero();
    }

    @Test(description = "Cancelling a CONFIRMED order releases the hold")
    public void cancelReleasesStock() {
        String sku = createItem("Cancel Probe", 4);
        String number = place("api-cust-3", sku, 4, 5.0);
        assertThat(available(sku)).isZero();
        orders().post("/api/v1/orders/{n}/cancel", number).then().statusCode(200).body("status", equalTo("CANCELLED"));
        assertThat(available(sku)).isEqualTo(4);
        assertThat(reserved(sku)).isZero();
    }

    @Test(description = "Shipping commits the reservation: reserved stock leaves the shelf for good")
    public void shipCommitsReservation() {
        String sku = createItem("Ship Probe", 6);
        String number = place("api-cust-4", sku, 2, 8.0);
        orders().post("/api/v1/orders/{n}/ship", number).then().statusCode(200).body("status", equalTo("SHIPPED"));
        assertThat(reserved(sku)).isZero();
        assertThat(available(sku)).isEqualTo(4);
    }

    @Test(description = "A shipped order cannot be cancelled: 409 and stock unchanged")
    public void shippedOrderCannotBeCancelled() {
        String sku = createItem("NoCancel Probe", 3);
        String number = place("api-cust-5", sku, 1, 3.0);
        orders().post("/api/v1/orders/{n}/ship", number).then().statusCode(200);
        orders().post("/api/v1/orders/{n}/cancel", number)
                .then().statusCode(409).body("title", equalTo("Illegal order state transition"));
        assertThat(available(sku)).isEqualTo(2);
    }

    @Test(description = "An unknown order number is 404")
    public void unknownOrderIs404() {
        orders().get("/api/v1/orders/{n}", "ORD-NOPE-" + uniqueSku()).then().statusCode(404);
    }

    @Test(description = "Invalid order bodies fail validation with 400")
    public void invalidOrdersAre400() {
        orders().body("{\"customerId\":\"\",\"lines\":[]}").post("/api/v1/orders")
                .then().statusCode(400).body("title", equalTo("Validation error"));
        String sku = createItem("Zero Probe", 1);
        orders().body(orderBody("api-cust-6", sku, 0, 1.0)).post("/api/v1/orders")
                .then().statusCode(400);
    }

    @Test(description = "A customer's orders are listed newest first")
    public void listsCustomerOrdersNewestFirst() {
        String customer = "api-list-" + uniqueSku();
        String sku = createItem("List Probe", 10);
        String first = place(customer, sku, 1, 1.0);
        String second = place(customer, sku, 1, 1.0);
        orders().queryParam("customerId", customer).get("/api/v1/orders")
                .then().statusCode(200)
                .body("orderNumber[0]", equalTo(second))
                .body("orderNumber[1]", equalTo(first));
    }
}
