package com.ssuai.domain.auth.mcp;

import java.util.UUID;

/** Generates opaque, single-provider credential namespaces for one login generation. */
public final class McpCredentialNamespace {

    private McpCredentialNamespace() {
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
