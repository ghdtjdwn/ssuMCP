package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface McpSessionRepository extends JpaRepository<McpSessionEntity, String> {

    Optional<McpSessionEntity> findBySessionIdAndExpiresAtAfter(String sessionId, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from McpSessionEntity e where e.sessionId = :sessionId and e.expiresAt > :now")
    Optional<McpSessionEntity> findActiveByIdForUpdate(
            @Param("sessionId") String sessionId,
            @Param("now") Instant now);

    /** Transport id fallback lookup (ADR 0036 §1B). */
    Optional<McpSessionEntity> findFirstByTransportSessionIdAndExpiresAtAfterOrderByCreatedAtDesc(
            String transportSessionId,
            Instant now
    );

    /** OAuth sub lookup (ADR 0036 §1C). */
    Optional<McpSessionEntity> findFirstByOauthSubjectAndExpiresAtAfterOrderByCreatedAtDesc(
            String oauthSubject,
            Instant now
    );

    int deleteByExpiresAtBefore(Instant now);
}
