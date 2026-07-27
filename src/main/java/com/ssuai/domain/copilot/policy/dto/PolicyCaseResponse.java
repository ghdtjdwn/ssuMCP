package com.ssuai.domain.copilot.policy.dto;

import java.time.Instant;
import java.util.List;

import com.ssuai.domain.copilot.policy.entity.PolicyReviewReasonCode;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus;

public record PolicyCaseResponse(
        Long id,
        PolicyReviewStatus status,
        String question,
        String category,
        String aiDraft,
        String finalAnswer,
        String rejectionReason,
        List<PolicyCaseCitationResponse> citations,
        List<PolicyReviewReasonCode> reviewReasonCodes,
        String sourceOrigin,
        String draftProvider,
        String draftModel,
        long draftLatencyMs,
        Instant createdAt,
        Instant reviewStartedAt,
        Instant claimExpiresAt,
        boolean claimedByCurrentReviewer,
        Instant reviewedAt,
        long version) {
}
