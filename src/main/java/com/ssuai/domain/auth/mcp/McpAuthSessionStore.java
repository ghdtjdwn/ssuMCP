package com.ssuai.domain.auth.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent store of {@link McpAuthSession} objects, keyed by
 * {@link McpAuthSessionId#value()}.
 *
 * <p>The actual upstream credentials (PortalCookies, LmsCookies, Pyxis tokens)
 * are NOT stored here. This store only holds the {@code principalKey} that
 * indexes into each provider-specific credential store.
 */
@Component
public class McpAuthSessionStore {

    private static final Logger log = LoggerFactory.getLogger(McpAuthSessionStore.class);
    private static final TypeReference<Map<String, ProviderEntry>> PROVIDERS_TYPE =
            new TypeReference<>() {};

    private final McpSessionRepository repository;
    private final ObjectMapper objectMapper;
    private final McpAuthProperties properties;
    private final Clock clock;

    @Autowired
    public McpAuthSessionStore(
            McpSessionRepository repository,
            ObjectMapper objectMapper,
            McpAuthProperties properties
    ) {
        this(repository, objectMapper, properties, Clock.systemUTC());
    }

    McpAuthSessionStore(
            McpSessionRepository repository,
            ObjectMapper objectMapper,
            McpAuthProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Creates a new session with a fresh {@link McpAuthSessionId} and no linked providers. */
    @Transactional
    public McpAuthSession create() {
        McpAuthSessionId id = McpAuthSessionId.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getSessionTtl());
        McpSessionEntity entity = new McpSessionEntity(id.value(), now, expiresAt, "{}");
        repository.save(entity);
        log.debug("mcp session created id={}", id.fingerprint());
        return toSession(entity);
    }

    /**
     * Returns the session for the given id if it exists and has not expired.
     * Expired sessions are dropped on access.
     */
    @Transactional(readOnly = true)
    public Optional<McpAuthSession> find(McpAuthSessionId id) {
        if (id == null) {
            return Optional.empty();
        }
        return findByValue(id.value());
    }

    /** Convenience overload accepting the raw string value from a tool argument. */
    @Transactional(readOnly = true)
    public Optional<McpAuthSession> find(String idValue) {
        if (idValue == null || idValue.isBlank()) {
            return Optional.empty();
        }
        return findByValue(idValue);
    }

    private Optional<McpAuthSession> findByValue(String value) {
        Instant now = clock.instant();
        return repository.findBySessionIdAndExpiresAtAfter(value, now)
                .map(this::toSession);
    }

    /**
     * Fallback lookup by transport session id (ADR 0036 §1B).
     * Used when the LLM drops the opaque {@code mcp_session_id} across turns (e.g. ChatGPT).
     */
    @Transactional(readOnly = true)
    public Optional<McpAuthSession> findByTransportId(String transportId) {
        if (transportId == null || transportId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return repository.findFirstByTransportSessionIdAndExpiresAtAfterOrderByCreatedAtDesc(transportId, now)
                .map(this::toSession);
    }

    /**
     * Lookup by OAuth {@code sub} claim (ADR 0036 §1A — first-tier principal).
     * Returns the session bound to this identity across conversations and connections.
     */
    @Transactional(readOnly = true)
    public Optional<McpAuthSession> findByOauthSubject(String oauthSubject) {
        if (oauthSubject == null || oauthSubject.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return repository.findFirstByOauthSubjectAndExpiresAtAfterOrderByCreatedAtDesc(oauthSubject, now)
                .map(this::toSession);
    }

    /**
     * Binds the HTTP-layer transport session id to this auth session.
     * A transport id is connection-scoped, so at most one live auth session may hold it.
     */
    @Transactional
    public boolean bindTransportId(McpAuthSessionId id, String transportId) {
        if (id == null || transportId == null || transportId.isBlank()) {
            return false;
        }
        Instant now = clock.instant();
        Optional<McpSessionEntity> target = repository.findActiveByIdForUpdate(id.value(), now);
        if (target.isEmpty()) {
            return false;
        }
        Optional<McpSessionEntity> currentHolder =
                repository.findFirstByTransportSessionIdAndExpiresAtAfterOrderByCreatedAtDesc(
                        transportId, now);
        if (currentHolder.isPresent()
                && !currentHolder.get().getSessionId().equals(id.value())) {
            log.warn("mcp transport bind conflict session={}", id.fingerprint());
            return false;
        }
        McpSessionEntity entity = target.get();
        if (!transportId.equals(entity.getTransportSessionId())) {
            entity.setTransportSessionId(transportId);
            repository.saveAndFlush(entity);
            log.debug("mcp transport bound session={}", id.fingerprint());
        }
        return true;
    }

    /**
     * Binds the OAuth {@code sub} to this auth session (idempotent, opportunistic).
     * Only sets the value when the column is still null — existing binding is preserved.
     * Called when the session is found via tier-2/3 and a JWT is present in SecurityContext.
     */
    @Transactional
    public void bindOauthSubject(McpAuthSessionId id, String oauthSubject) {
        if (id == null || oauthSubject == null || oauthSubject.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        repository.findBySessionIdAndExpiresAtAfter(id.value(), now).ifPresent(entity -> {
            if (entity.getOauthSubject() == null) {
                entity.setOauthSubject(oauthSubject);
                repository.save(entity);
                log.debug("mcp oauth sub bound session={}", id.fingerprint());
            }
        });
    }

    /**
     * Bind-or-verify the OAuth {@code sub} for ownership confirmation during tier-2/3
     * session resolution (security hardening). Unlike {@link #bindOauthSubject}, this
     * REJECTS a mismatch instead of silently keeping the existing binding, so a stolen
     * transport / opaque id cannot resolve a session bound to a different identity.
     *
     * <ul>
     *   <li>Stored {@code oauthSubject} is null → bind it, persist, return {@code true}.</li>
     *   <li>Stored value {@code equals} the arg → already this identity, return {@code true}.</li>
     *   <li>Stored value is a DIFFERENT non-null value → do NOT overwrite, return {@code false}.</li>
     *   <li>Null/blank arg, or no live session for the id → cannot confirm ownership,
     *       return {@code false}.</li>
     * </ul>
     *
     * @return {@code true} only when the caller's identity provably owns the session;
     *         {@code false} when ownership cannot be confirmed (treat as deny).
     */
    @Transactional
    public boolean bindOrVerifyOauthSubject(McpAuthSessionId id, String oauthSubject) {
        if (id == null || oauthSubject == null || oauthSubject.isBlank()) {
            return false;
        }
        Instant now = clock.instant();
        Optional<McpSessionEntity> entity = repository.findBySessionIdAndExpiresAtAfter(id.value(), now);
        if (entity.isEmpty()) {
            return false;
        }
        McpSessionEntity session = entity.get();
        String stored = session.getOauthSubject();
        if (stored == null) {
            session.setOauthSubject(oauthSubject);
            repository.save(session);
            log.debug("mcp oauth sub bound (verify) session={}", id.fingerprint());
            return true;
        }
        if (stored.equals(oauthSubject)) {
            return true;
        }
        log.warn("mcp oauth sub mismatch refused session={}", id.fingerprint());
        return false;
    }

    /** Verifies an existing OAuth binding without mutating an ordinary request. */
    @Transactional(readOnly = true)
    public boolean verifyOauthSubject(McpAuthSessionId id, String oauthSubject) {
        if (id == null || oauthSubject == null || oauthSubject.isBlank()) {
            return false;
        }
        return repository.findBySessionIdAndExpiresAtAfter(id.value(), clock.instant())
                .map(entity -> oauthSubject.equals(entity.getOauthSubject()))
                .orElse(false);
    }

    /**
     * Fences an irreversible provider write against logout/revocation. Both this method and
     * {@link #unlinkProvider(McpAuthSessionId, McpProviderType)} acquire the same active MCP
     * session row with {@code PESSIMISTIC_WRITE}; the lock is intentionally retained while the
     * supplied (rare, destructive) upstream operation runs.
     */
    @Transactional
    public <T> T executeWhileProviderCredentialCurrent(
            String ownerMcpSessionId,
            McpProviderType provider,
            String credentialKey,
            Supplier<T> operation) {
        if (ownerMcpSessionId == null || ownerMcpSessionId.isBlank()
                || provider == null || credentialKey == null || credentialKey.isBlank()
                || operation == null) {
            throw new McpProviderCredentialRevokedException();
        }
        McpSessionEntity entity = repository
                .findActiveByIdForUpdate(ownerMcpSessionId, clock.instant())
                .orElseThrow(McpProviderCredentialRevokedException::new);
        McpProviderLink link = deserializeProviders(entity.getProviders()).get(provider);
        if (link == null || !credentialKey.equals(link.principalKey())) {
            log.warn("mcp provider write fence refused session={} provider={}",
                    new McpAuthSessionId(ownerMcpSessionId).fingerprint(), provider);
            throw new McpProviderCredentialRevokedException();
        }
        return operation.get();
    }

    /** Starts a provider authentication generation and invalidates older callbacks. */
    @Transactional
    public long beginAuthentication(McpAuthSessionId id, McpProviderType provider) {
        if (id == null || provider == null) {
            throw new IllegalArgumentException("session and provider are required");
        }
        McpSessionEntity entity = repository.findActiveByIdForUpdate(id.value(), clock.instant())
                .orElseThrow(() -> new IllegalStateException("MCP session is not active"));
        long revision = entity.incrementAuthRevision(provider);
        repository.saveAndFlush(entity);
        return revision;
    }

    /**
     * Links a provider to the session, replacing any prior link for the same provider.
     * A no-op if the session does not exist or has expired.
     */
    @Transactional
    public void linkProvider(McpAuthSessionId id, McpProviderType provider, String principalKey) {
        if (id == null || provider == null || principalKey == null || principalKey.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        repository.findActiveByIdForUpdate(id.value(), now).ifPresent(entity -> {
            long generation = entity.incrementAuthRevision(provider);
            putProvider(entity, provider, principalKey, now, generation);
            repository.saveAndFlush(entity);
            log.debug("mcp provider linked session={} provider={} generation={}",
                    id.fingerprint(), provider, generation);
        });
    }

    /**
     * Links credentials only when the callback still owns the authentication generation.
     * Logout and a newer start_auth increment the revision, so stale callbacks fail closed.
     */
    @Transactional
    public boolean linkProviderIfCurrentAttempt(
            McpAuthSessionId id,
            McpProviderType provider,
            String principalKey,
            long expectedRevision) {
        if (id == null || provider == null || principalKey == null || principalKey.isBlank()
                || expectedRevision <= 0) {
            return false;
        }
        Instant now = clock.instant();
        Optional<McpSessionEntity> found = repository.findActiveByIdForUpdate(id.value(), now);
        if (found.isEmpty()) {
            return false;
        }
        McpSessionEntity entity = found.get();
        if (entity.getAuthRevision(provider) != expectedRevision) {
            log.warn("stale mcp provider callback refused session={} provider={}",
                    id.fingerprint(), provider);
            return false;
        }
        putProvider(entity, provider, principalKey, now, expectedRevision);
        repository.saveAndFlush(entity);
        log.debug("mcp provider callback committed session={} provider={} generation={}",
                id.fingerprint(), provider, expectedRevision);
        return true;
    }

    /**
     * Removes the provider link from the session.
     * A no-op if the session or provider does not exist.
     */
    @Transactional
    public void unlinkProvider(McpAuthSessionId id, McpProviderType provider) {
        unlinkProviderAndGetLink(id, provider);
    }

    /**
     * Removes and returns the exact link read while holding the live-session row lock. The
     * revision is incremented even when no link exists so an already-issued callback remains
     * fenced by an explicit logout attempt.
     */
    @Transactional
    public Optional<McpProviderLink> unlinkProviderAndGetLink(
            McpAuthSessionId id, McpProviderType provider) {
        if (id == null || provider == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<McpSessionEntity> found = repository.findActiveByIdForUpdate(id.value(), now);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        McpSessionEntity entity = found.get();
        Map<McpProviderType, McpProviderLink> updated = new EnumMap<>(McpProviderType.class);
        updated.putAll(deserializeProviders(entity.getProviders()));
        McpProviderLink removed = updated.remove(provider);
        entity.setProviders(serializeProviders(updated));
        entity.incrementAuthRevision(provider);
        repository.saveAndFlush(entity);
        log.debug("mcp provider unlinked session={} provider={}", id.fingerprint(), provider);
        return Optional.ofNullable(removed);
    }

    /** Removes the entire session (logout all). */
    @Transactional
    public void invalidate(McpAuthSessionId id) {
        if (id == null) {
            return;
        }
        repository.deleteById(id.value());
        log.debug("mcp session invalidated id={}", id.fingerprint());
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanupExpiredSessions() {
        cleanupExpired(clock.instant());
    }

    int size() {
        cleanupExpired(clock.instant());
        return Math.toIntExact(repository.count());
    }

    private void cleanupExpired(Instant now) {
        int deleted = repository.deleteByExpiresAtBefore(now);
        if (deleted > 0) {
            log.debug("mcp sessions expired count={}", deleted);
        }
    }

    private McpAuthSession toSession(McpSessionEntity entity) {
        return new McpAuthSession(
                new McpAuthSessionId(entity.getSessionId()),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                deserializeProviders(entity.getProviders())
        );
    }

    private Map<McpProviderType, McpProviderLink> deserializeProviders(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        Map<String, ProviderEntry> raw;
        try {
            raw = objectMapper.readValue(rawJson, PROVIDERS_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse MCP session providers JSON", exception);
        }

        Map<McpProviderType, McpProviderLink> providers = new EnumMap<>(McpProviderType.class);
        for (Map.Entry<String, ProviderEntry> entry : raw.entrySet()) {
            McpProviderType provider = McpProviderType.valueOf(entry.getKey());
            ProviderEntry providerEntry = entry.getValue();
            providers.put(provider, new McpProviderLink(
                    provider,
                    providerEntry.principalKey(),
                    providerEntry.linkedAt(),
                    providerEntry.generation()
            ));
        }
        return Map.copyOf(providers);
    }

    private String serializeProviders(Map<McpProviderType, McpProviderLink> providers) {
        Map<String, ProviderEntry> raw = new LinkedHashMap<>();
        providers.forEach((provider, link) -> raw.put(
                provider.name(),
                new ProviderEntry(link.principalKey(), link.linkedAt(), link.generation())
        ));
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize MCP session providers JSON", exception);
        }
    }

    static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
    }

    private void putProvider(
            McpSessionEntity entity,
            McpProviderType provider,
            String principalKey,
            Instant linkedAt,
            long generation) {
        Map<McpProviderType, McpProviderLink> updated = new EnumMap<>(McpProviderType.class);
        updated.putAll(deserializeProviders(entity.getProviders()));
        updated.put(provider, new McpProviderLink(provider, principalKey, linkedAt, generation));
        entity.setProviders(serializeProviders(updated));
    }

    private record ProviderEntry(String principalKey, Instant linkedAt, long generation) {
    }
}
