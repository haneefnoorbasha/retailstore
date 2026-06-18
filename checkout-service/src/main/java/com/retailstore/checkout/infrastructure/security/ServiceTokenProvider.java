package com.retailstore.checkout.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
public class ServiceTokenProvider {

    private final WebClient tokenClient;
    private final String clientId;
    private final String clientSecret;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public ServiceTokenProvider(
            WebClient.Builder webClientBuilder,
            @Value("${retail.checkout.keycloak.token-uri}") String tokenUri,
            @Value("${retail.checkout.keycloak.client-id}") String clientId,
            @Value("${retail.checkout.keycloak.client-secret}") String clientSecret) {
        this.tokenClient = webClientBuilder.baseUrl(tokenUri).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return cachedToken;
        }
        return fetchToken();
    }

    @SuppressWarnings("unchecked")
    private String fetchToken() {
        Map<?, ?> response = tokenClient.post()
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                .with("client_id", clientId)
                .with("client_secret", clientSecret))
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Keycloak returned no access_token");
        }
        String token = (String) response.get("access_token");
        Integer expiresIn = (Integer) response.get("expires_in");
        cachedToken = token;
        tokenExpiry = Instant.now().plusSeconds(expiresIn != null ? expiresIn : 300);
        log.debug("Fetched new service token, expires in {}s", expiresIn);
        return token;
    }
}
