package com.retailstore.cart.infrastructure.config;

import com.retailstore.cart.domain.model.Cart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import java.net.URI;

@Slf4j
@Configuration
public class DynamoDbConfig {

    @Value("${retail.cart.dynamodb.endpoint:}")
    private String endpoint;

    @Value("${retail.cart.dynamodb.region:us-east-1}")
    private String region;

    @Value("${retail.cart.dynamodb.table-name:Carts}")
    private String tableName;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpoint != null && !endpoint.isBlank()) {
            log.info("Using DynamoDB local endpoint: {}", endpoint);
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    public DynamoDbTable<Cart> cartTable(DynamoDbEnhancedClient client) {
        return client.table(tableName, TableSchema.fromBean(Cart.class));
    }
}
