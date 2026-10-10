package com.demo.agentfw.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion in Java: {@code score(d) = sum over lists of weight / (k + rank(d))}.
 * Only the position in each list matters, so BM25 scores and cosine scores need no normalization.
 * This is the same formula {@code $rankFusion} applies (verified on Day 1: d1 = 2/61 when ranked
 * first in both lists with k = 60), and the fallback when the Atlas stage is unavailable.
 */
public final class JavaRrfFusion {

    public record Scored(String id, double score) {
    }

    private JavaRrfFusion() {
    }

    /**
     * @param rankedLists ranked ids (best first) per pipeline name
     * @param weights     weight per pipeline name (missing means 1.0)
     * @return ids by descending fused score; ties broken by id so the result is deterministic
     */
    public static List<Scored> fuse(Map<String, List<String>> rankedLists, Map<String, Double> weights, int k) {
        Map<String, Double> score = new HashMap<>();
        rankedLists.forEach((pipeline, ids) -> {
            double weight = weights.getOrDefault(pipeline, 1.0);
            for (int rank = 0; rank < ids.size(); rank++) {
                score.merge(ids.get(rank), weight / (k + rank + 1), Double::sum);
            }
        });
        List<Scored> out = new ArrayList<>(score.size());
        score.forEach((id, s) -> out.add(new Scored(id, s)));
        out.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::id));
        return out;
    }
}
