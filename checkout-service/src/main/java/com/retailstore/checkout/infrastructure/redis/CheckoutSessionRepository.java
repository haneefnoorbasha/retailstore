package com.retailstore.checkout.infrastructure.redis;

import com.retailstore.checkout.domain.model.CheckoutSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CheckoutSessionRepository {

    private final RedisTemplate<String, CheckoutSession> redisTemplate;

    @Value("${retail.checkout.session.ttl-minutes:30}")
    private int ttlMinutes;

    private static final String KEY_PREFIX = "checkout:session:";

    public CheckoutSession save(CheckoutSession session) {
        String key = KEY_PREFIX + session.getSessionId();
        redisTemplate.opsForValue().set(key, session, Duration.ofMinutes(ttlMinutes));
        log.debug("Saved checkout session {} (TTL {}min)", session.getSessionId(), ttlMinutes);
        return session;
    }

    public Optional<CheckoutSession> findById(String sessionId) {
        CheckoutSession session = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        return Optional.ofNullable(session);
    }

    public void delete(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    public boolean exists(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
    }
}
