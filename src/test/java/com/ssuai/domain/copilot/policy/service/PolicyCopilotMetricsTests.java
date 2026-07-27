package com.ssuai.domain.copilot.policy.service;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.copilot.policy.entity.PolicyReviewDecision;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCopilotMetricsTests {

    @Test
    void recordsOnlyUntaggedLowCardinalityMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PolicyCopilotMetrics metrics = new PolicyCopilotMetrics(registry);

        metrics.recordCreated(120L, true);
        metrics.recordDecision(PolicyReviewDecision.APPROVE, 2_000L, true);
        metrics.recordDecision(PolicyReviewDecision.REJECT, 1_000L, null);

        assertThat(registry.get("ssuai.copilot.policy.cases.created").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ssuai.copilot.policy.cases.safe_hold_created").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ssuai.copilot.policy.reviews.approved").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ssuai.copilot.policy.reviews.rejected").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ssuai.copilot.policy.reviews.corrected").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ssuai.copilot.policy.draft.latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("ssuai.copilot.policy.review.duration").timer().count()).isEqualTo(2L);

        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getName().startsWith("ssuai.copilot.policy"))
                .allSatisfy(meter -> assertThat(meter.getId().getTags()).isEqualTo(List.of()));
    }
}
