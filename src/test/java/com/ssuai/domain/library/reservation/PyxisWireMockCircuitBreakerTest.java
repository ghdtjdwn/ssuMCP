package com.ssuai.domain.library.reservation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.resilience.PyxisResilience;

/**
 * WireMock-based integration test for Pyxis circuit breaker.
 *
 * <p>Uses a real HTTP stub server to verify that consecutive 5xx responses from the
 * Pyxis reservation endpoint cause the circuit breaker to open and short-circuit
 * subsequent calls without hitting the upstream.
 */
class PyxisWireMockCircuitBreakerTest {

    private static final String RESERVE_PATH = "/pyxis-api/1/api/seat-charges";
    private static final String STUB_TOKEN = "stub-pyxis-auth-token";
    private static final String NO_RECORD_BODY =
            "{\"success\":true,\"code\":\"success.noRecord\",\"message\":\"no record\",\"data\":null}";
    private static final String RESERVE_SUCCESS_BODY = """
            {
              "success": true,
              "code": "success.charged",
              "data": {
                "id": 1968552,
                "room": { "id": 58, "name": "room" },
                "seat": { "id": 3179, "code": "3179" },
                "beginTime": "2026-07-10 09:00",
                "endTime": "2026-07-10 13:00"
              }
            }
            """;

    private WireMockServer wireMockServer;
    private RealLibraryReservationConnector connector;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());

        String baseUrl = "http://localhost:" + wireMockServer.port();
        LibraryReservationProperties properties = new LibraryReservationProperties();
        properties.setBaseUrl(baseUrl);

        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        PyxisResilience pyxisResilience = PyxisResilience.forTesting(new SimpleMeterRegistry());

        connector = new RealLibraryReservationConnector(
                properties,
                new ObjectMapper(),
                restClient,
                pyxisResilience);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void circuitOpensAfterConsecutive5xxFromPyxisAndShortCircuitsNextCall() {
        // Stub all POST requests to /pyxis-api/1/api/seat-charges with 500
        stubFor(post(urlEqualTo(RESERVE_PATH))
                .willReturn(aResponse().withStatus(500)));

        // Drive calls until CallNotPermittedException (circuit open) or exhaust budget
        boolean circuitOpened = false;
        int maxCalls = 20;

        for (int i = 0; i < maxCalls; i++) {
            try {
                connector.reserve(STUB_TOKEN, new LibraryReservationRequest(3179L));
            } catch (CallNotPermittedException e) {
                circuitOpened = true;
                break;
            } catch (ConnectorUnavailableException | ConnectorTimeoutException ignored) {
                // expected transient failures while circuit is still CLOSED
            }
        }

        assertThat(circuitOpened)
                .as("Circuit breaker should have opened after repeated 5xx responses")
                .isTrue();

        // Do NOT assert an exact absolute count of upstream POSTs. The write chain runs a
        // RateLimiter / distributed dual-cap BEFORE the CircuitBreaker (see PyxisResilience.write),
        // and the count-based breaker opens once its minimum-calls + failure-rate window is
        // satisfied — so the precise number of real POSTs that reached WireMock before opening is
        // not a fixed function of the loop index (callsBeforeOpen). The only deterministic facts
        // are: (a) at least one real upstream call happened before the breaker opened, and
        // (b) once OPEN, the breaker issues ZERO further upstream calls. Assert exactly those.
        int seenAfterOpen = wireMockServer
                .countRequestsMatching(postRequestedFor(urlEqualTo(RESERVE_PATH)).build())
                .getCount();
        assertThat(seenAfterOpen)
                .as("At least one real upstream POST must have reached Pyxis before the breaker opened")
                .isGreaterThanOrEqualTo(1);

        // The behavioral contract that actually matters: an open breaker short-circuits the next
        // call — it throws CallNotPermittedException WITHOUT issuing a new HTTP request. Use a
        // unique token for the probe so delayed journal insertion from the final 500 response
        // cannot be mistaken for traffic from this call.
        String shortCircuitProbeToken = "short-circuit-probe-token";
        assertThatThrownBy(() -> connector.reserve(
                        shortCircuitProbeToken, new LibraryReservationRequest(3179L)))
                .isInstanceOf(CallNotPermittedException.class);

        await().during(Duration.ofMillis(250))
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> verify(0, postRequestedFor(urlEqualTo(RESERVE_PATH))
                        .withHeader("Pyxis-Auth-Token", equalTo(shortCircuitProbeToken))));

        // The read circuit breaker is independent of the (now-open) write breaker: a GET must
        // still reach upstream. Assert the read reached Pyxis via a delta of exactly one, not an
        // absolute verify — again robust to any journal lag on the still-settling POST history.
        stubFor(get(urlEqualTo(RESERVE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(NO_RECORD_BODY)));

        int getsBefore = wireMockServer
                .countRequestsMatching(getRequestedFor(urlEqualTo(RESERVE_PATH)).build())
                .getCount();
        assertThat(connector.getCurrentCharge(STUB_TOKEN)).isEmpty();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(wireMockServer
                        .countRequestsMatching(getRequestedFor(urlEqualTo(RESERVE_PATH)).build())
                        .getCount())
                        .as("The independent read path must still reach Pyxis exactly once")
                        .isEqualTo(getsBefore + 1));
    }

    @Test
    void readCircuitOpenDoesNotBlockReservationWrite() {
        stubFor(get(urlEqualTo(RESERVE_PATH))
                .willReturn(aResponse().withStatus(500)));
        stubFor(post(urlEqualTo(RESERVE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RESERVE_SUCCESS_BODY)));

        boolean readCircuitOpened = false;
        int previousGetRequests = 0;
        int maxCalls = 10;
        for (int i = 0; i < maxCalls; i++) {
            connector.getCurrentCharge(STUB_TOKEN);
            int getRequests = wireMockServer.findAll(getRequestedFor(urlEqualTo(RESERVE_PATH))).size();
            if (getRequests == previousGetRequests) {
                readCircuitOpened = true;
                break;
            }
            previousGetRequests = getRequests;
        }

        assertThat(readCircuitOpened)
                .as("Read circuit breaker should eventually short-circuit GET calls")
                .isTrue();

        LibraryReservationResult result = connector.reserve(STUB_TOKEN, new LibraryReservationRequest(3179L));

        assertThat(result.seatId()).isEqualTo(3179L);
        verify(1, postRequestedFor(urlEqualTo(RESERVE_PATH)));
    }
}
