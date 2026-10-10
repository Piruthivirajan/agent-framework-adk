package com.demo.agentfw.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every number of the retrieval funnel, so a walkthrough can show candidates (400) against the
 * final context (5).
 *
 * @param fusion           default strategy; callers may override per request (the eval does)
 * @param numCandidates    ANN candidates examined by {@code $vectorSearch}; MongoDB suggests at least 20x the limit
 * @param perPipelineLimit results taken from each of the two searches
 * @param fusedLimit       candidates kept after fusion (the funnel's "20")
 * @param rerankDepth      how many of the fused candidates the cross-encoder scores (20, or 12 if recall holds)
 * @param finalTopN        chunks returned to the answering model
 * @param vectorWeight     weight of the vector pipeline in fusion
 * @param textWeight       weight of the full-text pipeline in fusion
 * @param normalization    {@code $scoreFusion} normalization: {@code sigmoid}, {@code minMaxScaler} or {@code none}
 * @param rrfK             constant of reciprocal rank fusion (60 is the usual value)
 * @param minRerankScore   grounding threshold on the top rerank score; null means "not calibrated yet"
 */
@ConfigurationProperties("agentfw.retrieval")
public record RetrievalProperties(
        @DefaultValue("RANK") FusionStrategy fusion,
        @DefaultValue("400") int numCandidates,
        @DefaultValue("20") int perPipelineLimit,
        @DefaultValue("20") int fusedLimit,
        @DefaultValue("20") int rerankDepth,
        @DefaultValue("5") int finalTopN,
        @DefaultValue("1.0") double vectorWeight,
        @DefaultValue("1.0") double textWeight,
        @DefaultValue("sigmoid") String normalization,
        @DefaultValue("60") int rrfK,
        Double minRerankScore) {

    public RetrievalProperties {
        if (perPipelineLimit < 1 || fusedLimit < 1 || rerankDepth < 1 || finalTopN < 1) {
            throw new IllegalArgumentException("retrieval limits must be positive");
        }
        if (numCandidates < perPipelineLimit) {
            throw new IllegalArgumentException("numCandidates must be at least the per-pipeline limit");
        }
        if (rerankDepth > fusedLimit) {
            throw new IllegalArgumentException("rerankDepth cannot exceed fusedLimit");
        }
        if (finalTopN > rerankDepth) {
            throw new IllegalArgumentException("finalTopN cannot exceed rerankDepth");
        }
    }

    /** Same settings with another rerank depth; used by the depth experiment. */
    public RetrievalProperties withRerankDepth(int depth) {
        return new RetrievalProperties(fusion, numCandidates, perPipelineLimit, fusedLimit, depth, finalTopN,
                vectorWeight, textWeight, normalization, rrfK, minRerankScore);
    }

    public RetrievalProperties withFusion(FusionStrategy strategy) {
        return new RetrievalProperties(strategy, numCandidates, perPipelineLimit, fusedLimit, rerankDepth, finalTopN,
                vectorWeight, textWeight, normalization, rrfK, minRerankScore);
    }
}
