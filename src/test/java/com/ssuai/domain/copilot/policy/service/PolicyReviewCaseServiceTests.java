package com.ssuai.domain.copilot.policy.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import com.ssuai.domain.academic.dto.AcademicPolicyBriefResponse;
import com.ssuai.domain.academic.dto.AcademicPolicyCitation;
import com.ssuai.domain.academic.dto.AcademicPolicyEvidence;
import com.ssuai.domain.academic.service.AcademicPolicyService;
import com.ssuai.domain.copilot.policy.config.CopilotReviewerAccessProperties;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewCase;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewDecision;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewReasonCode;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus;
import com.ssuai.domain.copilot.policy.repository.PolicyReviewCaseRepository;
import com.ssuai.domain.copilot.policy.repository.PolicyReviewCaseRepository.PolicyCaseMetricAggregate;
import com.ssuai.global.exception.ApiException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.exception.ForbiddenException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyReviewCaseServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-28T03:00:00Z");
    private static final String REQUESTER = "20261234";
    private static final String REVIEWER = "reviewer-1";
    private static final String TEST_SECRET = "policy-review-test-hmac-secret-at-least-32-bytes";

    private AcademicPolicyService academicPolicyService;
    private PolicyReviewCaseRepository repository;
    private PolicyDraftGenerator draftGenerator;
    private PolicyCopilotMetrics metrics;
    private CopilotIdentityHasher identityHasher;
    private PolicyReviewCaseService service;

    @BeforeEach
    void setUp() {
        academicPolicyService = mock(AcademicPolicyService.class);
        repository = mock(PolicyReviewCaseRepository.class);
        draftGenerator = mock(PolicyDraftGenerator.class);
        metrics = mock(PolicyCopilotMetrics.class);
        identityHasher = new CopilotIdentityHasher(TEST_SECRET);
        CopilotReviewerAccessProperties reviewerAccess = new CopilotReviewerAccessProperties();
        reviewerAccess.setReviewerIds(List.of(REVIEWER));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PolicyReviewCaseService(
                academicPolicyService,
                repository,
                new PolicyQuestionGuard(),
                draftGenerator,
                reviewerAccess,
                identityHasher,
                metrics,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createPersistsGroundedDraftWithPseudonymousRequesterKey() {
        when(academicPolicyService.briefForPolicyReview(any(), any(), any())).thenReturn(groundedBrief());
        when(draftGenerator.generate(any(), any(), any())).thenReturn(
                new PolicyDraftGenerator.DraftResult("근거 기반 검토 초안", "deterministic", "fixture", false));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(REQUESTER, "졸업 학점 기준은 어떻게 되나요?", "graduation");

        ArgumentCaptor<PolicyReviewCase> captor = ArgumentCaptor.forClass(PolicyReviewCase.class);
        verify(repository).save(captor.capture());
        PolicyReviewCase saved = captor.getValue();
        assertThat(saved.getRequesterKey())
                .hasSize(64)
                .isNotEqualTo(REQUESTER)
                .isEqualTo(hmac(REQUESTER));
        assertThat(saved.getStatus()).isEqualTo(PolicyReviewStatus.PENDING_REVIEW);
        assertThat(saved.getReviewReasonCodes()).isEqualTo("[]");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.aiDraft()).isEqualTo("근거 기반 검토 초안");
        verify(metrics).recordCreated(anyLong(), anyBoolean());
    }

    @Test
    void createAddsExplainableSafeHoldReasonsAndOnlyFlagsRealGenerationFailure() {
        AcademicPolicyBriefResponse brief = noEvidenceBrief();
        when(academicPolicyService.briefForPolicyReview(any(), any(), any())).thenReturn(brief);
        when(draftGenerator.generate(any(), any(), any())).thenReturn(
                new PolicyDraftGenerator.DraftResult(
                        "직원 확인이 필요합니다.", "deterministic", "fixture", false));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var deterministic = service.create(REQUESTER, "졸업 학점 정책 근거를 알려 주세요.", null);

        assertThat(deterministic.reviewReasonCodes())
                .containsExactly(
                        PolicyReviewReasonCode.NO_EVIDENCE,
                        PolicyReviewReasonCode.UNRESOLVED_CONDITION);
        assertThat(deterministic.reviewReasonCodes())
                .doesNotContain(PolicyReviewReasonCode.DRAFT_GENERATION_FAILED);

        when(draftGenerator.generate(any(), any(), any())).thenReturn(
                new PolicyDraftGenerator.DraftResult(
                        "직원 확인이 필요합니다.", "deterministic", "fixture", true));
        var degraded = service.create(REQUESTER, "졸업 학점 정책 근거를 다시 알려 주세요.", null);

        assertThat(degraded.reviewReasonCodes())
                .contains(PolicyReviewReasonCode.DRAFT_GENERATION_FAILED);
    }

    @Test
    void getAllowsOwnerOrReviewerAndHidesExistenceFromAnotherUser() {
        PolicyReviewCase reviewCase = pending(hmac(REQUESTER));
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(reviewCase));
        when(repository.findByIdAndRequesterKey(1L, hmac(REQUESTER)))
                .thenReturn(java.util.Optional.of(reviewCase));

        assertThat(service.getVisible(REQUESTER, 1L).question()).contains("졸업 학점");
        assertThat(service.getVisible(REVIEWER, 1L).question()).contains("졸업 학점");
        assertThatThrownBy(() -> service.getVisible("other-user", 1L))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(repository).findByIdAndRequesterKey(1L, hmac(REQUESTER));
        verify(repository).findByIdAndRequesterKey(1L, hmac("other-user"));
    }

    @Test
    void ownerListUsesHmacKeyAndDoesNotRequireReviewerAuthority() {
        CopilotReviewerAccessProperties noReviewers = new CopilotReviewerAccessProperties();
        PolicyReviewCaseService ownerService = new PolicyReviewCaseService(
                academicPolicyService,
                repository,
                new PolicyQuestionGuard(),
                draftGenerator,
                noReviewers,
                identityHasher,
                metrics,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        PolicyReviewCase owned = pending(hmac(REQUESTER));
        when(repository.findTop20ByRequesterKeyOrderByCreatedAtDesc(hmac(REQUESTER)))
                .thenReturn(List.of(owned));

        var cases = ownerService.listOwned(REQUESTER);

        assertThat(cases).hasSize(1);
        assertThat(cases.getFirst().question()).contains("졸업 학점");
        verify(repository).findTop20ByRequesterKeyOrderByCreatedAtDesc(hmac(REQUESTER));
        verify(repository, never()).findTop20ByRequesterKeyOrderByCreatedAtDesc(REQUESTER);
    }

    @Test
    void reviewerAllowlistIsDenyByDefault() {
        CopilotReviewerAccessProperties empty = new CopilotReviewerAccessProperties();
        PolicyReviewCaseService deniedService = new PolicyReviewCaseService(
                academicPolicyService,
                repository,
                new PolicyQuestionGuard(),
                draftGenerator,
                empty,
                identityHasher,
                metrics,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> deniedService.listForReviewer(REVIEWER, null))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).findTop100ByOrderByCreatedAtDesc();

        assertThatThrownBy(() -> deniedService.create(
                REQUESTER, "졸업 학점 기준은 어떻게 되나요?", "graduation"))
                .isInstanceOf(com.ssuai.global.exception.ApiException.class)
                .extracting(exception -> ((com.ssuai.global.exception.ApiException) exception).getErrorCode())
                .isEqualTo(com.ssuai.global.exception.ErrorCode.COPILOT_UNAVAILABLE);
        verify(academicPolicyService, never()).briefForPolicyReview(any(), any(), any());
    }

    @Test
    void missingOrShortIdentitySecretKeepsEveryCopilotSurfaceFailClosed() {
        CopilotReviewerAccessProperties reviewers = new CopilotReviewerAccessProperties();
        reviewers.setReviewerIds(List.of(REVIEWER));
        PolicyReviewCaseService unavailable = new PolicyReviewCaseService(
                academicPolicyService,
                repository,
                new PolicyQuestionGuard(),
                draftGenerator,
                reviewers,
                new CopilotIdentityHasher("too-short"),
                metrics,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> unavailable.create(
                REQUESTER, "졸업 학점 기준은 어떻게 되나요?", "graduation"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.COPILOT_UNAVAILABLE);
        assertThatThrownBy(() -> unavailable.listForReviewer(REVIEWER, null))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.COPILOT_UNAVAILABLE);
        assertThatThrownBy(() -> unavailable.listOwned(REQUESTER))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.COPILOT_UNAVAILABLE);
        assertThatThrownBy(() -> unavailable.getVisible(REQUESTER, 999L))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.COPILOT_UNAVAILABLE);
        verify(repository, never()).findTop20ByRequesterKeyOrderByCreatedAtDesc(any());
        verify(repository, never()).findById(anyLong());
        verify(repository, never()).findByIdAndRequesterKey(anyLong(), any());
    }

    @Test
    void claimIsIdempotentForSameReviewerAndConflictsForAnotherReviewer() {
        PolicyReviewCase reviewCase = pending(hmac(REQUESTER));
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(reviewCase));
        when(repository.saveAndFlush(reviewCase)).thenReturn(reviewCase);

        var firstClaim = service.claim(REVIEWER, 1L);
        assertThat(firstClaim.status()).isEqualTo(PolicyReviewStatus.IN_REVIEW);
        assertThat(firstClaim.claimExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(firstClaim.claimedByCurrentReviewer()).isTrue();
        assertThat(reviewCase.getReviewerKey()).isEqualTo(hmac(REVIEWER)).isNotEqualTo(REVIEWER);
        assertThat(service.claim(REVIEWER, 1L).claimExpiresAt()).isEqualTo(firstClaim.claimExpiresAt());
        verify(repository).saveAndFlush(reviewCase);

        CopilotReviewerAccessProperties reviewers = new CopilotReviewerAccessProperties();
        reviewers.setReviewerIds(List.of(REVIEWER, "reviewer-2"));
        PolicyReviewCaseService twoReviewerService = new PolicyReviewCaseService(
                academicPolicyService,
                repository,
                new PolicyQuestionGuard(),
                draftGenerator,
                reviewers,
                identityHasher,
                metrics,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> twoReviewerService.claim("reviewer-2", 1L))
                .isInstanceOf(PolicyCaseConflictException.class);
    }

    @Test
    void expiredClaimCanBeReclaimedButCannotBeDecided() {
        CopilotReviewerAccessProperties reviewers = new CopilotReviewerAccessProperties();
        reviewers.setReviewerIds(List.of(REVIEWER, "reviewer-2"));
        PolicyReviewCaseService twoReviewerService = new PolicyReviewCaseService(
                academicPolicyService,
                repository,
                new PolicyQuestionGuard(),
                draftGenerator,
                reviewers,
                identityHasher,
                metrics,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        PolicyReviewCase expired = pending(hmac(REQUESTER));
        expired.claim(hmac(REVIEWER), NOW.minus(Duration.ofMinutes(31)), Duration.ofMinutes(30));
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(expired));
        when(repository.saveAndFlush(expired)).thenReturn(expired);

        assertThatThrownBy(() -> service.decide(
                REVIEWER, 1L, 0L, PolicyReviewDecision.APPROVE, "최종 답변", null))
                .isInstanceOf(PolicyCaseConflictException.class)
                .hasMessageContaining("만료");

        var reclaimed = twoReviewerService.claim("reviewer-2", 1L);
        assertThat(reclaimed.claimedByCurrentReviewer()).isTrue();
        assertThat(reclaimed.claimExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(expired.getReviewerKey()).isEqualTo(hmac("reviewer-2"));
        assertThat(expired.getReviewStartedAt()).isEqualTo(NOW);
    }

    @Test
    void pendingQueueUsesOldestFirstRepositoryOrder() {
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(PolicyReviewStatus.PENDING_REVIEW))
                .thenReturn(List.of());

        assertThat(service.listForReviewer(REVIEWER, PolicyReviewStatus.PENDING_REVIEW)).isEmpty();

        verify(repository).findTop100ByStatusOrderByCreatedAtAsc(PolicyReviewStatus.PENDING_REVIEW);
        verify(repository, never()).findTop100ByStatusOrderByCreatedAtDesc(PolicyReviewStatus.PENDING_REVIEW);
    }

    @Test
    void decideRequiresCurrentVersionAndClaimingReviewerThenRecordsMetrics() {
        PolicyReviewCase reviewCase = pending(hmac(REQUESTER));
        reviewCase.claim(hmac(REVIEWER), NOW.minusSeconds(30), Duration.ofMinutes(30));
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(reviewCase));
        when(repository.saveAndFlush(reviewCase)).thenReturn(reviewCase);

        assertThatThrownBy(() -> service.decide(
                REVIEWER, 1L, 1L, PolicyReviewDecision.APPROVE, "최종 답변", null))
                .isInstanceOf(PolicyCaseConflictException.class)
                .hasMessageContaining("expectedVersion");

        var response = service.decide(
                REVIEWER, 1L, 0L, PolicyReviewDecision.APPROVE, "수정된 최종 답변", null);

        assertThat(response.status()).isEqualTo(PolicyReviewStatus.APPROVED);
        assertThat(response.finalAnswer()).isEqualTo("수정된 최종 답변");
        verify(metrics).recordDecision(PolicyReviewDecision.APPROVE, 30_000L, true);
    }

    @Test
    void allowlistedReviewerGetsConflictWhenAnotherReviewerOwnsClaim() {
        CopilotReviewerAccessProperties reviewers = new CopilotReviewerAccessProperties();
        reviewers.setReviewerIds(List.of(REVIEWER, "reviewer-2"));
        PolicyReviewCaseService twoReviewerService = new PolicyReviewCaseService(
                academicPolicyService,
                repository,
                new PolicyQuestionGuard(),
                draftGenerator,
                reviewers,
                identityHasher,
                metrics,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        PolicyReviewCase reviewCase = pending(hmac(REQUESTER));
        reviewCase.claim(hmac(REVIEWER), NOW.minusSeconds(30), Duration.ofMinutes(30));
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(reviewCase));

        assertThatThrownBy(() -> twoReviewerService.decide(
                "reviewer-2", 1L, 0L, PolicyReviewDecision.REJECT, null, "근거 부족"))
                .isInstanceOf(PolicyCaseConflictException.class)
                .hasMessageContaining("다른 검토자");
    }

    @Test
    void optimisticWriteFailureMapsToConflict() {
        PolicyReviewCase reviewCase = pending(hmac(REQUESTER));
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(reviewCase));
        when(repository.saveAndFlush(reviewCase)).thenThrow(new OptimisticLockingFailureException("race"));

        assertThatThrownBy(() -> service.claim(REVIEWER, 1L))
                .isInstanceOf(PolicyCaseConflictException.class);
    }

    @Test
    void metricsUseDurableRowsAndExpectedFieldSemantics() {
        PolicyCaseMetricAggregate aggregate = metricAggregate(
                5L, 1L, 1L, 2L, 1L, 1L, 1L, 3L, 2L, 300.0d, 2_000.0d);
        when(repository.findMetricAggregate()).thenReturn(aggregate);

        var response = service.metrics(REVIEWER);

        assertThat(response.totalCases()).isEqualTo(5);
        assertThat(response.pendingCases()).isEqualTo(1);
        assertThat(response.inReviewCases()).isEqualTo(1);
        assertThat(response.approvedCases()).isEqualTo(2);
        assertThat(response.rejectedCases()).isEqualTo(1);
        assertThat(response.averageDraftLatencyMs()).isEqualTo(300.0d);
        assertThat(response.averageReviewDurationMs()).isEqualTo(2_000.0d);
        assertThat(response.approvalRate()).isEqualTo(2.0d / 3.0d);
        assertThat(response.unchangedApprovalRate()).isEqualTo(0.5d);
        assertThat(response.correctionRate()).isEqualTo(0.5d);
        assertThat(response.citationCoverageRate()).isEqualTo(0.6d);
        assertThat(response.safeHoldRate()).isEqualTo(0.4d);
    }

    @Test
    void metricsReturnZeroForEmptyAggregateAndNeverDivideByZero() {
        PolicyCaseMetricAggregate aggregate = metricAggregate(
                0L, null, null, null, null, null, null, null, null, null, null);
        when(repository.findMetricAggregate()).thenReturn(aggregate);

        var response = service.metrics(REVIEWER);

        assertThat(response.totalCases()).isZero();
        assertThat(response.averageDraftLatencyMs()).isZero();
        assertThat(response.averageReviewDurationMs()).isZero();
        assertThat(response.approvalRate()).isZero();
        assertThat(response.unchangedApprovalRate()).isZero();
        assertThat(response.correctionRate()).isZero();
        assertThat(response.citationCoverageRate()).isZero();
        assertThat(response.safeHoldRate()).isZero();
    }

    private static PolicyReviewCase pending(String requesterKey) {
        return PolicyReviewCase.pending(
                requesterKey,
                "졸업 학점 기준에 관한 일반 정책 질문입니다.",
                "graduation",
                "공식 근거상 초안입니다.",
                "[]",
                "[]",
                "LIVE",
                "deterministic",
                "fixture",
                10L,
                1,
                false,
                NOW.minusSeconds(60));
    }

    private static AcademicPolicyBriefResponse groundedBrief() {
        AcademicPolicyEvidence evidence = new AcademicPolicyEvidence(
                "rule-1",
                "학칙",
                "graduation",
                "RULE",
                "https://rule.ssu.ac.kr/rule-1",
                "2026-03-01",
                "2026-03-01",
                true,
                false,
                NOW,
                10,
                "졸업",
                "졸업에 필요한 기준은 교육과정에 따른다.",
                List.of("졸업"),
                LocalDate.of(2026, 7, 28),
                true,
                "LIVE",
                NOW);
        AcademicPolicyCitation citation = new AcademicPolicyCitation(
                evidence.sourceId(),
                evidence.title(),
                evidence.url(),
                evidence.revision(),
                evidence.effectiveDate(),
                evidence.lastVerifiedDate(),
                evidence.revisionVerified(),
                evidence.heading());
        return new AcademicPolicyBriefResponse(
                "졸업 학점",
                "graduation",
                "요약",
                List.of(),
                List.of(evidence),
                "공식 근거상 졸업 기준입니다.",
                List.of(evidence.snippet()),
                List.of(),
                List.of(citation),
                false,
                false,
                false,
                true,
                "LIVE",
                false,
                NOW,
                NOW);
    }

    private static AcademicPolicyBriefResponse noEvidenceBrief() {
        return new AcademicPolicyBriefResponse(
                "졸업 학점",
                "graduation",
                "근거 없음",
                List.of(),
                List.of(),
                "반환된 공식 근거만으로는 질문에 답할 수 없습니다.",
                List.of(),
                List.of("질문과 직접 일치하는 공식 정책 근거"),
                List.of(),
                true,
                true,
                true,
                false,
                "LIVE",
                false,
                NOW,
                NOW);
    }

    private static PolicyCaseMetricAggregate metricAggregate(
            Long total,
            Long pending,
            Long inReview,
            Long approved,
            Long rejected,
            Long unchanged,
            Long corrected,
            Long citationCases,
            Long safeHolds,
            Double averageDraft,
            Double averageReview) {
        PolicyCaseMetricAggregate aggregate = mock(PolicyCaseMetricAggregate.class);
        when(aggregate.getTotalCases()).thenReturn(total);
        when(aggregate.getPendingCases()).thenReturn(pending);
        when(aggregate.getInReviewCases()).thenReturn(inReview);
        when(aggregate.getApprovedCases()).thenReturn(approved);
        when(aggregate.getRejectedCases()).thenReturn(rejected);
        when(aggregate.getUnchangedApprovedCases()).thenReturn(unchanged);
        when(aggregate.getCorrectedApprovedCases()).thenReturn(corrected);
        when(aggregate.getCitationCases()).thenReturn(citationCases);
        when(aggregate.getSafeHoldCases()).thenReturn(safeHolds);
        when(aggregate.getAverageDraftLatencyMs()).thenReturn(averageDraft);
        when(aggregate.getAverageReviewDurationMs()).thenReturn(averageReview);
        return aggregate;
    }

    private static String hmac(String value) {
        return new CopilotIdentityHasher(TEST_SECRET).key(value);
    }
}
