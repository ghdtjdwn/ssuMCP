package com.ssuai.domain.academic.dto;

import java.util.List;

public record AcademicQuestionClassificationResponse(
        String query,
        String intent,
        double confidence,
        List<String> categories,
        List<String> recommendedTools,
        String rationale) {
}
