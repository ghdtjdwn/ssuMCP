package com.ssuai.domain.auth.saint;

/**
 * Plaintext SAINT session material loaded from {@link SaintSessionStore}.
 *
 * <p>The legacy Java WebDynpro connectors interpret {@code rawCookieHeader}
 * as an HTTP {@code Cookie} header. The rusaint connectors interpret the same
 * opaque slot as serialized rusaint session JSON. Keeping the record shape
 * stable avoids a session-store migration; existing entries simply expire and
 * users re-run SmartID SSO.
 *
 * <p>Never log {@code rawCookieHeader} directly. It can contain upstream
 * cookies or rusaint's serialized authenticated session.
 */
public final class PortalCookies {

    private final String initialSessionMaterial;
    private final java.util.concurrent.atomic.AtomicReference<String> currentSessionMaterial;

    public PortalCookies(String rawCookieHeader) {
        if (rawCookieHeader == null || rawCookieHeader.isBlank()) {
            throw new IllegalArgumentException("rawCookieHeader is required");
        }
        this.initialSessionMaterial = rawCookieHeader;
        this.currentSessionMaterial = new java.util.concurrent.atomic.AtomicReference<>(rawCookieHeader);
    }

    public String rawCookieHeader() {
        return currentSessionMaterial.get();
    }

    public String sessionJson() {
        return currentSessionMaterial.get();
    }

    /**
     * Records refreshed rusaint state on this transient call snapshot. The provider store
     * persists it after the connector returns; the connector never accesses persistence.
     */
    public void refreshSessionJson(String refreshedSessionJson) {
        if (refreshedSessionJson == null || refreshedSessionJson.isBlank()) {
            throw new IllegalArgumentException("refreshedSessionJson is required");
        }
        currentSessionMaterial.set(refreshedSessionJson);
    }

    public boolean wasRefreshed() {
        return !initialSessionMaterial.equals(currentSessionMaterial.get());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PortalCookies cookies
                && initialSessionMaterial.equals(cookies.initialSessionMaterial);
    }

    @Override
    public int hashCode() {
        return initialSessionMaterial.hashCode();
    }

    @Override
    public String toString() {
        return "PortalCookies[redacted]";
    }
}
