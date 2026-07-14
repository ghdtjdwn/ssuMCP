package com.ssuai.domain.auth.saint;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SaintSessionRepository extends JpaRepository<SaintSessionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SaintSessionEntity e WHERE e.sessionKey = :sessionKey")
    Optional<SaintSessionEntity> findForUpdate(@Param("sessionKey") String sessionKey);

    @Modifying
    @Query("DELETE FROM SaintSessionEntity e WHERE e.expiresAt < :now")
    void deleteExpiredBefore(@Param("now") Instant now);
}
