package com.ssuai.domain.auth.lms;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class LmsSessionStoreTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Test
    void credentialNamespaceRetainsUpstreamIdentityAndVersion() {
        LmsSessionStore store = store();
        store.putForSession("owner-a", "upstream-user", new LmsCookies("base=one"));

        LmsSessionStore.LmsProviderSession session = store.session("owner-a").orElseThrow();

        assertThat(session.studentId()).isEqualTo("upstream-user");
        assertThat(session.cookies().sessionKey()).isEqualTo("owner-a");
        assertThat(session.credentialVersion()).isEqualTo(1);
    }

    @Test
    void outOfOrderNonConflictingSetCookieUpdatesAreBothPreserved() throws Exception {
        LmsSessionStore store = store();
        store.putForSession("owner-a", "upstream-user", new LmsCookies("base=one"));
        LmsCookies snapshot = store.cookies("owner-a").orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                await(start);
                store.mergeSetCookie(snapshot, List.of("route=a; Path=/"));
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                store.mergeSetCookie(snapshot, List.of("csrf=b; Path=/"));
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        String header = store.cookies("owner-a").orElseThrow().rawCookieHeader();
        assertThat(header).contains("base=one", "route=a", "csrf=b");
    }

    @Test
    void staleResponseCannotOverwriteCookieChangedByNewerCredentialVersion() {
        LmsSessionStore store = store();
        store.putForSession("owner-a", "upstream-user", new LmsCookies("refresh=initial"));
        LmsCookies stale = store.cookies("owner-a").orElseThrow();

        LmsCookies current = store.mergeSetCookie(stale, List.of("refresh=newer; Path=/"));
        store.mergeSetCookie(stale, List.of("refresh=stale-response; Path=/"));

        assertThat(current.credentialVersion()).isEqualTo(2);
        assertThat(store.cookies("owner-a").orElseThrow().rawCookieHeader())
                .contains("refresh=newer")
                .doesNotContain("stale-response");
    }

    @Test
    void unchangedFullJarValuesDoNotBlockAConcurrentRealCookieUpdate() {
        LmsSessionStore store = store();
        store.putForSession(
                "owner-a", "upstream-user", new LmsCookies("route=old; csrf=old"));
        LmsCookies sharedSnapshot = store.cookies("owner-a").orElseThrow();

        store.mergeSetCookie(sharedSnapshot, List.of("route=new; Path=/", "csrf=old"));
        store.mergeSetCookie(sharedSnapshot, List.of("csrf=new; Path=/", "route=old"));

        assertThat(store.cookies("owner-a").orElseThrow().rawCookieHeader())
                .contains("route=new", "csrf=new")
                .doesNotContain("route=old", "csrf=old");
    }

    @Test
    void deletionCookieRemovesCanonicalValue() {
        LmsSessionStore store = store();
        store.putForSession("owner-a", "upstream-user", new LmsCookies("keep=1; remove=2"));
        LmsCookies snapshot = store.cookies("owner-a").orElseThrow();

        store.mergeSetCookie(snapshot, List.of("remove=; Max-Age=0; Path=/"));

        assertThat(store.cookies("owner-a").orElseThrow().rawCookieHeader())
                .contains("keep=1")
                .doesNotContain("remove=");
    }

    @Test
    void staleResponseCannotResurrectCompositeKeyTombstone() {
        LmsSessionStore store = store();
        store.putForSession("owner-a", "upstream-user", new LmsCookies("route=old"));
        LmsCookies stale = store.cookies("owner-a").orElseThrow();

        store.mergeSetCookie(stale, List.of("route=; Max-Age=0; Path=/"));
        store.mergeSetCookie(stale, List.of("route=stale; Path=/"));

        assertThat(store.cookies("owner-a")).hasValueSatisfying(cookies ->
                assertThat(cookies.rawCookieHeader()).isBlank());
    }

    @Test
    void oneHundredSequentialAndConcurrentCallsUseOneValidSession() throws Exception {
        LmsSessionStore store = store();
        store.putForSession(
                "owner-a", "upstream-user", new LmsCookies("xn_api_token=valid; route=one"));

        for (int index = 0; index < 100; index++) {
            boolean authenticated = store.withSession(
                    "owner-a",
                    session -> session.cookies().rawCookieHeader().contains("xn_api_token=valid"));
            assertThat(authenticated).isTrue();
        }

        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                calls.add(() -> store.withSession(
                        "owner-a",
                        session -> session.cookies().rawCookieHeader().contains("xn_api_token=valid")));
            }
            List<Future<Boolean>> results = executor.invokeAll(calls);
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(store.health("owner-a").orElseThrow().health().name()).isEqualTo("VALID");
    }

    @Test
    void copiedCredentialIsIsolatedFromSourceInvalidation() {
        LmsSessionStore store = store();
        store.putForSession("web-owner", "upstream-user", new LmsCookies("xn_api_token=valid"));

        assertThat(store.copyForSession("web-owner", "mcp-owner")).isTrue();
        store.invalidate("web-owner");

        assertThat(store.cookies("web-owner")).isEmpty();
        assertThat(store.cookies("mcp-owner")).isPresent();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static LmsSessionStore store() {
        LmsSessionProperties properties = new LmsSessionProperties();
        properties.setTtl(Duration.ofHours(2));
        properties.setEncryptionKey("");
        return new LmsSessionStore(
                properties, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }
}
