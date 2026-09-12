package com.ssuai.domain.copilot.policy.entity;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyReviewCaseTests {

    private static final Instant CREATED_AT = Instant.parse("2026-07-28T01:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);

    @Test
    void followsPendingClaimApproveStateMachineAndDetectsCorrection() {
        PolicyReviewCase reviewCase = pending();

        assertThat(reviewCase.getStatus()).isEqualTo(PolicyReviewStatus.PENDING_REVIEW);
        assertThat(reviewCase.claim("reviewer-a", CREATED_AT.plusSeconds(10), LEASE)).isTrue();
        assertThat(reviewCase.getStatus()).isEqualTo(PolicyReviewStatus.IN_REVIEW);
        assertThat(reviewCase.getClaimExpiresAt()).isEqualTo(CREATED_AT.plusSeconds(10).plus(LEASE));

        reviewCase.decide(
                "reviewer-a",
                PolicyReviewDecision.APPROVE,
                "수정된 최종 답변",
                null,
                CREATED_AT.plusSeconds(70));

        assertThat(reviewCase.getStatus()).isEqualTo(PolicyReviewStatus.APPROVED);
        assertThat(reviewCase.getFinalAnswer()).isEqualTo("수정된 최종 답변");
        assertThat(reviewCase.getDraftChanged()).isTrue();
        assertThat(reviewCase.getReviewDurationMs()).isEqualTo(60_000L);
        assertThat(reviewCase.getClaimExpiresAt()).isNull();
    }

    @Test
    void sameReviewerClaimIsIdempotentButAnotherReviewerConflicts() {
        PolicyReviewCase reviewCase = pending();
        reviewCase.claim("reviewer-a", CREATED_AT.plusSeconds(10), LEASE);

        assertThat(reviewCase.claim("reviewer-a", CREATED_AT.plusSeconds(20), LEASE)).isFalse();
        assertThat(reviewCase.getReviewStartedAt()).isEqualTo(CREATED_AT.plusSeconds(10));
        assertThatThrownBy(() -> reviewCase.claim("reviewer-b", CREATED_AT.plusSeconds(20), LEASE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyClaimingReviewerCanDecideAndRejectRequiresReason() {
        PolicyReviewCase reviewCase = pending();
        reviewCase.claim("reviewer-a", CREATED_AT.plusSeconds(10), LEASE);

        assertThatThrownBy(() -> reviewCase.decide(
                "reviewer-b", PolicyReviewDecision.REJECT, null, "근거 부족", CREATED_AT.plusSeconds(20)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> reviewCase.decide(
                "reviewer-a", PolicyReviewDecision.REJECT, null, " ", CREATED_AT.plusSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class);

        reviewCase.decide(
                "reviewer-a", PolicyReviewDecision.REJECT, null, "근거 부족", CREATED_AT.plusSeconds(20));
        assertThat(reviewCase.getStatus()).isEqualTo(PolicyReviewStatus.REJECTED);
        assertThat(reviewCase.getRejectionReason()).isEqualTo("근거 부족");
        assertThat(reviewCase.getFinalAnswer()).isNull();
    }

    @Test
    void expiredLeaseCanBeReclaimedAndCannotBeDecided() {
        PolicyReviewCase reviewCase = pending();
        Instant firstClaim = CREATED_AT.plusSeconds(10);
        reviewCase.claim("reviewer-a", firstClaim, LEASE);
        Instant expiredAt = firstClaim.plus(LEASE);

        assertThat(reviewCase.isClaimActive(expiredAt)).isFalse();
        assertThatThrownBy(() -> reviewCase.decide(
                "reviewer-a", PolicyReviewDecision.APPROVE, "최종 답변", null, expiredAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        assertThat(reviewCase.claim("reviewer-b", expiredAt, LEASE)).isTrue();
        assertThat(reviewCase.getReviewerKey()).isEqualTo("reviewer-b");
        assertThat(reviewCase.getReviewStartedAt()).isEqualTo(expiredAt);
        assertThat(reviewCase.getClaimExpiresAt()).isEqualTo(expiredAt.plus(LEASE));
    }

    private static PolicyReviewCase pending() {
        return PolicyReviewCase.pending(
                "a".repeat(64),
                "졸업 학점 기준에 관한 일반 정책 질문입니다.",
                "graduation",
                "공식 근거상 초안입니다.",
                "[]",
                "[]",
                "LIVE",
                "deterministic",
                "academic-policy-brief-v1",
                20L,
                1,
                false,
                CREATED_AT);
    }
}
