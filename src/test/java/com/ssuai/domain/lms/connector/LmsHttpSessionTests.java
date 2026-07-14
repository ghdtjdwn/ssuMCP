package com.ssuai.domain.lms.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsCookieJar;
import com.ssuai.domain.auth.lms.LmsSessionProperties;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.mcp.McpProviderHealth;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LmsSessionExpiredException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LmsHttpSessionTests {

    private MockWebServer server;
    private LmsSessionStore store;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        LmsSessionProperties properties = new LmsSessionProperties();
        properties.setTtl(Duration.ofHours(2));
        store = new LmsSessionStore(properties);
        LmsCookieJar jar = LmsCookieJar.fromLegacyHeader(
                "xn_api_token=jwt; route=old", URI.create(url("/")));
        store.putForSession("owner-a", "upstream-user",
                new LmsCookies("xn_api_token=jwt; route=old", null, 0L, jar.serialize()));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void setCookieRefreshIsPersistedAndUsedByTheNextEndpointCall() throws Exception {
        server.enqueue(json("{\"ok\":true}")
                .setHeader("Set-Cookie", "route=rotated; Path=/; HttpOnly"));
        server.enqueue(json("{\"ok\":true}"));

        store.withSession("owner-a", provider -> {
            http(provider.cookies()).getJson(url("/first"), true);
            return null;
        });
        LmsCookies refreshed = store.cookies("owner-a").orElseThrow();
        assertThat(refreshed.rawCookieHeader()).contains("xn_api_token=jwt", "route=rotated");
        assertThat(refreshed.credentialVersion()).isGreaterThan(1);

        store.withSession("owner-a", provider -> {
            http(provider.cookies()).getJson(url("/second"), true);
            return null;
        });

        server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(second.getHeader("Cookie")).contains("route=rotated");
        assertThat(store.health("owner-a").orElseThrow().health())
                .isEqualTo(McpProviderHealth.VALID);
    }

    @Test
    void loginHtmlReturnedWithHttp200IsDefinitiveExpiration() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setHeader("Set-Cookie", "route=rotated; Path=/")
                .setBody("<html><body><form action='/login'>로그인</form></body></html>"));

        assertThatThrownBy(() -> store.withSession("owner-a", provider ->
                http(provider.cookies()).getJson(url("/api/terms"), true)))
                .isInstanceOf(LmsSessionExpiredException.class)
                .hasMessageContaining("login page");
        assertThat(store.health("owner-a").orElseThrow())
                .satisfies(health -> {
                    assertThat(health.health()).isEqualTo(McpProviderHealth.EXPIRED);
                    assertThat(health.failureCode()).isEqualTo("UPSTREAM_SESSION_EXPIRED");
                });
        assertThat(store.cookies("owner-a").orElseThrow().rawCookieHeader()).contains("route=rotated");
    }

    @Test
    void maintenanceHtmlIsParserFailureNotSessionExpiration() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html><title>Scheduled maintenance</title><body>Try later</body></html>"));

        assertThatThrownBy(() -> store.withSession("owner-a", provider ->
                http(provider.cookies()).getJson(url("/api/terms"), true)))
                .isInstanceOf(ConnectorParseException.class)
                .isNotInstanceOf(LmsSessionExpiredException.class);
        assertThat(store.health("owner-a").orElseThrow())
                .satisfies(health -> {
                    assertThat(health.health()).isEqualTo(McpProviderHealth.ERROR);
                    assertThat(health.failureCode()).isEqualTo("PARSER_ERROR");
                });
    }

    @Test
    void transientServerFailureIsRetriedAndDoesNotMarkSessionExpired() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("temporarily unavailable"));
        server.enqueue(json("{\"ok\":true}"));

        store.withSession("owner-a", provider ->
                http(provider.cookies()).getJson(url("/api/terms"), true));

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(store.health("owner-a").orElseThrow())
                .satisfies(health -> {
                    assertThat(health.health()).isEqualTo(McpProviderHealth.VALID);
                    assertThat(health.failureCode()).isNull();
                });
    }

    @Test
    void repeatedServerFailureIsUnavailableNotExpiration() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("one"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("two"));

        assertThatThrownBy(() -> store.withSession("owner-a", provider ->
                http(provider.cookies()).getJson(url("/api/terms"), true)))
                .isInstanceOf(ConnectorUnavailableException.class)
                .isNotInstanceOf(LmsSessionExpiredException.class);
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(store.health("owner-a").orElseThrow())
                .satisfies(health -> {
                    assertThat(health.health()).isEqualTo(McpProviderHealth.ERROR);
                    assertThat(health.failureCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
                });
    }

    @Test
    void untrustedRedirectIsNeverFollowed() {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "https://attacker.example/collect")
                .setHeader("Set-Cookie", "route=rotated-before-reject; Path=/"));

        assertThatThrownBy(() -> store.withSession("owner-a", provider ->
                http(provider.cookies()).getJson(url("/api/terms"), true)))
                .isInstanceOf(com.ssuai.global.exception.LmsApiException.class)
                .hasMessageContaining("not trusted");
        assertThat(store.cookies("owner-a").orElseThrow().rawCookieHeader())
                .contains("route=rotated-before-reject");
    }

    private LmsHttpSession http(LmsCookies cookies) {
        return new LmsHttpSession(
                new ObjectMapper(), store, cookies, Duration.ofSeconds(2), url("/"));
    }

    private String url(String path) {
        return server.url(path).toString();
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
