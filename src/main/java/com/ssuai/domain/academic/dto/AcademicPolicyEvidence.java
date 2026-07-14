package com.ssuai.domain.academic.dto;

import java.time.Instant;
import java.time.LocalDate;
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
        List<String> matchedTerms,
        LocalDate lastVerifiedDate,
        boolean revisionVerified,
        String sourceOrigin,
        Instant sourceFetchedAt) {

    /** Compatibility constructor for callers using the original evidence contract. */
    public AcademicPolicyEvidence(
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
        this(
                sourceId, title, category, sourceType, url, revision, effectiveDate, live, fallbackUsed,
                fetchedAt, score, heading, snippet, matchedTerms, null, false,
                live ? "LIVE" : "SEED", fetchedAt);
    }
}
