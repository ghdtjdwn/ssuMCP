package com.ssuai.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestConcurrencyLimiterTests {

    @Test
    void enforcesPerClientAndGlobalCapsAndReleasesKeys() {
        RequestConcurrencyLimiter limiter = new RequestConcurrencyLimiter(2, 3);

        assertThat(limiter.tryAcquire("client-a")).isTrue();
        assertThat(limiter.tryAcquire("client-a")).isTrue();
        assertThat(limiter.tryAcquire("client-a")).isFalse();
        assertThat(limiter.tryAcquire("client-b")).isTrue();
        assertThat(limiter.tryAcquire("client-c")).isFalse();

        limiter.release("client-a");
        assertThat(limiter.tryAcquire("client-c")).isTrue();
        limiter.release("client-a");
        limiter.release("client-b");
        limiter.release("client-c");

        assertThat(limiter.activeKeys()).isZero();
    }
}
