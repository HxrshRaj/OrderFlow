package com.orderflow.order.service;

import java.util.List;

public record PlaceOrderCommand(String customerId, List<OrderLineCommand> lines) {
}
