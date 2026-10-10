package com.demo.agentfw.retrieval;

/** The search-and-fuse stage as a port, so the pipeline above it can be tested without MongoDB. */
public interface ChunkSearch {

    /**
     * @param query       the user's question, used for the full-text pipeline
     * @param queryVector its embedding, used for the vector pipeline
     * @param settings    limits, weights and the fusion strategy for this call
     */
    SearchOutcome search(String query, float[] queryVector, RetrievalProperties settings);
}
