package com.orderflow.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Just the fields we need from the Inventory Service's RFC 7807 error body. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryProblemDetail(String title, String detail, List<ShortfallDto> shortfalls) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShortfallDto(String sku, int requested, int available, String reason) {
    }
}
