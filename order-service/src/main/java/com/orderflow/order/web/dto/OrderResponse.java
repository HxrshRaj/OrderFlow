package com.orderflow.order.web.dto;

import com.orderflow.order.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        String orderNumber,
        UUID reservationId,
        String customerId,
        String status,
        String rejectionReason,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderNumber(),
                order.getReservationId(),
                order.getCustomerId(),
                order.getStatus().name(),
                order.getRejectionReason(),
                order.getTotalAmount(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
