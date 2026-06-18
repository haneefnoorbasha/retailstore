package com.retailstore.order.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "retail.order.messaging.enabled", havingValue = "true")
public class SqsConfig {

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // Set to LocalStack URL in dev (http://localhost:4566 or http://localstack:4566)
    @Value("${SQS_ENDPOINT:}")
    private String sqsEndpoint;

    @Bean
    public SqsClient sqsClient() {
        var builder = SqsClient.builder()
            .region(Region.of(awsRegion));
        if (StringUtils.hasText(sqsEndpoint)) {
            builder.endpointOverride(URI.create(sqsEndpoint))
                   .credentialsProvider(StaticCredentialsProvider.create(
                       AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }
}
