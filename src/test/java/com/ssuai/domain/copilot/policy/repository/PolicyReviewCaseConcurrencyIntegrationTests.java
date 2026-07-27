package com.ssuai.domain.copilot.policy.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssuai.domain.copilot.policy.entity.PolicyReviewCase;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewDecision;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus;
import com.ssuai.domain.copilot.policy.repository.PolicyReviewCaseRepository.PolicyCaseMetricAggregate;
import com.ssuai.domain.library.reservation.intent.LibraryReservationEventRelay;
import com.ssuai.domain.library.reservation.intent.LibraryReservationWorker;
import com.ssuai.domain.lms.export.LmsExportBuildWorker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway V18 + JPA optimistic-lock proof using two real, independent transactions.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class PolicyReviewCaseConcurrencyIntegrationTests {

    @Autowired
    private PolicyReviewCaseRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LibraryReservationWorker worker;

    @MockitoBean
    private LibraryReservationEventRelay eventRelay;

    @MockitoBean
    private LmsExportBuildWorker lmsExportBuildWorker;

    @Test
    void twoStalePendingClaimsProduceExactlyOneWinnerAndPreserveTerminalState() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long caseId = transactions.execute(status -> repository.saveAndFlush(pending()).getId());

        CountDownLatch bothLoaded = new CountDownLatch(2);
        CountDownLatch startFlush = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ClaimOutcome> first = pool.submit(() -> claimInIndependentTransaction(
                    transactions, caseId, "reviewer-a", bothLoaded, startFlush));
            Future<ClaimOutcome> second = pool.submit(() -> claimInIndependentTransaction(
                    transactions, caseId, "reviewer-b", bothLoaded, startFlush));

            assertThat(bothLoaded.await(5, TimeUnit.SECONDS)).isTrue();
            startFlush.countDown();

            List<ClaimOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(ClaimOutcome::success).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.success()).hasSize(1);
            assertThat(outcomes).allMatch(outcome -> outcome.loadedVersion() == 0L);

            String winner = outcomes.stream().filter(ClaimOutcome::success).findFirst().orElseThrow().reviewerKey();
            PolicyReviewCase claimed = transactions.execute(status -> repository.findById(caseId).orElseThrow());
            assertThat(claimed.getStatus()).isEqualTo(PolicyReviewStatus.IN_REVIEW);
            assertThat(claimed.getReviewerKey()).isEqualTo(winner);
            assertThat(claimed.getVersion()).isEqualTo(1L);

            transactions.executeWithoutResult(status -> {
                PolicyReviewCase managed = repository.findById(caseId).orElseThrow();
                managed.decide(
                        winner,
                        PolicyReviewDecision.APPROVE,
                        "검토 완료 답변",
                        null,
                        Instant.parse("2026-07-28T04:02:00Z"));
                repository.saveAndFlush(managed);
            });

            PolicyReviewCase approved = transactions.execute(status -> repository.findById(caseId).orElseThrow());
            assertThat(approved.getStatus()).isEqualTo(PolicyReviewStatus.APPROVED);
            assertThat(approved.getVersion()).isEqualTo(2L);
            assertThat(approved.getFinalAnswer()).isEqualTo("검토 완료 답변");
            assertThatThrownBy(() -> approved.claim(
                    "reviewer-c", Instant.parse("2026-07-28T04:03:00Z"), Duration.ofMinutes(30)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            startFlush.countDown();
            pool.shutdownNow();
            if (caseId != null) {
                transactions.executeWithoutResult(status -> repository.deleteById(caseId));
            }
        }
    }

    @Test
    void conditionalAggregateReturnsOneDatabaseRowWithoutLoadingCases() {
        PolicyCaseMetricAggregate before = repository.findMetricAggregate();
        PolicyReviewCase pending = pending();
        PolicyReviewCase inReview = pending();
        inReview.claim("reviewer-in-review", Instant.parse("2026-07-28T04:01:00Z"), Duration.ofMinutes(30));
        PolicyReviewCase unchanged = pending();
        unchanged.claim("reviewer-unchanged", Instant.parse("2026-07-28T04:01:00Z"), Duration.ofMinutes(30));
        unchanged.decide(
                "reviewer-unchanged",
                PolicyReviewDecision.APPROVE,
                "공식 근거상 초안입니다.",
                null,
                Instant.parse("2026-07-28T04:02:00Z"));
        PolicyReviewCase corrected = pending();
        corrected.claim("reviewer-corrected", Instant.parse("2026-07-28T04:01:00Z"), Duration.ofMinutes(30));
        corrected.decide(
                "reviewer-corrected",
                PolicyReviewDecision.APPROVE,
                "수정된 최종 답변",
                null,
                Instant.parse("2026-07-28T04:03:00Z"));
        PolicyReviewCase rejected = pending();
        rejected.claim("reviewer-rejected", Instant.parse("2026-07-28T04:01:00Z"), Duration.ofMinutes(30));
        rejected.decide(
                "reviewer-rejected",
                PolicyReviewDecision.REJECT,
                null,
                "근거 부족",
                Instant.parse("2026-07-28T04:02:00Z"));

        List<PolicyReviewCase> saved = repository.saveAllAndFlush(
                List.of(pending, inReview, unchanged, corrected, rejected));
        try {
            PolicyCaseMetricAggregate aggregate = repository.findMetricAggregate();
            assertThat(value(aggregate.getTotalCases()) - value(before.getTotalCases())).isEqualTo(5L);
            assertThat(value(aggregate.getPendingCases()) - value(before.getPendingCases())).isEqualTo(1L);
            assertThat(value(aggregate.getInReviewCases()) - value(before.getInReviewCases())).isEqualTo(1L);
            assertThat(value(aggregate.getApprovedCases()) - value(before.getApprovedCases())).isEqualTo(2L);
            assertThat(value(aggregate.getRejectedCases()) - value(before.getRejectedCases())).isEqualTo(1L);
            assertThat(value(aggregate.getUnchangedApprovedCases())
                    - value(before.getUnchangedApprovedCases())).isEqualTo(1L);
            assertThat(value(aggregate.getCorrectedApprovedCases())
                    - value(before.getCorrectedApprovedCases())).isEqualTo(1L);
            assertThat(aggregate.getAverageDraftLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0.0d);
            assertThat(aggregate.getAverageReviewDurationMs()).isNotNull().isGreaterThanOrEqualTo(0.0d);
        } finally {
            repository.deleteAllById(saved.stream().map(PolicyReviewCase::getId).toList());
            repository.flush();
        }
    }

    @Test
    void ownerQueryReturnsOnlyNewestTwentyForTheRequestedHmacKey() {
        String ownerKey = "a".repeat(64);
        String otherKey = "b".repeat(64);
        Instant base = Instant.parse("2026-07-28T05:00:00Z");
        List<PolicyReviewCase> fixtures = new ArrayList<>();
        for (int index = 0; index < 22; index++) {
            fixtures.add(pending(ownerKey, base.plusSeconds(index)));
        }
        fixtures.add(pending(otherKey, base.plusSeconds(100)));
        List<PolicyReviewCase> saved = repository.saveAllAndFlush(fixtures);
        try {
            List<PolicyReviewCase> results =
                    repository.findTop20ByRequesterKeyOrderByCreatedAtDesc(ownerKey);

            assertThat(results).hasSize(20);
            assertThat(results).allMatch(reviewCase -> ownerKey.equals(reviewCase.getRequesterKey()));
            assertThat(results.getFirst().getCreatedAt()).isEqualTo(base.plusSeconds(21));
            assertThat(results.getLast().getCreatedAt()).isEqualTo(base.plusSeconds(2));
        } finally {
            repository.deleteAllById(saved.stream().map(PolicyReviewCase::getId).toList());
            repository.flush();
        }
    }

    @Test
    void v18CheckRejectsPendingRowsWithClaimFields() {
        Instant now = Instant.parse("2026-07-28T04:00:00Z");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into policy_review_cases (
                    version, requester_key, status, question, ai_draft, citations_json,
                    review_reason_codes, source_origin, draft_provider, draft_model,
                    draft_latency_ms, citation_count, safe_hold, reviewer_key,
                    created_at, review_started_at, claim_expires_at
                ) values (0, ?, 'PENDING_REVIEW', ?, ?, '[]', '[]', 'LIVE',
                          'deterministic', 'schema-test', 0, 0, false, ?, ?, ?, ?)
                """,
                "a".repeat(64),
                "졸업 학점 기준에 관한 일반 정책 질문입니다.",
                "공식 근거상 초안입니다.",
                "b".repeat(64),
                now,
                now,
                now.plus(Duration.ofMinutes(30))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v18CheckRejectsNegativeReviewDuration() {
        Instant now = Instant.parse("2026-07-28T04:00:00Z");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into policy_review_cases (
                    version, requester_key, status, question, ai_draft, final_answer,
                    citations_json, review_reason_codes, source_origin, draft_provider,
                    draft_model, draft_latency_ms, citation_count, safe_hold, reviewer_key,
                    review_duration_ms, draft_changed, created_at, review_started_at, reviewed_at
                ) values (0, ?, 'APPROVED', ?, ?, ?, '[]', '[]', 'LIVE',
                          'deterministic', 'schema-test', 0, 0, false, ?, -1, false, ?, ?, ?)
                """,
                "c".repeat(64),
                "졸업 학점 기준에 관한 일반 정책 질문입니다.",
                "공식 근거상 초안입니다.",
                "공식 근거상 초안입니다.",
                "d".repeat(64),
                now,
                now.plusSeconds(1),
                now.plusSeconds(2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ClaimOutcome claimInIndependentTransaction(
            TransactionTemplate transactions,
            Long caseId,
            String reviewerKey,
            CountDownLatch bothLoaded,
            CountDownLatch startFlush) {
        try {
            return transactions.execute(status -> {
                PolicyReviewCase reviewCase = repository.findById(caseId).orElseThrow();
                long loadedVersion = reviewCase.getVersion();
                reviewCase.claim(
                        reviewerKey, Instant.parse("2026-07-28T04:01:00Z"), Duration.ofMinutes(30));
                bothLoaded.countDown();
                try {
                    if (!startFlush.await(5, TimeUnit.SECONDS)) {
                        status.setRollbackOnly();
                        return new ClaimOutcome(reviewerKey, loadedVersion, false);
                    }
                    repository.saveAndFlush(reviewCase);
                    return new ClaimOutcome(reviewerKey, loadedVersion, true);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    status.setRollbackOnly();
                    return new ClaimOutcome(reviewerKey, loadedVersion, false);
                } catch (OptimisticLockingFailureException exception) {
                    status.setRollbackOnly();
                    return new ClaimOutcome(reviewerKey, loadedVersion, false);
                }
            });
        } catch (OptimisticLockingFailureException exception) {
            return new ClaimOutcome(reviewerKey, 0L, false);
        }
    }

    private static PolicyReviewCase pending() {
        return pending("f".repeat(64), Instant.parse("2026-07-28T04:00:00Z"));
    }

    private static PolicyReviewCase pending(String requesterKey, Instant createdAt) {
        return PolicyReviewCase.pending(
                requesterKey,
                "졸업 학점 기준에 관한 일반 정책 질문입니다.",
                "graduation",
                "공식 근거상 초안입니다.",
                "[]",
                "[]",
                "LIVE",
                "deterministic",
                "integration-fixture",
                10L,
                1,
                false,
                createdAt);
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private record ClaimOutcome(String reviewerKey, long loadedVersion, boolean success) {
    }
}
