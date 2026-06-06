package com.ssuai.domain.academic.dto;

import java.time.Instant;
import java.util.List;

public record AcademicPolicySearchResponse(
        String query,
        String category,
        boolean liveRequested,
        boolean fallbackUsed,
        Instant searchedAt,
        int totalSources,
        int totalMatches,
        List<AcademicPolicyEvidence> evidence,
        List<AcademicPolicySource> sources) {
}
