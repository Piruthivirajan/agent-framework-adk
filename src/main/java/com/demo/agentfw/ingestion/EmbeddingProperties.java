package com.demo.agentfw.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding settings. {@code dimensions} is the single source of truth: it is sent to the model as
 * {@code outputDimensionality} and used as {@code numDimensions} of the Atlas vector index, which
 * must be equal.
 */
@ConfigurationProperties("agentfw.embedding")
public record EmbeddingProperties(
        @DefaultValue("gemini") String provider,
        @DefaultValue("gemini-embedding-2") String model,
        @DefaultValue("768") int dimensions,
        @DefaultValue("32") int batchSize,
        @DefaultValue("5") int maxRetries,
        @DefaultValue("") String apiKey) {

    @Override
    public String toString() {   // never print the key
        return "EmbeddingProperties[provider=" + provider + ", model=" + model + ", dimensions=" + dimensions
                + ", batchSize=" + batchSize + ", maxRetries=" + maxRetries + ", apiKey=***]";
    }
}
