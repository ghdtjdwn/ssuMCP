package com.ssuai.domain.copilot.policy.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssuai.domain.academic.dto.AcademicPolicyBriefResponse;
import com.ssuai.domain.academic.service.AcademicPolicyService;
import com.ssuai.domain.copilot.policy.config.CopilotReviewerAccessProperties;
import com.ssuai.domain.copilot.policy.dto.PolicyCaseCitationResponse;
import com.ssuai.domain.copilot.policy.dto.PolicyCaseMetricsResponse;
import com.ssuai.domain.copilot.policy.dto.PolicyCaseResponse;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewCase;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewDecision;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewReasonCode;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus;
import com.ssuai.domain.copilot.policy.repository.PolicyReviewCaseRepository;
import com.ssuai.domain.copilot.policy.repository.PolicyReviewCaseRepository.PolicyCaseMetricAggregate;
import com.ssuai.global.exception.ApiException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.exception.ForbiddenException;

@Service
public class PolicyReviewCaseService {

    private static final TypeReference<List<PolicyCaseCitationResponse>> CITATION_LIST = new TypeReference<>() {};
    private static final TypeReference<List<PolicyReviewReasonCode>> REASON_LIST = new TypeReference<>() {};
    private static final int EVIDENCE_LIMIT = 5;

    private final AcademicPolicyService academicPolicyService;
    private final PolicyReviewCaseRepository repository;
    private final PolicyQuestionGuard questionGuard;
    private final PolicyDraftGenerator draftGenerator;
    private final CopilotReviewerAccessProperties reviewerAccess;
    private final CopilotIdentityHasher identityHasher;
    private final PolicyCopilotMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PolicyReviewCaseService(
            AcademicPolicyService academicPolicyService,
            PolicyReviewCaseRepository repository,
            PolicyQuestionGuard questionGuard,
            PolicyDraftGenerator draftGenerator,
            CopilotReviewerAccessProperties reviewerAccess,
            CopilotIdentityHasher identityHasher,
            PolicyCopilotMetrics metrics,
            ObjectMapper objectMapper,
            Clock clock) {
        this.academicPolicyService = academicPolicyService;
        this.repository = repository;
        this.questionGuard = questionGuard;
        this.draftGenerator = draftGenerator;
        this.reviewerAccess = reviewerAccess;
        this.identityHasher = identityHasher;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Builds the draft outside a database transaction so retrieval/provider latency never holds
     * a connection; Spring Data opens the short transaction only for the final {@code save}.
     */
    public PolicyCaseResponse create(String requesterId, String question, String category) {
        if (!reviewerAccess.hasReviewers() || !identityHasher.isConfigured()) {
            throw new ApiException(ErrorCode.COPILOT_UNAVAILABLE);
        }
        String safeQuestion = questionGuard.validateQuestion(question);
        String safeCategory = questionGuard.normalizeCategory(category);
        String requesterKey = identityHasher.key(requesterId);
        long startedNanos = System.nanoTime();

        AcademicPolicyBriefResponse brief = academicPolicyService.briefForPolicyReview(
                safeQuestion, safeCategory, EVIDENCE_LIMIT);
        List<PolicyCaseCitationResponse> citations = brief.citations().stream()
                .map(PolicyCaseCitationResponse::from)
                .toList();
        PolicyDraftGenerator.DraftResult draft = draftGenerator.generate(safeQuestion, brief, citations);
        List<PolicyReviewReasonCode> reasons = reviewReasons(brief, citations, draft.generationFailed());
        long draftLatencyMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);

        PolicyReviewCase reviewCase = PolicyReviewCase.pending(
                requesterKey,
                safeQuestion,
                safeCategory,
                draft.draft(),
                writeJson(citations),
                writeJson(reasons),
                nonBlankOr(brief.sourceOrigin(), "UNKNOWN"),
                draft.provider(),
                draft.model(),
                draftLatencyMs,
                citations.size(),
                !reasons.isEmpty(),
                clock.instant());
        PolicyReviewCase saved = repository.save(reviewCase);
        metrics.recordCreated(draftLatencyMs, saved.isSafeHold());
        return toResponse(saved, requesterKey, clock.instant());
    }

