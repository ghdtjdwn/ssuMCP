package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionAuditRepository;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.action.ActionStatus;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentRepository;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationWaitRequest;
import com.ssuai.domain.lms.export.LmsExportJob;
import com.ssuai.domain.lms.export.LmsExportJobRepository;
import com.ssuai.domain.lms.export.LmsExportStatus;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpSessionRevocationIntegrationTests {

    @Autowired private McpAuthService authService;
    @Autowired private McpSessionRevocationService revocationService;
    @Autowired private ActionService actionService;
    @Autowired private ActionAuditRepository actionRepository;
    @Autowired private LibraryReservationIntentTransactions libraryIntents;
    @Autowired private LibraryReservationIntentRepository intentRepository;
    @Autowired private LibrarySessionStore librarySessions;
    @Autowired private LmsSessionStore lmsSessions;
    @Autowired private LmsExportJobRepository exportJobs;
    @Autowired private McpAuthStateRepository stateRepository;
    @Autowired private McpSessionRepository sessionRepository;

    @BeforeEach
    void clean() {
        exportJobs.deleteAll();
        intentRepository.deleteAll();
        actionRepository.deleteAll();
        stateRepository.deleteAll();
        sessionRepository.deleteAll();
    }

    @Test
    void libraryLogoutRevokesPendingActionWaitAndCredentialGeneration() {
        McpAuthSession session = authService.createSession();
        String owner = session.id().value();
        String credentialKey = "library-generation-a";
        librarySessions.put(credentialKey, "synthetic-library-token");
        authService.linkProvider(session.id(), McpProviderType.LIBRARY, credentialKey);

        ActionAudit action = actionService.createPendingMcpAction(
                owner, credentialKey, "LIBRARY_RESERVE", "seat:101", java.util.Map.of("seatId", 101));
        Long intentId = libraryIntents.registerWaitForMcp(
                owner,
                credentialKey,
                new LibraryReservationWaitRequest(
                        "5F", null, null, null, Duration.ofMinutes(10)))
                .intent().intentId();

        McpAuthSession current = authService.find(owner).orElseThrow();
        revocationService.revokeProvider(current, McpProviderType.LIBRARY);

        assertThat(authService.find(owner).orElseThrow().isLinked(McpProviderType.LIBRARY))
                .isFalse();
        assertThat(librarySessions.token(credentialKey)).isEmpty();
        assertThat(actionRepository.findById(action.getId()).orElseThrow().getStatus())
                .isEqualTo(ActionStatus.SUPERSEDED);
        assertThat(intentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(LibraryReservationIntentStatus.CANCELLED);
    }

    @Test
    void lmsLogoutRevokesQueuedJobCapabilityAndPreventsOldGenerationReuse() {
        McpAuthSession session = authService.createSession();
        String owner = session.id().value();
        String oldCredential = "lms-generation-a";
        lmsSessions.putForSession(
                oldCredential, "upstream-user", new LmsCookies("xn_api_token=synthetic"));
        authService.linkProvider(session.id(), McpProviderType.LMS, oldCredential);
        long oldGeneration = authService.find(owner).orElseThrow()
                .provider(McpProviderType.LMS).orElseThrow().generation();

        ActionAudit action = actionService.createPendingMcpAction(
                owner, oldCredential, "LMS_MATERIAL_EXPORT", "preview:one", java.util.Map.of("items", 1));
        LmsExportJob job = exportJobs.save(LmsExportJob.createQueuedForMcp(
                owner,
                action.getId(),
                oldCredential,
                "0".repeat(64),
                "{}",
                Instant.now(),
                Instant.now().plusSeconds(600)));

        revocationService.revokeProvider(
                authService.find(owner).orElseThrow(), McpProviderType.LMS);

        assertThat(lmsSessions.cookies(oldCredential)).isEmpty();
        assertThat(exportJobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(LmsExportStatus.EXPIRED);
        assertThat(actionRepository.findById(action.getId()).orElseThrow().getStatus())
                .isEqualTo(ActionStatus.SUPERSEDED);

        long newAttempt = authService
                .generateState(session.id(), McpProviderType.LMS).authRevision();
        assertThat(newAttempt).isGreaterThan(oldGeneration);
        assertThat(authService.linkProviderIfCurrentAttempt(
                session.id(), McpProviderType.LMS, "lms-generation-b", newAttempt))
                .isTrue();
        assertThat(authService.ownsProviderCredential(
                owner, McpProviderType.LMS, oldCredential)).isFalse();
    }

    @Test
    void providerWriteFenceAndLogoutHaveDeterministicCommitOrder() throws Exception {
        McpAuthSession session = authService.createSession();
        String owner = session.id().value();
        String credentialKey = "library-generation-fenced";
        librarySessions.put(credentialKey, "synthetic-library-token");
        authService.linkProvider(session.id(), McpProviderType.LIBRARY, credentialKey);

        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch allowOperationToFinish = new CountDownLatch(1);
        AtomicBoolean upstreamWriteFinished = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> write = executor.submit(() ->
                    authService.executeWhileProviderCredentialCurrent(
                            owner, McpProviderType.LIBRARY, credentialKey, () -> {
                                operationEntered.countDown();
                                await(allowOperationToFinish);
                                upstreamWriteFinished.set(true);
                                return "written";
                            }));
            assertThat(operationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            McpAuthSession beforeLogout = authService.find(owner).orElseThrow();
            Future<?> logout = executor.submit(() ->
                    revocationService.revokeProvider(beforeLogout, McpProviderType.LIBRARY));

            assertThatThrownBy(() -> logout.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(upstreamWriteFinished).isFalse();

            allowOperationToFinish.countDown();
            assertThat(write.get(5, TimeUnit.SECONDS)).isEqualTo("written");
            logout.get(5, TimeUnit.SECONDS);

            assertThat(upstreamWriteFinished).isTrue();
            assertThat(authService.find(owner).orElseThrow().isLinked(McpProviderType.LIBRARY))
                    .isFalse();
            assertThat(librarySessions.token(credentialKey)).isEmpty();
        } finally {
            allowOperationToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void logoutThatCommitsFirstPreventsTheProviderOperationFromStarting() {
        McpAuthSession session = authService.createSession();
        String owner = session.id().value();
        String credentialKey = "library-generation-revoked-first";
        librarySessions.put(credentialKey, "synthetic-library-token");
        authService.linkProvider(session.id(), McpProviderType.LIBRARY, credentialKey);

        revocationService.revokeProvider(
                authService.find(owner).orElseThrow(), McpProviderType.LIBRARY);
        AtomicBoolean invoked = new AtomicBoolean();

        assertThatThrownBy(() -> authService.executeWhileProviderCredentialCurrent(
                owner, McpProviderType.LIBRARY, credentialKey, () -> {
                    invoked.set(true);
                    return null;
                })).isInstanceOf(McpProviderCredentialRevokedException.class);
        assertThat(invoked).isFalse();
    }

    @Test
    void staleSessionSnapshotRevokesTheCredentialActuallyRemovedUnderLock() {
        McpAuthSession session = authService.createSession();
        String owner = session.id().value();
        String oldCredential = "library-generation-old";
        String currentCredential = "library-generation-current";
        librarySessions.put(oldCredential, "synthetic-old-token");
        authService.linkProvider(session.id(), McpProviderType.LIBRARY, oldCredential);
        McpAuthSession staleSnapshot = authService.find(owner).orElseThrow();

        librarySessions.put(currentCredential, "synthetic-current-token");
        authService.linkProvider(session.id(), McpProviderType.LIBRARY, currentCredential);

        revocationService.revokeProvider(staleSnapshot, McpProviderType.LIBRARY);

        assertThat(authService.find(owner).orElseThrow().isLinked(McpProviderType.LIBRARY))
                .isFalse();
        assertThat(librarySessions.token(currentCredential)).isEmpty();
        assertThat(librarySessions.token(oldCredential)).isPresent();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
