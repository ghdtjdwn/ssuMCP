package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;

import com.ssuai.domain.auth.mcp.McpProviderHealth;
import com.ssuai.domain.auth.mcp.McpProviderHealthSnapshot;
import com.ssuai.domain.auth.mcp.McpProviderType;

/**
 * Auth status for one provider inside {@link McpAuthStatusResponse}.
 * {@code linkedAt} is null when the provider is not linked.
 */
public record McpProviderStatusEntry(
        McpProviderType provider,
        boolean linked,
        Instant linkedAt,
        McpProviderHealth health,
        Instant lastValidatedAt,
        Instant lastSuccessfulCallAt,
        Instant lastFailureAt,
        String failureCode,
        long credentialVersion) {

    public static McpProviderStatusEntry linked(McpProviderType provider, Instant linkedAt) {
        return linked(provider, linkedAt, McpProviderHealthSnapshot.unknown(0));
    }

    public static McpProviderStatusEntry linked(
            McpProviderType provider,
            Instant linkedAt,
            McpProviderHealthSnapshot snapshot) {
        return new McpProviderStatusEntry(
                provider, true, linkedAt, snapshot.health(), snapshot.lastValidatedAt(),
                snapshot.lastSuccessfulCallAt(), snapshot.lastFailureAt(),
                snapshot.failureCode(), snapshot.credentialVersion());
    }

    public static McpProviderStatusEntry notLinked(McpProviderType provider) {
        return new McpProviderStatusEntry(
                provider, false, null, McpProviderHealth.UNKNOWN,
                null, null, null, null, 0);
    }
}
