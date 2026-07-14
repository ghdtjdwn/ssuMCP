package com.ssuai.domain.academic.dto;

import java.time.Instant;
import java.util.List;

public record AcademicPolicyBriefResponse(
        String query,
        String category,
        String summary,
        List<String> cautions,
        List<AcademicPolicyEvidence> evidence,
        String answer,
        List<String> facts,
        List<String> unresolved,
        List<AcademicPolicyCitation> citations,
        boolean liveFetchRequested,
        boolean liveFetchAttempted,
        boolean liveFetchSucceeded,
        boolean servedFromCache,
        String sourceOrigin,
        boolean fallbackUsed,
        Instant sourceFetchedAt,
        Instant searchExecutedAt) {

    /** Compatibility constructor for the original extractive brief response. */
    public AcademicPolicyBriefResponse(
            String query,
            String category,
            String summary,
            List<String> cautions,
            List<AcademicPolicyEvidence> evidence) {
        this(
                query, category, summary, cautions, evidence, summary, List.of(), cautions, List.of(),
                false, false, false, true, "UNKNOWN", false, null, null);
    }
}
