package com.orderflow.inventory.web.dto;

import com.orderflow.inventory.service.LineCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReserveRequest(
        @NotNull UUID reservationId,
        @NotEmpty @Valid List<ReserveLineRequest> lines) {

    public List<LineCommand> toCommands() {
        return lines.stream()
                .map(l -> new LineCommand(l.sku(), l.quantity()))
                .toList();
    }
}
