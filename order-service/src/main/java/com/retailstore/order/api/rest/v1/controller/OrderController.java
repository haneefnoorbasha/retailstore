package com.retailstore.order.api.rest.v1.controller;

import com.retailstore.order.api.rest.v1.dto.*;
import com.retailstore.order.application.service.OrderService;
import com.retailstore.order.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order management — place, track, cancel orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Place a new order")
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.placeOrder(request));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get order history for a customer (paginated, newest first)")
    public ResponseEntity<Page<OrderResponse>> getCustomerOrders(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(orderService.getOrdersByCustomer(customerId, page, size));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an order (only if PENDING or CONFIRMED)")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable String orderId,
            @RequestParam(required = false, defaultValue = "Customer requested cancellation") String reason) {
        return ResponseEntity.ok(orderService.cancelOrder(orderId, reason));
    }

    @PatchMapping("/{orderId}/status")
    @Operation(summary = "Update order status (internal/admin use)")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable String orderId,
            @RequestParam OrderStatus status) {
        return ResponseEntity.ok(orderService.updateStatus(orderId, status));
    }
}
