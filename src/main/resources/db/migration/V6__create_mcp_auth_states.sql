CREATE TABLE mcp_auth_states (
    state       VARCHAR(64)                  PRIMARY KEY,
    session_id  VARCHAR(64)                  NOT NULL,
    provider    VARCHAR(32)                  NOT NULL,
    expires_at  TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE  NOT NULL
);

CREATE INDEX idx_mcp_auth_states_expires_at ON mcp_auth_states (expires_at);
