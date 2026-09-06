package com.orderflow.order.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record OrderLineRequest(
        @NotBlank String sku,
        @Positive int quantity,
        @NotNull @PositiveOrZero BigDecimal unitPrice) {
}
