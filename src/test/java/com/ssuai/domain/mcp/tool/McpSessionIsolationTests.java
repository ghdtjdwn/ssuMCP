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
import com.ssuai.domain.auth.mcp.McpProviderType;

/**
 * Release-blocking security regression tests for authoritative MCP session resolution.
 * A supplied session id is an exact authorization boundary, never a hint that may be
 * replaced by an OAuth- or transport-bound session.
 */
class McpSessionIsolationTests {

    private static final McpAuthSessionId OWN_SESSION =
            new McpAuthSessionId("11111111-1111-1111-1111-111111111111");
    private static final McpAuthSessionId OTHER_SESSION =
            new McpAuthSessionId("22222222-2222-2222-2222-222222222222");
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

    @Test
    void randomExplicitId_withValidTransportBinding_isRejectedWithoutFallback() {
        McpAuthSession ownSession = new McpAuthSession(OWN_SESSION, NOW, EXPIRES, Map.of());
        when(request.getHeader("Mcp-Session-Id")).thenReturn("own-conn-123");
        when(mcpAuthService.findByTransportId("own-conn-123")).thenReturn(Optional.of(ownSession));
        String randomId = "some-stale-or-wrong-id";
        when(mcpAuthService.find(randomId)).thenReturn(Optional.empty());

        Optional<McpAuthSession> resolved = helper.resolveSession(randomId);

        assertThat(resolved).isEmpty();
        verify(mcpAuthService).find(randomId);
    }

    @Test
    void explicitSession_withDifferentTransportBinding_isRejected() {
        McpAuthSession explicit = new McpAuthSession(OWN_SESSION, NOW, EXPIRES, Map.of());
        McpAuthSession bound = new McpAuthSession(OTHER_SESSION, NOW, EXPIRES, Map.of());
        when(request.getHeader("Mcp-Session-Id")).thenReturn("other-conn");
        when(mcpAuthService.find(OWN_SESSION.value())).thenReturn(Optional.of(explicit));
        when(mcpAuthService.findByTransportId("other-conn")).thenReturn(Optional.of(bound));

        assertThat(helper.resolveSession(OWN_SESSION.value())).isEmpty();
    }

    @Test
    void validUnlinkedExplicitSession_withDifferentTransportBindingReturnsMismatch() {
        McpAuthSession explicit = new McpAuthSession(OTHER_SESSION, NOW, EXPIRES, Map.of());
        McpAuthSession bound = new McpAuthSession(OWN_SESSION, NOW, EXPIRES, Map.of());
        when(request.getHeader("Mcp-Session-Id")).thenReturn("bound-a");
        when(mcpAuthService.find(OTHER_SESSION.value())).thenReturn(Optional.of(explicit));
        when(mcpAuthService.findByTransportId("bound-a")).thenReturn(Optional.of(bound));

        var response = helper.buildAuthRequired(
                OTHER_SESSION.value(), McpProviderType.SAINT);

        assertThat(response.status()).isEqualTo("SESSION_MISMATCH");
        assertThat(response.mcpSessionId()).isNull();
        assertThat(response.loginUrl()).isNull();
        verify(mcpAuthService, never()).generateState(any(), any());
    }

    @Test
    void omittedId_withoutBinding_returnsNoSessionAndDoesNotCreateOne() {
        var response = helper.buildAuthRequired(
                null, McpProviderType.SAINT);

        assertThat(response.status()).isEqualTo("NO_SESSION");
        assertThat(response.mcpSessionId()).isNull();
        verify(mcpAuthService, never()).createSession();
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
