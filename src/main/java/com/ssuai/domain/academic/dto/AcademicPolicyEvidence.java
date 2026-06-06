package com.ssuai.domain.academic.dto;

import java.time.Instant;
import java.util.List;

public record AcademicPolicyEvidence(
        String sourceId,
        String title,
        String category,
        String sourceType,
        String url,
        String revision,
        String effectiveDate,
        boolean live,
        boolean fallbackUsed,
        Instant fetchedAt,
        int score,
        String heading,
        String snippet,
        List<String> matchedTerms) {
}
