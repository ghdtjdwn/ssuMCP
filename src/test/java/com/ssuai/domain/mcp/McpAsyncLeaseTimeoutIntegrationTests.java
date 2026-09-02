package com.ssuai.domain.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;

/** Exercises MCP concurrency-lease recovery through a real servlet async timeout. */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ssuai.mcp.tool-profile=playmcp",
            "ssuai.ratelimit.redis-enabled=false",
            "ssuai.ratelimit.mcp-concurrent-per-ip=1",
            "ssuai.ratelimit.mcp-concurrent-global=1",
            "ssuai.ratelimit.mcp-async-lease-timeout=2s"
        })
class McpAsyncLeaseTimeoutIntegrationTests {

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final String INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-03-26",
              "capabilities":{},
              "clientInfo":{"name":"async-lease-test","version":"1.0"}
            }}
            """;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ListeningGetProbe listeningGetProbe;

    @Test
    void servletTimeoutClosesListeningGetAndReleasesConcurrencyLease() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        URI endpoint = URI.create("http://127.0.0.1:" + serverPort + "/mcp");
        String firstSessionId = null;
        String recoveredSessionId = null;
        HttpResponse<InputStream> listeningResponse = null;

        try {
            HttpResponse<String> initialized = initialize(client, endpoint);
            assertThat(initialized.statusCode()).isEqualTo(200);
            firstSessionId = initialized.headers().firstValue("Mcp-Session-Id").orElse(null);
            assertThat(firstSessionId).isNotBlank();

            HttpRequest listeningGet = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", firstSessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<InputStream>> listeningFuture = client.sendAsync(
                    listeningGet, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(listeningGetProbe.await(Duration.ofSeconds(5))).isTrue();

            HttpResponse<String> whileListening = initialize(client, endpoint);
            assertThat(whileListening.statusCode()).isEqualTo(429);
            assertThat(whileListening.body()).contains("RATE_LIMITED");

            listeningResponse = listeningFuture.get(5, TimeUnit.SECONDS);
            awaitStreamClosure(listeningResponse.body());

            HttpResponse<String> afterTimeout = initialize(client, endpoint);
            assertThat(afterTimeout.statusCode()).isEqualTo(200);
            recoveredSessionId = afterTimeout.headers()
                    .firstValue("Mcp-Session-Id")
                    .orElse(null);
            assertThat(recoveredSessionId).isNotBlank();
        } finally {
            if (listeningResponse != null) {
                listeningResponse.body().close();
            }
            deleteSession(client, endpoint, firstSessionId);
            deleteSession(client, endpoint, recoveredSessionId);
        }
    }

    private static HttpResponse<String> initialize(HttpClient client, URI endpoint)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json,text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_BODY))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void awaitStreamClosure(InputStream stream) throws Exception {
        CompletableFuture.runAsync(() -> {
            try {
                stream.readAllBytes();
            } catch (IOException connectionClosed) {
                // Tomcat may report its timeout as either EOF or a closed chunked stream.
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private static void deleteSession(HttpClient client, URI endpoint, String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5))
                    .header("Mcp-Session-Id", sessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .DELETE()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException cleanupFailure) {
            if (cleanupFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class ListeningGetProbe {
        private final CountDownLatch started = new CountDownLatch(1);

        void markStarted() {
            started.countDown();
        }

        boolean await(Duration timeout) throws InterruptedException {
            return started.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ListeningGetProbe listeningGetProbe() {
            return new ListeningGetProbe();
        }

        @Bean
        FilterRegistrationBean<Filter> listeningGetProbeRegistration(
                ListeningGetProbe probe) {
            Filter filter = (request, response, chain) -> {
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                if ("GET".equalsIgnoreCase(httpRequest.getMethod())
                        && "/mcp".equals(httpRequest.getRequestURI())) {
                    probe.markStarted();
                }
                chain.doFilter(request, response);
            };
            FilterRegistrationBean<Filter> registration =
                    new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/mcp");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 130);
            return registration;
        }
    }
}
