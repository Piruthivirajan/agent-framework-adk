package com.demo.agentfw.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param corpusDir          folder scanned for PDFs
 * @param onStartup          run ingestion when the application starts (how a deployment seeds its corpus)
 * @param chunkTargetTokens  upper size of a normal chunk, in estimated tokens
 * @param chunkOverlapPercent share of the target repeated at the start of the next chunk
 * @param chunkMinTailTokens a piece shorter than this is never left over as a tiny last chunk
 */
@ConfigurationProperties("agentfw.ingestion")
public record IngestionProperties(
        @DefaultValue("corpus") String corpusDir,
        @DefaultValue("false") boolean onStartup,
        @DefaultValue("650") int chunkTargetTokens,
        @DefaultValue("15") int chunkOverlapPercent,
        @DefaultValue("100") int chunkMinTailTokens) {
}
