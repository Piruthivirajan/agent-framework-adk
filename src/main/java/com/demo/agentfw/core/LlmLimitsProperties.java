package com.demo.agentfw.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from the same {@code agentfw.llm} prefix as {@link LlmProperties}.
 *
 * @param requestTimeoutSeconds limit of one model call, retries included
 * @param retries               extra attempts after the first on 429 and 503 (with backoff); 0 = none
 */
@ConfigurationProperties("agentfw.llm")
public record LlmLimitsProperties(
        @DefaultValue("60") int requestTimeoutSeconds,
        @DefaultValue("1") int retries) {

    public LlmLimitsProperties {
        if (requestTimeoutSeconds < 1) {
            throw new IllegalArgumentException("agentfw.llm.request-timeout-seconds must be at least 1");
        }
        if (retries < 0 || retries > 5) {
            throw new IllegalArgumentException("agentfw.llm.retries must be between 0 and 5");
        }
    }

    public static LlmLimitsProperties defaults() {
        return new LlmLimitsProperties(GeminiLimits.DEFAULT_TIMEOUT_SECONDS, GeminiLimits.DEFAULT_RETRIES);
    }
}
