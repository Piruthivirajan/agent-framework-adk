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

    /**
     * Fingerprint of everything that decides what chunks a file turns into: the algorithm version
     * and the chunking settings. It is stored on every chunk and compared by the skip check, so
     * changing the settings (or bumping {@link #CODE_VERSION} after changing the loader or chunker)
     * re-ingests files whose bytes did not change. Chunks stored before versions existed carry the
     * legacy marker "1" and never match, so they are rebuilt once.
     */
    public String pipelineVersion() {
        return "v" + CODE_VERSION + "|chunk=" + chunkTargetTokens + "/" + chunkOverlapPercent + "/" + chunkMinTailTokens;
    }

    /** Bump when the loader or chunker algorithm changes. */
    public static final String CODE_VERSION = "1";
}
