package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;

/**
 * Security regression tests for MCP session isolation (ADR 0039).
 *
 * <p>Background: an external tool-by-tool review reported a suspected P0 — "passing an
 * arbitrary {@code mcp_session_id} still returned my LMS data" — and inferred a dangerous
 * fallback to a global / current-user / last-active session. These tests pin down the
 * <b>actual</b> security property of {@link McpAuthHelper#resolveSession(String)} so the
 * report's worst case (cross-connection access from a guessed/forged id) cannot regress.
 *
 * <h2>The property proven here</h2>
 * <p>Session resolution is <b>bearer-only</b>: it succeeds only when the caller presents a
 * secret that already maps to a session — a verified OAuth {@code sub} (HTTP layer), a
 * server-issued transport id ({@code Mcp-Session-Id}, connection-scoped), or the opaque
 * {@code mcp_session_id} (the capability itself). There is <b>no</b> ambient lookup
 * ("latest session", "current user", "any session") — the {@link McpAuthService} interface
 * exposes none, and these tests prove the behavioral consequence: a caller holding none of
 * the three secrets resolves to {@link Optional#empty()} and therefore gets AUTH_REQUIRED,
 * never another principal's data.
 *
 * <p>The reported symptom ("arbitrary UUID still returned data") is reproduced and shown to
 * be Tier-2 transport resolution of the caller's <b>own</b> connection (see
 * {@link #staleOpaqueId_withOwnTransportBinding_resolvesOwnSessionNotAnotherPrincipal()}),
 * not a leak across connections.
 */
class McpSessionIsolationTests {

    private static final McpAuthSessionId OWN_SESSION =
            new McpAuthSessionId("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-06-18T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-06-25T00:00:00Z");

    private McpAuthService mcpAuthService;
    private McpAuthUrlFactory urlFactory;
    private HttpServletRequest request;
    private McpAuthHelper helper;

    @BeforeEach
    void setUp() {
        mcpAuthService = mock(McpAuthService.class);
        urlFactory = mock(McpAuthUrlFactory.class);
        request = mock(HttpServletRequest.class);
        // Default: anonymous transport — no Bearer JWT, no Mcp-Session-Id header.
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        helper = new McpAuthHelper(mcpAuthService, urlFactory, request);
    }

    /**
     * The reviewers' worst case: a caller with no transport binding and no OAuth token
     * presents a forged/guessed opaque id that maps to nothing. Resolution must be empty —
     * never a fallback to some other live session.
     */
    @Test
    void forgedOpaqueId_noTransport_noOauth_resolvesEmpty() {
        String forgedId = "00000000-0000-0000-0000-000000000000";
        when(mcpAuthService.find(forgedId)).thenReturn(Optional.empty());

        Optional<McpAuthSession> resolved = helper.resolveSession(forgedId);

        assertThat(resolved).isEmpty();
    }

    /**
     * Resolution of an opaque id is an <b>exact</b> lookup of that id only. It must never
     * widen the search to other sessions (no enumeration, no "nearest", no ambient session).
     */
    @Test
    void foreignOpaqueId_looksUpExactIdOnly_andNothingElse() {
        String foreignId = "deadbeef-0000-0000-0000-000000000000";
        when(mcpAuthService.find(foreignId)).thenReturn(Optional.empty());

        Optional<McpAuthSession> resolved = helper.resolveSession(foreignId);

        assertThat(resolved).isEmpty();
        // Only the exact id was queried; no transport/oauth secret was presented, so those
        // tiers must not be consulted with any real value.
        verify(mcpAuthService).find(foreignId);
        verify(mcpAuthService, never()).findByTransportId(anyString());
        verify(mcpAuthService, never()).findByOauthSubject(anyString());
    }

    /**
     * An attacker's own connection (some transport id that is bound to nothing) plus a
     * forged opaque id must still resolve empty — there is no leak to a victim's session
     * that happens to be the "current"/"latest" one.
     */
    @Test
    void unboundTransport_plusForgedOpaqueId_resolvesEmpty() {
        when(request.getHeader("Mcp-Session-Id")).thenReturn("attacker-conn-xyz");
        when(mcpAuthService.findByTransportId("attacker-conn-xyz")).thenReturn(Optional.empty());
        String forgedId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        when(mcpAuthService.find(forgedId)).thenReturn(Optional.empty());

        Optional<McpAuthSession> resolved = helper.resolveSession(forgedId);

        assertThat(resolved).isEmpty();
    }

    /**
     * Reproduces the reported symptom and shows it is benign: when the caller has a valid
     * transport binding to their <b>own</b> session, a stale/wrong opaque arg is ignored and
     * the caller's own session resolves (Tier 2 precedes Tier 3). This is connection-scoped
     * recovery (ADR 0036), not cross-principal access — the resolved session is the one
     * bound to <i>this</i> connection, never another's.
     */
    @Test
    void staleOpaqueId_withOwnTransportBinding_resolvesOwnSessionNotAnotherPrincipal() {
        McpAuthSession ownSession = new McpAuthSession(OWN_SESSION, NOW, EXPIRES, Map.of());
        when(request.getHeader("Mcp-Session-Id")).thenReturn("own-conn-123");
        when(mcpAuthService.findByTransportId("own-conn-123")).thenReturn(Optional.of(ownSession));

        // Caller passes a wrong/stale opaque id; it must not be honored over the transport.
        Optional<McpAuthSession> resolved = helper.resolveSession("some-stale-or-wrong-id");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo(OWN_SESSION);
        // The wrong opaque id is never looked up, because Tier 2 already resolved.
        verify(mcpAuthService, never()).find(anyString());
    }

    /**
     * No opaque id, no transport, no OAuth: an anonymous caller resolves empty. Guards
     * against any accidental "default session" behavior.
     */
    @Test
    void fullyAnonymousCaller_resolvesEmpty() {
        Optional<McpAuthSession> resolved = helper.resolveSession(null);

        assertThat(resolved).isEmpty();
        verify(mcpAuthService, never()).find(any());
        verify(mcpAuthService, never()).findByTransportId(anyString());
    }
}
