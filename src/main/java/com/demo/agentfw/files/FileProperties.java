package com.demo.agentfw.files;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits for files a user uploads to a chat session. They are starting values: the final ones are set
 * from the large-file measurement (docs/PROGRESS.md, Day 5 step 9).
 *
 * @param maxBytes          largest accepted upload; also the multipart limit of the web layer
 * @param maxChars          largest extracted text accepted; the Tika write limit stops a decompression
 *                          bomb or a huge file early. 1M characters is about 250k tokens, so the 300+
 *                          page framework PDF fits
 * @param ttlHours          how long an upload is kept (MongoDB TTL index)
 * @param maxFilesPerSession uploads allowed per chat session
 */
@ConfigurationProperties("agentfw.files")
public record FileProperties(
        @DefaultValue("20000000") long maxBytes,
        @DefaultValue("1000000") int maxChars,
        @DefaultValue("24") int ttlHours,
        @DefaultValue("10") int maxFilesPerSession) {

    public FileProperties {
        if (maxBytes < 1 || maxChars < 1 || ttlHours < 1 || maxFilesPerSession < 1) {
            throw new IllegalArgumentException("agentfw.files limits must all be at least 1");
        }
        if (maxChars > 3_000_000) {
            throw new IllegalArgumentException(
                    "agentfw.files.max-chars above 3,000,000 could exceed MongoDB's 16 MB document limit");
        }
    }

    public static FileProperties defaults() {
        return new FileProperties(20_000_000L, 1_000_000, 24, 10);
    }
}
