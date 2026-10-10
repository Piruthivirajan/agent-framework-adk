package com.demo.agentfw.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationReportTest {

    private static final CalibrationReport.Context CTX =
            new CalibrationReport.Context("2026-10-10T00:00:00Z", "emb", "rr", "RANK", 20, 5);

    private static QuestionOutcome in(String id, String split, double d20, double d12, Integer expectedRank,
                                      boolean top5d20, boolean top5d12) {
        return new QuestionOutcome(id, "IN_CORPUS", split, "question " + id, d20, "doc p1", d12, expectedRank,
                top5d20, top5d12, 0, 100, 200);
    }

    private static QuestionOutcome out(String id, String type, String split, double d20) {
        return new QuestionOutcome(id, type, split, "question " + id, d20, "doc p9", d20, null, false, false, 0, 100, 200);
    }

    private static List<QuestionOutcome> sample() {
        List<QuestionOutcome> o = new ArrayList<>();
        o.add(in("in-1", "calibration", 8.0, 8.0, 1, true, true));
        o.add(in("in-2", "calibration", 5.0, 5.0, 3, true, true));
        o.add(in("in-3", "calibration", 4.0, 4.0, 15, true, false));   // answer page sits at fused rank 15
        o.add(in("in-4", "holdout", 6.0, 6.0, 2, true, true));
        o.add(out("far-1", "OUT_FAR", "calibration", -9.0));
        o.add(out("near-1", "OUT_NEAR", "calibration", -2.0));
        o.add(out("near-2", "OUT_NEAR", "holdout", 4.5));              // a confusable near-domain question
        return o;
    }

    @Test
    void reportsEveryQuestionAndTheSections() {
        String md = CalibrationReport.render(sample(), CTX);

        assertThat(md).contains("# Grounding threshold calibration");
        assertThat(md).contains("## Every question", "## Recall of the answer-bearing page",
                "## Rerank depth: 12 or 20", "## Threshold at depth 20", "## Threshold at depth 12",
                "## Out-of-corpus questions by score");
        for (String id : List.of("in-1", "in-2", "in-3", "in-4", "far-1", "near-1", "near-2")) {
            assertThat(md).contains("| " + id + " |");
        }
        assertThat(md).contains("| 15 |");   // in-3: answer page at fused rank 15
    }

    @Test
    void depthDecisionNamesTheQuestionThatLosesRecallAtTwelve() {
        String md = CalibrationReport.render(sample(), CTX);

        assertThat(md).contains("lose the answer page from the final top 5 when only 12 candidates are reranked: in-3");
        assertThat(md).contains("Recall does not hold at depth 12");
    }

    @Test
    void depthTwelveIsSupportedWhenNothingIsLost() {
        List<QuestionOutcome> o = sample().stream()
                .map(q -> q.id().equals("in-3") ? in("in-3", "calibration", 4.0, 4.0, 5, true, true) : q).toList();

        assertThat(CalibrationReport.render(o, CTX)).contains("Recall holds at depth 12");
    }

    @Test
    void thresholdIsChosenFromCalibrationOnlyAndCheckedOnHoldout() {
        String md = CalibrationReport.render(sample(), CTX);

        // calibration: in-corpus 4.0, 5.0, 8.0 ; out-of-corpus -9.0, -2.0 -> separable, midpoint of -2.0 and 4.0 = 1.0
        assertThat(md).contains("lowest in-corpus top-1 = **4.00**, highest out-of-corpus top-1 = **-2.00**");
        assertThat(md).contains("Chosen threshold: **1.00**");
        // holdout: in-4 (6.0 accepted, correct), near-2 (4.5 accepted, wrong)
        assertThat(md).contains("1 of 2 held-out questions classified correctly");
        assertThat(md).contains("**near-2** (OUT_NEAR) scored 4.50 and was wrongly accepted");
    }

    @Test
    void recallTableCountsPerSplit() {
        String md = CalibrationReport.render(sample(), CTX);

        // calibration in-corpus: 3 questions; in fused top 20: 3; in fused top 12: 2; top5 d20: 3; top5 d12: 2
        assertThat(md).contains("| calibration | 3 | 3 | 2 | 3 | 2 |");
        assertThat(md).contains("| holdout | 1 | 1 | 1 | 1 | 1 |");
        assertThat(md).contains("| all | 4 | 4 | 3 | 4 | 3 |");
    }
}
