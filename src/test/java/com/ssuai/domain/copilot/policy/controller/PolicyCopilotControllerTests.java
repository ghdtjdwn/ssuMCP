package com.ssuai.domain.copilot.policy.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.copilot.policy.dto.PolicyCaseMetricsResponse;
import com.ssuai.domain.copilot.policy.dto.PolicyCaseResponse;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewDecision;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus;
import com.ssuai.domain.copilot.policy.service.PolicyCaseConflictException;
import com.ssuai.domain.copilot.policy.service.PolicyReviewCaseService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.exception.ApiException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.exception.ForbiddenException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest({PolicyCopilotController.class, PolicyCopilotReviewerController.class})
class PolicyCopilotControllerTests {

    private static final String USER = "20261234";
    private static final String REVIEWER = "reviewer-test";

    private final MockMvc mockMvc;

    @MockitoBean
    private PolicyReviewCaseService service;

    @Autowired
    PolicyCopilotControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void createRequiresAuthenticationAndValidBody() throws Exception {
        mockMvc.perform(post("/api/copilot/policy-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"졸업 학점 기준은 어떻게 되나요?","category":"graduation"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/copilot/policy-cases")
                        .requestAttr(AuthAttributes.STUDENT_ID, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"짧음\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void createReturnsNumericCaseIdAndSuccessEnvelope() throws Exception {
        when(service.create(USER, "졸업 학점 기준은 어떻게 되나요?", "graduation"))
                .thenReturn(pendingResponse());

        mockMvc.perform(post("/api/copilot/policy-cases")
                        .requestAttr(AuthAttributes.STUDENT_ID, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"졸업 학점 기준은 어떻게 되나요?","category":"graduation"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.aiDraft").value("공식 근거상 초안입니다."))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void createFailsClosedWhenNoReviewerIsConfigured() throws Exception {
        when(service.create(eq(USER), any(), any()))
                .thenThrow(new ApiException(ErrorCode.COPILOT_UNAVAILABLE));

        mockMvc.perform(post("/api/copilot/policy-cases")
                        .requestAttr(AuthAttributes.STUDENT_ID, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"졸업 학점 기준은 어떻게 되나요?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("COPILOT_UNAVAILABLE"));
    }

    @Test
    void getMapsHiddenOwnershipFailureToNotFound() throws Exception {
        when(service.getVisible(USER, 42L))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND, "정책 검토 케이스를 찾을 수 없습니다."));

        mockMvc.perform(get("/api/copilot/policy-cases/42")
                        .requestAttr(AuthAttributes.STUDENT_ID, USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void ownerListRequiresAuthenticationAndReturnsOnlyServiceOwnedCases() throws Exception {
        mockMvc.perform(get("/api/copilot/policy-cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        when(service.listOwned(USER)).thenReturn(List.of(pendingResponse()));

        mockMvc.perform(get("/api/copilot/policy-cases")
                        .requestAttr(AuthAttributes.STUDENT_ID, USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(42))
                .andExpect(jsonPath("$.data[0].status").value("PENDING_REVIEW"));

        verify(service).listOwned(USER);
    }

    @Test
    void reviewerEndpointsRequireAuthAndExposeStableMetricNames() throws Exception {
        mockMvc.perform(get("/api/reviewer/policy-cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        when(service.metrics(REVIEWER)).thenReturn(new PolicyCaseMetricsResponse(
                10, 2, 1, 5, 2, 120.5, 3_000.0, 5.0 / 7.0, 0.6, 0.4, 0.9, 0.3));

        mockMvc.perform(get("/api/reviewer/policy-cases/metrics")
                        .requestAttr(AuthAttributes.STUDENT_ID, REVIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCases").value(10))
                .andExpect(jsonPath("$.data.pendingCases").value(2))
                .andExpect(jsonPath("$.data.inReviewCases").value(1))
                .andExpect(jsonPath("$.data.approvedCases").value(5))
                .andExpect(jsonPath("$.data.rejectedCases").value(2))
                .andExpect(jsonPath("$.data.averageDraftLatencyMs").value(120.5))
                .andExpect(jsonPath("$.data.averageReviewDurationMs").value(3_000.0));
    }

    @Test
    void reviewerAuthorizationAndClaimRaceMapTo403And409() throws Exception {
        when(service.listForReviewer("not-reviewer", null)).thenThrow(new ForbiddenException());
        mockMvc.perform(get("/api/reviewer/policy-cases")
                        .requestAttr(AuthAttributes.STUDENT_ID, "not-reviewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        when(service.claim(REVIEWER, 42L)).thenThrow(new PolicyCaseConflictException("already claimed"));
        mockMvc.perform(post("/api/reviewer/policy-cases/42/claim")
                        .requestAttr(AuthAttributes.STUDENT_ID, REVIEWER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void rejectDecisionReturnsRejectionReasonInEnvelope() throws Exception {
        PolicyCaseResponse rejected = new PolicyCaseResponse(
                42L,
                PolicyReviewStatus.REJECTED,
                "졸업 학점 기준은 어떻게 되나요?",
                "graduation",
                "공식 근거상 초안입니다.",
                null,
                "현재 개정본을 확인할 수 없습니다.",
                List.of(),
                List.of(),
                "LIVE",
                "deterministic",
                "fixture",
                100L,
                Instant.parse("2026-07-28T01:00:00Z"),
                Instant.parse("2026-07-28T01:01:00Z"),
                null,
                false,
                Instant.parse("2026-07-28T01:02:00Z"),
                2L);
        when(service.decide(
                REVIEWER,
                42L,
                1L,
                PolicyReviewDecision.REJECT,
                null,
                "현재 개정본을 확인할 수 없습니다."))
                .thenReturn(rejected);

        mockMvc.perform(post("/api/reviewer/policy-cases/42/decision")
                        .requestAttr(AuthAttributes.STUDENT_ID, REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":1,
                                  "decision":"REJECT",
                                  "rejectionReason":"현재 개정본을 확인할 수 없습니다."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason")
                        .value("현재 개정본을 확인할 수 없습니다."));
    }

    private static PolicyCaseResponse pendingResponse() {
        return new PolicyCaseResponse(
                42L,
                PolicyReviewStatus.PENDING_REVIEW,
                "졸업 학점 기준은 어떻게 되나요?",
                "graduation",
                "공식 근거상 초안입니다.",
                null,
                null,
                List.of(),
                List.of(),
                "LIVE",
                "deterministic",
                "fixture",
                100L,
                Instant.parse("2026-07-28T01:00:00Z"),
                null,
                null,
                false,
                null,
                0L);
    }
}
