package com.ssuai.domain.auth.lms;

import java.net.HttpCookie;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serializable, browser-like LMS cookie jar.  Cookie identity is deliberately
 * {@code name + domain + path + hostOnly}; a name alone is never sufficient.
 */
public final class LmsCookieJar {

    private static final String FORMAT = "SSU_LMS_COOKIE_JAR_V1";

    private final Set<String> allowedOrigins;
    private final Map<CookieKey, StoredCookie> cookies;

    private LmsCookieJar(Set<String> allowedOrigins, Map<CookieKey, StoredCookie> cookies) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.cookies = new LinkedHashMap<>(cookies);
    }

    public static LmsCookieJar empty(Collection<String> allowedOrigins) {
        return new LmsCookieJar(normalizeOrigins(allowedOrigins), Map.of());
    }

    /** Compatibility entry point for callers that only have the old Cookie header. */
    public static LmsCookieJar fromLegacyHeader(String rawCookieHeader, URI initialUri) {
        LmsCookieJar jar = empty(List.of(origin(initialUri)));
        for (String pair : rawCookieHeader == null ? new String[0] : rawCookieHeader.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            jar.put(new StoredCookie(
                    pair.substring(0, equals).trim(), pair.substring(equals + 1).trim(),
                    host(initialUri), defaultPath(initialUri.getPath()), true, false, null, 1L, false));
        }
        return jar;
    }

    public static LmsCookieJar fromSerialized(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("missing LMS cookie jar payload");
        }
        String[] lines = payload.split("\\R", -1);
        if (lines.length < 2 || !FORMAT.equals(lines[0])) {
            throw new IllegalArgumentException("unsupported LMS cookie jar payload");
        }
        Set<String> origins = normalizeOrigins(decodeList(lines[1]));
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("LMS cookie jar has no allowed origins");
        }
        Map<CookieKey, StoredCookie> values = new LinkedHashMap<>();
        for (int index = 2; index < lines.length; index++) {
            if (lines[index].isBlank()) {
                continue;
            }
            String[] parts = lines[index].split("\\t", -1);
            if (parts.length != 9) {
                throw new IllegalArgumentException("invalid LMS cookie jar entry");
            }
            StoredCookie cookie = new StoredCookie(
                    decode(parts[0]), decode(parts[1]), decode(parts[2]), decode(parts[3]),
                    Boolean.parseBoolean(parts[4]), Boolean.parseBoolean(parts[5]),
                    "-".equals(parts[6]) ? null : Instant.ofEpochMilli(Long.parseLong(parts[6])),
                    Long.parseLong(parts[7]), Boolean.parseBoolean(parts[8]));
            values.put(cookie.key(), cookie);
        }
        return new LmsCookieJar(origins, values);
    }

    public static LmsCookieJar fromCookies(
            Collection<HttpCookie> source, Collection<String> allowedOrigins) {
        LmsCookieJar jar = empty(allowedOrigins);
        Instant now = Instant.now();
        for (HttpCookie cookie : source) {
            String domain = cookie.getDomain();
            boolean hostOnly = domain == null || domain.isBlank();
            if (hostOnly) {
                continue; // CookieStore cannot recover the originating host reliably.
            }
            jar.put(new StoredCookie(cookie.getName(), cookie.getValue(), normalizeDomain(domain),
                    normalizePath(cookie.getPath()), false, cookie.getSecure(),
                    cookie.getMaxAge() < 0 ? null : now.plusSeconds(cookie.getMaxAge()), 1L, false));
        }
        return jar;
    }

    public boolean permits(URI uri) {
        return uri != null && allowedOrigins.contains(origin(uri));
    }

    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    public void applySetCookie(URI responseUri, String header) {
        if (!permits(responseUri) || header == null || header.isBlank()) {
            return;
        }
        try {
            for (HttpCookie parsed : HttpCookie.parse(header)) {
                apply(responseUri, parsed, nextVersion(parsed, responseUri));
            }
        } catch (IllegalArgumentException ignored) {
            // An upstream malformed Set-Cookie must not corrupt the canonical jar.
        }
    }

    public void apply(URI responseUri, HttpCookie parsed) {
        apply(responseUri, parsed, nextVersion(parsed, responseUri));
    }

    /** Performs composite-key compare-and-set against a response-side jar snapshot. */
    public boolean mergeChangedFrom(LmsCookieJar snapshot, LmsCookieJar observed, long newVersion) {
        boolean changed = false;
        for (Map.Entry<CookieKey, StoredCookie> entry : observed.cookies.entrySet()) {
            StoredCookie previous = snapshot.cookies.get(entry.getKey());
            StoredCookie candidate = entry.getValue();
            if (Objects.equals(previous, candidate)) {
                continue;
            }
            StoredCookie current = cookies.get(entry.getKey());
            long currentVersion = current == null ? 0L : current.version();
            long snapshotVersion = previous == null ? 0L : previous.version();
            if (currentVersion > snapshotVersion) {
                continue;
            }
            cookies.put(entry.getKey(), candidate.withVersion(newVersion));
            changed = true;
        }
        return changed;
    }

    private void apply(URI responseUri, HttpCookie parsed, long version) {
        if (!permits(responseUri)) {
            return;
        }
        String domain = parsed.getDomain();
        boolean hostOnly = domain == null || domain.isBlank();
        domain = hostOnly ? host(responseUri) : normalizeDomain(domain);
        if (!domainMatches(host(responseUri), domain, hostOnly)) {
            return;
        }
        String path = parsed.getPath() == null || parsed.getPath().isBlank()
                ? defaultPath(responseUri.getPath()) : normalizePath(parsed.getPath());
        CookieKey key = new CookieKey(parsed.getName(), domain, path, hostOnly);
        boolean deleted = parsed.getMaxAge() == 0 || parsed.hasExpired();
        Instant expiry = deleted ? Instant.now() : parsed.getMaxAge() > 0
                ? Instant.now().plusSeconds(parsed.getMaxAge()) : null;
        StoredCookie candidate = new StoredCookie(parsed.getName(), parsed.getValue(), domain, path,
                hostOnly, parsed.getSecure(), expiry, version, deleted);
        StoredCookie previous = cookies.get(key);
        if (previous != null && previous.equivalent(candidate)) {
            return;
        }
        cookies.put(key, candidate);
    }

    private long nextVersion(HttpCookie parsed, URI responseUri) {
        String domain = parsed.getDomain();
        boolean hostOnly = domain == null || domain.isBlank();
        domain = hostOnly ? host(responseUri) : normalizeDomain(domain);
        String path = parsed.getPath() == null || parsed.getPath().isBlank()
                ? defaultPath(responseUri.getPath()) : normalizePath(parsed.getPath());
        StoredCookie previous = cookies.get(new CookieKey(parsed.getName(), domain, path, hostOnly));
        return previous == null ? 1L : previous.version() + 1L;
    }

    public String cookieHeader(URI uri) {
        if (!permits(uri)) {
            return "";
        }
        Instant now = Instant.now();
        return cookies.values().stream()
                .filter(cookie -> cookie.matches(uri, now))
                .sorted(Comparator.comparingInt((StoredCookie cookie) -> cookie.path().length()).reversed())
                .map(cookie -> cookie.name() + "=" + cookie.value())
                .collect(Collectors.joining("; "));
    }

    public String valueFor(URI uri, String name) {
        if (!permits(uri)) {
            return null;
        }
        Instant now = Instant.now();
        return cookies.values().stream()
                .filter(cookie -> name.equals(cookie.name()) && cookie.matches(uri, now))
                .max(Comparator.comparingInt(cookie -> cookie.path().length()))
                .map(StoredCookie::value)
                .orElse(null);
    }

    public String compatibilityHeader() {
        Instant now = Instant.now();
        return cookies.values().stream()
                .filter(cookie -> !cookie.deleted() && !cookie.expired(now))
                .sorted(Comparator.comparing(StoredCookie::name))
                .map(cookie -> cookie.name() + "=" + cookie.value())
                .collect(Collectors.joining("; "));
    }

    public Map<String, Long> versions() {
        return cookies.entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().serialize(), entry -> entry.getValue().version(),
                Math::max, LinkedHashMap::new));
    }

    public String serialize() {
        StringBuilder result = new StringBuilder(FORMAT).append('\n')
                .append(encodeList(allowedOrigins)).append('\n');
        cookies.values().stream().sorted(Comparator.comparing(cookie -> cookie.key().serialize()))
                .forEach(cookie -> result.append(encode(cookie.name())).append('\t')
                        .append(encode(cookie.value())).append('\t')
                        .append(encode(cookie.domain())).append('\t')
                        .append(encode(cookie.path())).append('\t')
                        .append(cookie.hostOnly()).append('\t').append(cookie.secure()).append('\t')
                        .append(cookie.expiry() == null ? "-" : cookie.expiry().toEpochMilli()).append('\t')
                        .append(cookie.version()).append('\t').append(cookie.deleted()).append('\n'));
        return result.toString();
    }

    public LmsCookieJar copy() {
        return new LmsCookieJar(allowedOrigins, cookies);
    }

    private void put(StoredCookie cookie) {
        cookies.put(cookie.key(), cookie);
    }

    private static Set<String> normalizeOrigins(Collection<String> origins) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : origins == null ? List.<String>of() : origins) {
            try {
                URI uri = URI.create(value);
                result.add(origin(uri));
            } catch (IllegalArgumentException ignored) {
                // Invalid configured origins are excluded and cause callers to fail closed.
            }
        }
        return result;
    }

    private static String origin(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("absolute HTTP(S) URI is required");
        }
        int port = uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host(uri)
                + (port < 0 ? "" : ":" + port);
    }

    private static String host(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static String normalizeDomain(String domain) {
        return domain.toLowerCase(Locale.ROOT).replaceFirst("^\\.+", "");
    }

    private static String normalizePath(String path) {
        return path == null || path.isBlank() || !path.startsWith("/") ? "/" : path;
    }

    private static String defaultPath(String path) {
        String normalized = normalizePath(path);
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : normalized.substring(0, lastSlash);
    }

    private static boolean domainMatches(String host, String domain, boolean hostOnly) {
        return hostOnly ? host.equals(domain) : host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean pathMatches(String requestPath, String cookiePath) {
        String path = normalizePath(requestPath);
        return path.equals(cookiePath) || (path.startsWith(cookiePath)
                && (cookiePath.endsWith("/") || path.charAt(cookiePath.length()) == '/'));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String encodeList(Collection<String> values) {
        return values.stream().sorted().map(LmsCookieJar::encode).collect(Collectors.joining(","));
    }

    private static List<String> decodeList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            result.add(decode(part));
        }
        return result;
    }

    private record CookieKey(String name, String domain, String path, boolean hostOnly) {
        String serialize() {
            return encode(name) + "|" + encode(domain) + "|" + encode(path) + "|" + hostOnly;
        }
    }

    private record StoredCookie(String name, String value, String domain, String path,
                                boolean hostOnly, boolean secure, Instant expiry,
                                long version, boolean deleted) {
        CookieKey key() { return new CookieKey(name, domain, path, hostOnly); }
        boolean expired(Instant now) { return expiry != null && !expiry.isAfter(now); }
        boolean matches(URI uri, Instant now) {
            return !deleted && !expired(now) && domainMatches(host(uri), domain, hostOnly)
                    && pathMatches(uri.getPath(), path) && (!secure || "https".equalsIgnoreCase(uri.getScheme()));
        }
        StoredCookie withVersion(long value) {
            return new StoredCookie(name, this.value, domain, path, hostOnly, secure,
                    expiry, value, deleted);
        }
        boolean equivalent(StoredCookie other) {
            return other != null && name.equals(other.name) && this.value.equals(other.value)
                    && domain.equals(other.domain) && path.equals(other.path)
                    && hostOnly == other.hostOnly && secure == other.secure && deleted == other.deleted
                    && Objects.equals(expiry, other.expiry);
        }
    }
}
