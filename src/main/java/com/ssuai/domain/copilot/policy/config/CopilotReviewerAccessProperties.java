package com.ssuai.domain.copilot.policy.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.copilot")
public class CopilotReviewerAccessProperties {

    private List<String> reviewerIds = List.of();
    private Duration claimLease = Duration.ofMinutes(30);

    public List<String> getReviewerIds() {
        return reviewerIds;
    }

    public void setReviewerIds(List<String> reviewerIds) {
        this.reviewerIds = reviewerIds == null
                ? List.of()
                : reviewerIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    /** Whether review capacity is explicitly configured; empty is fail-closed. */
    public boolean hasReviewers() {
        return !reviewerIds.isEmpty();
    }

    public boolean isReviewer(String userId) {
        return userId != null && !userId.isBlank() && reviewerIds.contains(userId);
    }

    public Duration getClaimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
        if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
        this.claimLease = claimLease;
    }
}
