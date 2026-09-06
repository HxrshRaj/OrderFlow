package com.orderflow.inventory.web.dto;

import com.orderflow.inventory.domain.ReservationLine;

public record ReservationLineResponse(String sku, int quantity) {

    public static ReservationLineResponse from(ReservationLine line) {
        return new ReservationLineResponse(line.getSku(), line.getQuantity());
    }
}
