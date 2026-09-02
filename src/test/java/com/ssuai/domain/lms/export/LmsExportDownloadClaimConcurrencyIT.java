package com.ssuai.domain.lms.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssuai.support.AbstractPostgresIT;

/**
 * Verifies the one-shot download claim against the production database dialect. The guarantee
 * depends on PostgreSQL re-evaluating the {@code status = 'READY'} predicate after a competing
 * update commits; an H2 or mocked repository test cannot establish that behavior.
 */
class LmsExportDownloadClaimConcurrencyIT extends AbstractPostgresIT {

    private static final Instant CREATED_AT = Instant.parse("2026-09-02T00:00:00Z");
    private static final Instant FIRST_CLAIM_AT = Instant.parse("2026-09-02T00:01:00Z");
    private static final Instant SECOND_CLAIM_AT = Instant.parse("2026-09-02T00:02:00Z");

    @Autowired
    private LmsExportJobRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void cleanJobs() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> repository.deleteAll());
    }

    @Test
    void concurrentReadyClaimsProduceExactlyOneDownloadWinner() throws Exception {
        LmsExportJob job = readyJob();
        transactions.executeWithoutResult(status -> repository.save(job));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(claimAfterStart(
                    ready, start, job.getId(), FIRST_CLAIM_AT));
            Future<Integer> second = executor.submit(claimAfterStart(
                    ready, start, job.getId(), SECOND_CLAIM_AT));

            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }

        LmsExportJob claimed = repository.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(LmsExportStatus.DOWNLOADED);
        assertThat(claimed.getCompletedAt()).isIn(FIRST_CLAIM_AT, SECOND_CLAIM_AT);
    }

    private Callable<Integer> claimAfterStart(
            CountDownLatch ready,
            CountDownLatch start,
            String jobId,
            Instant claimedAt) {
        return () -> {
            ready.countDown();
            assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
            return transactions.execute(status -> {
                int claimed = repository.claimReadyForDownload(jobId, claimedAt);
                if (claimed == 1) {
                    holdWinningTransaction();
                }
                return claimed;
            });
        };
    }

    private static void holdWinningTransaction() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static LmsExportJob readyJob() {
        LmsExportJob job = LmsExportJob.createQueued(
                "student",
                "token-hash",
                "{\"selections\":[],\"totalBytes\":0}",
                CREATED_AT,
                CREATED_AT.plusSeconds(1_200));
        job.markBuilding();
        job.markReady("/tmp/lms-export.zip", 1, 128L, CREATED_AT.plusSeconds(30));
        return job;
    }
}
