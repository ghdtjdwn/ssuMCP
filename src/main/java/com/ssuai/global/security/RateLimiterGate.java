package com.ssuai.global.security;

/**
 * Common contract for a per-key request gate used by {@link RateLimitFilter}.
 * Lets the filter treat the per-pod {@link IpRateLimiter} and the
 * Redis-shared {@link SharedIpRateLimiter}
 * interchangeably.
 */
interface RateLimiterGate {

    IpRateLimiter.Outcome tryAcquire(String key);
}
