package com.ssuai.domain.auth.mcp;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mcp_auth_states")
public class McpAuthStateEntity {

    @Id
    @Column(name = "state", length = 64)
    private String state;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "auth_revision", nullable = false)
    private long authRevision;

    protected McpAuthStateEntity() {
        // JPA
    }

    public McpAuthStateEntity(
            String state,
            String sessionId,
            String provider,
            Instant expiresAt,
            Instant createdAt,
            long authRevision) {
        this.state = state;
        this.sessionId = sessionId;
        this.provider = provider;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.authRevision = authRevision;
    }

    public McpAuthStateEntity(
            String state,
            String sessionId,
            String provider,
            Instant expiresAt,
            Instant createdAt) {
        this(state, sessionId, provider, expiresAt, createdAt, 0L);
    }

    public String getState() {
        return state;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProvider() {
        return provider;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getAuthRevision() {
        return authRevision;
    }
}
