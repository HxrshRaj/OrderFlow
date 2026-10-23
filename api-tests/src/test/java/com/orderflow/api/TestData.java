package com.orderflow.api;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.util.Map;
import java.util.UUID;

/** Shared base URLs, request specs and a test-data factory. Every item gets a unique SKU. */
final class TestData {

    static final String INVENTORY_URL = System.getProperty("orderflow.inventoryUrl", "http://localhost:8081");
    static final String ORDER_URL = System.getProperty("orderflow.orderUrl", "http://localhost:8080");

    private TestData() {
    }

    static RequestSpecification inventory() {
        return RestAssured.given().baseUri(INVENTORY_URL).contentType(ContentType.JSON).accept(ContentType.JSON);
    }

    static RequestSpecification orders() {
        return RestAssured.given().baseUri(ORDER_URL).contentType(ContentType.JSON).accept(ContentType.JSON);
    }

    static String uniqueSku() {
        return "API-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Creates a stock item through the API and returns its SKU. */
    static String createItem(String name, int quantity) {
        String sku = uniqueSku();
        inventory().body(Map.of("sku", sku, "name", name, "quantity", quantity))
                .post("/api/v1/inventory")
                .then().statusCode(201);
        return sku;
    }

    static int available(String sku) {
        return inventory().get("/api/v1/inventory/{sku}", sku).then().statusCode(200).extract().path("availableQuantity");
    }

    static int reserved(String sku) {
        return inventory().get("/api/v1/inventory/{sku}", sku).then().statusCode(200).extract().path("reservedQuantity");
    }

    static Map<String, Object> orderBody(String customerId, String sku, int quantity, double unitPrice) {
        return Map.of("customerId", customerId,
                "lines", java.util.List.of(Map.of("sku", sku, "quantity", quantity, "unitPrice", unitPrice)));
    }
}
