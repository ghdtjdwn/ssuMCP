package com.ssuai.domain.auth.lms;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ssuai.global.exception.LmsAuthFailedException;

/**
 * LMS auth after SmartID SSO.
 *
 * <p>Phase 1 — GET {@code lms.ssu.ac.kr/xn-sso/gw-cb.php?sToken=&sIdno=}
 * with the one-shot SmartID tokens. gw-cb.php validates with SmartID,
 * issues LMS session cookies, and 302-redirects to the Canvas auth callback.
 * We do NOT auto-follow the redirect so the 302's Set-Cookie and Location
 * headers are captured.
 *
 * <p>Phase 2 — GET the gw-cb.php Location when present, with a dashboard URL
 * fallback for older flows.
 *
 * <p>Phase 3 — visit {@code canvas.ssu.ac.kr/learningx/login/from_cc?result=}
 * with the SmartID result value captured from phase 1. Canvas issues its own
 * session cookies including {@code xn_api_token} (JWT, 2h TTL),
 * {@code _legacy_normandy_session}, and {@code _normandy_session}. These are
 * the auth credentials the {@code RealLmsAssignmentsConnector} sends to the
 * Canvas API.
 *
 * <p>Both sets of cookies are merged and stored encrypted in
 * {@link LmsSessionStore} keyed by {@code sIdno} (= ssuAI studentId).
 * The TTL is bound by the shorter of the two cookie lifetimes; the
 * store default of 2h matches the {@code xn_api_token} JWT expiry.
 */
@Service
public class LmsSsoService {

    private static final Logger log = LoggerFactory.getLogger(LmsSsoService.class);

    private final LmsSsoProperties properties;
    private final LmsSessionStore sessionStore;

    public LmsSsoService(LmsSsoProperties properties, LmsSessionStore sessionStore) {
        this.properties = properties;
        this.sessionStore = sessionStore;
    }

    public void authenticate(String sToken, String sIdno) {
        authenticateForSession(sToken, sIdno, null);
    }

    /** Stores the canonical cookie jar under an exact MCP owner when supplied. */
    public void authenticateForSession(String sToken, String sIdno, String ownerSessionKey) {
        if (sToken == null || sToken.isBlank()) {
            throw new LmsAuthFailedException("sToken is required");
        }
        if (sIdno == null || sIdno.isBlank()) {
            throw new LmsAuthFailedException("sIdno is required");
        }

        LmsCookieJar jar = LmsCookieJar.empty(allowedOrigins());
        LmsCookieHandler cookieHandler = new LmsCookieHandler(jar);

        HttpClient sessionClient = HttpClient.newBuilder()
                .cookieHandler(cookieHandler)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getTimeout())
                .build();

        // Phase 1: gw-cb.php → lms session cookies
        String location = callGwCallback(sessionClient, sToken, sIdno, jar);
        log.info("lms auth phase1 cookies: {}", getCookieNames(jar));
        log.info("lms auth phase1 redirect: {}",
                location != null ? sanitizeRedirectLocation(location) : "(none)");

        String canvasStartUrl = location != null && !location.isBlank()
                ? location
                : properties.getCanvasBaseUrl() + "/learningx/dashboard?user_login="
                        + URLEncoder.encode(sIdno.trim(), StandardCharsets.UTF_8);

        // Phase 2: follow gw-cb.php redirect or fallback dashboard.
        fetchCanvasDashboard(sessionClient, canvasStartUrl, jar);
        log.info("lms auth phase2 cookies: {}", getCookieNames(jar));

        // Phase 3: the LearningX from_cc endpoint issues xn_api_token in the live browser flow.
        String resultParam = extractResultParam(location);
        if (resultParam != null && !resultParam.isBlank()) {
            String fromCcUrl = properties.getCanvasBaseUrl()
                    + "/learningx/login/from_cc?result="
                    + URLEncoder.encode(resultParam, StandardCharsets.UTF_8);
            fetchCanvasDashboard(sessionClient, fromCcUrl, jar);
            log.info("lms auth phase3 from_cc cookies: {}", getCookieNames(jar));
        } else {
            log.warn("lms auth phase1 missing result param; from_cc skipped");
        }

