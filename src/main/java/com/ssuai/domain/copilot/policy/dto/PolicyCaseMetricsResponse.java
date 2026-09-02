package com.ssuai.domain.copilot.policy.dto;

public record PolicyCaseMetricsResponse(
        long totalCases,
        long pendingCases,
        long inReviewCases,
        long approvedCases,
        long rejectedCases,
        double averageDraftLatencyMs,
        double averageReviewDurationMs,
        double approvalRate,
        double unchangedApprovalRate,
        double correctionRate,
        double citationCoverageRate,
        double safeHoldRate) {
}
