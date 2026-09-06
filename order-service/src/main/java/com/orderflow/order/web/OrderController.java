package com.orderflow.order.web;

import com.orderflow.order.domain.Order;
import com.orderflow.order.service.OrderService;
import com.orderflow.order.web.dto.OrderResponse;
import com.orderflow.order.web.dto.PlaceOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order lifecycle")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Place an order",
            description = "Always returns 201 with the created order. Inspect `status`: CONFIRMED "
                    + "(stock held), REJECTED (`rejectionReason` explains the shortfall), or PLACED "
                    + "(Inventory was unreachable; the reconciler will retry).")
    public ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request,
                                               UriComponentsBuilder uriBuilder) {
        Order order = orderService.placeOrder(request.toCommand());
        URI location = uriBuilder.path("/api/v1/orders/{orderNumber}").build(order.getOrderNumber());
        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderNumber}")
    @Operation(summary = "Get an order by its number")
    public OrderResponse get(@PathVariable String orderNumber) {
        return OrderResponse.from(orderService.getByNumber(orderNumber));
    }

    @GetMapping
    @Operation(summary = "List a customer's orders, newest first")
    public List<OrderResponse> listForCustomer(@RequestParam String customerId) {
        return orderService.listByCustomer(customerId).stream().map(OrderResponse::from).toList();
    }

    @PostMapping("/{orderNumber}/ship")
    @Operation(summary = "Ship a CONFIRMED order (commits the stock reservation)")
    public OrderResponse ship(@PathVariable String orderNumber) {
        return OrderResponse.from(orderService.ship(orderNumber));
    }

    @PostMapping("/{orderNumber}/cancel")
    @Operation(summary = "Cancel a PLACED or CONFIRMED order (releases any stock hold)")
    public OrderResponse cancel(@PathVariable String orderNumber) {
        return OrderResponse.from(orderService.cancel(orderNumber));
    }
}
