package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthStateEntry;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderCredentialService;
import com.ssuai.domain.auth.mcp.McpProviderHealthSnapshot;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.McpSessionResolver.Code;
import com.ssuai.domain.auth.mcp.McpSessionResolver.Resolution;
import com.ssuai.domain.auth.mcp.McpSessionResolver.Source;
import com.ssuai.domain.auth.mcp.McpSessionRevocationService;
import com.ssuai.domain.auth.mcp.dto.McpAuthLogoutResponse;
import com.ssuai.domain.auth.mcp.dto.McpAuthStartResponse;
import com.ssuai.domain.auth.mcp.dto.McpAuthStatusResponse;

class McpAuthMcpToolsTests {

    private McpAuthService mcpAuthService;
    private McpAuthUrlFactory urlFactory;
    private McpAuthHelper mcpAuthHelper;
    private McpProviderCredentialService credentialService;
    private McpSessionRevocationService revocationService;
    private McpAuthMcpTools tools;

    private static final McpAuthSessionId SESSION_ID = new McpAuthSessionId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        mcpAuthService = mock(McpAuthService.class);
        urlFactory = mock(McpAuthUrlFactory.class);
        mcpAuthHelper = mock(McpAuthHelper.class);
        credentialService = mock(McpProviderCredentialService.class);
        revocationService = mock(McpSessionRevocationService.class);
        tools = new McpAuthMcpTools(
                mcpAuthService, urlFactory, mcpAuthHelper, credentialService, revocationService);
    }

    // --- get_auth_status ---

    @Test
    void getAuthStatus_unknownSession_returnsAllNotLinked() {
        when(mcpAuthHelper.resolveDetailed("unknown"))
                .thenReturn(Resolution.failure(Code.INVALID_SESSION, Source.EXPLICIT));

        McpAuthStatusResponse resp = tools.getAuthStatus("unknown");

        assertThat(resp.status()).isEqualTo("INVALID_SESSION");
        assertThat(resp.mcpSessionId()).isNull();
        assertThat(resp.providers()).hasSize(McpProviderType.values().length);
        assertThat(resp.providers()).allMatch(p -> !p.linked());
    }

    @Test
    void getAuthStatus_nullSession_returnsAllNotLinked() {
        when(mcpAuthHelper.resolveDetailed(null))
                .thenReturn(Resolution.failure(Code.NO_SESSION, Source.NONE));

        McpAuthStatusResponse resp = tools.getAuthStatus(null);

        assertThat(resp.status()).isEqualTo("NO_SESSION");
        assertThat(resp.mcpSessionId()).isNull();
        assertThat(resp.providers()).allMatch(p -> !p.linked());
    }

    @Test
    void getAuthStatus_linkedSession_returnsMcpSessionId() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        when(mcpAuthHelper.resolveDetailed(SESSION_ID.value()))
                .thenReturn(Resolution.success(session, Source.EXPLICIT));

        McpAuthStatusResponse resp = tools.getAuthStatus(SESSION_ID.value());

        assertThat(resp.mcpSessionId()).isEqualTo(SESSION_ID.value());
    }

    @Test
    void getAuthStatus_responseDoesNotContainPrincipalKey() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES,
                java.util.Map.of(McpProviderType.SAINT,
                        new com.ssuai.domain.auth.mcp.McpProviderLink(McpProviderType.SAINT, "20231234", Instant.now())));
        when(mcpAuthHelper.resolveDetailed(SESSION_ID.value()))
                .thenReturn(Resolution.success(session, Source.EXPLICIT));
        when(credentialService.health(any())).thenReturn(McpProviderHealthSnapshot.unknown(1));

        McpAuthStatusResponse resp = tools.getAuthStatus(SESSION_ID.value());

        // The response object contains no principalKey field — verify via toString
        assertThat(resp.toString()).doesNotContain("20231234");
    }

    // --- start_auth ---

    @Test
    void startAuth_withoutSession_createsNewSession() {
        McpAuthSession newSession = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.createSession()).thenReturn(newSession);
        when(mcpAuthHelper.bindCurrentTransportId(SESSION_ID)).thenReturn(true);
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.SAINT)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.SAINT, "state-token")).thenReturn("https://login.url/saint");

        McpAuthStartResponse resp = tools.startAuth("SAINT", null);

        assertThat(resp.status()).isEqualTo("LOGIN_STARTED");
        assertThat(resp.mcpSessionId()).isEqualTo(SESSION_ID.value());
        assertThat(resp.loginUrl()).isEqualTo("https://login.url/saint");
        assertThat(resp.provider()).isEqualTo("SAINT");
        assertThat(resp.message()).contains("mcp_session_id");
        assertThat(resp.message()).contains("https://login.url/saint");
        assertThat(resp.message()).contains("Do not substitute");
    }

    @Test
    void startAuth_withValidSession_reusesSession() {
        McpAuthSession existing = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.LMS, EXPIRES);
        when(mcpAuthHelper.resolveForAuthentication(SESSION_ID.value()))
                .thenReturn(Resolution.success(existing, Source.EXPLICIT));
        when(mcpAuthHelper.bindCurrentTransportId(SESSION_ID)).thenReturn(true);
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.LMS)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.LMS, "state-token")).thenReturn("https://login.url/lms");

        McpAuthStartResponse resp = tools.startAuth("LMS", SESSION_ID.value());

        assertThat(resp.mcpSessionId()).isEqualTo(SESSION_ID.value());
        verify(mcpAuthHelper).resolveForAuthentication(SESSION_ID.value());
    }

    @Test
    void startAuth_invalidProvider_returnsError() {
        McpAuthStartResponse resp = tools.startAuth("INVALID", SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("ERROR");
        verify(mcpAuthService, never()).createSession();
    }

    @Test
    void startAuth_lowercaseProvider_isAccepted() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.LIBRARY, EXPIRES);
        when(mcpAuthService.createSession()).thenReturn(session);
        when(mcpAuthHelper.bindCurrentTransportId(SESSION_ID)).thenReturn(true);
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.LIBRARY)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.LIBRARY, "state-token")).thenReturn("https://login.url/library");

        McpAuthStartResponse resp = tools.startAuth("library", null);

        assertThat(resp.status()).isEqualTo("LOGIN_STARTED");
    }

    // --- logout_provider ---

    @Test
    void logoutProvider_validSession_unlinksProvider() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        when(mcpAuthHelper.resolveDetailed(SESSION_ID.value()))
                .thenReturn(Resolution.success(session, Source.EXPLICIT));

        McpAuthLogoutResponse resp = tools.logoutProvider("SAINT", SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.provider()).isEqualTo("SAINT");
        verify(revocationService).revokeProvider(session, McpProviderType.SAINT);
    }

    @Test
    void logoutProvider_unknownSession_returnsError() {
        when(mcpAuthHelper.resolveDetailed(SESSION_ID.value()))
                .thenReturn(Resolution.failure(Code.INVALID_SESSION, Source.EXPLICIT));

        McpAuthLogoutResponse resp = tools.logoutProvider("SAINT", SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("INVALID_SESSION");
        verify(revocationService, never()).revokeProvider(any(), any());
    }

    @Test
    void logoutProvider_invalidProvider_returnsError() {
        McpAuthLogoutResponse resp = tools.logoutProvider("UNKNOWN", SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("ERROR");
        verify(revocationService, never()).revokeProvider(any(), any());
    }

    @Test
    void logoutProvider_omittedSessionWithoutTransportBinding_returnsNoSession() {
        when(mcpAuthHelper.resolveDetailed(null))
                .thenReturn(Resolution.failure(Code.NO_SESSION, Source.NONE));

        McpAuthLogoutResponse resp = tools.logoutProvider("SAINT", null);

        assertThat(resp.status()).isEqualTo("NO_SESSION");
        assertThat(resp.mcpSessionId()).isNull();
        verify(revocationService, never()).revokeProvider(any(), any());
    }

    // --- logout_all ---

    @Test
    void logoutAll_validSession_invalidatesSession() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        when(mcpAuthHelper.resolveDetailed(SESSION_ID.value()))
                .thenReturn(Resolution.success(session, Source.EXPLICIT));

        McpAuthLogoutResponse resp = tools.logoutAll(SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.provider()).isNull();
        verify(revocationService).revokeAll(session);
    }

    @Test
    void logoutAll_unknownSession_returnsError() {
        when(mcpAuthHelper.resolveDetailed(SESSION_ID.value()))
                .thenReturn(Resolution.failure(Code.INVALID_SESSION, Source.EXPLICIT));

        McpAuthLogoutResponse resp = tools.logoutAll(SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("INVALID_SESSION");
        verify(revocationService, never()).revokeAll(any());
    }

    @Test
    void logoutAll_omittedSessionWithoutTransportBinding_returnsNoSession() {
        when(mcpAuthHelper.resolveDetailed(null))
                .thenReturn(Resolution.failure(Code.NO_SESSION, Source.NONE));

        McpAuthLogoutResponse resp = tools.logoutAll(null);

        assertThat(resp.status()).isEqualTo("NO_SESSION");
        assertThat(resp.mcpSessionId()).isNull();
        verify(revocationService, never()).revokeAll(any());
    }
}
