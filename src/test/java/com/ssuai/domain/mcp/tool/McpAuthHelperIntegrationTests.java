package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthSessionStore;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.McpSessionEntity;
import com.ssuai.domain.auth.mcp.McpSessionRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class McpAuthHelperIntegrationTests {

    private static final Instant T0 = Instant.parse("2026-06-18T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2099-01-01T00:00:00Z");
    private static final String TRANSPORT_ID = "transport-abc";
    private static final McpAuthSessionId OLDER_SESSION = new McpAuthSessionId("old-session");

    @Autowired
    private McpSessionRepository repository;

    @Autowired
    private McpAuthSessionStore store;

    @Autowired
    private McpAuthService mcpAuthService;

    private McpAuthHelper helper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        repository.deleteAll();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Mcp-Session-Id")).thenReturn(TRANSPORT_ID);
        helper = new McpAuthHelper(mcpAuthService, mock(McpAuthUrlFactory.class), request);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveSessionAndPrincipalKeyUseTheUniqueTransportBinding() {
        McpSessionEntity bound = new McpSessionEntity(
                OLDER_SESSION.value(),
                T0,
                EXPIRES,
                "{}"
        );
        bound.setTransportSessionId(TRANSPORT_ID);
        repository.save(bound);
        store.linkProvider(OLDER_SESSION, McpProviderType.SAINT, "bound-student");

        Optional<McpAuthSession> resolved = helper.resolveSession(null);
        Optional<String> principalKey = helper.principalKey(null, McpProviderType.SAINT);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo(OLDER_SESSION);
        assertThat(principalKey).contains("bound-student");
    }

    @Test
    void resolveSessionDeniesTransportTierWhenJwtSubMismatchesBoundSubject() {
        // End-to-end ownership guard (real store + real bindOrVerify + real fall-through):
        // a session is bound to oauth_subject sub-A and carries the transport id, but the
        // request presents a verified JWT with sub-B. The stolen transport id must NOT
        // resolve the victim's session. With no opaque arg, resolution returns empty and
        // the existing sub-A binding must be left untouched (not overwritten to sub-B).
        McpSessionEntity victim = new McpSessionEntity(OLDER_SESSION.value(), T0, EXPIRES, "{}");
        victim.setTransportSessionId(TRANSPORT_ID);
        victim.setOauthSubject("sub-A");
        repository.save(victim);
        store.linkProvider(OLDER_SESSION, McpProviderType.SAINT, "victim-student");

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        Jwt.withTokenValue("token").header("alg", "none").subject("sub-B").build()));

        Optional<McpAuthSession> resolved = helper.resolveSession(null);

        assertThat(resolved).isEmpty();
        assertThat(repository.findById(OLDER_SESSION.value()).orElseThrow().getOauthSubject())
                .isEqualTo("sub-A");
    }

    @Test
    void ordinaryResolutionWithJwtDeniesAnInitiallyUnboundSession() {
        McpSessionEntity unbound = new McpSessionEntity(
                OLDER_SESSION.value(), T0, EXPIRES, "{}");
        unbound.setTransportSessionId(TRANSPORT_ID);
        repository.save(unbound);

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        Jwt.withTokenValue("token").header("alg", "none").subject("sub-A").build()));

        assertThat(helper.resolveSession(OLDER_SESSION.value())).isEmpty();
        assertThat(helper.resolveSession(null)).isEmpty();
        assertThat(repository.findById(OLDER_SESSION.value()).orElseThrow().getOauthSubject())
                .isNull();
    }
}
