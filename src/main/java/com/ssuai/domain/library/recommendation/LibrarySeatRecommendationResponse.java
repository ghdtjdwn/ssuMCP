package com.ssuai.domain.library.recommendation;

import java.util.List;

public record LibrarySeatRecommendationResponse(
        int floor,
        String floorLabel,
        int requestedLimit,
        // Internal scan counters (liveAvailableSeats/liveSeatItemsSeen/catalogSeatsOnFloor/
        // catalogMatchedAvailableSeats) were removed: they leaked operational signals and
        // no client consumes them (ssuAI grep: 0). The natural-language `message` already
        // conveys availability (security follow-up #14).
        String availabilitySource,
        String message,
        List<String> excludedRooms,
        List<String> warnings,
        List<LibrarySeatRecommendation> recommendations,
        boolean softPreferences,
        int requestedPreferenceCount,
        String attributeDataCoverage
) {

    public LibrarySeatRecommendationResponse {
        excludedRooms = excludedRooms == null ? List.of() : List.copyOf(excludedRooms);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }

    public LibrarySeatRecommendationResponse(
            int floor,
            String floorLabel,
            int requestedLimit,
            String availabilitySource,
            String message,
            List<String> excludedRooms,
            List<String> warnings,
            List<LibrarySeatRecommendation> recommendations) {
        this(
                floor, floorLabel, requestedLimit, availabilitySource, message,
                excludedRooms, warnings, recommendations, true, 0, "POSITIVE_ONLY");
    }
}
