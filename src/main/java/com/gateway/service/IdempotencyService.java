package com.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents duplicate payment processing using idempotency keys.
 * Uses Redis in production for distributed deduplication across instances.
 * Falls back to in-memory ConcurrentHashMap for local development.
 *
 * Payment systems MUST be idempotent — a network retry should never
 * result in a customer being charged twice.
 */
@Service
@Slf4j
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "idempotency:";

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    /**
     * Attempt to acquire an idempotency lock for this key.
     *
     * @return true if this is the first time we've seen this key (proceed with processing),
     *         false if this key was already processed (return cached result)
     */
    public boolean tryAcquire(String key) {
        if (redisTemplate != null) {
            Boolean set = redisTemplate.opsForValue()
                    .setIfAbsent(PREFIX + key, "PROCESSING", TTL);
            return Boolean.TRUE.equals(set);
        }
        return localCache.putIfAbsent(key, "PROCESSING") == null;
    }

    /**
     * Store the result for a processed idempotency key so duplicates
     * can return the same response.
     */
    public void storeResult(String key, String transactionId) {
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(PREFIX + key, transactionId, TTL);
        } else {
            localCache.put(key, transactionId);
        }
    }

    /**
     * Get the stored transaction ID for a previously processed key.
     */
    public String getStoredResult(String key) {
        if (redisTemplate != null) {
            return redisTemplate.opsForValue().get(PREFIX + key);
        }
        return localCache.get(key);
    }

    public boolean exists(String key) {
        if (redisTemplate != null) {
            return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + key));
        }
        return localCache.containsKey(key);
    }
}
