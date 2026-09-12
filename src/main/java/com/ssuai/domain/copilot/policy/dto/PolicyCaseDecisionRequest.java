package com.ssuai.domain.copilot.policy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.ssuai.domain.copilot.policy.entity.PolicyReviewDecision;

public record PolicyCaseDecisionRequest(
        @NotNull
        @PositiveOrZero
        Long expectedVersion,
        @NotNull
        PolicyReviewDecision decision,
        @Size(max = 10_000)
        String finalAnswer,
        @Size(max = 4_000)
        String rejectionReason) {
}
