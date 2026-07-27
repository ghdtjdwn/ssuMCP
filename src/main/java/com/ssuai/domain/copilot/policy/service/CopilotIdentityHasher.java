package com.ssuai.domain.copilot.policy.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssuai.global.exception.ApiException;
import com.ssuai.global.exception.ErrorCode;

/**
 * Produces stable, non-reversible Copilot owner keys without storing raw SmartID principals.
 * The dedicated secret deliberately separates this namespace from JWT and credential keys.
 */
@Component
public class CopilotIdentityHasher {

    static final int MIN_SECRET_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public CopilotIdentityHasher(
            @Value("${ssuai.copilot.identity-hmac-secret:}") String identityHmacSecret) {
        this.secret = identityHmacSecret == null
                ? new byte[0]
                : identityHmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return secret.length >= MIN_SECRET_BYTES;
    }

    public String key(String principal) {
        if (!isConfigured()) {
            throw new ApiException(ErrorCode.COPILOT_UNAVAILABLE);
        }
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("인증된 사용자 식별자가 필요합니다.");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(principal.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(ALGORITHM + "을 사용할 수 없습니다.", exception);
        }
    }
}
