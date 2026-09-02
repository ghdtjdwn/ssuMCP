CREATE TABLE policy_review_cases (
    id                   BIGSERIAL                   PRIMARY KEY,
    version              BIGINT                      NOT NULL DEFAULT 0,
    requester_key        VARCHAR(64)                 NOT NULL,
    status               VARCHAR(24)                 NOT NULL,
    question             TEXT                        NOT NULL,
    category             VARCHAR(32),
    ai_draft             TEXT                        NOT NULL,
    final_answer         TEXT,
    citations_json       TEXT                        NOT NULL,
    review_reason_codes  TEXT                        NOT NULL,
    source_origin        VARCHAR(32)                 NOT NULL,
    draft_provider       VARCHAR(64)                 NOT NULL,
    draft_model          VARCHAR(160)                NOT NULL,
    draft_latency_ms     BIGINT                      NOT NULL,
    citation_count       INTEGER                     NOT NULL,
    safe_hold            BOOLEAN                     NOT NULL,
    reviewer_key         VARCHAR(64),
    rejection_reason     TEXT,
    review_duration_ms   BIGINT,
    draft_changed        BOOLEAN,
    created_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    review_started_at    TIMESTAMP(6) WITH TIME ZONE,
    claim_expires_at     TIMESTAMP(6) WITH TIME ZONE,
    reviewed_at          TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT chk_policy_review_status CHECK (
        status IN ('PENDING_REVIEW', 'IN_REVIEW', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT chk_policy_review_draft_latency CHECK (draft_latency_ms >= 0),
    CONSTRAINT chk_policy_review_citation_count CHECK (citation_count >= 0),
    CONSTRAINT chk_policy_review_duration CHECK (
        review_duration_ms IS NULL OR review_duration_ms >= 0
    ),
    CONSTRAINT chk_policy_review_state_fields CHECK (
        (status = 'PENDING_REVIEW'
            AND reviewer_key IS NULL
            AND review_started_at IS NULL
            AND claim_expires_at IS NULL
            AND reviewed_at IS NULL
            AND review_duration_ms IS NULL
            AND final_answer IS NULL
            AND rejection_reason IS NULL
            AND draft_changed IS NULL)
        OR
        (status = 'IN_REVIEW'
            AND reviewer_key IS NOT NULL
            AND review_started_at IS NOT NULL
            AND claim_expires_at IS NOT NULL
            AND reviewed_at IS NULL
            AND review_duration_ms IS NULL
            AND final_answer IS NULL
            AND rejection_reason IS NULL
            AND draft_changed IS NULL)
        OR
        (status = 'APPROVED'
            AND reviewer_key IS NOT NULL
            AND review_started_at IS NOT NULL
            AND claim_expires_at IS NULL
            AND reviewed_at IS NOT NULL
            AND review_duration_ms IS NOT NULL
            AND final_answer IS NOT NULL
            AND CHAR_LENGTH(TRIM(final_answer)) > 0
            AND rejection_reason IS NULL
            AND draft_changed IS NOT NULL)
        OR
        (status = 'REJECTED'
            AND reviewer_key IS NOT NULL
            AND review_started_at IS NOT NULL
            AND claim_expires_at IS NULL
            AND reviewed_at IS NOT NULL
            AND review_duration_ms IS NOT NULL
            AND final_answer IS NULL
            AND rejection_reason IS NOT NULL
            AND CHAR_LENGTH(TRIM(rejection_reason)) > 0
            AND draft_changed IS NULL)
    )
);

CREATE INDEX idx_policy_review_queue
    ON policy_review_cases(status, created_at);
CREATE INDEX idx_policy_review_requester
    ON policy_review_cases(requester_key, created_at);
CREATE INDEX idx_policy_review_terminal_retention
    ON policy_review_cases(status, reviewed_at);
