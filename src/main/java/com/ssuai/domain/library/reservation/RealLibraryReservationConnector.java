package com.ssuai.domain.library.reservation;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.library-reservation", havingValue = "real")
public class RealLibraryReservationConnector implements LibraryReservationConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLibraryReservationConnector.class);

    // TODO: Verify the actual reservation endpoint/body in oasis.ssu.ac.kr DevTools.
    // Current implementation follows the existing Pyxis seat-rooms API pattern.
    private static final String RESERVE_PATH = "/pyxis-api/1/seat-reservations";
    private static final String NEED_LOGIN_CODE = "error.authentication.needLogin";

    private final LibraryReservationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RealLibraryReservationConnector(
            LibraryReservationProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("libraryReservationRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public void reserve(String pyxisAuthToken, LibraryReservationRequest request) {
        int floorCode = parseFloor(request.floor());
        Map<String, Object> body = Map.of(
                "seatId", request.seatId(),
                "floor", floorCode,
                "smufMethodCode", "PC",
                "branchGroupId", 1
        );
        String response = callUpstream(pyxisAuthToken, body);
        parseResponse(response);
    }

    private String callUpstream(String pyxisAuthToken, Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(RESERVE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Pyxis-Auth-Token", pyxisAuthToken != null ? pyxisAuthToken : "")
                    .header("Referer", properties.getReferer())
                    .header("Accept-Language", "ko")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException exception) {
            log.warn("library reservation connector timeout/io");
            throw alert(new ConnectorTimeoutException(exception));
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            log.warn("library reservation connector http error: status={}", status.value());
            if (status.is5xxServerError()) {
                throw alert(new ConnectorUnavailableException(exception));
            }
            throw new ConnectorParseException(exception);
        }
    }

    private static ConnectorException alert(ConnectorException exception) {
        return exception;
    }

    private void parseResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new ConnectorParseException();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new ConnectorParseException(exception);
        }

        if (root.path("success").asBoolean(false)) {
            return;
        }

        String code = root.path("code").asText("");
        if (NEED_LOGIN_CODE.equals(code)) {
            log.info("library reservation upstream returned needLogin");
            throw new LibraryAuthRequiredException();
        }
        log.warn("library reservation upstream returned success=false: code={}", code);
        throw new ConnectorParseException();
    }

    private static int parseFloor(String floor) {
        if (floor == null || floor.isBlank()) {
            throw new IllegalArgumentException("floor is required");
        }
        String normalized = floor.trim().toUpperCase();
        if (normalized.endsWith("F")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid floor: " + floor, exception);
        }
    }
}
