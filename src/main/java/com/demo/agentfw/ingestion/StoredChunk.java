package com.demo.agentfw.ingestion;

/**
 * The chunk document stored in MongoDB (collection {@code chunks}).
 *
 * <p>{@code contentHash} is the hash of the whole source file, identical on all chunks of a
 * document. {@code embeddingModel} records which model produced {@code embedding}, so a model
 * change is detected and re-embedded even when the file did not change. {@code pipelineVersion}
 * records how the text was produced (cleaning and chunking), for the same reason: chunks written
 * by another pipeline version are rebuilt even when the file did not change.
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
        float[] embedding,
        String pipelineVersion) {

    /** Legacy pipeline: chunks stored before the version existed count as version "1". */
    public static final String LEGACY_PIPELINE = "1";

    public StoredChunk(String tenantId, String docId, int chunkIndex, String file, int page, String text,
                       String contentHash, String embeddingModel, float[] embedding) {
        this(tenantId, docId, chunkIndex, file, page, text, contentHash, embeddingModel, embedding, LEGACY_PIPELINE);
    }
}