        // Phase 4: diagnostic fallback for older flows that still issue the token from dashboard.
        if (!hasCookie(jar, "xn_api_token")) {
            String canvasDashboardUrl = properties.getCanvasBaseUrl()
                    + "/learningx/dashboard?user_login="
                    + URLEncoder.encode(sIdno.trim(), StandardCharsets.UTF_8);
            fetchCanvasDashboard(sessionClient, canvasDashboardUrl, jar);
        }
        log.info("lms auth final cookies: {}", getCookieNames(jar));

        if (!hasCookie(jar, "xn_api_token")) {
            log.warn("lms auth missing xn_api_token: cookies={}", getCookieNames(jar));
            throw new LmsAuthFailedException("xn_api_token not issued");
        }

        String allCookiesHeader = jar.compatibilityHeader();
        String credentialKey = ownerSessionKey == null || ownerSessionKey.isBlank()
                ? sIdno.trim()
                : ownerSessionKey;
        sessionStore.putForSession(credentialKey, sIdno.trim(),
                new LmsCookies(allCookiesHeader, null, 0L, jar.serialize()));
    }

    private String callGwCallback(HttpClient sessionClient, String sToken, String sIdno, LmsCookieJar jar) {
        String url = properties.getGwCallbackUrl()
                + "?sToken=" + URLEncoder.encode(sToken, StandardCharsets.UTF_8)
                + "&sIdno=" + URLEncoder.encode(sIdno, StandardCharsets.UTF_8);
        URI uri = URI.create(url);
        requireTrusted(jar, uri);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Cookie", "sToken=" + sToken + "; sIdno=" + sIdno)
                .timeout(properties.getTimeout())
                .GET()
                .build();
        try {
            HttpResponse<Void> response = sessionClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.headers().firstValue("location")
                    .filter(value -> !value.isBlank())
                    .map(value -> URI.create(url).resolve(value).toString())
                    .orElse(null);
        } catch (IOException exception) {
            throw new LmsAuthFailedException("gw-cb.php io error", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LmsAuthFailedException("gw-cb.php interrupted", exception);
        }
    }

    private void fetchCanvasDashboard(HttpClient sessionClient, String startUrl, LmsCookieJar jar) {
        String url = startUrl;
        for (int hop = 0; hop <= 10; hop++) {
            URI uri = URI.create(url);
            requireTrusted(jar, uri);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Referer", "https://lms.ssu.ac.kr/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .timeout(properties.getTimeout())
                    .GET()
                    .build();
            try {
                HttpResponse<Void> response = sessionClient.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return;
                }
                if (status / 100 == 3) {
                    String location = response.headers().firstValue("location").orElse(null);
                    if (location == null || location.isBlank()) {
                        return;
                    }
                    url = URI.create(url).resolve(location).toString();
                    continue;
                }
                return;
            } catch (IOException exception) {
                throw new LmsAuthFailedException("canvas dashboard io error", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LmsAuthFailedException("canvas dashboard interrupted", exception);
            }
        }
    }

    private List<String> allowedOrigins() {
        return List.of(properties.getGwCallbackUrl(), properties.getCanvasBaseUrl(), properties.getCommonsBaseUrl());
    }

    private static void requireTrusted(LmsCookieJar jar, URI uri) {
        if (!jar.permits(uri)) {
            throw new LmsAuthFailedException("LMS redirect origin is not trusted");
        }
    }

    private static String getCookieNames(LmsCookieJar jar) {
        return java.util.Arrays.stream(jar.compatibilityHeader().split(";"))
                .map(String::trim).map(value -> value.split("=", 2)[0])
                .filter(value -> !value.isBlank()).collect(Collectors.joining(","));
    }

    private static boolean hasCookie(LmsCookieJar jar, String name) {
        return java.util.Arrays.stream(jar.compatibilityHeader().split(";"))
                .map(String::trim).anyMatch(value -> value.startsWith(name + "="));
    }

    private static String extractResultParam(String redirectLocation) {
        if (redirectLocation == null || redirectLocation.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(redirectLocation);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "result".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String sanitizeRedirectLocation(String location) {
        return location.replaceAll("(?i)(token|sToken|result)=[^&]*", "$1=REDACTED");
    }
}

