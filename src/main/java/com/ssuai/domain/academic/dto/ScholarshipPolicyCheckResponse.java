package com.ssuai.domain.academic.dto;

import java.time.Instant;
import java.util.List;

public record ScholarshipPolicyCheckResponse(
        String query,
        List<String> inputFacts,
        Decision decision,
        List<MatchedRequirement> matchedRequirements,
        String summary,
        List<String> caveats,
        List<AcademicPolicyEvidence> evidence,
        ScholarshipTierEvaluation tierEvaluation,
        boolean liveFetchRequested,
        boolean liveFetchAttempted,
        boolean liveFetchSucceeded,
        boolean servedFromCache,
        String sourceOrigin,
        boolean fallbackUsed,
        Instant sourceFetchedAt,
        Instant searchExecutedAt) {

    /** Compatibility constructor for the original generic requirement response. */
    public ScholarshipPolicyCheckResponse(
            String query,
            List<String> inputFacts,
            Decision decision,
            List<MatchedRequirement> matchedRequirements,
            String summary,
            List<String> caveats,
            List<AcademicPolicyEvidence> evidence) {
        this(
                query, inputFacts, decision, matchedRequirements, summary, caveats, evidence,
                new ScholarshipTierEvaluation(
                        "UNSUPPORTED", "UNDETERMINED", List.of(), List.of(),
                        List.of("structured scholarship rule"), evidence, 0.0d, caveats),
                false, false, false, true, "UNKNOWN", false, null, null);
    }

    public enum Decision {
        ELIGIBLE,
        NOT_ELIGIBLE,
        INSUFFICIENT_EVIDENCE
    }

    public enum RequirementResult {
        OK,
        FAIL,
        UNKNOWN
    }

    public record MatchedRequirement(
            String requirement,
            String required,
            Object userValue,
            RequirementResult result) {
    }
}
