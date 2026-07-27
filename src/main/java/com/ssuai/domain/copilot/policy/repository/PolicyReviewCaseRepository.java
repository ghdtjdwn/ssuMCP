package com.ssuai.domain.copilot.policy.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssuai.domain.copilot.policy.entity.PolicyReviewCase;
import com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus;

public interface PolicyReviewCaseRepository extends JpaRepository<PolicyReviewCase, Long> {

    Optional<PolicyReviewCase> findByIdAndRequesterKey(long id, String requesterKey);

    List<PolicyReviewCase> findTop20ByRequesterKeyOrderByCreatedAtDesc(String requesterKey);

    List<PolicyReviewCase> findTop100ByOrderByCreatedAtDesc();

    List<PolicyReviewCase> findTop100ByStatusOrderByCreatedAtDesc(PolicyReviewStatus status);

    List<PolicyReviewCase> findTop100ByStatusOrderByCreatedAtAsc(PolicyReviewStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from PolicyReviewCase c
             where c.createdAt < :cutoff
               and (c.status = :pending
                    or (c.status = :inReview and c.claimExpiresAt <= :now))
            """)
    int deleteInactiveCreatedBefore(
            @Param("pending") PolicyReviewStatus pending,
            @Param("inReview") PolicyReviewStatus inReview,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from PolicyReviewCase c
             where c.status in :statuses
               and c.reviewedAt < :cutoff
            """)
    int deleteTerminalReviewedBefore(
            @Param("statuses") Collection<PolicyReviewStatus> statuses,
            @Param("cutoff") Instant cutoff);

    @Query("""
            select count(c) as totalCases,
                   sum(case when c.status = com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus.PENDING_REVIEW then 1 else 0 end) as pendingCases,
                   sum(case when c.status = com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus.IN_REVIEW then 1 else 0 end) as inReviewCases,
                   sum(case when c.status = com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus.APPROVED then 1 else 0 end) as approvedCases,
                   sum(case when c.status = com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus.REJECTED then 1 else 0 end) as rejectedCases,
                   sum(case when c.status = com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus.APPROVED and c.draftChanged = false then 1 else 0 end) as unchangedApprovedCases,
                   sum(case when c.status = com.ssuai.domain.copilot.policy.entity.PolicyReviewStatus.APPROVED and c.draftChanged = true then 1 else 0 end) as correctedApprovedCases,
                   sum(case when c.citationCount > 0 then 1 else 0 end) as citationCases,
                   sum(case when c.safeHold = true then 1 else 0 end) as safeHoldCases,
                   avg(c.draftLatencyMs) as averageDraftLatencyMs,
                   avg(c.reviewDurationMs) as averageReviewDurationMs
              from PolicyReviewCase c
            """)
    PolicyCaseMetricAggregate findMetricAggregate();

    interface PolicyCaseMetricAggregate {
        Long getTotalCases();
        Long getPendingCases();
        Long getInReviewCases();
        Long getApprovedCases();
        Long getRejectedCases();
        Long getUnchangedApprovedCases();
        Long getCorrectedApprovedCases();
        Long getCitationCases();
        Long getSafeHoldCases();
        Double getAverageDraftLatencyMs();
        Double getAverageReviewDurationMs();
    }
}
