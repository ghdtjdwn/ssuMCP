package com.ssuai.domain.auth.mcp;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authoritative resolver for every MCP authentication-session lookup.
 *
 * <p>A non-blank explicit id is an exact authorization boundary. It is looked up before
 * any request binding and is never replaced by an OAuth- or transport-bound session.
 * Ordinary operations also reject a valid explicit session when the current request is
 * bound to a different live session. Only an explicit authentication/rebinding flow may
 * opt into {@link OperationContext#AUTHENTICATION_REBIND}.
 */
@Component
public class McpSessionResolver {

    private static final Logger log = LoggerFactory.getLogger(McpSessionResolver.class);

    private final McpAuthService authService;
    private final HttpServletRequest request;

    public McpSessionResolver(McpAuthService authService, HttpServletRequest request) {
        this.authService = authService;
        this.request = request;
    }

    public Resolution resolve(String explicitSessionId, OperationContext context) {
        String explicit = normalize(explicitSessionId);
        String oauthSubject = currentOauthSubject();
        String transportId = currentTransportId();

        if (explicit != null) {
            Optional<McpAuthSession> exact = authService.find(explicit);
            if (exact.isEmpty()) {
                logResolution(Code.INVALID_SESSION, Source.EXPLICIT, null);
                return Resolution.failure(Code.INVALID_SESSION, Source.EXPLICIT);
            }
            McpAuthSession exactSession = exact.get();
            if (context == OperationContext.ORDINARY) {
                if (oauthSubject != null
                        && !authService.verifyOauthSubject(exactSession.id(), oauthSubject)) {
                    logResolution(Code.SESSION_MISMATCH, Source.EXPLICIT, exactSession);
                    return Resolution.failure(Code.SESSION_MISMATCH, Source.EXPLICIT);
                }
                Optional<McpAuthSession> transportBinding = findTransportBinding(transportId);
                if (isDifferent(transportBinding, exactSession)) {
                    logResolution(Code.SESSION_MISMATCH, Source.EXPLICIT, exactSession);
                    return Resolution.failure(Code.SESSION_MISMATCH, Source.EXPLICIT);
                }
            }
            logResolution(Code.OK, Source.EXPLICIT, exactSession);
            return Resolution.success(exactSession, Source.EXPLICIT);
        }

        Optional<McpAuthSession> transportBinding = findTransportBinding(transportId);
        if (transportBinding.isPresent()) {
            if (oauthSubject != null
                    && !authService.verifyOauthSubject(transportBinding.get().id(), oauthSubject)) {
                logResolution(Code.SESSION_MISMATCH, Source.TRANSPORT, transportBinding.get());
                return Resolution.failure(Code.SESSION_MISMATCH, Source.TRANSPORT);
            }
            logResolution(Code.OK, Source.TRANSPORT, transportBinding.get());
            return Resolution.success(transportBinding.get(), Source.TRANSPORT);
        }

        logResolution(Code.NO_SESSION, Source.NONE, null);
        return Resolution.failure(Code.NO_SESSION, Source.NONE);
    }

    /** Bind request identity only from an explicitly authorized auth/rebind operation. */
    public boolean bindCurrentRequest(McpAuthSessionId sessionId) {
        if (sessionId == null) {
            return false;
        }
        String oauthSubject = currentOauthSubject();
        if (oauthSubject != null && !authService.bindOrVerifyOauthSubject(sessionId, oauthSubject)) {
            log.warn("mcp authorized rebind refused due to oauth ownership session={}",
                    sessionId.fingerprint());
            return false;
        }
        String transportId = currentTransportId();
        if (transportId != null) {
            try {
                if (!authService.bindTransportId(sessionId, transportId)) {
                    log.warn("mcp authorized transport bind refused session={}",
                            sessionId.fingerprint());
                    return false;
                }
            } catch (RuntimeException exception) {
                log.warn("mcp authorized transport bind conflicted session={}",
                        sessionId.fingerprint());
                return false;
            }
        }
        return true;
    }

    String currentTransportId() {
        try {
            return normalize(request.getHeader("Mcp-Session-Id"));
        } catch (Exception exception) {
            log.trace("transport session id unavailable", exception);
            return null;
        }
    }

    private Optional<McpAuthSession> findTransportBinding(String transportId) {
        return transportId == null ? Optional.empty() : authService.findByTransportId(transportId);
    }

    private static boolean isDifferent(Optional<McpAuthSession> binding, McpAuthSession exact) {
        return binding.isPresent() && !sameSession(binding.get(), exact);
    }

    private static boolean sameSession(McpAuthSession left, McpAuthSession right) {
        return left.id().equals(right.id());
    }

    private static String currentOauthSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return normalize(jwtAuthentication.getName());
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void logResolution(Code code, Source source, McpAuthSession session) {
        if (code == Code.SESSION_MISMATCH || code == Code.INVALID_SESSION) {
            log.warn("mcp session resolution denied code={} source={} session={}",
                    code, source, session == null ? "none" : session.id().fingerprint());
        } else {
            log.debug("mcp session resolution code={} source={} session={}",
                    code, source, session == null ? "none" : session.id().fingerprint());
        }
    }

    public enum OperationContext {
        ORDINARY,
        AUTHENTICATION_REBIND
    }

    public enum Code {
        OK,
        NO_SESSION,
        INVALID_SESSION,
        SESSION_MISMATCH
    }

    public enum Source {
        EXPLICIT,
        TRANSPORT,
        NONE
    }

    public record Resolution(Code code, Source source, McpAuthSession session) {

        public static Resolution success(McpAuthSession session, Source source) {
            return new Resolution(Code.OK, source, session);
        }

        public static Resolution failure(Code code, Source source) {
            return new Resolution(code, source, null);
        }

        public boolean resolved() {
            return code == Code.OK && session != null;
        }
    }
}
