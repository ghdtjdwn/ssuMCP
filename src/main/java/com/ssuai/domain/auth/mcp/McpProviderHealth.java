package com.ssuai.domain.auth.mcp;

/** Last known health of one session-owned upstream provider credential. */
public enum McpProviderHealth {
    VALID,
    EXPIRED,
    UNKNOWN,
    ERROR
}
