package com.ssuai.global.security;

import java.util.HashMap;
import java.util.Map;

/**
 * Small per-process in-flight guard used in addition to the Redis-shared request-rate limit.
 *
 * <p>The shared limiter bounds aggregate request volume across replicas. This guard bounds the
 * servlet work that one client, or all clients together, can occupy concurrently on a single
 * pod. Its key map contains only currently active callers and therefore cannot grow beyond the
 * configured global limit.</p>
 */
final class RequestConcurrencyLimiter {

    private final int perKeyLimit;
    private final int globalLimit;
    private final Map<String, Integer> activeByKey = new HashMap<>();
    private int activeTotal;

    RequestConcurrencyLimiter(int perKeyLimit, int globalLimit) {
        if (perKeyLimit < 1 || globalLimit < 1 || perKeyLimit > globalLimit) {
            throw new IllegalArgumentException(
                    "Concurrency limits must satisfy 1 <= perKeyLimit <= globalLimit");
        }
        this.perKeyLimit = perKeyLimit;
        this.globalLimit = globalLimit;
    }

    synchronized boolean tryAcquire(String key) {
        String normalized = key == null || key.isBlank() ? "unknown" : key;
        int activeForKey = activeByKey.getOrDefault(normalized, 0);
        if (activeTotal >= globalLimit || activeForKey >= perKeyLimit) {
            return false;
        }
        activeByKey.put(normalized, activeForKey + 1);
        activeTotal++;
        return true;
    }

    synchronized void release(String key) {
        String normalized = key == null || key.isBlank() ? "unknown" : key;
        Integer activeForKey = activeByKey.get(normalized);
        if (activeForKey == null) {
            return;
        }
        if (activeForKey <= 1) {
            activeByKey.remove(normalized);
        } else {
            activeByKey.put(normalized, activeForKey - 1);
        }
        activeTotal--;
    }

    synchronized int activeKeys() {
        return activeByKey.size();
    }
}
