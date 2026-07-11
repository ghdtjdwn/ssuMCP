package com.ssuai.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/auth/exchange}. Carries the one-time code minted
 * by the SSO callback and handed to the browser via {@code ?code=} on the
 * frontend return URL (Fix B, ADR 0095).
 */
public record ExchangeRequest(
        @NotBlank String code
) {
}
