package com.ssuai.domain.lms.connector;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsCookieHandler;
import com.ssuai.domain.auth.lms.LmsCookieJar;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorRateLimitedException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

/**
 * One endpoint-call HTTP context backed by the canonical LMS cookie jar.
 * Every response, including trusted manual redirects, is merged back into
 * {@link LmsSessionStore}; no connector owns an independent long-lived cookie snapshot.
 */
final class LmsHttpSession {

    private static final int MAX_ATTEMPTS = 2;

    private final ObjectMapper objectMapper;
    private final LmsSessionStore sessionStore;
    private LmsCookies snapshot;
    private final Duration timeout;
    private final LmsCookieHandler cookieHandler;
    private final HttpClient client;

    LmsHttpSession(
            ObjectMapper objectMapper,
            LmsSessionStore sessionStore,
            LmsCookies snapshot,
            Duration timeout,
            String initialUrl) {
        this.objectMapper = objectMapper;
        this.sessionStore = sessionStore;
        this.snapshot = snapshot;
        this.timeout = timeout;
        URI initialUri = URI.create(initialUrl);
        LmsCookieJar jar = snapshot.cookieJarPayload() == null || snapshot.cookieJarPayload().isBlank()
                ? LmsCookieJar.fromLegacyHeader(snapshot.rawCookieHeader(), initialUri)
                : LmsCookieJar.fromSerialized(snapshot.cookieJarPayload());
        this.cookieHandler = new LmsCookieHandler(jar);
        this.client = HttpClient.newBuilder()
                .cookieHandler(cookieHandler)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    JsonNode getJson(String url, boolean bearerRequired) {
        String body = getText(url, bearerRequired);
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new ConnectorParseException(exception);
        }
    }

    String getText(String url, boolean bearerRequired) {
        URI target = URI.create(url);
        assertTrusted(target);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(target)
                    .header("Accept", "application/json")
                    .header("Referer", origin(url) + "/")
                    .timeout(timeout)
                    .GET();
            if (bearerRequired) {
                String bearer = cookieHandler.jar().valueFor(target, "xn_api_token");
                if (bearer == null || bearer.isBlank()) {
                    throw new LmsSessionExpiredException("LMS bearer credential is missing");
                }
                builder.header("Authorization", "Bearer " + bearer);
            }
            try {
                HttpResponse<String> response = sendFollowingTrustedRedirects(
                        builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                persistCookies();
                if (response.statusCode() >= 500 && attempt < MAX_ATTEMPTS) {
                    continue;
                }
                classify(response.statusCode(), response.uri(), response.body(), response.headers()
                        .firstValue("content-type").orElse(""));
                return response.body();
            } catch (IOException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new ConnectorUnavailableException(exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ConnectorUnavailableException(exception);
            }
        }
        throw new ConnectorUnavailableException();
    }

    void download(String url, java.io.OutputStream destination) {
        URI target = URI.create(url);
        assertTrusted(target);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(target)
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = sendFollowingTrustedRedirects(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            persistCookies();
            classify(response.statusCode(), response.uri(), null,
                    response.headers().firstValue("content-type").orElse(""));
            try (InputStream input = response.body()) {
                input.transferTo(destination);
            }
        } catch (IOException exception) {
            throw new ConnectorUnavailableException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConnectorUnavailableException(exception);
        }
    }

    HttpClient client() {
        return client;
    }

    LmsCookies cookies() {
        return snapshot;
    }

    private void persistCookies() {
        if (sessionStore == null || snapshot.sessionKey() == null) {
            return;
        }
        snapshot = sessionStore.mergeCookieJar(snapshot, cookieHandler.jar().serialize());
    }

    private static void classify(int status, URI finalUri, String body, String contentType) {
        if (status == 401 || status == 403) {
            throw new LmsSessionExpiredException("LMS authentication was rejected");
        }
        if (status == 429) {
            throw new ConnectorRateLimitedException(Duration.ofSeconds(30), null);
        }
        if (status >= 500) {
            throw new ConnectorUnavailableException();
        }
        if (status < 200 || status >= 300) {
            throw new LmsApiException("LMS upstream protocol error (HTTP " + status + ")", status);
        }
        String path = finalUri == null ? "" : finalUri.getPath().toLowerCase(Locale.ROOT);
        String normalized = body == null ? "" : body.stripLeading().toLowerCase(Locale.ROOT);
        boolean html = contentType.toLowerCase(Locale.ROOT).contains("text/html")
                || normalized.startsWith("<!doctype html")
                || normalized.startsWith("<html");
        if (html) {
            boolean login = path.contains("login") || path.contains("auth") || path.contains("sso")
                    || normalized.contains("login") || normalized.contains("로그인")
                    || normalized.contains("smartid");
            if (login) {
                throw new LmsSessionExpiredException("LMS login page returned instead of API data");
            }
            throw new ConnectorParseException();
        }
    }

    private void assertTrusted(URI target) {
        if (!cookieHandler.jar().permits(target)) {
            throw new LmsApiException("LMS request origin is not trusted", 400);
        }
    }

    private <T> HttpResponse<T> sendFollowingTrustedRedirects(
            HttpRequest initial, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        HttpRequest request = initial;
        for (int hop = 0; hop <= 10; hop++) {
            HttpResponse<T> response = client.send(request, bodyHandler);
            if (response.statusCode() / 100 != 3) {
                return response;
            }
            String location = response.headers().firstValue("location").orElse(null);
            if (location == null || location.isBlank()) {
                return response;
            }
            URI target = request.uri().resolve(location);
            if (!cookieHandler.jar().permits(target)) {
                persistCookies();
            }
            assertTrusted(target);
            closeRedirectBody(response.body());
            request = HttpRequest.newBuilder()
                    .uri(target)
                    .header("Accept", "application/json")
                    .header("Referer", request.uri().getScheme() + "://"
                            + request.uri().getAuthority() + "/")
                    .timeout(timeout)
                    .GET()
                    .build();
        }
        throw new LmsApiException("LMS redirect limit exceeded", 502);
    }

    private static void closeRedirectBody(Object body) {
        if (body instanceof InputStream input) {
            try {
                input.close();
            } catch (IOException ignored) {
                // The redirect has already been rejected or superseded.
            }
        }
    }

    private static String origin(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getAuthority();
    }
}
