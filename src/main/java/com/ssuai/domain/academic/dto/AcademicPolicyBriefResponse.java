package com.ssuai.domain.academic.dto;

import java.util.List;

public record AcademicPolicyBriefResponse(
        String query,
        String category,
        String summary,
        List<String> cautions,
        List<AcademicPolicyEvidence> evidence) {
}
