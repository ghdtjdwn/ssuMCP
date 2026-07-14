package com.ssuai.domain.mcp.tool;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthStateEntry;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderLink;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.McpSessionResolver;
import com.ssuai.domain.auth.mcp.McpSessionResolver.Code;
import com.ssuai.domain.auth.mcp.McpSessionResolver.OperationContext;
import com.ssuai.domain.auth.mcp.McpSessionResolver.Resolution;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;

/**
 * Helper shared by private MCP tools. All selection is delegated to the single
 * {@link McpSessionResolver} authorization boundary.
 *
 * <p>A supplied {@code mcp_session_id} is authoritative: the exact live session is
 * selected or the call is denied with {@code INVALID_SESSION}/{@code SESSION_MISMATCH}.
 * It is never replaced by a transport-bound session. When the argument is omitted, a
 * live binding for the current MCP transport may be used; otherwise the result is
 * {@code NO_SESSION}. Ordinary tool calls never create or rebind a session.
 *
 * <p>Only explicit authentication lifecycle operations use the resolver's authorized
 * rebind context. Provider linkage is checked after session resolution, so a valid but
 * unlinked session returns {@code AUTH_REQUIRED} for that exact session without reading
 * another session's provider state.
 */
@Component
public class McpAuthHelper {

    private final McpAuthService mcpAuthService;
    private final McpAuthUrlFactory urlFactory;
    private final McpSessionResolver sessionResolver;

    @Autowired
    public McpAuthHelper(
            McpSessionResolver sessionResolver,
            McpAuthService mcpAuthService,
            McpAuthUrlFactory urlFactory
    ) {
        this.sessionResolver = sessionResolver;
        this.mcpAuthService = mcpAuthService;
        this.urlFactory = urlFactory;
    }

    /** Test/compatibility constructor; production injects the singleton resolver above. */
    McpAuthHelper(McpAuthService mcpAuthService, McpAuthUrlFactory urlFactory, HttpServletRequest request) {
        this(new McpSessionResolver(mcpAuthService, request), mcpAuthService, urlFactory);
    }

    /** Resolves an ordinary operation without creating or rebinding a session. */
    public Optional<McpAuthSession> resolveSession(String mcpSessionId) {
        Resolution resolution = sessionResolver.resolve(mcpSessionId, OperationContext.ORDINARY);
        return resolution.resolved() ? Optional.of(resolution.session()) : Optional.empty();
    }

    /** Typed ordinary resolution for lifecycle tools that must preserve denial codes. */
    public Resolution resolveDetailed(String mcpSessionId) {
        return sessionResolver.resolve(mcpSessionId, OperationContext.ORDINARY);
    }

    /** Exact resolution for an explicitly authorized authentication/rebinding operation. */
    public Resolution resolveForAuthentication(String mcpSessionId) {
        return sessionResolver.resolve(mcpSessionId, OperationContext.AUTHENTICATION_REBIND);
    }

    /**
     * Returns the principalKey stored in the session for {@code provider}, or empty
     * if the session is missing, expired, or the provider has not been linked.
     * Uses the authoritative explicit-first resolver.
     */
    public Optional<String> principalKey(String idValue, McpProviderType provider) {
        return resolveSession(idValue)
                .flatMap(session -> session.provider(provider))
                .map(McpProviderLink::principalKey);
    }

    /**
     * Resolves both the provider {@code principalKey} and the canonical resolved session
     * id in one authoritative lookup. Returns empty in exactly the same cases as
     * {@link #principalKey(String, McpProviderType)} — i.e. when resolution is denied or
     * the provider has not been linked — so OK-path callers can
     * keep using the empty branch to emit AUTH_REQUIRED.
     *
     * <p>When an explicit id was supplied, {@code sessionId} is always that exact id. With
     * no explicit id it may be the current transport binding. Denied calls never disclose
     * a resolved id.
     */
    public Optional<ResolvedPrincipal> resolvePrincipal(String idValue, McpProviderType provider) {
        return resolveSession(idValue)
                .flatMap(session -> session.provider(provider)
                        .map(link -> new ResolvedPrincipal(link.principalKey(), session.id().value())));
    }

    /**
     * Pairing of the provider {@code principalKey} (studentId) with the canonical resolved
     * session id, returned by {@link #resolvePrincipal(String, McpProviderType)}.
     */
    public record ResolvedPrincipal(String providerSessionKey, String sessionId) {

        /** Compatibility alias: the value is now an opaque credential key, not a student id. */
        @Deprecated(forRemoval = false)
        public String studentId() {
            return providerSessionKey;
        }
    }

    /**
     * Builds the precise denial/authentication response for authoritative resolution.
     * A one-time state and login URL are generated only for an existing valid session
     * that is not linked to the requested provider.
     */
    public <T> McpPrivateToolResponse<T> buildAuthRequired(String idValue, McpProviderType provider) {
        Resolution resolution = sessionResolver.resolve(idValue, OperationContext.ORDINARY);
        McpAuthSession session = resolution.session();

        if (resolution.code() == Code.INVALID_SESSION) {
            return McpPrivateToolResponse.invalidSession(provider.name());
        }
        if (resolution.code() == Code.NO_SESSION) {
            return McpPrivateToolResponse.noSession(provider.name());
        }
        if (resolution.code() == Code.SESSION_MISMATCH) {
            // This method is reached from ordinary private tools after resolution failed.
            // Re-resolving in AUTHENTICATION_REBIND mode here would turn a rejected explicit
            // id into an authenticated session and disclose it in AUTH_REQUIRED. Only the
            // start_auth lifecycle tool may opt into an explicit rebind.
            return McpPrivateToolResponse.sessionMismatch(provider.name());
        }
        if (session == null) {
            return McpPrivateToolResponse.noSession(provider.name());
        }

        McpAuthStateEntry state = mcpAuthService.generateState(session.id(), provider);
        String loginUrl = urlFactory.buildLoginUrl(provider, state.state());
        return McpPrivateToolResponse.authRequired(
                session.id().value(), provider.name(), loginUrl, state.expiresAt());
    }

    /**
     * Binds the current request's transport session id to the given auth session.
     * Call this immediately after {@code start_auth} creates or reuses a session
     * so that the transport fallback path works for all subsequent tool calls.
     */
    public boolean bindCurrentTransportId(McpAuthSessionId sessionId) {
        return sessionResolver.bindCurrentRequest(sessionId);
    }
}
