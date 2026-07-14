package com.ssuai.domain.auth.mcp;

import java.time.Clock;
import java.util.EnumSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.lms.export.LmsExportJobRepository;
import com.ssuai.domain.lms.export.LmsExportStatus;

/**
 * One revocation boundary for links, credentials, pending actions, background work,
 * and download capabilities owned by an exact MCP session/provider generation.
 */
@Service
public class McpSessionRevocationService {

    private static final EnumSet<LmsExportStatus> REVOCABLE_EXPORT_STATUSES = EnumSet.of(
            LmsExportStatus.QUEUED,
            LmsExportStatus.BUILDING,
            LmsExportStatus.READY);

    private final McpAuthService authService;
    private final McpAuthStateStore stateStore;
    private final McpProviderCredentialService credentialService;
    private final ActionService actionService;
    private final LibraryReservationIntentTransactions libraryIntents;
    private final LmsExportJobRepository exportJobs;
    private final Clock clock;

    public McpSessionRevocationService(
            McpAuthService authService,
            McpAuthStateStore stateStore,
            McpProviderCredentialService credentialService,
            ActionService actionService,
            LibraryReservationIntentTransactions libraryIntents,
            LmsExportJobRepository exportJobs,
            Clock clock) {
        this.authService = authService;
        this.stateStore = stateStore;
        this.credentialService = credentialService;
        this.actionService = actionService;
        this.libraryIntents = libraryIntents;
        this.exportJobs = exportJobs;
        this.clock = clock;
    }

    @Transactional
    public void revokeProvider(McpAuthSession session, McpProviderType provider) {
        if (session == null || provider == null) {
            return;
        }
        // Increment the provider auth revision before cleanup. A callback that already
        // consumed its state can no longer commit after this point. Cleanup must use the exact
        // link removed under the lock, never the caller's potentially stale session snapshot.
        McpProviderLink link = authService
                .unlinkProviderAndGetLink(session.id(), provider)
                .orElse(null);
        stateStore.invalidateForProvider(session.id(), provider);

        if (link == null) {
            return;
        }
        String owner = session.id().value();
        String credentialKey = link.principalKey();
        actionService.revokeMcpActions(owner, credentialKey);
        if (provider == McpProviderType.LIBRARY) {
            libraryIntents.revokeForMcpSession(owner, credentialKey);
        } else if (provider == McpProviderType.LMS) {
            exportJobs.revokeMcpJobs(
                    owner, credentialKey, REVOCABLE_EXPORT_STATUSES, clock.instant());
        }
        credentialService.invalidate(link);
    }

    @Transactional
    public void revokeAll(McpAuthSession session) {
        if (session == null) {
            return;
        }
        for (McpProviderType provider : McpProviderType.values()) {
            // Re-read after each mutation so provider maps and optimistic versions remain current.
            McpAuthSession current = authService.find(session.id().value()).orElse(null);
            if (current == null) {
                break;
            }
            revokeProvider(current, provider);
        }
        stateStore.invalidateForSession(session.id());
        authService.invalidateSession(session.id());
    }
}
