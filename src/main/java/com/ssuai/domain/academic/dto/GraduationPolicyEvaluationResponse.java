package com.ssuai.domain.academic.dto;

import java.util.List;

import com.ssuai.domain.saint.dto.GraduationStatus;

public record GraduationPolicyEvaluationResponse(
        GraduationStatus graduationStatus,
        AcademicQuestionClassificationResponse classification,
        AcademicPolicyBriefResponse policyBrief,
        List<String> nextChecks) {
}
