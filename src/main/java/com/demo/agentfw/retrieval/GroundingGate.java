package com.demo.agentfw.retrieval;

/**
 * Decides whether retrieval found anything worth answering from. The test is on the best
 * cross-encoder score only; the vector similarity is not used, because nearest-neighbour search
 * always returns its nearest chunks and their cosine scores are compressed (an unrelated question
 * still scored 0.76 against 0.89 for a real match in the Day 2 smoke test).
 */
public final class GroundingGate {

    private GroundingGate() {
    }

    /**
     * @param topScore  best rerank score, or null when there were no candidates at all
     * @param threshold calibrated minimum, or null when not calibrated
     */
    public static GroundingDecision decide(Double topScore, Double threshold) {
        if (threshold == null) {
            return GroundingDecision.NOT_CALIBRATED;
        }
        if (topScore == null || topScore < threshold) {
            return GroundingDecision.NO_RELEVANT_CONTEXT;
        }
        return GroundingDecision.OK;
    }
}
