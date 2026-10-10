package com.demo.agentfw.retrieval;

import java.util.List;

/**
 * Scores query-passage pairs. A higher score means more relevant. The scale belongs to the model
 * (the MS MARCO cross-encoder returns raw logits, not 0 to 1), so the grounding threshold is
 * calibrated for one specific model and must be recalibrated when the model changes.
 */
public interface Reranker {

    /**
     * @param scores         one score per passage, in the input order
     * @param truncatedPairs how many pairs exceeded the model's token limit and were cut
     */
    record Result(float[] scores, int truncatedPairs) {
    }

    Result rerank(String query, List<String> passages);

    /** Identifies model and variant in traces and calibration reports. */
    String modelName();
}