    @Transactional(readOnly = true)
    public PolicyCaseResponse getVisible(String requesterId, long caseId) {
        String requesterKey = identityHasher.key(requesterId);
        PolicyReviewCase reviewCase = reviewerAccess.isReviewer(requesterId)
                ? findCase(caseId)
                : repository.findByIdAndRequesterKey(caseId, requesterKey)
                        .orElseThrow(PolicyReviewCaseService::caseNotFound);
        return toResponse(reviewCase, requesterKey, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<PolicyCaseResponse> listOwned(String requesterId) {
        String requesterKey = identityHasher.key(requesterId);
        Instant now = clock.instant();
        return repository.findTop20ByRequesterKeyOrderByCreatedAtDesc(requesterKey).stream()
                .map(reviewCase -> toResponse(reviewCase, requesterKey, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PolicyCaseResponse> listForReviewer(String reviewerId, PolicyReviewStatus status) {
        requireReviewer(reviewerId);
        List<PolicyReviewCase> cases;
        if (status == null) {
            cases = repository.findTop100ByOrderByCreatedAtDesc();
        } else if (status == PolicyReviewStatus.PENDING_REVIEW) {
            cases = repository.findTop100ByStatusOrderByCreatedAtAsc(status);
        } else {
            cases = repository.findTop100ByStatusOrderByCreatedAtDesc(status);
        }
        String reviewerKey = identityHasher.key(reviewerId);
        Instant now = clock.instant();
        return cases.stream().map(reviewCase -> toResponse(reviewCase, reviewerKey, now)).toList();
    }

    @Transactional
    public PolicyCaseResponse claim(String reviewerId, long caseId) {
        requireReviewer(reviewerId);
        PolicyReviewCase reviewCase = findCase(caseId);
        String reviewerKey = identityHasher.key(reviewerId);
        Instant now = clock.instant();
        try {
            boolean changed = reviewCase.claim(reviewerKey, now, reviewerAccess.getClaimLease());
            if (!changed) {
                return toResponse(reviewCase, reviewerKey, now);
            }
            return toResponse(repository.saveAndFlush(reviewCase), reviewerKey, now);
        } catch (IllegalStateException exception) {
            throw new PolicyCaseConflictException("이미 다른 검토자가 선점했거나 결정된 케이스입니다.");
        } catch (OptimisticLockingFailureException exception) {
            throw new PolicyCaseConflictException("다른 검토자가 먼저 케이스를 변경했습니다.");
        }
    }

    @Transactional
    public PolicyCaseResponse decide(
            String reviewerId,
            long caseId,
            long expectedVersion,
            PolicyReviewDecision decision,
            String finalAnswer,
            String rejectionReason) {
        requireReviewer(reviewerId);
        PolicyReviewCase reviewCase = findCase(caseId);
        String reviewerKey = identityHasher.key(reviewerId);
        Instant now = clock.instant();
        if (reviewCase.getStatus() != PolicyReviewStatus.IN_REVIEW) {
            throw new PolicyCaseConflictException("IN_REVIEW 상태의 케이스만 결정할 수 있습니다.");
        }
        if (!reviewerKey.equals(reviewCase.getReviewerKey())) {
            throw new PolicyCaseConflictException("다른 검토자가 선점한 케이스입니다.");
        }
        if (!reviewCase.isClaimActive(now)) {
            throw new PolicyCaseConflictException("검토 claim lease가 만료되었습니다. 다시 선점해 주세요.");
        }
        if (reviewCase.getVersion() != expectedVersion) {
            throw new PolicyCaseConflictException("expectedVersion이 현재 케이스 버전과 일치하지 않습니다.");
        }
        validateDecision(decision, finalAnswer, rejectionReason);

        reviewCase.decide(reviewerKey, decision, finalAnswer, rejectionReason, now);
        try {
            PolicyReviewCase saved = repository.saveAndFlush(reviewCase);
            metrics.recordDecision(decision, saved.getReviewDurationMs(), saved.getDraftChanged());
            return toResponse(saved, reviewerKey, now);
        } catch (OptimisticLockingFailureException exception) {
            throw new PolicyCaseConflictException("다른 검토 요청이 먼저 반영되었습니다.");
        }
    }

    @Transactional(readOnly = true)
    public PolicyCaseMetricsResponse metrics(String reviewerId) {
        requireReviewer(reviewerId);
        PolicyCaseMetricAggregate aggregate = repository.findMetricAggregate();
        long total = value(aggregate.getTotalCases());
        long pending = value(aggregate.getPendingCases());
        long inReview = value(aggregate.getInReviewCases());
        long approved = value(aggregate.getApprovedCases());
        long rejected = value(aggregate.getRejectedCases());
        long decided = approved + rejected;
        long unchanged = value(aggregate.getUnchangedApprovedCases());
        long corrected = value(aggregate.getCorrectedApprovedCases());
        long withCitations = value(aggregate.getCitationCases());
        long safeHolds = value(aggregate.getSafeHoldCases());
        double avgDraftLatency = value(aggregate.getAverageDraftLatencyMs());
        double avgReviewDuration = value(aggregate.getAverageReviewDurationMs());

        return new PolicyCaseMetricsResponse(
                total,
                pending,
                inReview,
                approved,
                rejected,
                avgDraftLatency,
                avgReviewDuration,
                rate(approved, decided),
                rate(unchanged, approved),
                rate(corrected, approved),
                rate(withCitations, total),
                rate(safeHolds, total));
    }

    private PolicyReviewCase findCase(long caseId) {
        return repository.findById(caseId)
                .orElseThrow(PolicyReviewCaseService::caseNotFound);
    }

    private static ApiException caseNotFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "정책 검토 케이스를 찾을 수 없습니다.");
    }

    private void requireReviewer(String reviewerId) {
        if (!identityHasher.isConfigured()) {
            throw new ApiException(ErrorCode.COPILOT_UNAVAILABLE);
        }
        if (!reviewerAccess.isReviewer(reviewerId)) {
            throw new ForbiddenException();
        }
    }

    private static void validateDecision(
            PolicyReviewDecision decision, String finalAnswer, String rejectionReason) {
        if (decision == PolicyReviewDecision.APPROVE && (finalAnswer == null || finalAnswer.isBlank())) {
            throw new IllegalArgumentException("APPROVE 결정에는 finalAnswer가 필요합니다.");
        }
        if (decision == PolicyReviewDecision.REJECT && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException("REJECT 결정에는 rejectionReason이 필요합니다.");
        }
    }

    private static List<PolicyReviewReasonCode> reviewReasons(
            AcademicPolicyBriefResponse brief,
            List<PolicyCaseCitationResponse> citations,
            boolean generationFailed) {
        List<PolicyReviewReasonCode> reasons = new ArrayList<>();
        if (brief.evidence().isEmpty()) {
            reasons.add(PolicyReviewReasonCode.NO_EVIDENCE);
        }
        if (brief.fallbackUsed() || brief.evidence().stream().anyMatch(evidence -> evidence.fallbackUsed())) {
            reasons.add(PolicyReviewReasonCode.FALLBACK_SOURCE);
        }
        if (!citations.isEmpty() && citations.stream().anyMatch(citation -> !citation.revisionVerified())) {
            reasons.add(PolicyReviewReasonCode.REVISION_UNVERIFIED);
        }
        if (!brief.unresolved().isEmpty()) {
            reasons.add(PolicyReviewReasonCode.UNRESOLVED_CONDITION);
        }
        if (generationFailed) {
            reasons.add(PolicyReviewReasonCode.DRAFT_GENERATION_FAILED);
        }
        return List.copyOf(reasons);
    }

    private PolicyCaseResponse toResponse(PolicyReviewCase reviewCase, String viewerKey, Instant now) {
        return new PolicyCaseResponse(
                reviewCase.getId(),
                reviewCase.getStatus(),
                reviewCase.getQuestion(),
                reviewCase.getCategory(),
                reviewCase.getAiDraft(),
                reviewCase.getFinalAnswer(),
                reviewCase.getRejectionReason(),
                readJson(reviewCase.getCitationsJson(), CITATION_LIST),
                readJson(reviewCase.getReviewReasonCodes(), REASON_LIST),
                reviewCase.getSourceOrigin(),
                reviewCase.getDraftProvider(),
                reviewCase.getDraftModel(),
                reviewCase.getDraftLatencyMs(),
                reviewCase.getCreatedAt(),
                reviewCase.getReviewStartedAt(),
                reviewCase.getClaimExpiresAt(),
                reviewCase.isClaimedBy(viewerKey, now),
                reviewCase.getReviewedAt(),
                reviewCase.getVersion());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("정책 검토 데이터를 직렬화하지 못했습니다.", exception);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 정책 검토 데이터를 해석하지 못했습니다.", exception);
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static double value(Double value) {
        return value == null ? 0.0d : value;
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : numerator / (double) denominator;
    }

    private static String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
