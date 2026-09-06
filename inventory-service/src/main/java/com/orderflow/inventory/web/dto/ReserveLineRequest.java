package com.orderflow.inventory.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ReserveLineRequest(
        @NotBlank String sku,
        @Positive int quantity) {
}
