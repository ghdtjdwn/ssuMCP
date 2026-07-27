package com.ssuai.domain.copilot.policy.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.copilot.policy.dto.PolicyCaseDecisionRequest;
import com.ssuai.domain.copilot.policy.dto.PolicyCaseMetricsResponse;
import com.ssuai.domain.copilot.policy.dto.PolicyCaseResponse;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus;
import com.ssuai.domain.copilot.policy.service.PolicyReviewCaseService;
import com.ssuai.global.auth.AuthUser;
import com.ssuai.global.response.ApiResponse;

/** Reviewer-only queue, claim, decision, and aggregate metrics endpoints. */
@Validated
@RestController
@RequestMapping("/api/reviewer/policy-cases")
public class PolicyCopilotReviewerController {

    private final PolicyReviewCaseService service;

    public PolicyCopilotReviewerController(PolicyReviewCaseService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<PolicyCaseResponse>> list(
            @AuthUser String reviewerId,
            @RequestParam(required = false) PolicyReviewStatus status) {
        return ApiResponse.success(service.listForReviewer(reviewerId, status));
    }

    @GetMapping("/metrics")
    public ApiResponse<PolicyCaseMetricsResponse> metrics(@AuthUser String reviewerId) {
        return ApiResponse.success(service.metrics(reviewerId));
    }

    @PostMapping("/{caseId}/claim")
    public ApiResponse<PolicyCaseResponse> claim(
            @AuthUser String reviewerId,
            @Positive @PathVariable long caseId) {
        return ApiResponse.success(service.claim(reviewerId, caseId));
    }

    @PostMapping("/{caseId}/decision")
    public ApiResponse<PolicyCaseResponse> decide(
            @AuthUser String reviewerId,
            @Positive @PathVariable long caseId,
            @Valid @RequestBody PolicyCaseDecisionRequest request) {
        return ApiResponse.success(service.decide(
                reviewerId,
                caseId,
                request.expectedVersion(),
                request.decision(),
                request.finalAnswer(),
                request.rejectionReason()));
    }
}
