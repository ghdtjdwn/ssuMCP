package com.ssuai.domain.saint.connector;

import java.util.Objects;

/** Result plus the canonical rusaint session state after the upstream operation. */
public record RusaintSessionResult<T>(T value, String sessionJson) {

    public RusaintSessionResult {
        Objects.requireNonNull(value, "value required");
        if (sessionJson == null || sessionJson.isBlank()) {
            throw new IllegalArgumentException("sessionJson is required");
        }
    }
}
