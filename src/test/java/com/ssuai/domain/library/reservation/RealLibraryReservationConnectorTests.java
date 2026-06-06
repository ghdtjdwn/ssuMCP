package com.ssuai.domain.library.reservation;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class RealLibraryReservationConnectorTests {

    private static final String TOKEN = "stub-pyxis-auth-token";
    private static final String BASE_URL = "https://oasis.test.local";
    private static final String RESERVE_URL = BASE_URL + "/pyxis-api/1/seat-reservations";

    private MockRestServiceServer server;
    private RealLibraryReservationConnector connector;

    @BeforeEach
    void setUp() {
        LibraryReservationProperties properties = new LibraryReservationProperties();
        properties.setBaseUrl(BASE_URL);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new RealLibraryReservationConnector(properties, new ObjectMapper(), builder.build());
    }

    @Test
    void reservesSeatOnSuccessResponse() {
        server.expect(requestTo(RESERVE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Pyxis-Auth-Token", TOKEN))
                .andExpect(header("Referer", "https://oasis.ssu.ac.kr/library-services/smuf/reading-rooms"))
                .andExpect(content().json("""
                        {
                          "seatId": "15",
                          "floor": 5,
                          "smufMethodCode": "PC",
                          "branchGroupId": 1
                        }
                        """))
                .andRespond(withSuccess("""
                        {"success":true,"code":"success","data":{}}
                        """, MediaType.APPLICATION_JSON));

        assertThatNoException()
                .isThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest("5F", "15")));
    }

    @Test
    void throwsLibraryAuthRequiredOnNeedLogin() {
        String needLoginBody = """
                {"success":false,"code":"error.authentication.needLogin","message":"Please log in.","data":null}
                """;
        server.expect(requestTo(RESERVE_URL))
                .andRespond(withSuccess(needLoginBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest("2F", "15")))
                .isInstanceOf(LibraryAuthRequiredException.class);
    }

    @Test
    void throwsConnectorParseExceptionOnSuccessFalseWithUnknownCode() {
        String body = """
                {"success":false,"code":"error.unknown","message":"Unknown.","data":null}
                """;
        server.expect(requestTo(RESERVE_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest("2F", "15")))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void serverErrorMapsToConnectorUnavailable() {
        server.expect(requestTo(RESERVE_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest("2F", "15")))
                .isInstanceOf(ConnectorUnavailableException.class);
    }

    @Test
    void ioExceptionMapsToConnectorTimeout() {
        server.expect(requestTo(RESERVE_URL))
                .andRespond(withException(new IOException("connect timed out")));

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest("2F", "15")))
                .isInstanceOf(ConnectorTimeoutException.class);
    }
}
