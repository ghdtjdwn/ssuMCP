package com.ssuai.domain.lms.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsCookieJar;
import com.ssuai.domain.auth.lms.LmsSessionProperties;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.auth.mcp.McpProviderHealth;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LmsProviderSessionReliabilityTests {

    private MockWebServer server;
    private LmsSessionStore store;
    private RealLmsAssignmentsConnector assignments;
    private RealLmsMaterialsConnector materials;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public @NotNull MockResponse dispatch(@NotNull RecordedRequest request) {
                if (request.getPath() != null && request.getPath().contains("/terms")) {
                    return json("{\"enrollment_terms\":[{\"id\":42,\"name\":\"2026년 1학기\"}]}");
                }
                if (request.getPath() != null && request.getPath().contains("/courses")) {
                    return json("[{\"id\":100,\"name\":\"Course\",\"course_code\":\"C100\"}]");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();

        LmsSessionProperties sessionProperties = new LmsSessionProperties();
        sessionProperties.setTtl(Duration.ofHours(2));
        store = new LmsSessionStore(sessionProperties);
        LmsCookieJar jar = LmsCookieJar.fromLegacyHeader(
                "xn_api_token=valid; route=one", URI.create(server.url("/").toString()));
        store.putForSession("owner-a", "upstream-user",
                new LmsCookies("xn_api_token=valid; route=one", null, 0L, jar.serialize()));

        LmsSsoProperties connectorProperties = new LmsSsoProperties();
        String base = server.url("/").toString();
        base = base.substring(0, base.length() - 1);
        connectorProperties.setCanvasBaseUrl(base);
        connectorProperties.setCommonsBaseUrl(base);
        connectorProperties.setTimeout(Duration.ofSeconds(2));
        ObjectMapper objectMapper = new ObjectMapper();
        assignments = new RealLmsAssignmentsConnector(connectorProperties, objectMapper, store);
        materials = new RealLmsMaterialsConnector(
                connectorProperties, objectMapper, mock(LmsMaterialSizeResolver.class), store);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void oneHundredSequentialAndConcurrentMixedEndpointCallsRemainAuthenticated()
            throws Exception {
        for (int index = 0; index < 100; index++) {
            assertThat(mixedCall(index)).isTrue();
        }

        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int callIndex = index;
                calls.add(() -> mixedCall(callIndex));
            }
            List<Future<Boolean>> results = executor.invokeAll(calls);
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(server.getRequestCount()).isEqualTo(200);
        assertThat(store.cookies("owner-a").orElseThrow().rawCookieHeader())
                .contains("xn_api_token=valid");
        assertThat(store.health("owner-a").orElseThrow().health())
                .isEqualTo(McpProviderHealth.VALID);
    }

    @Test
    void cookieRotatedByOneConnectorIsUsedByTheImmediatelyFollowingConnector()
            throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public @NotNull MockResponse dispatch(@NotNull RecordedRequest request) {
                if (request.getPath() != null && request.getPath().contains("/terms")) {
                    return json("{\"enrollment_terms\":[{\"id\":42,\"name\":\"2026년 1학기\"}]}")
                            .setHeader("Set-Cookie", "xn_api_token=rotated; Path=/; HttpOnly");
                }
                if (request.getPath() != null && request.getPath().contains("/courses")) {
                    if (!"Bearer rotated".equals(request.getHeader("Authorization"))) {
                        return new MockResponse().setResponseCode(401);
                    }
                    return json("[{\"id\":100,\"name\":\"Course\",\"course_code\":\"C100\"}]");
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        boolean authenticated = store.withSession("owner-a", provider -> {
            // provider.cookies() is intentionally the snapshot captured before fetchTerms.
            boolean termOk = assignments.fetchTerms(
                    provider.studentId(), provider.cookies()).size() == 1;
            boolean courseOk = materials.fetchCourses(
                    provider.studentId(), provider.cookies(), 42L).size() == 1;
            return termOk && courseOk;
        });

        assertThat(authenticated).isTrue();
        assertThat(store.cookies("owner-a").orElseThrow().rawCookieHeader())
                .contains("xn_api_token=rotated")
                .doesNotContain("xn_api_token=valid");
    }

    private boolean mixedCall(int index) {
        return store.withSession("owner-a", provider -> {
            if (index % 2 == 0) {
                return assignments.fetchTerms(provider.studentId(), provider.cookies()).size() == 1;
            }
            return materials.fetchCourses(
                    provider.studentId(), provider.cookies(), 42L).size() == 1;
        });
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Set-Cookie", "route=stable; Path=/; HttpOnly")
                .setBody(body);
    }
}
