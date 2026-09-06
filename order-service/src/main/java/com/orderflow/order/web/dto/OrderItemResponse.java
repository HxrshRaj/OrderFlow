package com.orderflow.order.web.dto;

import com.orderflow.order.domain.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getSku(), item.getQuantity(), item.getUnitPrice(), item.lineTotal());
    }
}
