package com.ssuai.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;

class RateLimitFilterTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration MCP_ASYNC_LEASE_TIMEOUT = Duration.ofSeconds(30);

    // Small injected limits (login=2, chat=3, confirm=4, refresh=5) so tests fire a handful of requests.
    private RateLimitFilter filter() {
        return RateLimitFilter.forRules(
                2, 3, 4, 5, 2, 1, 2,
                MCP_ASYNC_LEASE_TIMEOUT, Duration.ofMinutes(1), MAPPER);
    }

    private static MockHttpServletRequest post(String uri, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("10.0.0.1"); // ingress hop — must be ignored when XFF present
        if (xff != null) {
            request.addHeader(ClientIpResolver.X_FORWARDED_FOR, xff);
        }
        return request;
    }

    private static int fire(RateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    // --- under the limit passes -------------------------------------------

    @Test
    void requestsUnderTheLimitPass() throws Exception {
        RateLimitFilter filter = filter();
        // login limit = 2: first two pass.
        assertThat(fire(filter, post("/api/library/login", "1.2.3.4"))).isEqualTo(HttpStatus.OK.value());
        assertThat(fire(filter, post("/api/library/login", "1.2.3.4"))).isEqualTo(HttpStatus.OK.value());
    }

    // --- exceeding the per-IP limit → 429 + Retry-After --------------------

    @Test
    void exceedingTheLimitReturns429WithRetryAfter() throws Exception {
        RateLimitFilter filter = filter();
        fire(filter, post("/api/library/login", "1.2.3.4"));
        fire(filter, post("/api/library/login", "1.2.3.4"));

        MockHttpServletRequest third = post("/api/library/login", "1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(third, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(Integer.parseInt(response.getHeader(HttpHeaders.RETRY_AFTER))).isGreaterThan(0);
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
    }

    // --- per-IP isolation: two IPs are limited independently ---------------

    @Test
    void twoDifferentIpsAreLimitedIndependently() throws Exception {
        RateLimitFilter filter = filter();
        // Exhaust IP A's login budget (limit 2).
        fire(filter, post("/api/library/login", "1.1.1.1"));
        fire(filter, post("/api/library/login", "1.1.1.1"));
        assertThat(fire(filter, post("/api/library/login", "1.1.1.1")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // IP B has its own untouched budget.
        assertThat(fire(filter, post("/api/library/login", "2.2.2.2"))).isEqualTo(HttpStatus.OK.value());
        assertThat(fire(filter, post("/api/library/login", "2.2.2.2"))).isEqualTo(HttpStatus.OK.value());
        assertThat(fire(filter, post("/api/library/login", "2.2.2.2")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // --- chat endpoint uses its own (higher) limit -------------------------

    @Test
    void chatEndpointUsesItsOwnLimit() throws Exception {
        RateLimitFilter filter = filter();
        // chat limit = 3.
        assertThat(fire(filter, post("/api/chat", "9.9.9.9"))).isEqualTo(HttpStatus.OK.value());
        assertThat(fire(filter, post("/api/chat", "9.9.9.9"))).isEqualTo(HttpStatus.OK.value());
        assertThat(fire(filter, post("/api/chat", "9.9.9.9"))).isEqualTo(HttpStatus.OK.value());
        assertThat(fire(filter, post("/api/chat", "9.9.9.9")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // --- login and chat buckets are separate even for the same IP ----------

    @Test
    void loginAndChatBucketsAreSeparatePerSameIp() throws Exception {
        RateLimitFilter filter = filter();
        // Exhaust login (limit 2) for an IP.
        fire(filter, post("/api/library/login", "5.5.5.5"));
        fire(filter, post("/api/library/login", "5.5.5.5"));
        assertThat(fire(filter, post("/api/library/login", "5.5.5.5")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        // chat for the same IP is unaffected.
        assertThat(fire(filter, post("/api/chat", "5.5.5.5"))).isEqualTo(HttpStatus.OK.value());
    }

    // --- reservation confirm endpoint has its own limit -------------------

    @Test
    void confirmEndpointIsThrottledOnItsOwnLimit() throws Exception {
        RateLimitFilter filter = filter();
        // confirm limit = 4 (write path: real seat reserve/cancel/swap).
        for (int i = 0; i < 4; i++) {
            assertThat(fire(filter, post("/api/library/reservations/confirm", "7.7.7.7")))
                    .isEqualTo(HttpStatus.OK.value());
        }
        assertThat(fire(filter, post("/api/library/reservations/confirm", "7.7.7.7")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // --- unmatched paths and methods are not throttled ---------------------

    @Test
    void unmatchedPathIsNotFiltered() {
        RateLimitFilter filter = filter();
        assertThat(filter.shouldNotFilter(post("/api/meal/today", null))).isTrue();
    }

    @Test
    void getRequestIsNotFiltered() {
        RateLimitFilter filter = filter();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/library/login");
        assertThat(filter.shouldNotFilter(get)).isTrue();
    }

    @Test
    void mcpGetIsFilteredButDeleteRemainsAvailable() {
        RateLimitFilter filter = filter();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/mcp");
        MockHttpServletRequest delete = new MockHttpServletRequest("DELETE", "/mcp");

        assertThat(filter.shouldNotFilter(get)).isFalse();
        assertThat(filter.shouldNotFilter(delete)).isTrue();
    }

    @Test
    void mcpPostIsThrottledAndNestedPathUsesSameRule() throws Exception {
        RateLimitFilter filter = filter();

        assertThat(fire(filter, post("/mcp", "8.8.8.8"))).isEqualTo(HttpStatus.OK.value());
        assertThat(fire(filter, post("/mcp/messages", "8.8.8.8"))).isEqualTo(HttpStatus.OK.value());
        assertThat(fire(filter, post("/mcp", "8.8.8.8")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void throttlingLogUsesMatchedRuleInsteadOfRawControlCharacterPath() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimitFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RateLimitFilter filter = filter();
            String attackerPath = "/mcp/stream\r\nforged=route\u0000";
            fire(filter, post(attackerPath, "8.8.8.8"));
            fire(filter, post(attackerPath, "8.8.8.8"));
            assertThat(fire(filter, post(attackerPath, "8.8.8.8")))
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .isEqualTo("event=rate_limit_exceeded route=/mcp")
                    .doesNotContain("\r", "\n", "\u0000", "forged", "stream");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void mcpAsyncLeaseIsHeldUntilStreamCompletes() throws Exception {
        RateLimitFilter filter = RateLimitFilter.forRules(
                2, 3, 4, 5, 10, 1, 2,
                MCP_ASYNC_LEASE_TIMEOUT, Duration.ofMinutes(1), MAPPER);
        MockHttpServletRequest streaming = new MockHttpServletRequest("GET", "/mcp");
        streaming.setRemoteAddr("10.0.0.1");
        streaming.addHeader(ClientIpResolver.X_FORWARDED_FOR, "8.8.4.4");
        streaming.setAsyncSupported(true);
        MockHttpServletResponse streamingResponse = new MockHttpServletResponse();

        filter.doFilter(streaming, streamingResponse, (request, response) ->
                ((HttpServletRequest) request).startAsync());

        assertThat(streaming.getAsyncContext().getTimeout())
                .isEqualTo(MCP_ASYNC_LEASE_TIMEOUT.toMillis());

        MockHttpServletRequest whileStreaming = new MockHttpServletRequest("GET", "/mcp");
        whileStreaming.setRemoteAddr("10.0.0.1");
        whileStreaming.addHeader(ClientIpResolver.X_FORWARDED_FOR, "8.8.4.4");
        assertThat(fire(filter, whileStreaming)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        streaming.getAsyncContext().complete();

        MockHttpServletRequest afterCompletion = new MockHttpServletRequest("GET", "/mcp");
        afterCompletion.setRemoteAddr("10.0.0.1");
        afterCompletion.addHeader(ClientIpResolver.X_FORWARDED_FOR, "8.8.4.4");
        assertThat(fire(filter, afterCompletion)).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void mcpAsyncLeaseIsReleasedWhenBoundedTimeoutFires() throws Exception {
        RateLimitFilter filter = RateLimitFilter.forRules(
                2, 3, 4, 5, 10, 1, 2,
                MCP_ASYNC_LEASE_TIMEOUT, Duration.ofMinutes(1), MAPPER);
        MockHttpServletRequest streaming = new MockHttpServletRequest("GET", "/mcp");
        streaming.setRemoteAddr("10.0.0.1");
        streaming.addHeader(ClientIpResolver.X_FORWARDED_FOR, "8.8.4.4");
        streaming.setAsyncSupported(true);
        MockHttpServletResponse streamingResponse = new MockHttpServletResponse();

        filter.doFilter(streaming, streamingResponse, (request, response) ->
                ((HttpServletRequest) request).startAsync());

        MockHttpServletRequest whileStreaming = new MockHttpServletRequest("GET", "/mcp");
        whileStreaming.setRemoteAddr("10.0.0.1");
        whileStreaming.addHeader(ClientIpResolver.X_FORWARDED_FOR, "8.8.4.4");
        assertThat(fire(filter, whileStreaming)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        MockAsyncContext asyncContext = (MockAsyncContext) streaming.getAsyncContext();
        AsyncEvent timeoutEvent = new AsyncEvent(asyncContext, streaming, streamingResponse);
        for (var listener : asyncContext.getListeners()) {
            listener.onTimeout(timeoutEvent);
        }

        MockHttpServletRequest afterTimeout = new MockHttpServletRequest("GET", "/mcp");
        afterTimeout.setRemoteAddr("10.0.0.1");
        afterTimeout.addHeader(ClientIpResolver.X_FORWARDED_FOR, "8.8.4.4");
        assertThat(fire(filter, afterTimeout)).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void synchronousMcpPostReleasesLeaseImmediately() throws Exception {
        RateLimitFilter filter = RateLimitFilter.forRules(
                2, 3, 4, 5, 10, 1, 2,
                MCP_ASYNC_LEASE_TIMEOUT, Duration.ofMinutes(1), MAPPER);

        MockHttpServletRequest initialize = post("/mcp", "8.8.4.4");
        assertThat(fire(filter, initialize)).isEqualTo(HttpStatus.OK.value());
        assertThat(initialize.isAsyncStarted()).isFalse();

        assertThat(fire(filter, post("/mcp", "8.8.4.4")))
                .isEqualTo(HttpStatus.OK.value());
    }

    // --- X-Forwarded-For resolution (right-trusted-hop): see ClientIpResolverTests
    // for the full matrix (forged prefixes, multi-hop, malformed headers). This
    // file only keeps filter-level integration coverage below.

    @Test
    void differentXffClientsBypassSharedIngressRemoteAddr() throws Exception {
        // Both requests share remoteAddr 10.0.0.1 (the ingress) but carry
        // different XFF clients — they must NOT share a bucket.
        RateLimitFilter filter = filter();
        fire(filter, post("/api/library/login", "100.0.0.1"));
        fire(filter, post("/api/library/login", "100.0.0.1"));
        assertThat(fire(filter, post("/api/library/login", "100.0.0.1")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        // Different XFF client, same ingress remoteAddr → independent budget.
        assertThat(fire(filter, post("/api/library/login", "100.0.0.2")))
                .isEqualTo(HttpStatus.OK.value());
    }
}
