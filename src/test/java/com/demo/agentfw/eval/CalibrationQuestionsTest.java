package com.demo.agentfw.eval;

import com.demo.agentfw.retrieval.FusionStrategy;
import com.demo.agentfw.retrieval.RetrievalProperties;
import com.demo.agentfw.retrieval.RetrievalTrace;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the committed question file and the way outcomes are derived from a trace. */
class CalibrationQuestionsTest {

    @Test
    void theCommittedQuestionFileIsComplete() {
        List<CalibrationQuestion> all = CalibrationQuestions.load();

        assertThat(all).hasSize(36);
        assertThat(all.stream().filter(q -> q.type().equals("IN_CORPUS"))).hasSize(24);
        assertThat(all.stream().filter(q -> q.type().equals("OUT_FAR"))).hasSize(6);
        assertThat(all.stream().filter(q -> q.type().equals("OUT_NEAR"))).hasSize(6);

        Set<String> ids = new HashSet<>();
        for (CalibrationQuestion q : all) {
            assertThat(ids.add(q.id())).as("duplicate id " + q.id()).isTrue();
            assertThat(q.question()).isNotBlank();
            assertThat(q.split()).isIn("calibration", "holdout");
            if (q.inCorpus()) {
                assertThat(q.expected()).as(q.id() + " expected pages").isNotEmpty();
                assertThat(q.expected().stream().flatMap(e -> e.pages().stream())).isNotEmpty();
            } else {
                assertThat(q.expected() == null || q.expected().isEmpty()).as(q.id()).isTrue();
            }
        }
        assertThat(all.stream().filter(CalibrationQuestion::holdout).count()).isEqualTo(8);
    }

    @Test
    void everyTypeHasAHoldoutSoTheThresholdCanBeChecked() {
        List<CalibrationQuestion> all = CalibrationQuestions.load();
        for (String type : List.of("IN_CORPUS", "OUT_FAR", "OUT_NEAR")) {
            assertThat(all.stream().anyMatch(q -> q.type().equals(type) && q.holdout())).as(type).isTrue();
            assertThat(all.stream().anyMatch(q -> q.type().equals(type) && !q.holdout())).as(type).isTrue();
        }
    }

    @Test
    void outcomeIsDerivedFromTheTraceForBothDepths() {
        CalibrationQuestion q = new CalibrationQuestion("in-x", "IN_CORPUS", "question", "calibration",
                List.of(new CalibrationQuestion.Expected("wellarchitected-security-pillar.pdf", List.of(47))));

        // 14 scored passages; the answer page (file/page 47) was fused rank 14 and reranked first
        List<RetrievalTrace.FusedCandidate> fused = new java.util.ArrayList<>();
        List<RetrievalTrace.ScoredPassage> scored = new java.util.ArrayList<>();
        for (int rank = 1; rank <= 14; rank++) {
            boolean answer = rank == 14;
            String file = answer ? "wellarchitected-security-pillar.pdf" : "other.pdf";
            int page = answer ? 47 : rank;
            fused.add(new RetrievalTrace.FusedCandidate(rank, file, page, 0, "s", 0.01, rank, 0.5, null, null));
            scored.add(new RetrievalTrace.ScoredPassage(answer ? 1 : rank + 1, rank, file, page, 0, "s",
                    answer ? 9.0 : 2.0 - rank * 0.1));
        }
        scored.sort(java.util.Comparator.comparingInt(RetrievalTrace.ScoredPassage::rerankRank));
        RetrievalTrace trace = new RetrievalTrace("question",
                new RetrievalTrace.Config("RANK", 400, 20, 20, 20, 5, 1, 1, "sigmoid", null, "e", 768, "r"),
                1, new RetrievalTrace.Search("RANK", 20, 5, 25, Map.of(), fused),
                new RetrievalTrace.Rerank("r", 14, 3, 50, scored, 5),
                new RetrievalTrace.Grounding(9.0, null, null, com.demo.agentfw.retrieval.GroundingDecision.NOT_CALIBRATED),
                60);
        RetrievalProperties s = new RetrievalProperties(FusionStrategy.RANK, 400, 20, 20, 20, 5, 1, 1, "sigmoid", 60, null);

        QuestionOutcome o = CalibrationRunner.outcome(q, trace, s);

        assertThat(o.expectedFusedRank()).isEqualTo(14);
        assertThat(o.inTop5Depth20()).isTrue();     // reranked to first place
        assertThat(o.inTop5Depth12()).isFalse();    // fused rank 14 is outside the top 12
        assertThat(o.top1Depth20()).isEqualTo(9.0);
        assertThat(o.top1Depth12()).isLessThan(9.0);
        assertThat(o.truncatedPairs()).isEqualTo(3);
        assertThat(o.top1Where()).isEqualTo("wellarchitected-security-pillar".replace("wellarchitected-", "wa-") + " p47");
    }
}
