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
}
