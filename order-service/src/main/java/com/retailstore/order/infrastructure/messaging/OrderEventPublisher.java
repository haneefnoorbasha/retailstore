package com.retailstore.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailstore.order.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${retail.order.messaging.kafka.topic:order-events}")
    private String topic;

    @Value("${retail.order.messaging.enabled:false}")
    private boolean messagingEnabled;

    public void publishOrderPlaced(Order order) {
        if (!messagingEnabled) {
            log.debug("Kafka messaging disabled — skipping ORDER_PLACED event for order {}", order.getId());
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "eventType",  "ORDER_PLACED",
                "orderId",    order.getId(),
                "customerId", order.getCustomerId(),
                "total",      order.getTotal().toString(),
                "itemCount",  order.getLineItems().size(),
                "timestamp",  Instant.now().toString()
            ));
            kafkaTemplate.send(topic, order.getId().toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ORDER_PLACED event for order {} — non-fatal", order.getId(), ex);
                    } else {
                        log.info("Published ORDER_PLACED event for order {} → topic={} partition={} offset={}",
                            order.getId(), topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                    }
                });
        } catch (Exception e) {
            log.error("Failed to publish ORDER_PLACED event for order {} — non-fatal", order.getId(), e);
        }
    }

    public void publishOrderCancelled(Order order) {
        if (!messagingEnabled) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "eventType",  "ORDER_CANCELLED",
                "orderId",    order.getId(),
                "customerId", order.getCustomerId(),
                "reason",     order.getCancellationReason() != null ? order.getCancellationReason() : "",
                "timestamp",  Instant.now().toString()
            ));
            kafkaTemplate.send(topic, order.getId().toString(), payload);
        } catch (Exception e) {
            log.error("Failed to publish ORDER_CANCELLED event for order {} — non-fatal", order.getId(), e);
        }
    }
}
