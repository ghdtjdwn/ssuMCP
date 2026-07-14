package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Objects;

/**
 * A linked provider session inside a {@link McpAuthSession}.
 *
 * <p>{@code principalKey} is the key used to look up the actual credentials in
 * the provider-specific store:
 * <ul>
 *   <li>SAINT / LMS → an exact MCP-session-owned credential namespace
 *   <li>LIBRARY → an exact MCP-session-owned opaque credential namespace
 * </ul>
 *
 * <p>The upstream identity is stored inside the encrypted provider record rather than
 * used as this key. The principalKey must not be logged in plain — pass it through
 * {@link com.ssuai.domain.auth.saint.SaintSessionStore#fingerprint} for log output.
 */
public record McpProviderLink(
        McpProviderType provider,
        String principalKey,
        Instant linkedAt,
        long generation) {

    public McpProviderLink(McpProviderType provider, String principalKey, Instant linkedAt) {
        this(provider, principalKey, linkedAt, 0L);
    }

    public McpProviderLink {
        Objects.requireNonNull(provider, "provider required");
        Objects.requireNonNull(principalKey, "principalKey required");
        if (principalKey.isBlank()) {
            throw new IllegalArgumentException("principalKey must not be blank");
        }
        Objects.requireNonNull(linkedAt, "linkedAt required");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
    }
}
