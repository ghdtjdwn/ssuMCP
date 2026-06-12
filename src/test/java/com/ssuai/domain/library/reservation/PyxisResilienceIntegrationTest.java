package com.ssuai.domain.library.reservation;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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

class PyxisResilienceIntegrationTest {

    private static final String TOKEN = "stub-pyxis-auth-token";
    private static final String RESERVE_PATH = "/pyxis-api/1/api/seat-charges";

    private WireMockServer wireMockServer;
    private SimpleMeterRegistry meterRegistry;
    private RealLibraryReservationConnector connector;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        LibraryReservationProperties properties = new LibraryReservationProperties();
        properties.setBaseUrl(wireMockServer.baseUrl());
        meterRegistry = new SimpleMeterRegistry();
        connector = new RealLibraryReservationConnector(
                properties,
                new ObjectMapper(),
                RestClient.builder().baseUrl(wireMockServer.baseUrl()).build(),
                new PyxisResilience(meterRegistry));
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void opensCircuitAfterConsecutiveServerErrorsAndShortCircuitsNextCall() {
        stubFor(WireMock.post(urlEqualTo(RESERVE_PATH))
                .willReturn(WireMock.serverError()));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                    .isInstanceOfAny(ConnectorUnavailableException.class, ConnectorTimeoutException.class);
        }

        assertThat(meterRegistry.find("resilience4j.circuitbreaker.state")
                .tag("name", "pyxis")
                .tag("state", "open")
                .gauge()
                .value()).isEqualTo(1.0);

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                .isInstanceOf(CallNotPermittedException.class);
        wireMockServer.verify(10, postRequestedFor(urlEqualTo(RESERVE_PATH))
                .withHeader("Pyxis-Auth-Token", equalTo(TOKEN)));
    }
}
