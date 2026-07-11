package com.ssuai.domain.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One-time exchange-code store for the SSO-callback → {@code POST
 * /api/auth/exchange} hop (Fix B, ADR 0095). The SSO callback no longer sets
 * the refresh cookie itself — it mints a short-lived, single-use code and
 * hands it to the browser in the redirect URL ({@code ?code=...}). The
 * frontend then POSTs that code to {@code /api/auth/exchange} same-origin,
 * whose plain 200 response is the delivery path that reliably carries
 * Set-Cookie (unlike the callback response, which passed through a
 * cookie-mangling frontend proxy).
 *
 * <p><strong>Backing store.</strong> Same Redisson-with-in-memory-fallback
 * shape as {@link com.ssuai.global.auth.RefreshTokenDenylist}: when a
 * {@link RedissonClient} is wired (dev/prod), the code is stored in Redis so
 * it is visible to whichever of the 2 replicas serves the exchange call — a
 * {@code ConcurrentHashMap} alone would only be visible on the pod that
 * minted it. The in-memory map is a fallback for when no Redisson client is
 * configured at all (tests).
 *
 * <p>Each code is its own key ({@code auth:sso-exchange:<code>}) with a TTL,
 * so an unredeemed code disappears on its own; single-use is enforced by an
 * atomic read-and-remove ({@link RBucket#getAndDelete()} in Redis, {@link
 * ConcurrentHashMap#remove(Object)} in-memory) so a concurrent double-submit
 * of the same code can only ever succeed once.
 *
 * <p><strong>Fail-CLOSED on Redis errors — the opposite of
 * {@code RefreshTokenDenylist}.</strong> {@code RefreshTokenDenylist} fails
 * open because a denylist miss just lets an already-short-lived revoked
 * token be accepted a little longer — small, bounded blast radius. This
 * store is different: it hands out the one credential that lets the browser
 * complete login. Swallowing a Redis error here would either (a) hand the
 * browser a code on {@code issue} that can never be redeemed because the
 * write never landed, or (b) on {@code consume}, misreport a genuine Redis
 * read failure as "code not found" and bounce a legitimate login to an
 * error page while the code might still be sitting there, valid, on a
 * healthy replica. Neither is acceptable for a one-time login credential, so
 * both methods let a Redis {@link RuntimeException} propagate — the caller
 * (the SSO callback / the exchange endpoint) surfaces it as a failed login
 * attempt rather than silently corrupting the one-time-use guarantee.
 */
@Component
public class AuthExchangeCodeStore {

    private static final String KEY_PREFIX = "auth:sso-exchange:";

    /** 256-bit code, per spec (Fix B design). */
    private static final int CODE_BYTES = 32;

    private final RedissonClient redissonClient;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    @Autowired
    public AuthExchangeCodeStore(
            ObjectProvider<RedissonClient> redissonClientProvider,
            AuthProperties authProperties) {
        this(redissonClientProvider.getIfAvailable(), authProperties, Clock.systemUTC());
    }

    // In-memory-only fallback (no Redis client). Used by AuthExchangeCodeStoreTests.
    AuthExchangeCodeStore(AuthProperties authProperties, Clock clock) {
        this(null, authProperties, clock);
    }

    AuthExchangeCodeStore(RedissonClient redissonClient, AuthProperties authProperties, Clock clock) {
        this.redissonClient = redissonClient;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /**
     * Generates a 256-bit random code, stores {@code studentId} under it with
     * the configured TTL ({@code ssuai.auth.exchange-code-ttl}), and returns
     * the base64url (no padding) encoded code.
     */
    public String issue(String studentId) {
        byte[] raw = new byte[CODE_BYTES];
        secureRandom.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Duration ttl = authProperties.getExchangeCodeTtl();
        if (redissonClient != null) {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + code, StringCodec.INSTANCE);
            bucket.set(studentId, ttl);
        } else {
            codes.put(code, new Entry(studentId, clock.instant().plus(ttl)));
            pruneExpired();
        }
        return code;
    }

    /**
     * Atomically reads and removes the code. Returns the stored student id
     * if the code exists (and, for the in-memory fallback, has not expired);
     * empty otherwise. A blank/null code is treated as not-found without
     * touching the store.
     */
    public Optional<String> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        if (redissonClient != null) {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + code, StringCodec.INSTANCE);
            return Optional.ofNullable(bucket.getAndDelete());
        }
        Entry entry = codes.remove(code);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.studentId());
    }

    int size() {
        pruneExpired();
        return codes.size();
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        codes.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record Entry(String studentId, Instant expiresAt) {
    }
}
