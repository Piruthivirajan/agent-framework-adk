package com.demo.agentfw.ingestion;

/**
 * The chunk document stored in MongoDB (collection {@code chunks}).
 *
 * <p>{@code contentHash} is the hash of the whole source file, identical on all chunks of a
 * document. {@code embeddingModel} records which model produced {@code embedding}, so a model
 * change is detected and re-embedded even when the file did not change.
 */
public record StoredChunk(
        String tenantId,
        String docId,
        int chunkIndex,
        String file,
        int page,
        String text,
        String contentHash,
        String embeddingModel,
        float[] embedding) {
}
