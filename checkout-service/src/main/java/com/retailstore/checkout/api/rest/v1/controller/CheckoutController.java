package com.retailstore.checkout.api.rest.v1.controller;

import com.retailstore.checkout.api.rest.v1.dto.*;
import com.retailstore.checkout.application.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/checkout/sessions")
@RequiredArgsConstructor
@Tag(name = "Checkout", description = "Checkout sessions — price calculation, shipping, and order placement")
public class CheckoutController {

    private final CheckoutService checkoutService;

    @PostMapping
    @Operation(summary = "Create checkout session from cart items",
               description = "Calculates subtotal, shipping (free over $50), and 20% tax. Session expires in 30 min.")
    public ResponseEntity<CheckoutSessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(checkoutService.createSession(request));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get checkout session by ID")
    public ResponseEntity<CheckoutSessionResponse> getSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(checkoutService.getSession(sessionId));
    }

    @PutMapping("/{sessionId}/shipping")
    @Operation(summary = "Update shipping address on the session")
    public ResponseEntity<CheckoutSessionResponse> updateShipping(
            @PathVariable String sessionId,
            @Valid @RequestBody UpdateShippingRequest request) {
        return ResponseEntity.ok(checkoutService.updateShipping(sessionId, request));
    }

    @PostMapping("/{sessionId}/submit")
    @Operation(summary = "Submit checkout — places the order via order-service",
               description = "Validates session is ACTIVE, shipping is set, then calls order-service. Returns the session with submittedOrderId.")
    public ResponseEntity<CheckoutSessionResponse> submitCheckout(@PathVariable String sessionId) {
        return ResponseEntity.ok(checkoutService.submitCheckout(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Abandon a checkout session")
    public ResponseEntity<Void> abandonSession(@PathVariable String sessionId) {
        checkoutService.abandonSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
