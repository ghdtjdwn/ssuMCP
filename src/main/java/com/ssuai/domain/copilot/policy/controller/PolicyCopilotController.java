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
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.copilot.policy.dto.CreatePolicyCaseRequest;
import com.ssuai.domain.copilot.policy.dto.PolicyCaseResponse;
import com.ssuai.domain.copilot.policy.service.PolicyReviewCaseService;
import com.ssuai.global.auth.AuthUser;
import com.ssuai.global.response.ApiResponse;

/** Authenticated entry point for creating and reading the caller's policy-review cases. */
@Validated
@RestController
@RequestMapping("/api/copilot/policy-cases")
public class PolicyCopilotController {

    private final PolicyReviewCaseService service;

    public PolicyCopilotController(PolicyReviewCaseService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<PolicyCaseResponse> create(
            @AuthUser String requesterId,
            @Valid @RequestBody CreatePolicyCaseRequest request) {
        return ApiResponse.success(service.create(requesterId, request.question(), request.category()));
    }

    @GetMapping
    public ApiResponse<List<PolicyCaseResponse>> listOwned(@AuthUser String requesterId) {
        return ApiResponse.success(service.listOwned(requesterId));
    }

    @GetMapping("/{caseId}")
    public ApiResponse<PolicyCaseResponse> get(
            @AuthUser String requesterId,
            @Positive @PathVariable long caseId) {
        return ApiResponse.success(service.getVisible(requesterId, caseId));
    }
}
