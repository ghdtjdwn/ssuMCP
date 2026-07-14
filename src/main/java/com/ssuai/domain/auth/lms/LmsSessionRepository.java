package com.ssuai.domain.auth.lms;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LmsSessionRepository extends JpaRepository<LmsSessionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM LmsSessionEntity e WHERE e.sessionKey = :sessionKey")
    Optional<LmsSessionEntity> findForUpdate(@Param("sessionKey") String sessionKey);

    @Modifying
    @Query("DELETE FROM LmsSessionEntity e WHERE e.expiresAt < :now")
    void deleteExpiredBefore(@Param("now") Instant now);
}
