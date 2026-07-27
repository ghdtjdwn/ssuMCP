package com.ssuai.domain.copilot.policy.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "policy_review_cases")
public class PolicyReviewCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "requester_key", length = 64, nullable = false)
    private String requesterKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private PolicyReviewStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(length = 32)
    private String category;

    @Column(name = "ai_draft", nullable = false, columnDefinition = "TEXT")
    private String aiDraft;

    @Column(name = "final_answer", columnDefinition = "TEXT")
    private String finalAnswer;

    @Column(name = "citations_json", nullable = false, columnDefinition = "TEXT")
    private String citationsJson;

    @Column(name = "review_reason_codes", nullable = false, columnDefinition = "TEXT")
    private String reviewReasonCodes;

    @Column(name = "source_origin", length = 32, nullable = false)
    private String sourceOrigin;

    @Column(name = "draft_provider", length = 64, nullable = false)
    private String draftProvider;

    @Column(name = "draft_model", length = 160, nullable = false)
    private String draftModel;

    @Column(name = "draft_latency_ms", nullable = false)
    private long draftLatencyMs;

    @Column(name = "citation_count", nullable = false)
    private int citationCount;

    @Column(name = "safe_hold", nullable = false)
    private boolean safeHold;

    @Column(name = "reviewer_key", length = 64)
    private String reviewerKey;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "review_duration_ms")
    private Long reviewDurationMs;

    @Column(name = "draft_changed")
    private Boolean draftChanged;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "review_started_at")
    private Instant reviewStartedAt;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected PolicyReviewCase() {
        // JPA
    }

    private PolicyReviewCase(
            String requesterKey,
            String question,
            String category,
            String aiDraft,
            String citationsJson,
            String reviewReasonCodes,
            String sourceOrigin,
            String draftProvider,
            String draftModel,
            long draftLatencyMs,
            int citationCount,
            boolean safeHold,
            Instant createdAt) {
        this.requesterKey = requireNonBlank(requesterKey, "requesterKey");
        this.status = PolicyReviewStatus.PENDING_REVIEW;
        this.question = requireNonBlank(question, "question");
        this.category = blankToNull(category);
        this.aiDraft = requireNonBlank(aiDraft, "aiDraft");
        this.citationsJson = requireNonBlank(citationsJson, "citationsJson");
        this.reviewReasonCodes = requireNonBlank(reviewReasonCodes, "reviewReasonCodes");
        this.sourceOrigin = requireNonBlank(sourceOrigin, "sourceOrigin");
        this.draftProvider = requireNonBlank(draftProvider, "draftProvider");
        this.draftModel = requireNonBlank(draftModel, "draftModel");
        if (draftLatencyMs < 0) {
            throw new IllegalArgumentException("draftLatencyMs cannot be negative");
        }
        if (citationCount < 0) {
            throw new IllegalArgumentException("citationCount cannot be negative");
        }
        this.draftLatencyMs = draftLatencyMs;
        this.citationCount = citationCount;
        this.safeHold = safeHold;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static PolicyReviewCase pending(
            String requesterKey,
            String question,
            String category,
            String aiDraft,
            String citationsJson,
            String reviewReasonCodes,
            String sourceOrigin,
            String draftProvider,
            String draftModel,
            long draftLatencyMs,
            int citationCount,
            boolean safeHold,
            Instant createdAt) {
        return new PolicyReviewCase(
                requesterKey,
                question,
                category,
                aiDraft,
                citationsJson,
                reviewReasonCodes,
                sourceOrigin,
                draftProvider,
                draftModel,
                draftLatencyMs,
                citationCount,
                safeHold,
                createdAt);
    }

    public boolean claim(String reviewerKey, Instant claimedAt, Duration lease) {
        String claimedBy = requireNonBlank(reviewerKey, "reviewerKey");
        Instant startedAt = Objects.requireNonNull(claimedAt, "claimedAt");
        Duration safeLease = Objects.requireNonNull(lease, "lease");
        if (safeLease.isZero() || safeLease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (status == PolicyReviewStatus.IN_REVIEW && isClaimActive(startedAt)) {
            if (Objects.equals(this.reviewerKey, claimedBy)) {
                return false;
            }
            throw new IllegalStateException("An active claim belongs to another reviewer.");
        }
        if (status != PolicyReviewStatus.PENDING_REVIEW && status != PolicyReviewStatus.IN_REVIEW) {
            throw new IllegalStateException("Only pending or lease-expired cases can be claimed.");
        }
        this.reviewerKey = claimedBy;
        this.reviewStartedAt = startedAt;
        this.claimExpiresAt = startedAt.plus(safeLease);
        this.status = PolicyReviewStatus.IN_REVIEW;
        return true;
    }

    public boolean isClaimActive(Instant at) {
        return status == PolicyReviewStatus.IN_REVIEW
                && claimExpiresAt != null
                && Objects.requireNonNull(at, "at").isBefore(claimExpiresAt);
    }

    public boolean isClaimedBy(String reviewerKey, Instant at) {
        return Objects.equals(this.reviewerKey, reviewerKey) && isClaimActive(at);
    }

    public void decide(
            String reviewerKey,
            PolicyReviewDecision decision,
            String finalAnswer,
            String rejectionReason,
            Instant decidedAt) {
        if (status != PolicyReviewStatus.IN_REVIEW) {
            throw new IllegalStateException("Only IN_REVIEW cases can be decided.");
        }
        if (!Objects.equals(this.reviewerKey, requireNonBlank(reviewerKey, "reviewerKey"))) {
            throw new SecurityException("Only the claiming reviewer can decide this case.");
        }
        Objects.requireNonNull(decision, "decision");
        Instant completedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        if (!isClaimActive(completedAt)) {
            throw new IllegalStateException("The reviewer claim lease has expired.");
        }
        if (decision == PolicyReviewDecision.APPROVE) {
            this.finalAnswer = requireNonBlank(finalAnswer, "finalAnswer").trim();
            this.rejectionReason = null;
            this.draftChanged = !normalized(this.aiDraft).equals(normalized(this.finalAnswer));
            this.status = PolicyReviewStatus.APPROVED;
        } else {
            this.finalAnswer = null;
            this.rejectionReason = requireNonBlank(rejectionReason, "rejectionReason").trim();
            this.draftChanged = null;
            this.status = PolicyReviewStatus.REJECTED;
        }
        this.claimExpiresAt = null;
        this.reviewedAt = completedAt;
        this.reviewDurationMs = Math.max(0, Duration.between(reviewStartedAt, completedAt).toMillis());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public String getRequesterKey() { return requesterKey; }
    public PolicyReviewStatus getStatus() { return status; }
    public String getQuestion() { return question; }
    public String getCategory() { return category; }
    public String getAiDraft() { return aiDraft; }
    public String getFinalAnswer() { return finalAnswer; }
    public String getCitationsJson() { return citationsJson; }
    public String getReviewReasonCodes() { return reviewReasonCodes; }
    public String getSourceOrigin() { return sourceOrigin; }
    public String getDraftProvider() { return draftProvider; }
    public String getDraftModel() { return draftModel; }
    public long getDraftLatencyMs() { return draftLatencyMs; }
    public int getCitationCount() { return citationCount; }
    public boolean isSafeHold() { return safeHold; }
    public String getReviewerKey() { return reviewerKey; }
    public String getRejectionReason() { return rejectionReason; }
    public Long getReviewDurationMs() { return reviewDurationMs; }
    public Boolean getDraftChanged() { return draftChanged; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReviewStartedAt() { return reviewStartedAt; }
    public Instant getClaimExpiresAt() { return claimExpiresAt; }
    public Instant getReviewedAt() { return reviewedAt; }
}
