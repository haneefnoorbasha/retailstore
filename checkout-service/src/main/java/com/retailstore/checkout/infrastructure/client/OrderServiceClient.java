package com.retailstore.checkout.infrastructure.client;

import com.retailstore.checkout.domain.model.CheckoutSession;
import com.retailstore.checkout.infrastructure.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calls order-service to place the order once checkout is submitted.
 * Checkout-service owns the session; order-service owns the permanent record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderServiceClient {

    private final WebClient.Builder webClientBuilder;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${retail.checkout.endpoints.orders:http://orders}")
    private String ordersEndpoint;

    public String placeOrder(CheckoutSession session) {
        WebClient client = webClientBuilder.baseUrl(ordersEndpoint).build();
        String serviceToken = serviceTokenProvider.getToken();

        List<Map<String, Object>> lineItems = session.getLineItems().stream()
            .map(li -> Map.of(
                "productId",   (Object) li.getProductId(),
                "productName", li.getProductName(),
                "quantity",    li.getQuantity(),
                "unitPrice",   li.getUnitPrice().toString()
            ))
            .collect(Collectors.toList());

        CheckoutSession.ShippingDetails addr = session.getShippingDetails();
        Map<String, Object> shippingAddress = Map.of(
            "fullName",     addr.getFullName(),
            "addressLine1", addr.getAddressLine1(),
            "addressLine2", addr.getAddressLine2() != null ? addr.getAddressLine2() : "",
            "city",         addr.getCity(),
            "state",        addr.getState(),
            "postalCode",   addr.getPostalCode(),
            "country",      addr.getCountry()
        );

        CheckoutSession.PriceSummary prices = session.getPriceSummary();
        Map<String, Object> orderRequest = Map.of(
            "customerId",        session.getCustomerId(),
            "checkoutSessionId", session.getSessionId(),
            "lineItems",         lineItems,
            "shippingAddress",   shippingAddress,
            "subtotal",          prices.getSubtotal().toString(),
            "shippingCost",      prices.getShippingCost().toString(),
            "total",             prices.getTotal().toString()
        );

        Map<?, ?> response = client.post()
            .uri("/api/v1/orders")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(orderRequest)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        String orderId = response != null ? String.valueOf(response.get("id")) : null;
        log.info("Order placed via order-service: orderId={} sessionId={}", orderId, session.getSessionId());
        return orderId;
    }
}
