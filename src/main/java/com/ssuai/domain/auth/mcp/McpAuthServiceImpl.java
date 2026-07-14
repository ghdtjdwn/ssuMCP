package com.ssuai.domain.auth.mcp;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class McpAuthServiceImpl implements McpAuthService {

    private final McpAuthSessionStore sessionStore;
    private final McpAuthStateStore stateStore;

    McpAuthServiceImpl(McpAuthSessionStore sessionStore, McpAuthStateStore stateStore) {
        this.sessionStore = sessionStore;
        this.stateStore = stateStore;
    }

    @Override
    public Optional<McpAuthSession> find(String idValue) {
        return sessionStore.find(idValue);
    }

    @Override
    public McpAuthSession getOrCreate(String idValue) {
        if (idValue != null && !idValue.isBlank()) {
            Optional<McpAuthSession> existing = sessionStore.find(idValue);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return sessionStore.create();
    }

    @Override
    public McpAuthSession createSession() {
        return sessionStore.create();
    }

    @Override
    @Transactional
    public McpAuthStateEntry generateState(McpAuthSessionId sessionId, McpProviderType provider) {
        long revision = sessionStore.beginAuthentication(sessionId, provider);
        stateStore.invalidateForProvider(sessionId, provider);
        return stateStore.generate(sessionId, provider, revision);
    }

    @Override
    public Optional<McpAuthStateEntry> peekState(String state) {
        return stateStore.peek(state);
    }

    @Override
    public Optional<McpAuthStateEntry> consumeState(String state) {
        return stateStore.consume(state);
    }

    @Override
    public Optional<McpAuthSession> findByTransportId(String transportId) {
        return sessionStore.findByTransportId(transportId);
    }

    @Override
    public Optional<McpAuthSession> findByOauthSubject(String oauthSubject) {
        return sessionStore.findByOauthSubject(oauthSubject);
    }

    @Override
    public boolean bindTransportId(McpAuthSessionId sessionId, String transportId) {
        return sessionStore.bindTransportId(sessionId, transportId);
    }

    @Override
    public void bindOauthSubject(McpAuthSessionId sessionId, String oauthSubject) {
        sessionStore.bindOauthSubject(sessionId, oauthSubject);
    }

    @Override
    public boolean bindOrVerifyOauthSubject(McpAuthSessionId sessionId, String oauthSubject) {
        return sessionStore.bindOrVerifyOauthSubject(sessionId, oauthSubject);
    }

    @Override
    public boolean verifyOauthSubject(McpAuthSessionId sessionId, String oauthSubject) {
        return sessionStore.verifyOauthSubject(sessionId, oauthSubject);
    }

    @Override
    public void linkProvider(McpAuthSessionId sessionId, McpProviderType provider, String principalKey) {
        sessionStore.linkProvider(sessionId, provider, principalKey);
    }

    @Override
    public boolean linkProviderIfCurrentAttempt(
            McpAuthSessionId sessionId,
            McpProviderType provider,
            String principalKey,
            long expectedRevision) {
        return sessionStore.linkProviderIfCurrentAttempt(
                sessionId, provider, principalKey, expectedRevision);
    }

    @Override
    public boolean ownsProviderCredential(
            String ownerMcpSessionId,
            McpProviderType provider,
            String credentialKey) {
        if (ownerMcpSessionId == null || ownerMcpSessionId.isBlank()
                || provider == null || credentialKey == null || credentialKey.isBlank()) {
            return false;
        }
        return sessionStore.find(ownerMcpSessionId)
                .flatMap(session -> session.provider(provider))
                .map(link -> credentialKey.equals(link.principalKey()))
                .orElse(false);
    }

    @Override
    public <T> T executeWhileProviderCredentialCurrent(
            String ownerMcpSessionId,
            McpProviderType provider,
            String credentialKey,
            Supplier<T> operation) {
        return sessionStore.executeWhileProviderCredentialCurrent(
                ownerMcpSessionId, provider, credentialKey, operation);
    }

    @Override
    public void unlinkProvider(McpAuthSessionId sessionId, McpProviderType provider) {
        sessionStore.unlinkProvider(sessionId, provider);
    }

    @Override
    public Optional<McpProviderLink> unlinkProviderAndGetLink(
            McpAuthSessionId sessionId, McpProviderType provider) {
        return sessionStore.unlinkProviderAndGetLink(sessionId, provider);
    }

    @Override
    public void invalidateSession(McpAuthSessionId sessionId) {
        sessionStore.invalidate(sessionId);
    }
}
