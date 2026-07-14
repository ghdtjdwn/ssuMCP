package com.ssuai.domain.library.recommendation;

import java.util.List;
import java.util.Map;

public record LibrarySeatRecommendation(
        String seatId,
        String externalSeatId,
        String label,
        String roomCode,
        String roomName,
        String zone,
        String seatType,
        String audience,
        String status,
        int score,
        List<String> matchedPreferences,
        List<String> missingPreferences,
        LibrarySeatAttributes attributes,
        String note,
        int requestedPreferenceCount,
        int matchedPreferenceCount,
        List<String> unknownPreferences,
        Map<String, String> attributeStates,
        String confidence,
        double attributeCoverage
) {

    public LibrarySeatRecommendation {
        matchedPreferences = matchedPreferences == null ? List.of() : List.copyOf(matchedPreferences);
        missingPreferences = missingPreferences == null ? List.of() : List.copyOf(missingPreferences);
        unknownPreferences = unknownPreferences == null ? List.of() : List.copyOf(unknownPreferences);
        attributeStates = attributeStates == null ? Map.of() : Map.copyOf(attributeStates);
    }

    public LibrarySeatRecommendation(
            String seatId,
            String externalSeatId,
            String label,
            String roomCode,
            String roomName,
            String zone,
            String seatType,
            String audience,
            String status,
            int score,
            List<String> matchedPreferences,
            List<String> missingPreferences,
            LibrarySeatAttributes attributes,
            String note) {
        this(
                seatId, externalSeatId, label, roomCode, roomName, zone, seatType, audience,
                status, score, matchedPreferences, missingPreferences, attributes, note,
                matchedPreferences == null || missingPreferences == null
                        ? 0 : matchedPreferences.size() + missingPreferences.size(),
                matchedPreferences == null ? 0 : matchedPreferences.size(),
                List.of(),
                Map.of(),
                "UNKNOWN",
                0.0);
    }
}
