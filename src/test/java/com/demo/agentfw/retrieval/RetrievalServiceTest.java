package com.demo.agentfw.retrieval;

import com.demo.agentfw.ingestion.FakeEmbeddingService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** No MongoDB, no model, no API: stubbed search, scripted reranker, fake embeddings. */
class RetrievalServiceTest {

    private static final FakeEmbeddingService EMBEDDINGS = new FakeEmbeddingService(8, "fake-embed");

    private static RetrievalProperties settings(int fused, int depth, int topN, Double threshold) {
        return new RetrievalProperties(FusionStrategy.RANK, 400, 20, fused, depth, topN, 1.0, 1.0, "sigmoid", 60,
                threshold);
    }

    /** n candidates, fused rank 1..n, page = rank, text mentions its rank. */
    private static List<Candidate> candidates(int n) {
        List<Candidate> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new Candidate("id" + i, "doc", "file.pdf", i, i, "Passage number " + i + " about topic " + i,
                    i <= 15 ? i : null, i <= 15 ? 1.0 / i : null, i % 2 == 0 ? i / 2 : null,
                    i % 2 == 0 ? 5.0 / i : null, 1.0 / (60 + i), i));
        }
        return out;
    }

    private static ChunkSearch searchReturning(List<Candidate> candidates, AtomicReference<RetrievalProperties> seen) {
        return (query, vector, s) -> {
            if (seen != null) {
                seen.set(s);
            }
            Map<String, Long> ms = new LinkedHashMap<>();
            ms.put("search_and_fusion", 7L);
            return new SearchOutcome(s.fusion(), candidates, 20, 9, 26, ms);
        };
    }

    /** Scores passage i (0-based, in call order) with the given function; records how many it was given. */
    private static Reranker rerankerScoring(java.util.function.IntToDoubleFunction score, List<Integer> calls, int truncated) {
        return new Reranker() {
            @Override
            public Result rerank(String query, List<String> passages) {
                calls.add(passages.size());
                float[] s = new float[passages.size()];
                for (int i = 0; i < s.length; i++) {
                    s[i] = (float) score.applyAsDouble(i);
                }
                return new Result(s, truncated);
            }

            @Override
            public String modelName() {
                return "scripted";
            }
        };
    }

    @Test
    void returnsTheBestFiveByRerankScoreNotByFusedRank() {
        // candidate 7 (index 6) is the best, then 3, then 12
        List<Integer> calls = new ArrayList<>();
        Reranker rr = rerankerScoring(i -> i == 6 ? 9 : i == 2 ? 7 : i == 11 ? 5 : -i, calls, 0);
        RetrievalService svc = new RetrievalService(EMBEDDINGS, searchReturning(candidates(20), null), rr,
                settings(20, 20, 5, 2.0));

        RetrievalResult r = svc.retrieve("what is topic seven?");

        assertThat(r.decision()).isEqualTo(GroundingDecision.OK);
        assertThat(r.chunks()).extracting(RetrievedChunk::page).containsExactly(7, 3, 12, 1, 2);
        assertThat(r.chunks().get(0).rerankScore()).isEqualTo(9.0);
        assertThat(calls).containsExactly(20);
    }

    @Test
    void belowThresholdMeansNoContextButTheTraceKeepsTheScores() {
        Reranker rr = rerankerScoring(i -> -5.0 - i, new ArrayList<>(), 0);
        RetrievalService svc = new RetrievalService(EMBEDDINGS, searchReturning(candidates(20), null), rr,
                settings(20, 20, 5, 0.0));

        RetrievalResult r = svc.retrieve("unrelated question");

        assertThat(r.decision()).isEqualTo(GroundingDecision.NO_RELEVANT_CONTEXT);
        assertThat(r.chunks()).isEmpty();
        assertThat(r.trace().rerank().scored()).hasSize(20);
        assertThat(r.trace().grounding().topScore()).isEqualTo(-5.0);
        assertThat(r.trace().grounding().margin()).isEqualTo(-5.0);
    }

    @Test
    void withoutAThresholdTheChunksAreReturnedButNoDecisionIsMade() {
        Reranker rr = rerankerScoring(i -> 1.0 - i, new ArrayList<>(), 0);
        RetrievalService svc = new RetrievalService(EMBEDDINGS, searchReturning(candidates(20), null), rr,
                settings(20, 20, 5, null));

        RetrievalResult r = svc.retrieve("anything");

        assertThat(r.decision()).isEqualTo(GroundingDecision.NOT_CALIBRATED);
        assertThat(r.chunks()).hasSize(5);
        assertThat(r.trace().grounding().margin()).isNull();
    }

    @Test
    void rerankDepthLimitsHowManyCandidatesAreScored() {
        List<Integer> calls = new ArrayList<>();
        Reranker rr = rerankerScoring(i -> 10 - i, calls, 0);
        RetrievalService svc = new RetrievalService(EMBEDDINGS, searchReturning(candidates(20), null), rr,
                settings(20, 12, 5, 0.0));

        RetrievalResult r = svc.retrieve("q");

        assertThat(calls).containsExactly(12);
        assertThat(r.trace().rerank().pairsScored()).isEqualTo(12);
        assertThat(r.trace().rerank().scored()).extracting(RetrievalTrace.ScoredPassage::fusedRank)
                .allMatch(rank -> rank <= 12);
        assertThat(r.trace().search().fusedTop()).hasSize(20);   // fusion output is traced in full
    }

    @Test
    void traceRecordsEveryStageForThePanel() {
        Reranker rr = rerankerScoring(i -> 3.0 - i, new ArrayList<>(), 2);
        RetrievalService svc = new RetrievalService(EMBEDDINGS, searchReturning(candidates(20), null), rr,
                settings(20, 20, 5, 1.0));

        RetrievalTrace t = svc.retrieve("how are keys managed?").trace();

        assertThat(t.query()).isEqualTo("how are keys managed?");
        assertThat(t.config().fusion()).isEqualTo("RANK");
        assertThat(t.config().numCandidates()).isEqualTo(400);
        assertThat(t.config().embeddingModel()).isEqualTo("fake-embed");
        assertThat(t.config().rerankModel()).isEqualTo("scripted");
        assertThat(t.config().minRerankScore()).isEqualTo(1.0);

        assertThat(t.search().vectorHits()).isEqualTo(20);
        assertThat(t.search().textHits()).isEqualTo(9);
        assertThat(t.search().unionSize()).isEqualTo(26);
        RetrievalTrace.FusedCandidate second = t.search().fusedTop().get(1);
        assertThat(second.fusedRank()).isEqualTo(2);
        assertThat(second.vectorRank()).isEqualTo(2);
        assertThat(second.textRank()).isEqualTo(1);
        assertThat(second.snippet()).startsWith("Passage number 2");

        assertThat(t.rerank().truncatedPairs()).isEqualTo(2);
        assertThat(t.rerank().kept()).isEqualTo(5);
        assertThat(t.rerank().scored().get(0).rerankRank()).isEqualTo(1);
        assertThat(t.rerank().scored().get(0).fusedRank()).isEqualTo(1);   // scripted: first candidate scores best

        assertThat(t.stageMillis()).containsKeys("embed_query", "search_and_fusion", "rerank");
        assertThat(t.totalMillis()).isGreaterThanOrEqualTo(0);
        assertThat(t.grounding().decision()).isEqualTo(GroundingDecision.OK);
        assertThat(t.grounding().margin()).isEqualTo(2.0);
    }

    @Test
    void snippetsAreShortAndTheTraceSerializesToJson() {
        String longText = "word ".repeat(200);
        List<Candidate> one = List.of(new Candidate("id", "doc", "f.pdf", 3, 0, longText, 1, 0.9, null, null, 0.03, 1));
        RetrievalService svc = new RetrievalService(EMBEDDINGS, searchReturning(one, null),
                rerankerScoring(i -> 4.0, new ArrayList<>(), 0), settings(20, 20, 5, 0.0));

        RetrievalTrace t = svc.retrieve("q").trace();

        assertThat(t.search().fusedTop().get(0).snippet()).hasSizeLessThanOrEqualTo(161);
        String json = JsonMapper.builder().build().writeValueAsString(t);
        assertThat(json).contains("\"fusedTop\"").contains("\"rerank\"").contains("\"grounding\"");
        assertThat(json).doesNotContain(longText.strip());   // full passages never travel in the trace
    }

    @Test
    void noCandidatesSkipsTheRerankerAndSaysNoContext() {
        List<Integer> calls = new ArrayList<>();
        RetrievalService svc = new RetrievalService(EMBEDDINGS, searchReturning(List.of(), null),
                rerankerScoring(i -> 1.0, calls, 0), settings(20, 20, 5, 0.0));

        RetrievalResult r = svc.retrieve("q");

        assertThat(calls).isEmpty();
        assertThat(r.decision()).isEqualTo(GroundingDecision.NO_RELEVANT_CONTEXT);
        assertThat(r.chunks()).isEmpty();
        assertThat(r.trace().rerank().scored()).isEmpty();
    }

    @Test
    void perCallSettingsOverrideTheDefaults() {
        AtomicReference<RetrievalProperties> seen = new AtomicReference<>();
        RetrievalService svc = new RetrievalService(EMBEDDINGS, searchReturning(candidates(20), seen),
                rerankerScoring(i -> 1.0, new ArrayList<>(), 0), settings(20, 20, 5, 0.0));

        svc.retrieve("q", settings(20, 20, 5, 0.0).withFusion(FusionStrategy.JAVA_RRF).withRerankDepth(12));

        assertThat(seen.get().fusion()).isEqualTo(FusionStrategy.JAVA_RRF);
        assertThat(seen.get().rerankDepth()).isEqualTo(12);
    }

    @Test
    void retrievalSettingsRejectInconsistentFunnels() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings(20, 25, 5, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings(20, 3, 5, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
