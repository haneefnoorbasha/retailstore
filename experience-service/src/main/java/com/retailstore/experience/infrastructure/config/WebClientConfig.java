package com.retailstore.experience.infrastructure.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.*;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
            .responseTimeout(Duration.ofSeconds(5))
            .doOnConnected(conn ->
                conn.addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS)));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .filter(ExchangeFilterFunction.ofRequestProcessor(req -> {
                log.debug("→ {} {}", req.method(), req.url());
                return reactor.core.publisher.Mono.just(req);
            }));
    }

    @Bean("catalogClient")
    public WebClient catalogClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.catalog:http://catalog}") String url) {
        return builder.baseUrl(url).build();
    }

    @Bean("cartClient")
    public WebClient cartClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.carts:http://carts}") String url) {
        return builder.baseUrl(url).build();
    }

    @Bean("orderClient")
    public WebClient orderClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.orders:http://orders}") String url) {
        return builder.baseUrl(url).build();
    }
}
