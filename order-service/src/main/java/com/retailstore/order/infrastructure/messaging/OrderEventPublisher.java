package com.retailstore.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailstore.order.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${retail.order.messaging.sqs.queue-url:}")
    private String queueUrl;

    @Value("${retail.order.messaging.enabled:false}")
    private boolean messagingEnabled;

    public void publishOrderPlaced(Order order) {
        if (!messagingEnabled || queueUrl.isBlank()) {
            log.debug("SQS messaging disabled — skipping ORDER_PLACED event for order {}", order.getId());
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "eventType",   "ORDER_PLACED",
                "orderId",     order.getId(),
                "customerId",  order.getCustomerId(),
                "total",       order.getTotal().toString(),
                "itemCount",   order.getLineItems().size(),
                "timestamp",   Instant.now().toString()
            ));
            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(payload)
                .messageGroupId("orders")
                .build());
            log.info("Published ORDER_PLACED event for order {}", order.getId());
        } catch (Exception e) {
            log.error("Failed to publish ORDER_PLACED event for order {} — non-fatal", order.getId(), e);
        }
    }

    public void publishOrderCancelled(Order order) {
        if (!messagingEnabled || queueUrl.isBlank()) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "eventType",  "ORDER_CANCELLED",
                "orderId",    order.getId(),
                "customerId", order.getCustomerId(),
                "reason",     order.getCancellationReason() != null ? order.getCancellationReason() : "",
                "timestamp",  Instant.now().toString()
            ));
            sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(payload).build());
        } catch (Exception e) {
            log.error("Failed to publish ORDER_CANCELLED event for order {}", order.getId(), e);
        }
    }
}
