package com.ssuai.domain.lms.export;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface LmsExportJobRepository extends JpaRepository<LmsExportJob, String> {

    @Query(value = """
            SELECT *
              FROM lms_export_jobs
             WHERE status = 'QUEUED'
                OR (status = 'BUILDING' AND (claimed_at IS NULL OR claimed_at <= :leaseCutoff))
             ORDER BY created_at, id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<LmsExportJob> findClaimableForUpdate(@Param("leaseCutoff") Instant leaseCutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from LmsExportJob j where j.id = :id")
    Optional<LmsExportJob> findByIdForUpdate(@Param("id") String id);

    List<LmsExportJob> findAllByExpiresAtBefore(Instant now);

    Optional<LmsExportJob> findByOwnerMcpSessionIdAndSourceActionId(
            String ownerMcpSessionId, Long sourceActionId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LmsExportJob j set j.status = com.ssuai.domain.lms.export.LmsExportStatus.DOWNLOADED, "
            + "j.completedAt = :now "
            + "where j.id = :id and j.status = com.ssuai.domain.lms.export.LmsExportStatus.READY")
    int markDownloaded(@Param("id") String id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LmsExportJob j set j.status = com.ssuai.domain.lms.export.LmsExportStatus.EXPIRED, "
            + "j.completedAt = :revokedAt, j.claimedAt = null, j.claimedBy = null, "
            + "j.version = j.version + 1 "
            + "where j.ownerMcpSessionId = :ownerMcpSessionId and j.studentId = :credentialKey "
            + "and j.status in :revocableStatuses")
    int revokeMcpJobs(
            @Param("ownerMcpSessionId") String ownerMcpSessionId,
            @Param("credentialKey") String credentialKey,
            @Param("revocableStatuses") Collection<LmsExportStatus> revocableStatuses,
            @Param("revokedAt") Instant revokedAt);
}
