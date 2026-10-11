package com.demo.agentfw.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param corpusDir          folder scanned for PDFs
 * @param onStartup          run ingestion when the application starts (how a deployment seeds its corpus)
 * @param chunkTargetTokens  upper size of a normal chunk, in estimated tokens
 * @param chunkOverlapPercent share of the target repeated at the start of the next chunk
 * @param chunkMinTailTokens a piece shorter than this is never left over as a tiny last chunk
 * @param stripRunningHeaders remove lines repeated at the top or bottom of most pages before chunking
 */
@ConfigurationProperties("agentfw.ingestion")
public record IngestionProperties(
        @DefaultValue("corpus") String corpusDir,
        @DefaultValue("false") boolean onStartup,
        @DefaultValue("650") int chunkTargetTokens,
        @DefaultValue("15") int chunkOverlapPercent,
        @DefaultValue("100") int chunkMinTailTokens,
        @DefaultValue("true") boolean stripRunningHeaders) {

    /**
     * Fingerprint of everything that decides what text and chunks a file turns into. It is stored on
     * every chunk and compared by the skip check, so changing the cleaning or chunking settings
     * (or bumping {@link #CODE_VERSION} after changing the algorithms) re-ingests unchanged files.
     */
    public String pipelineVersion() {
        return CODE_VERSION + "|strip=" + stripRunningHeaders
                + "|chunk=" + chunkTargetTokens + "/" + chunkOverlapPercent + "/" + chunkMinTailTokens;
    }

    /** Bump when the loader, stripper or chunker algorithm changes. */
    public static final String CODE_VERSION = "2";
}
