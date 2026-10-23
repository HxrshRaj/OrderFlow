package com.orderflow.api;

import org.testng.annotations.Test;

import java.util.Map;

import static com.orderflow.api.TestData.available;
import static com.orderflow.api.TestData.createItem;
import static com.orderflow.api.TestData.inventory;
import static com.orderflow.api.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

public class InventoryApiTest {

    @Test(description = "Creating an item returns 201, a Location header and the stored fields")
    public void createReturns201WithLocationAndBody() {
        String sku = uniqueSku();
        inventory().body(Map.of("sku", sku, "name", "Create Probe", "quantity", 7))
                .post("/api/v1/inventory")
                .then().statusCode(201)
                .header("Location", containsString("/api/v1/inventory/" + sku))
                .body("sku", equalTo(sku))
                .body("availableQuantity", equalTo(7))
                .body("reservedQuantity", equalTo(0))
                .body("onHandQuantity", equalTo(7));
    }

    @Test(description = "A duplicate SKU is rejected with 409 and an RFC 7807 problem body")
    public void duplicateSkuIs409() {
        String sku = createItem("Duplicate Probe", 1);
        inventory().body(Map.of("sku", sku, "name", "Again", "quantity", 1))
                .post("/api/v1/inventory")
                .then().statusCode(409)
                .body("title", equalTo("Duplicate SKU"));
    }

    @Test(description = "A blank SKU and a negative quantity fail validation with 400")
    public void validationErrorsAre400() {
        inventory().body(Map.of("sku", "", "name", "No Sku", "quantity", 1))
                .post("/api/v1/inventory")
                .then().statusCode(400).body("title", equalTo("Validation error"));
        inventory().body(Map.of("sku", uniqueSku(), "name", "Negative", "quantity", -1))
                .post("/api/v1/inventory")
                .then().statusCode(400).body("title", equalTo("Validation error"));
    }

    @Test(description = "An unknown SKU is 404")
    public void unknownSkuIs404() {
        inventory().get("/api/v1/inventory/{sku}", "DOES-NOT-EXIST-" + uniqueSku())
                .then().statusCode(404).body("title", equalTo("Not found"));
    }

    @Test(description = "Restock increases available stock; a zero delta is 400")
    public void restockAndZeroDelta() {
        String sku = createItem("Restock Probe", 5);
        inventory().body(Map.of("quantityDelta", 10)).patch("/api/v1/inventory/{sku}", sku)
                .then().statusCode(200).body("availableQuantity", equalTo(15));
        assertThat(available(sku)).isEqualTo(15);
        inventory().body(Map.of("quantityDelta", 0)).patch("/api/v1/inventory/{sku}", sku)
                .then().statusCode(400);
    }

    @Test(description = "Removing more stock than is available is rejected with 422 and changes nothing")
    public void overDeductionIs422AndLeavesStockUntouched() {
        String sku = createItem("Overdraw Probe", 3);
        inventory().body(Map.of("quantityDelta", -4)).patch("/api/v1/inventory/{sku}", sku)
                .then().statusCode(422).body("title", equalTo("Invalid stock adjustment"));
        assertThat(available(sku)).isEqualTo(3);
    }

    @Test(description = "Search by name prefix finds the item")
    public void searchByPrefix() {
        String word = "Zephyrlamp" + uniqueSku().substring(4).toLowerCase();
        String sku = createItem(word + " Desk Light", 4);
        inventory().queryParam("q", word.substring(0, 10)).get("/api/v1/inventory/search")
                .then().statusCode(200).body("sku", hasItem(sku));
    }

    @Test(description = "Search tolerates a small typo in the product name")
    public void searchToleratesTypos() {
        String word = "Quillbord" + uniqueSku().substring(4).toLowerCase();
        String sku = createItem(word + " Wireless", 4);
        String typo = word.replace("ll", "l"); // one dropped letter
        inventory().queryParam("q", typo).get("/api/v1/inventory/search")
                .then().statusCode(200).body("sku", hasItem(sku));
    }

    @Test(description = "Search honours the limit parameter")
    public void searchHonoursLimit() {
        inventory().queryParam("q", "SKU").queryParam("limit", 2).get("/api/v1/inventory/search")
                .then().statusCode(200).body("size()", lessThanOrEqualTo(2));
    }

    @Test(description = "An unrelated query returns an empty list, not an error")
    public void searchWithNoMatchIsEmpty() {
        inventory().queryParam("q", "qqzzxxvv" + uniqueSku().toLowerCase()).get("/api/v1/inventory/search")
                .then().statusCode(200).body("$", hasSize(0));
    }
}
