package com.ssuai.domain.auth.mcp;

/**
 * Raised when an operation can no longer prove that an MCP session owns the exact
 * provider-credential generation it was resolved with.
 */
public class McpProviderCredentialRevokedException extends RuntimeException {

    public McpProviderCredentialRevokedException() {
        super("MCP provider credential is no longer current");
    }
}
