package com.ssuai.domain.copilot.policy.service;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ssuai.domain.copilot.policy.entity.PolicyReviewDecision;

/**
 * Low-cardinality operational telemetry for the review workflow.
 *
 * <p>No question, requester, reviewer, case id, category, or source is ever used
 * as a meter tag. Business ratios remain durable DB aggregates; these meters are
 * only for request health and latency alerting.</p>
 */
@Component
public class PolicyCopilotMetrics {

    private static final Logger log = LoggerFactory.getLogger(PolicyCopilotMetrics.class);

    private final Counter created;
    private final Counter safeHoldCreated;
    private final Counter approved;
    private final Counter rejected;
    private final Counter corrected;
    private final Timer draftLatency;
    private final Timer reviewDuration;

    public PolicyCopilotMetrics(MeterRegistry registry) {
        this.created = registry.counter("ssuai.copilot.policy.cases.created");
        this.safeHoldCreated = registry.counter("ssuai.copilot.policy.cases.safe_hold_created");
        this.approved = registry.counter("ssuai.copilot.policy.reviews.approved");
        this.rejected = registry.counter("ssuai.copilot.policy.reviews.rejected");
        this.corrected = registry.counter("ssuai.copilot.policy.reviews.corrected");
        this.draftLatency = registry.timer("ssuai.copilot.policy.draft.latency");
        this.reviewDuration = registry.timer("ssuai.copilot.policy.review.duration");
    }

    void recordCreated(long latencyMs, boolean safeHold) {
        try {
            created.increment();
            draftLatency.record(Math.max(0L, latencyMs), TimeUnit.MILLISECONDS);
            if (safeHold) {
                safeHoldCreated.increment();
            }
        } catch (RuntimeException exception) {
            // Telemetry must never roll back a durable case creation.
            log.warn("policy copilot metrics recording failed: operation=create failureType={}",
                    exception.getClass().getSimpleName());
        }
    }

    void recordDecision(PolicyReviewDecision decision, Long durationMs, Boolean draftChanged) {
        try {
            if (decision == PolicyReviewDecision.APPROVE) {
                approved.increment();
                if (Boolean.TRUE.equals(draftChanged)) {
                    corrected.increment();
                }
            } else {
                rejected.increment();
            }
            if (durationMs != null) {
                reviewDuration.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException exception) {
            // No case/user identifiers are logged or used as tags.
            log.warn("policy copilot metrics recording failed: operation=decision failureType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
