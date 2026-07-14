-- Exact MCP-session ownership and concurrent binding hardening.
ALTER TABLE mcp_sessions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mcp_sessions ADD COLUMN saint_auth_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mcp_sessions ADD COLUMN lms_auth_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mcp_sessions ADD COLUMN library_auth_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mcp_auth_states ADD COLUMN auth_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE action_audit ADD COLUMN owner_mcp_session_id VARCHAR(64);
ALTER TABLE action_audit ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE library_reservation_intents ADD COLUMN owner_mcp_session_id VARCHAR(64);
ALTER TABLE library_reservation_intents ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE lms_export_jobs ADD COLUMN owner_mcp_session_id VARCHAR(64);
ALTER TABLE lms_export_jobs ADD COLUMN source_action_id BIGINT;
ALTER TABLE lms_export_jobs ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Provider credentials must be visible to browser callbacks, MCP requests, and workers
-- regardless of which application replica handles each step. Principal identifiers and
-- cookies are encrypted independently with AES-GCM; only health/version metadata is clear.
CREATE TABLE saint_sessions (
    session_key               VARCHAR(255) PRIMARY KEY,
    principal_iv_b64          VARCHAR(64) NOT NULL,
    principal_cipher_b64      TEXT NOT NULL,
    cookie_iv_b64             VARCHAR(64) NOT NULL,
    cookie_cipher_b64         TEXT NOT NULL,
    captured_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at                TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    credential_version        BIGINT NOT NULL,
    health                    VARCHAR(16) NOT NULL,
    last_validated_at         TIMESTAMP(6) WITH TIME ZONE,
    last_successful_call_at   TIMESTAMP(6) WITH TIME ZONE,
    last_failure_at           TIMESTAMP(6) WITH TIME ZONE,
    failure_code              VARCHAR(64),
    row_version               BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE lms_sessions (
    session_key               VARCHAR(255) PRIMARY KEY,
    principal_iv_b64          VARCHAR(64) NOT NULL,
    principal_cipher_b64      TEXT NOT NULL,
    cookie_iv_b64             VARCHAR(64) NOT NULL,
    cookie_cipher_b64         TEXT NOT NULL,
    captured_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at                TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    credential_version        BIGINT NOT NULL,
    cookie_versions           TEXT NOT NULL,
    health                    VARCHAR(16) NOT NULL,
    last_validated_at         TIMESTAMP(6) WITH TIME ZONE,
    last_successful_call_at   TIMESTAMP(6) WITH TIME ZONE,
    last_failure_at           TIMESTAMP(6) WITH TIME ZONE,
    failure_code              VARCHAR(64),
    row_version               BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_saint_sessions_expires ON saint_sessions(expires_at);
CREATE INDEX idx_lms_sessions_expires ON lms_sessions(expires_at);

-- Retain only the newest historical transport binding before enforcing uniqueness.
WITH ranked AS (
    SELECT session_id,
           ROW_NUMBER() OVER (
               PARTITION BY transport_session_id
               ORDER BY created_at DESC, session_id DESC
           ) AS row_number
      FROM mcp_sessions
     WHERE transport_session_id IS NOT NULL
)
UPDATE mcp_sessions
   SET transport_session_id = NULL
 WHERE session_id IN (SELECT session_id FROM ranked WHERE row_number > 1);

CREATE UNIQUE INDEX uq_mcp_sessions_transport
    ON mcp_sessions(transport_session_id)
    WHERE transport_session_id IS NOT NULL;
CREATE INDEX idx_action_audit_mcp_owner
    ON action_audit(owner_mcp_session_id, student_id, status);
CREATE INDEX idx_library_intents_mcp_owner
    ON library_reservation_intents(owner_mcp_session_id, session_key, status);
CREATE INDEX idx_lms_export_jobs_mcp_owner
    ON lms_export_jobs(owner_mcp_session_id, student_id, status);
CREATE UNIQUE INDEX uq_lms_export_jobs_owner_action
    ON lms_export_jobs(owner_mcp_session_id, source_action_id)
    WHERE owner_mcp_session_id IS NOT NULL AND source_action_id IS NOT NULL;
