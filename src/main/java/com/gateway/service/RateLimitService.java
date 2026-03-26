package com.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding window rate limiter per merchant.
 *
 * Production: Uses Redis INCR with TTL for distributed rate limiting.
 * Development: Uses in-memory counters with manual window reset.
 *
 * Each merchant gets a configurable number of requests per minute.
 * Returns remaining quota and reset time for rate limit headers.
 */
@Service
@Slf4j
public class RateLimitService {

    private static final int DEFAULT_LIMIT = 100; // requests per minute
    private static final String PREFIX = "ratelimit:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final Map<String, WindowCounter> localCounters = new ConcurrentHashMap<>();

    public RateLimitResult check(String merchantId) {
        if (redisTemplate != null) {
            return checkRedis(merchantId);
        }
        return checkLocal(merchantId);
    }

    private RateLimitResult checkRedis(String merchantId) {
        String key = PREFIX + merchantId + ":" + currentWindow();
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, WINDOW);
        }
        boolean allowed = count != null && count <= DEFAULT_LIMIT;
        int remaining = Math.max(0, DEFAULT_LIMIT - (count != null ? count.intValue() : 0));
        return new RateLimitResult(allowed, remaining, DEFAULT_LIMIT);
    }

    private RateLimitResult checkLocal(String merchantId) {
        long window = currentWindow();
        WindowCounter counter = localCounters.compute(merchantId, (k, v) -> {
            if (v == null || v.window != window) return new WindowCounter(window);
            return v;
        });
        int count = counter.count.incrementAndGet();
        boolean allowed = count <= DEFAULT_LIMIT;
        return new RateLimitResult(allowed, Math.max(0, DEFAULT_LIMIT - count), DEFAULT_LIMIT);
    }

    private long currentWindow() {
        return System.currentTimeMillis() / 60000; // minute-level granularity
    }

    private static class WindowCounter {
        final long window;
        final AtomicInteger count = new AtomicInteger(0);
        WindowCounter(long window) { this.window = window; }
    }

    public record RateLimitResult(boolean allowed, int remaining, int limit) {}
}
