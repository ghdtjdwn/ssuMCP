package com.ssuai.domain.auth.mcp;

import java.time.Instant;

/** Privacy-safe provider health metadata; contains no credentials or user identifiers. */
public record McpProviderHealthSnapshot(
        McpProviderHealth health,
        Instant lastValidatedAt,
        Instant lastSuccessfulCallAt,
        Instant lastFailureAt,
        String failureCode,
        long credentialVersion) {

    public static McpProviderHealthSnapshot unknown(long credentialVersion) {
        return new McpProviderHealthSnapshot(
                McpProviderHealth.UNKNOWN, null, null, null, null, credentialVersion);
    }
}
