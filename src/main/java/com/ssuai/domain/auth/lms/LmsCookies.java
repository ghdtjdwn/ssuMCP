package com.ssuai.domain.auth.lms;

/**
 * Raw {@code Cookie} header value captured after the LMS two-phase auth
 * (SmartID SSO → gw-cb.php → canvas dashboard). Sent as-is on every
 * canvas.ssu.ac.kr API request. Never logged — see docs/security.md §4.
 */
public record LmsCookies(
        String rawCookieHeader,
        String sessionKey,
        long credentialVersion,
        String cookieJarPayload) {

    public LmsCookies(String rawCookieHeader) {
        this(rawCookieHeader, null, 0L, null);
    }

    public LmsCookies(String rawCookieHeader, String sessionKey, long credentialVersion) {
        this(rawCookieHeader, sessionKey, credentialVersion, null);
    }

    public LmsCookies {
        if ((rawCookieHeader == null || rawCookieHeader.isBlank())
                && (cookieJarPayload == null || cookieJarPayload.isBlank())) {
            throw new IllegalArgumentException("rawCookieHeader or cookieJarPayload is required");
        }
        if (credentialVersion < 0) {
            throw new IllegalArgumentException("credentialVersion must not be negative");
        }
    }
}
