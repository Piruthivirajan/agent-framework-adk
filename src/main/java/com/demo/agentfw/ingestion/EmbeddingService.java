package com.demo.agentfw.ingestion;

import java.util.List;

/**
 * Turns text into vectors. Documents and queries are embedded differently (the model expects a
 * different prefix for each), so they are separate methods and callers never build prefixes.
 */
public interface EmbeddingService {

    /** Embeds document chunks; returns one vector per text, in the same order. */
    List<float[]> embedDocuments(String title, List<String> texts);

    /** Embeds a search query. */
    float[] embedQuery(String query);

    /** Model name, stored on every chunk so a model change triggers re-embedding. */
    String modelName();

    /** Vector size; must equal the vector index {@code numDimensions}. */
    int dimensions();
}
