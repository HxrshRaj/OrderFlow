package com.orderflow.inventory.web;

import com.orderflow.inventory.service.InventoryService;
import com.orderflow.inventory.web.dto.AdjustStockRequest;
import com.orderflow.inventory.web.dto.CreateInventoryItemRequest;
import com.orderflow.inventory.web.dto.InventoryItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Stock catalogue and admin restock")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    @Operation(summary = "List all stock items")
    public List<InventoryItemResponse> list() {
        return inventoryService.listAll().stream().map(InventoryItemResponse::from).toList();
    }

    @GetMapping("/{sku}")
    @Operation(summary = "Get one stock item by SKU")
    public InventoryItemResponse get(@PathVariable String sku) {
        return InventoryItemResponse.from(inventoryService.getBySku(sku));
    }

    @PostMapping
    @Operation(summary = "Create a stock item")
    public ResponseEntity<InventoryItemResponse> create(@Valid @RequestBody CreateInventoryItemRequest request,
                                                        UriComponentsBuilder uriBuilder) {
        var created = inventoryService.create(request.sku(), request.name(), request.quantity());
        URI location = uriBuilder.path("/api/v1/inventory/{sku}").build(created.getSku());
        return ResponseEntity.created(location).body(InventoryItemResponse.from(created));
    }

    @PatchMapping("/{sku}")
    @Operation(summary = "Restock or correct a stock item (optimistic-locked, retried on conflict)")
    public InventoryItemResponse adjust(@PathVariable String sku, @Valid @RequestBody AdjustStockRequest request) {
        if (request.quantityDelta() == 0) {
            throw new IllegalArgumentException("quantityDelta must not be zero");
        }
        return InventoryItemResponse.from(inventoryService.adjustStock(sku, request.quantityDelta()));
    }
}
