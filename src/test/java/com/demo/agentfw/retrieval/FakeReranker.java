package com.demo.agentfw.retrieval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic stand-in for the cross-encoder, used by every test that does not load the real
 * model. The score is a logit-like number: it grows with the number of query words found in the
 * passage and is negative when none are.
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.rerank", name = "provider", havingValue = "fake")
public class FakeReranker implements Reranker {

    @Override
    public Result rerank(String query, List<String> passages) {
        Set<String> queryWords = words(query);
        float[] scores = new float[passages.size()];
        for (int i = 0; i < passages.size(); i++) {
            Set<String> passageWords = words(passages.get(i));
            int overlap = 0;
            for (String w : queryWords) {
                if (passageWords.contains(w)) {
                    overlap++;
                }
            }
            scores[i] = overlap * 3f - 6f;
        }
        return new Result(scores, 0);
    }

    @Override
    public String modelName() {
        return "fake-reranker";
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() > 2) {
                out.add(w);
            }
        }
        return out;
    }
}
