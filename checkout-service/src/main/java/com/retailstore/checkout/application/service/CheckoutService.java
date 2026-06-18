package com.retailstore.checkout.application.service;

import com.retailstore.checkout.api.rest.v1.dto.*;
import com.retailstore.checkout.domain.exception.*;
import com.retailstore.checkout.domain.model.*;
import com.retailstore.checkout.infrastructure.client.OrderServiceClient;
import com.retailstore.checkout.infrastructure.redis.CheckoutSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final CheckoutSessionRepository sessionRepository;
    private final OrderServiceClient orderServiceClient;

    // Standard UK/EU VAT rate — configurable per environment
    @Value("${retail.checkout.pricing.tax-rate:0.20}")
    private BigDecimal taxRate;

    @Value("${retail.checkout.pricing.shipping-cost:5.99}")
    private BigDecimal shippingCost;

    @Value("${retail.checkout.pricing.free-shipping-threshold:50.00}")
    private BigDecimal freeShippingThreshold;

    @Value("${retail.checkout.session.ttl-minutes:30}")
    private int ttlMinutes;

    /**
     * Create a new checkout session from the customer's cart items.
     * Calculates subtotal, shipping (free above threshold), and tax.
     */
    public CheckoutSessionResponse createSession(CreateSessionRequest request) {
        List<CheckoutSession.CheckoutLineItem> lineItems = request.getLineItems().stream()
            .map(li -> CheckoutSession.CheckoutLineItem.builder()
                .productId(li.getProductId())
                .productName(li.getProductName())
                .quantity(li.getQuantity())
                .unitPrice(li.getUnitPrice())
                .build())
            .collect(Collectors.toList());

        CheckoutSession.PriceSummary pricing = calculatePricing(lineItems);

        CheckoutSession session = CheckoutSession.builder()
            .sessionId(UUID.randomUUID().toString())
            .customerId(request.getCustomerId())
            .status(CheckoutStatus.ACTIVE)
            .lineItems(lineItems)
            .priceSummary(pricing)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES))
            .build();

        sessionRepository.save(session);
        log.info("Checkout session created: sessionId={} customerId={} total={}",
            session.getSessionId(), session.getCustomerId(), pricing.getTotal());
        return toResponse(session);
    }

    /**
     * Retrieve an existing session. Returns 404 if expired or missing.
     */
    public CheckoutSessionResponse getSession(String sessionId) {
        CheckoutSession session = loadSession(sessionId);
        if (session.isExpired()) {
            session.setStatus(CheckoutStatus.EXPIRED);
            sessionRepository.save(session);
            throw new CheckoutSessionNotFoundException(sessionId);
        }
        return toResponse(session);
    }

    /**
     * Update shipping address and recalculate shipping cost if needed.
     */
    public CheckoutSessionResponse updateShipping(String sessionId, UpdateShippingRequest request) {
        CheckoutSession session = loadActiveSession(sessionId);

        session.setShippingDetails(CheckoutSession.ShippingDetails.builder()
            .fullName(request.getFullName())
            .addressLine1(request.getAddressLine1())
            .addressLine2(request.getAddressLine2())
            .city(request.getCity())
            .state(request.getState())
            .postalCode(request.getPostalCode())
            .country(request.getCountry())
            .build());

        sessionRepository.save(session);
        log.info("Shipping updated for sessionId={}", sessionId);
        return toResponse(session);
    }

    /**
     * Submit the checkout — validates session, calls order-service, marks SUBMITTED.
     * This is the critical business transaction.
     */
    public CheckoutSessionResponse submitCheckout(String sessionId) {
        CheckoutSession session = loadActiveSession(sessionId);

        if (!session.isSubmittable()) {
            String reason = session.getShippingDetails() == null
                ? "shipping address is missing"
                : "session is not in a valid state";
            throw new CheckoutSessionNotSubmittableException(reason);
        }

        // Call order-service — if this throws, session stays ACTIVE (retry is safe)
        String orderId = orderServiceClient.placeOrder(session);

        session.setStatus(CheckoutStatus.SUBMITTED);
        session.setSubmittedOrderId(orderId);
        sessionRepository.save(session);

        log.info("Checkout submitted: sessionId={} orderId={} customerId={}",
            sessionId, orderId, session.getCustomerId());
        return toResponse(session);
    }

    /**
     * Abandon a session explicitly (e.g. customer clicks "cancel").
     */
    public void abandonSession(String sessionId) {
        CheckoutSession session = loadSession(sessionId);
        session.setStatus(CheckoutStatus.ABANDONED);
        sessionRepository.save(session);
        log.info("Checkout session abandoned: sessionId={}", sessionId);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private CheckoutSession loadSession(String sessionId) {
        return sessionRepository.findById(sessionId)
            .orElseThrow(() -> new CheckoutSessionNotFoundException(sessionId));
    }

    private CheckoutSession loadActiveSession(String sessionId) {
        CheckoutSession session = loadSession(sessionId);
        if (session.isExpired() || session.getStatus() != CheckoutStatus.ACTIVE) {
            throw new CheckoutSessionNotFoundException(sessionId);
        }
        return session;
    }

    /**
     * Pricing rules:
     * - Subtotal = sum of all line totals
     * - Shipping = free if subtotal >= freeShippingThreshold, otherwise shippingCost
     * - Tax = 20% on subtotal (configurable)
     * - Total = subtotal + shipping + tax
     */
    private CheckoutSession.PriceSummary calculatePricing(
            List<CheckoutSession.CheckoutLineItem> items) {

        BigDecimal subtotal = items.stream()
            .map(CheckoutSession.CheckoutLineItem::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal shipping = subtotal.compareTo(freeShippingThreshold) >= 0
            ? BigDecimal.ZERO
            : shippingCost;

        BigDecimal tax = subtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(shipping).add(tax);

        return CheckoutSession.PriceSummary.builder()
            .subtotal(subtotal)
            .shippingCost(shipping)
            .taxAmount(tax)
            .total(total)
            .build();
    }

    private CheckoutSessionResponse toResponse(CheckoutSession s) {
        List<CheckoutSessionResponse.LineItemDto> items = s.getLineItems().stream()
            .map(li -> CheckoutSessionResponse.LineItemDto.builder()
                .productId(li.getProductId()).productName(li.getProductName())
                .quantity(li.getQuantity()).unitPrice(li.getUnitPrice())
                .lineTotal(li.lineTotal()).build())
            .collect(Collectors.toList());

        CheckoutSessionResponse.ShippingDto shipping = null;
        if (s.getShippingDetails() != null) {
            CheckoutSession.ShippingDetails sd = s.getShippingDetails();
            shipping = CheckoutSessionResponse.ShippingDto.builder()
                .fullName(sd.getFullName()).addressLine1(sd.getAddressLine1())
                .addressLine2(sd.getAddressLine2()).city(sd.getCity())
                .state(sd.getState()).postalCode(sd.getPostalCode())
                .country(sd.getCountry()).build();
        }

        CheckoutSessionResponse.PriceSummaryDto prices = null;
        if (s.getPriceSummary() != null) {
            CheckoutSession.PriceSummary p = s.getPriceSummary();
            prices = CheckoutSessionResponse.PriceSummaryDto.builder()
                .subtotal(p.getSubtotal()).shippingCost(p.getShippingCost())
                .taxAmount(p.getTaxAmount()).total(p.getTotal()).build();
        }

        return CheckoutSessionResponse.builder()
            .sessionId(s.getSessionId()).customerId(s.getCustomerId())
            .status(s.getStatus()).lineItems(items).shippingDetails(shipping)
            .priceSummary(prices).createdAt(s.getCreatedAt()).expiresAt(s.getExpiresAt())
            .submittable(s.isSubmittable()).submittedOrderId(s.getSubmittedOrderId())
            .build();
    }
}
