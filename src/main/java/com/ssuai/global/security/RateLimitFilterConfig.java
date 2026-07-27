package com.ssuai.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RateLimitFilter} on abuse-prone REST endpoints and the Streamable HTTP MCP
 * endpoint.
 *
 * <p>Mirrors {@link CsrfOriginGuardFilterConfig} / {@code JwtAuthFilterConfig}:
 * the guard runs outside the Spring Security chain (this code base does REST
 * auth via {@code JwtAuthFilter}, not the security filter chain). It is registered on
 * {@code /api/*} and {@code /mcp/**}; the filter itself narrows to the exact protected paths and
 * methods via {@code shouldNotFilter}. Actuator traffic never reaches it.</p>
 *
 * <p>Ordered to run just after the CSRF Origin guard (HIGHEST_PRECEDENCE + 110)
 * so a forged cross-site request is rejected before we even spend a rate-limit
 * slot on it.</p>
 */
@Configuration
class RateLimitFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilterConfig.class);

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<RedissonClient> redissonClientProvider,
            RateLimitRedisMetrics redisMetrics) {

        RedissonClient redissonClient = properties.isRedisEnabled() ? redissonClientProvider.getIfAvailable() : null;

        log.info("Per-IP rate limiting active — login={}/window, chat={}/window, copilot={}/window, "
                        + "confirm={}/window, refresh={}/window, mcp={}/window, "
                        + "mcpConcurrency={}/{}, window={}, redisShared={}, trustedProxyCount={}",
                properties.getLoginPerMinute(),
                properties.getChatPerMinute(),
                properties.getCopilotPerMinute(),
                properties.getConfirmPerMinute(),
                properties.getRefreshPerMinute(),
                properties.getMcpPerMinute(),
                properties.getMcpConcurrentPerIp(),
                properties.getMcpConcurrentGlobal(),
                properties.getWindow(),
                redissonClient != null,
                properties.getTrustedProxyCount());

        RateLimitFilter filter = RateLimitFilter.forSharedRules(properties, redissonClient, redisMetrics, objectMapper);

        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*", "/mcp", "/mcp/*");
        // Run just after CsrfOriginGuardFilter (HIGHEST_PRECEDENCE + 110).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 120);
        return registration;
    }
}
