package com.ssuai.domain.auth.lms;

import java.time.Instant;
import java.util.Map;

import com.ssuai.domain.auth.mcp.McpProviderHealthSnapshot;

record LmsSessionEntry(
        byte[] iv,
        byte[] ciphertext,
        String studentId,
        Instant capturedAt,
        Instant expiresAt,
        long credentialVersion,
        Map<String, Long> cookieVersions,
        McpProviderHealthSnapshot health) {

    LmsSessionEntry {
        if (iv == null || iv.length == 0) {
            throw new IllegalArgumentException("iv is required");
        }
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("ciphertext is required");
        }
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required");
        }
        if (capturedAt == null || expiresAt == null || health == null) {
            throw new IllegalArgumentException("timestamps are required");
        }
        cookieVersions = cookieVersions == null ? Map.of() : Map.copyOf(cookieVersions);
    }
}
