package com.ssuai.domain.auth.lms;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Encrypted, multi-replica source of truth for one LMS credential namespace. */
@Entity
@Table(name = "lms_sessions")
class LmsSessionEntity {

    @Id
    @Column(name = "session_key", length = 255)
    private String sessionKey;

    @Column(name = "principal_iv_b64", nullable = false, length = 64)
    private String principalIvB64;

    @Column(name = "principal_cipher_b64", nullable = false, columnDefinition = "TEXT")
    private String principalCipherB64;

    @Column(name = "cookie_iv_b64", nullable = false, length = 64)
    private String cookieIvB64;

    @Column(name = "cookie_cipher_b64", nullable = false, columnDefinition = "TEXT")
    private String cookieCipherB64;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    @Column(name = "cookie_versions", nullable = false, columnDefinition = "TEXT")
    private String cookieVersions;

    @Column(name = "health", nullable = false, length = 16)
    private String health;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "last_successful_call_at")
    private Instant lastSuccessfulCallAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected LmsSessionEntity() {
        // JPA
    }

    LmsSessionEntity(
            String sessionKey,
            String principalIvB64,
            String principalCipherB64,
            String cookieIvB64,
            String cookieCipherB64,
            Instant capturedAt,
            Instant expiresAt,
            long credentialVersion,
            String cookieVersions,
            String health,
            Instant lastValidatedAt,
            Instant lastSuccessfulCallAt,
            Instant lastFailureAt,
            String failureCode) {
        this.sessionKey = sessionKey;
        this.principalIvB64 = principalIvB64;
        this.principalCipherB64 = principalCipherB64;
        updateCredential(
                cookieIvB64, cookieCipherB64, capturedAt, expiresAt, credentialVersion,
                cookieVersions, health, lastValidatedAt, lastSuccessfulCallAt,
                lastFailureAt, failureCode);
    }

    void updateCredential(
            String cookieIvB64,
            String cookieCipherB64,
            Instant capturedAt,
            Instant expiresAt,
            long credentialVersion,
            String cookieVersions,
            String health,
            Instant lastValidatedAt,
            Instant lastSuccessfulCallAt,
            Instant lastFailureAt,
            String failureCode) {
        this.cookieIvB64 = cookieIvB64;
        this.cookieCipherB64 = cookieCipherB64;
        this.capturedAt = capturedAt;
        this.expiresAt = expiresAt;
        this.credentialVersion = credentialVersion;
        this.cookieVersions = cookieVersions;
        this.health = health;
        this.lastValidatedAt = lastValidatedAt;
        this.lastSuccessfulCallAt = lastSuccessfulCallAt;
        this.lastFailureAt = lastFailureAt;
        this.failureCode = failureCode;
    }

    void updatePrincipal(String principalIvB64, String principalCipherB64) {
        this.principalIvB64 = principalIvB64;
        this.principalCipherB64 = principalCipherB64;
    }

    String getSessionKey() { return sessionKey; }
    String getPrincipalIvB64() { return principalIvB64; }
    String getPrincipalCipherB64() { return principalCipherB64; }
    String getCookieIvB64() { return cookieIvB64; }
    String getCookieCipherB64() { return cookieCipherB64; }
    Instant getCapturedAt() { return capturedAt; }
    Instant getExpiresAt() { return expiresAt; }
    long getCredentialVersion() { return credentialVersion; }
    String getCookieVersions() { return cookieVersions; }
    String getHealth() { return health; }
    Instant getLastValidatedAt() { return lastValidatedAt; }
    Instant getLastSuccessfulCallAt() { return lastSuccessfulCallAt; }
    Instant getLastFailureAt() { return lastFailureAt; }
    String getFailureCode() { return failureCode; }
}
