package com.ssuai.global.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunable per-IP rate-limit settings ({@code ssuai.ratelimit.*}) for the
 * abuse-prone endpoints guarded by {@link RateLimitFilter}.
 *
 * <p>Defaults are deliberately <em>generous</em> — they exist to stop abuse
 * (brute-force, LLM-cost exhaustion), not to throttle a normal user clicking
 * around. Per-IP, per-minute by default. Override per environment via config.</p>
 */
@Component
@ConfigurationProperties(prefix = "ssuai.ratelimit")
public class RateLimitProperties {

    /** The rolling window each limit is counted over. */
    private Duration window = Duration.ofMinutes(1);

    /** Max {@code POST /api/library/login} requests per IP per window. */
    private int loginPerMinute = 10;

    /** Max {@code POST /api/chat} requests per IP per window. */
    private int chatPerMinute = 30;

    /**
     * Max {@code POST /api/copilot/policy-cases} requests per IP per window.
     * Each accepted request performs cached lexical policy search and may perform one
     * metered private LLM completion, so this gets a smaller, independent budget from chat.
     */
    private int copilotPerMinute = 10;

    /**
     * Max {@code POST /api/library/reservations/confirm} requests per IP per
     * window. This executes real seat reserve/cancel/swap on oasis.ssu.ac.kr, so
     * it is the write-abuse target; kept moderate (a normal user confirms a
     * handful of times, never dozens per minute).
     */
    private int confirmPerMinute = 20;

    /**
     * Max {@code POST /api/auth/refresh} requests per IP per window. Generous —
     * a legitimate open tab refreshes its access token periodically; this only
     * stops a refresh flood.
     */
    private int refreshPerMinute = 60;

    /** Max Streamable HTTP MCP POST requests per IP per window. */
    private int mcpPerMinute = 120;

    /** Max concurrent MCP requests from one resolved client IP on a single pod. */
    private int mcpConcurrentPerIp = 4;

    /** Max concurrent MCP requests across all clients on a single pod. */
    private int mcpConcurrentGlobal = 64;

    /**
     * Whether the inbound per-IP limiter shares its counters via Redis
     * (SCALE-ROADMAP Phase 1 audit A1). Defaults on: at replica=1 this is
     * behaviorally identical to the old per-pod-only counter, and it removes
     * the "limit × replicas" leak the moment replicas &gt; 1 — no config
     * change needed to get correct multi-pod behavior. A Redis outage or a
     * missing Redisson bean falls back to the exact same per-pod counting
     * this flag would otherwise disable (see {@link SharedIpRateLimiter}).
     */
    private boolean redisEnabled = true;

    /**
     * Number of trusted reverse-proxy hops between the client and this
     * service that append to {@code X-Forwarded-For}
     * ({@link ClientIpResolver}). Default {@code 1} covers the standard k3s
     * Traefik ingress deployment with no config change. A larger value is safe
     * only when every additional proxy hop is authenticated by infrastructure;
     * public callers can otherwise forge the extra XFF position. {@code 0}
     * disables XFF trust entirely (always uses {@code getRemoteAddr()}).
     */
    private int trustedProxyCount = 1;

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getLoginPerMinute() {
        return loginPerMinute;
    }

    public void setLoginPerMinute(int loginPerMinute) {
        this.loginPerMinute = loginPerMinute;
    }

    public int getChatPerMinute() {
        return chatPerMinute;
    }

    public void setChatPerMinute(int chatPerMinute) {
        this.chatPerMinute = chatPerMinute;
    }

    public int getCopilotPerMinute() {
        return copilotPerMinute;
    }

    public void setCopilotPerMinute(int copilotPerMinute) {
        this.copilotPerMinute = copilotPerMinute;
    }

    public int getConfirmPerMinute() {
        return confirmPerMinute;
    }

    public void setConfirmPerMinute(int confirmPerMinute) {
        this.confirmPerMinute = confirmPerMinute;
    }

    public int getRefreshPerMinute() {
        return refreshPerMinute;
    }

    public void setRefreshPerMinute(int refreshPerMinute) {
        this.refreshPerMinute = refreshPerMinute;
    }

    public int getMcpPerMinute() {
        return mcpPerMinute;
    }

    public void setMcpPerMinute(int mcpPerMinute) {
        this.mcpPerMinute = mcpPerMinute;
    }

    public int getMcpConcurrentPerIp() {
        return mcpConcurrentPerIp;
    }

    public void setMcpConcurrentPerIp(int mcpConcurrentPerIp) {
        this.mcpConcurrentPerIp = mcpConcurrentPerIp;
    }

    public int getMcpConcurrentGlobal() {
        return mcpConcurrentGlobal;
    }

    public void setMcpConcurrentGlobal(int mcpConcurrentGlobal) {
        this.mcpConcurrentGlobal = mcpConcurrentGlobal;
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public int getTrustedProxyCount() {
        return trustedProxyCount;
    }

    public void setTrustedProxyCount(int trustedProxyCount) {
        if (trustedProxyCount < 0) {
            throw new IllegalArgumentException("trustedProxyCount must be >= 0");
        }
        this.trustedProxyCount = trustedProxyCount;
    }

}
