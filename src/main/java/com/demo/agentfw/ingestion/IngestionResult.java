package com.demo.agentfw.ingestion;

import java.util.List;
import java.util.Map;

/**
 * Outcome of one ingestion run.
 *
 * @param ingested     docIds that were parsed, embedded and stored
 * @param skipped      docIds whose stored hash and embedding model already matched (no work done)
 * @param empty        file names with no extractable text (for example scanned PDFs); nothing stored
 * @param failed       file name to error message; the previous chunks of these documents are untouched
 * @param prunedChunks chunks removed because their document is no longer in the corpus folder
 * @param pruneSkipped true when pruning was skipped because the folder contained no PDFs at all
 */
public record IngestionResult(
        List<String> ingested,
        List<String> skipped,
        List<String> empty,
        Map<String, String> failed,
        long prunedChunks,
        boolean pruneSkipped) {

    public boolean hasFailures() {
        return !failed.isEmpty();
    }

    public String summary() {
        return "ingested=" + ingested.size() + " skipped=" + skipped.size() + " empty=" + empty.size()
                + " failed=" + failed.size() + " prunedChunks=" + prunedChunks
                + (pruneSkipped ? " (prune skipped: no PDFs found)" : "");
    }
}
