package com.ssuai.domain.academic.dto;

import java.util.List;

/** Deterministic result for the explicitly supported scholarship rule families. */
public record ScholarshipTierEvaluation(
        String selectedRule,
        String tier,
        List<String> matched,
        List<String> unmet,
        List<String> unknown,
        List<AcademicPolicyEvidence> evidence,
        double confidence,
        List<String> caveats) {
}
