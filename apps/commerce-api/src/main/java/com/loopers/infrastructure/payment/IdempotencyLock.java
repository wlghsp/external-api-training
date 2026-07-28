package com.loopers.infrastructure.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class IdempotencyLock {

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "payment:idempotency:";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * true면 이 요청이 최초로 선점에 성공한 것, false면 이미 다른 요청이 선점 중
     */
    public boolean acquire(String idempotencyKey) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + idempotencyKey, "1", LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(String idempotencyKey) {
        redisTemplate.delete(KEY_PREFIX + idempotencyKey);
    }
}
