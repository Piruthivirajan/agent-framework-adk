package com.demo.agentfw.files;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Summarizer settings. Starting values; the final ones come from the large-file measurement.
 *
 * @param singlePassMaxChars text up to this size is summarized in one model call
 * @param segmentChars       target size of one segment in the map step
 * @param reduceMaxChars     most partial-summary text sent to one reduce call; more is reduced in groups
 * @param parallelism        concurrent model calls in the map step (bounded to stay inside rate limits)
 * @param maxRetries         retries of one model call before the whole summary fails
 * @param maxSegments        refuse a text that would need more map calls than this
 */
@ConfigurationProperties("agentfw.summary")
public record SummaryProperties(
        @DefaultValue("30000") int singlePassMaxChars,
        @DefaultValue("15000") int segmentChars,
        @DefaultValue("30000") int reduceMaxChars,
        @DefaultValue("4") int parallelism,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("250") int maxSegments) {

    public SummaryProperties {
        if (singlePassMaxChars < 1000 || segmentChars < 1000 || reduceMaxChars < 2000 || parallelism < 1
                || maxRetries < 0 || maxSegments < 1) {
            throw new IllegalArgumentException("agentfw.summary: sizes must be at least 1000 (reduce 2000), "
                    + "parallelism and max-segments at least 1, max-retries not negative");
        }
    }

    public static SummaryProperties defaults() {
        return new SummaryProperties(30_000, 15_000, 30_000, 4, 2, 250);
    }
}
