package com.retailstore.order.application.service;

import com.retailstore.order.api.rest.v1.dto.*;
import com.retailstore.order.domain.exception.*;
import com.retailstore.order.domain.model.*;
import com.retailstore.order.infrastructure.messaging.OrderEventPublisher;
import com.retailstore.order.infrastructure.persistence.OrderJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderJpaRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        var lineItems = request.getLineItems().stream()
            .map(li -> OrderLineItem.builder()
                .productId(li.getProductId())
                .productName(li.getProductName())
                .quantity(li.getQuantity())
                .unitPrice(li.getUnitPrice())
                .build())
            .collect(Collectors.toList());

        var addr = request.getShippingAddress();
        var shippingAddress = ShippingAddress.builder()
            .fullName(addr.getFullName())
            .addressLine1(addr.getAddressLine1())
            .addressLine2(addr.getAddressLine2())
            .city(addr.getCity())
            .state(addr.getState())
            .postalCode(addr.getPostalCode())
            .country(addr.getCountry())
            .build();

        var order = Order.builder()
            .id(UUID.randomUUID().toString())
            .customerId(request.getCustomerId())
            .checkoutSessionId(request.getCheckoutSessionId())
            .status(OrderStatus.PENDING)
            .lineItems(lineItems)
            .shippingAddress(shippingAddress)
            .subtotal(request.getSubtotal())
            .shippingCost(request.getShippingCost())
            .total(request.getTotal())
            .build();

        Order saved = orderRepository.save(order);
        eventPublisher.publishOrderPlaced(saved);
        log.info("Order placed — id={} customerId={} total={}", saved.getId(), saved.getCustomerId(), saved.getTotal());
        return toResponse(saved);
    }

    public OrderResponse getOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        return toResponse(order);
    }

    public Page<OrderResponse> getOrdersByCustomer(String customerId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
            .map(this::toResponse);
    }

    @Transactional
    public OrderResponse cancelOrder(String orderId, String reason) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!order.isCancellable()) {
            throw new OrderNotCancellableException(orderId, order.getStatus().name());
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        Order saved = orderRepository.save(order);
        eventPublisher.publishOrderCancelled(saved);
        log.info("Order cancelled — id={} reason={}", orderId, reason);
        return toResponse(saved);
    }

    @Transactional
    public OrderResponse updateStatus(String orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        log.info("Order {} status updated to {}", orderId, newStatus);
        return toResponse(saved);
    }

    private OrderResponse toResponse(Order o) {
        var items = o.getLineItems().stream()
            .map(li -> OrderResponse.LineItemResponse.builder()
                .productId(li.getProductId()).productName(li.getProductName())
                .quantity(li.getQuantity()).unitPrice(li.getUnitPrice())
                .lineTotal(li.getLineTotal()).build())
            .collect(Collectors.toList());

        var addr = o.getShippingAddress();
        OrderResponse.ShippingAddressResponse addrResp = null;
        if (addr != null) {
            addrResp = OrderResponse.ShippingAddressResponse.builder()
                .fullName(addr.getFullName()).addressLine1(addr.getAddressLine1())
                .addressLine2(addr.getAddressLine2()).city(addr.getCity())
                .state(addr.getState()).postalCode(addr.getPostalCode())
                .country(addr.getCountry()).build();
        }

        return OrderResponse.builder()
            .id(o.getId()).customerId(o.getCustomerId()).status(o.getStatus())
            .lineItems(items).shippingAddress(addrResp)
            .subtotal(o.getSubtotal()).shippingCost(o.getShippingCost()).total(o.getTotal())
            .cancellable(o.isCancellable()).cancellationReason(o.getCancellationReason())
            .createdAt(o.getCreatedAt()).updatedAt(o.getUpdatedAt())
            .build();
    }
}
