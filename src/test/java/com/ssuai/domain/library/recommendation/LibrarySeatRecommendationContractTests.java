package com.ssuai.domain.library.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Wire-contract guard for the seat-recommendation response (TROUBLESHOOTING
 * 2026-07-02 contract-drift incident). The web frontend consumes this envelope
 * through a runtime zod schema (ssuAI {@code lib/api/library.ts}) that mirrors
 * exactly these field names and types; the incident happened because the
 * backend envelope evolved while the frontend kept its own idea of the shape
 * and nothing failed loudly.
 *
 * <p>If this test breaks, the change is renaming/removing/retyping a field the
 * frontend depends on — land the frontend schema change in the same unit of
 * work, or keep the old field. Adding NEW fields is backward-compatible and
 * requires updating the expected sets here (a deliberate speed bump).
 */
class LibrarySeatRecommendationContractTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void responseEnvelopeKeepsThePublishedFieldNamesAndTypes() {
        JsonNode json = objectMapper.valueToTree(sampleResponse());

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "floor", "floorLabel", "requestedLimit", "availabilitySource",
                "message", "excludedRooms", "warnings", "recommendations",
                "softPreferences", "requestedPreferenceCount", "attributeDataCoverage");

        assertThat(json.get("floor").isInt()).as("floor is a number").isTrue();
        assertThat(json.get("floorLabel").isTextual()).isTrue();
        assertThat(json.get("availabilitySource").isTextual()).isTrue();
        assertThat(json.get("recommendations").isArray()).isTrue();
    }

    @Test
    void recommendationItemKeepsThePublishedFieldNamesAndTypes() {
        JsonNode item = objectMapper.valueToTree(sampleResponse())
                .get("recommendations").get(0);

        assertThat(fieldNames(item)).containsExactlyInAnyOrder(
                "seatId", "externalSeatId", "label", "roomCode", "roomName",
                "zone", "seatType", "audience", "status", "score",
                "matchedPreferences", "missingPreferences", "attributes", "note",
                "requestedPreferenceCount", "matchedPreferenceCount", "unknownPreferences",
                "attributeStates", "confidence", "attributeCoverage");

        // The incident's sharpest edge: externalSeatId is a STRING on the wire
        // (the web reservation path converts it to a number itself).
        assertThat(item.get("externalSeatId").isTextual()).isTrue();
        assertThat(item.get("score").isInt()).isTrue();
        assertThat(item.get("attributes").isObject())
                .as("attributes is a structured record, not a string array")
                .isTrue();
    }

    @Test
    void attributesRecordKeepsThePublishedBooleanFlags() {
        JsonNode attributes = objectMapper.valueToTree(sampleResponse())
                .get("recommendations").get(0).get("attributes");

        assertThat(fieldNames(attributes)).containsExactlyInAnyOrder(
                "window", "outlet", "standing", "edge", "quiet", "nearEntrance");
        attributes.forEach(flag -> assertThat(flag.isBoolean()).isTrue());
    }

    private static LibrarySeatRecommendationResponse sampleResponse() {
        LibrarySeatRecommendation item = new LibrarySeatRecommendation(
                "F2-OPEN-001",
                "101",
                "101",
                "OPEN",
                "오픈열람실(2F)",
                "창가",
                "일반",
                "all",
                "available",
                120,
                List.of("window"),
                List.of("outlet"),
                new LibrarySeatAttributes(true, false, false, true, false, false),
                "창가 자리");
        return new LibrarySeatRecommendationResponse(
                2,
                "2층",
                5,
                "live_per_seat",
                "2층에서 1석을 추천합니다.",
                List.of("대학원열람실(2F)"),
                List.of(),
                List.of(item));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }
}
