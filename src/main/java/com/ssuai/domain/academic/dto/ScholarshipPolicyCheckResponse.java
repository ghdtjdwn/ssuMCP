package com.ssuai.domain.academic.dto;

import java.util.List;

public record ScholarshipPolicyCheckResponse(
        String query,
        List<String> inputFacts,
        String summary,
        List<String> caveats,
        List<AcademicPolicyEvidence> evidence) {
}
