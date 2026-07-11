package com.ssuai.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class AuthExchangeCodeStoreTests {

    private static final Instant T0 = Instant.parse("2026-07-12T10:00:00Z");

    private static AuthProperties propertiesWithTtl(Duration ttl) {
        AuthProperties properties = new AuthProperties();
        properties.setExchangeCodeTtl(ttl);
        return properties;
    }

    @Test
    void issueThenConsumeRoundTripsTheStudentIdAndIsSingleUse() {
        AuthExchangeCodeStore store = new AuthExchangeCodeStore(
                propertiesWithTtl(Duration.ofSeconds(120)), Clock.fixed(T0, ZoneOffset.UTC));

        String code = store.issue("20231234");

        assertThat(code).isNotBlank();
        assertThat(store.consume(code)).contains("20231234");
        // Single-use: a second consume of the same code must be empty.
        assertThat(store.consume(code)).isEmpty();
    }

    @Test
    void issuedCodesAreDistinctAndUrlSafe() {
        AuthExchangeCodeStore store = new AuthExchangeCodeStore(
                propertiesWithTtl(Duration.ofSeconds(120)), Clock.fixed(T0, ZoneOffset.UTC));

        String first = store.issue("20231234");
        String second = store.issue("20231234");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void consumeReturnsEmptyOnceCodeHasExpired() {
        MutableClock clock = new MutableClock(T0);
        AuthExchangeCodeStore store = new AuthExchangeCodeStore(
                propertiesWithTtl(Duration.ofSeconds(120)), clock);

        String code = store.issue("20231234");
        clock.advance(Duration.ofSeconds(121));

        assertThat(store.consume(code)).isEmpty();
    }

    @Test
    void consumeReturnsEmptyForUnknownOrBlankCode() {
        AuthExchangeCodeStore store = new AuthExchangeCodeStore(
                propertiesWithTtl(Duration.ofSeconds(120)), Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(store.consume("never-issued")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume("   ")).isEmpty();
    }

    @Test
    void redisIssueErrorPropagatesFailClosed() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getBucket(anyString(), eq(StringCodec.INSTANCE)))
                .thenThrow(new IllegalStateException("redis down"));
        AuthExchangeCodeStore store = new AuthExchangeCodeStore(
                redissonClient, propertiesWithTtl(Duration.ofSeconds(120)), Clock.fixed(T0, ZoneOffset.UTC));

        // Unlike RefreshTokenDenylist, a Redis error must NOT be swallowed here.
        assertThatThrownBy(() -> store.issue("20231234"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void redisConsumeErrorPropagatesFailClosed() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getBucket(anyString(), eq(StringCodec.INSTANCE)))
                .thenThrow(new IllegalStateException("redis down"));
        AuthExchangeCodeStore store = new AuthExchangeCodeStore(
                redissonClient, propertiesWithTtl(Duration.ofSeconds(120)), Clock.fixed(T0, ZoneOffset.UTC));

        assertThatThrownBy(() -> store.consume("some-code"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void redisBackedConsumeIsAtomicReadAndDelete() {
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.<String>getBucket(eq("auth:sso-exchange:the-code"), eq(StringCodec.INSTANCE)))
                .thenReturn(bucket);
        when(bucket.getAndDelete()).thenReturn("20231234");
        AuthExchangeCodeStore store = new AuthExchangeCodeStore(
                redissonClient, propertiesWithTtl(Duration.ofSeconds(120)), Clock.fixed(T0, ZoneOffset.UTC));

        Optional<String> result = store.consume("the-code");

        assertThat(result).contains("20231234");
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
