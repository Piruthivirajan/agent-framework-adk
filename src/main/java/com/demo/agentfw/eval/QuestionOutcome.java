package com.demo.agentfw.eval;

import java.util.List;

/**
 * What retrieval did for one question, with the full 20 candidates scored once. The "depth 12"
 * figures are derived from the same scores (a pair's score does not depend on the other pairs), so
 * both depths are compared on identical numbers.
 *
 * <p>"Strict" matches only pages that contain the question's evidence phrase (fixed before the first
 * run); "widened" also accepts the pages added later for adjacent, near-duplicate or equivalent
 * answers. Both are recorded so neither can replace the other silently.
 *
 * @param top1Depth20        best rerank score over the fused top 20
 * @param top1Where          "file p123" of that best passage
 * @param top1Depth12        best rerank score over the fused top 12
 * @param expectedFusedRank  position of the first strictly expected page in the fused list; null if absent or n/a
 * @param inTop5Depth20      a strictly expected page is in the final 5 when reranking 20
 * @param inTop5Depth12      the same when reranking only the fused top 12
 * @param paraphrase         the question has little keyword overlap with its page (tests the semantic path)
 * @param expectedFusedRankWidened  as {@code expectedFusedRank}, accepting widened pages too
 * @param inTop5Depth20Widened      as {@code inTop5Depth20}, accepting widened pages too
 * @param inTop5Depth12Widened      as {@code inTop5Depth12}, accepting widened pages too
 * @param top5Pages          the final 5 at depth 12 as "file p123", best first (what the model would be given)
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
        long totalMillis,
        boolean paraphrase,
        Integer expectedFusedRankWidened,
        boolean inTop5Depth20Widened,
        boolean inTop5Depth12Widened,
        List<String> top5Pages) {

    public boolean inCorpus() {
        return "IN_CORPUS".equals(type);
    }

    public boolean holdout() {
        return "holdout".equals(split);
    }
}
