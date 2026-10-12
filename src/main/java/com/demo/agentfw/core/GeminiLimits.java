package com.demo.agentfw.core;

import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;

/**
 * The request limits applied to every call to the Gemini API: a timeout for the whole call (retries
 * and waits included) and a bounded number of retries on the two statuses that mean "try again":
 * 429 (rate limited) and 503 (overloaded). Other failures are not retried: a 4xx other than 429 will
 * not get better, and a 500 can be a request the model rejects every time.
 *
 * <p>The values go in as per-request {@link HttpOptions}, which the genai client applies to that call
 * (timeout as an OkHttp call timeout in milliseconds, retries in {@code RetryInterceptor}: the attempts
 * count includes the first call, delays are seconds with exponential growth and jitter).
 */
public final class GeminiLimits {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_RETRIES = 1;

    private GeminiLimits() {
    }

    /**
     * @param timeoutSeconds limit of one call, including its retries
     * @param retries        extra attempts after the first one on 429/503; 0 turns retrying off
     */
    public static HttpOptions httpOptions(int timeoutSeconds, int retries) {
        if (timeoutSeconds < 1 || retries < 0) {
            throw new IllegalArgumentException("timeout must be at least 1 second and retries at least 0");
        }
        return HttpOptions.builder()
                .timeout(timeoutSeconds * 1000)
                .retryOptions(HttpRetryOptions.builder()
                        .attempts(retries + 1)
                        .initialDelay(1.0)
                        .maxDelay(8.0)
                        .expBase(2.0)
                        .jitter(0.25)
                        .httpStatusCodes(429, 503)
                        .build())
                .build();
    }
}
