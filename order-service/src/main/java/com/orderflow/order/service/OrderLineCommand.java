package com.orderflow.order.service;

import java.math.BigDecimal;

public record OrderLineCommand(String sku, int quantity, BigDecimal unitPrice) {
}
