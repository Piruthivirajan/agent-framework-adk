package com.demo.agentfw.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the retrieval pipeline did for one question, as plain data for the trace panel and
 * for the debugging question "a user got no answer, why?". It carries scores, ranks, counts and
 * 160-character snippets; full passages travel only in {@link RetrievedChunk}, and none of it goes
 * into the model's context.
 */
public record RetrievalTrace(
        String query,
        Config config,
        long embedMillis,
        Search search,
        Rerank rerank,
        Grounding grounding,
        long totalMillis) {

    /** The settings in force for this call, so a trace is interpretable on its own. */
    public record Config(
            String fusion, int numCandidates, int perPipelineLimit, int fusedLimit, int rerankDepth, int finalTopN,
            double vectorWeight, double textWeight, String normalization, Double minRerankScore,
            String embeddingModel, int dimensions, String rerankModel) {
    }

    /** One fused candidate with the evidence of how it got there. */
    public record FusedCandidate(
            int fusedRank, String file, int page, int chunkIndex, String snippet, double fusedScore,
            Integer vectorRank, Double vectorScore, Integer textRank, Double textScore) {
    }

    /**
     * @param vectorHits chunks returned by the vector pipeline
     * @param textHits   chunks returned by the BM25 pipeline
     * @param unionSize  distinct chunks across both, before cutting to the fused limit
     * @param fusedTop   the fused candidates that entered the rerank stage's pool (top {@code fusedLimit})
     */
    public record Search(
            String strategy, int vectorHits, int textHits, int unionSize,
            Map<String, Long> stageMillis, List<FusedCandidate> fusedTop) {
    }

    /** One pair scored by the cross-encoder. {@code rerankRank} is 1 for the best. */
    public record ScoredPassage(
            int rerankRank, int fusedRank, String file, int page, int chunkIndex, String snippet, double rerankScore) {
    }

    /**
     * @param pairsScored    candidates actually scored (the rerank depth, or fewer)
     * @param truncatedPairs pairs that exceeded the model's token limit and were cut
     * @param scored         every scored pair, best first
     * @param kept           how many of them were returned as context
     */
    public record Rerank(
            String model, int pairsScored, int truncatedPairs, long millis, List<ScoredPassage> scored, int kept) {
    }

    /** {@code margin} is topScore - threshold; null when there is no score or no threshold. */
    public record Grounding(Double topScore, Double threshold, Double margin, GroundingDecision decision) {
    }

    /** Latency per stage in pipeline order, for the panel's timeline. */
    public Map<String, Long> stageMillis() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("embed_query", embedMillis);
        out.putAll(search.stageMillis());
        out.put("rerank", rerank.millis());
        return out;
    }
}
