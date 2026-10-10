package com.demo.agentfw.eval;

/**
 * What retrieval did for one question, with the full 20 candidates scored once. The "depth 12"
 * figures are derived from the same scores (a pair's score does not depend on the other pairs), so
 * both depths are compared on identical numbers.
 *
 * @param top1Depth20        best rerank score over the fused top 20
 * @param top1Where          "file p123" of that best passage
 * @param top1Depth12        best rerank score over the fused top 12
 * @param expectedFusedRank  position of the first answer-bearing page in the fused list; null if absent or not applicable
 * @param inTop5Depth20      an answer-bearing page is in the final 5 when reranking 20
 * @param inTop5Depth12      the same when reranking only the fused top 12
 */
public record QuestionOutcome(
        String id,
        String type,
        String split,
        String question,
        double top1Depth20,
        String top1Where,
        double top1Depth12,
        Integer expectedFusedRank,
        boolean inTop5Depth20,
        boolean inTop5Depth12,
        int truncatedPairs,
        long rerankMillis,
        long totalMillis) {

    public boolean inCorpus() {
        return "IN_CORPUS".equals(type);
    }

    public boolean holdout() {
        return "holdout".equals(split);
    }
}
