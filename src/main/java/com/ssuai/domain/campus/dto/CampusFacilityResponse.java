package com.ssuai.domain.campus.dto;

import java.util.List;

public record CampusFacilityResponse(
        String id,
        String name,
        CampusFacilityCategory category,
        String categoryLabel,
        String location,
        String phone,
        String extension,
        String fax,
        List<String> weekdayHours,
        List<String> weekendHours,
        List<String> notes,
        List<String> aliases,
        String source,
        String sourceUrl,
        String freshness
) {
    /** Keeps source provenance additive for existing facility constructors/clients. */
    public CampusFacilityResponse(
            String id, String name, CampusFacilityCategory category, String categoryLabel,
            String location, String phone, String extension, String fax,
            List<String> weekdayHours, List<String> weekendHours, List<String> notes,
            List<String> aliases) {
        this(id, name, category, categoryLabel, location, phone, extension, fax,
                weekdayHours, weekendHours, notes, aliases,
                "CURATED_STATIC", null, "STATIC_UNVERIFIED");
    }
}
