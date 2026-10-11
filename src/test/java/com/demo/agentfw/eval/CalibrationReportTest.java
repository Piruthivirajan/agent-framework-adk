package com.demo.agentfw.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationReportTest {

    private static final CalibrationReport.Context CTX =
            new CalibrationReport.Context("2026-10-10T00:00:00Z", "emb", "rr", "RANK", 20, 5, -6.5, 3.3);

    private static QuestionOutcome in(String id, String split, double d20, double d12, Integer expectedRank,
                                      boolean top5d20, boolean top5d12) {
        return new QuestionOutcome(id, "IN_CORPUS", split, "question " + id, d20, "doc p1", d12, expectedRank,
                top5d20, top5d12, 0, 100, 200, false, expectedRank, top5d20, top5d12, List.of());
    }

    private static QuestionOutcome out(String id, String type, String split, double d20) {
        return new QuestionOutcome(id, type, split, "question " + id, d20, "doc p9", d20, null, false, false, 0, 100, 200, false, null, false, false, List.of());
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
        assertThat(md).contains("| calibration | 3 | 3 / 3 | 2 / 2 | 3 / 3 | 2 / 2 |");
        assertThat(md).contains("| holdout | 1 | 1 / 1 | 1 / 1 | 1 / 1 | 1 / 1 |");
        assertThat(md).contains("| all | 4 | 4 / 4 | 3 / 3 | 4 / 4 | 3 / 3 |");
    }

    @Test
    void comparisonShowsBothRunsSideBySideWithAVerdict() {
        List<QuestionOutcome> baseline = sample();
        List<QuestionOutcome> variant = sample().stream()
                .map(q -> q.id().equals("in-3")
                        ? in("in-3", "calibration", 6.0, 6.0, 4, true, true)      // answer page moves from fused rank 15 to 4
                        : q).toList();
        // make the baseline miss in-3 in the final top 5, so the variant is a visible improvement
        baseline = baseline.stream()
                .map(q -> q.id().equals("in-3") ? in("in-3", "calibration", 4.0, 4.0, 15, false, false) : q).toList();

        String md = CalibrationReport.renderComparison(
                new CalibrationRun("baseline", "headers kept", CTX, baseline),
                new CalibrationRun("stripped", "headers removed", CTX, variant));

        assertThat(md).contains("# Grounding threshold calibration: baseline vs stripped");
        assertThat(md).contains("| What differs | headers kept | headers removed |");
        assertThat(md).contains("| answer page in final top 5 (rerank 20) | 3 | 4 | +1 |");
        assertThat(md).contains("stripped finds the answer page in 1 more question(s) (3 to 4 of 4)");
        assertThat(md).contains("## Per question").contains("# Detail: baseline").contains("# Detail: stripped");
        assertThat(md).contains("| in-3 | IN_CORPUS | 4.00 | 6.00 | 15 | 4 | no | yes |");
    }

    @Test
    void comparisonSaysWhenNothingImproved() {
        String md = CalibrationReport.renderComparison(
                new CalibrationRun("a", "x", CTX, sample()), new CalibrationRun("b", "y", CTX, sample()));

        assertThat(md).contains("**Verdict on final top 5 (rerank 20):** no change (4 of 4)");
    }

    @Test
    void aSavedRunSurvivesAJsonRoundTrip() {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        CalibrationRun run = new CalibrationRun("baseline", "note", CTX, sample());

        CalibrationRun back = mapper.readValue(mapper.writeValueAsString(run), CalibrationRun.class);

        assertThat(back).isEqualTo(run);
    }

    @Test
    void paraphrasedQuestionsGetTheirOwnRecallRow() {
        List<QuestionOutcome> o = new ArrayList<>(sample());
        o.add(new QuestionOutcome("in-p", "IN_CORPUS", "calibration", "paraphrased", 3.0, "doc p1", 3.0, 7,
                true, true, 0, 100, 200, true, 7, true, true, List.of()));

        String md = CalibrationReport.render(o, CTX);

        assertThat(md).contains("| paraphrased (semantic path) | 1 | 1 / 1 | 1 / 1 | 1 / 1 | 1 / 1 |");
    }

    @Test
    void strictAndWidenedAreReportedSideBySide() {
        List<QuestionOutcome> o = new ArrayList<>();
        // missed strictly, found once adjacent/equivalent pages are accepted
        o.add(new QuestionOutcome("in-w", "IN_CORPUS", "calibration", "q", 5.0, "doc p2", 5.0, null, false, false,
                0, 100, 200, false, 3, true, true, List.of("doc p2")));
        o.add(in("in-s", "calibration", 6.0, 6.0, 1, true, true));
        o.add(out("far-1", "OUT_FAR", "calibration", -9.0));

        String md = CalibrationReport.render(o, CTX);

        assertThat(md).contains("| all | 2 | 1 / 2 | 1 / 2 | 1 / 2 | 1 / 2 |");
        assertThat(md).contains("Each cell reads strict / widened");
    }

    @Test
    void floorTablePerTypeShowsWhatIsRejectedAndWhatIsHighConfidence() {
        List<QuestionOutcome> o = new ArrayList<>();
        o.add(in("in-1", "calibration", 9.0, 9.0, 1, true, true));    // passes, high confidence (>= 3.3)
        o.add(in("in-2", "calibration", -2.0, -2.0, 2, true, true));  // passes, not high confidence
        o.add(out("far-1", "OUT_FAR", "calibration", -9.0));          // below the floor of -6.5
        o.add(out("far-2", "OUT_FAR", "calibration", -7.0));          // below the floor
        o.add(out("aws-1", "OUT_AWS", "calibration", 1.0));           // passes the floor, handed on

        String md = CalibrationReport.render(o, CTX);

        assertThat(md).contains("Floor **-6.50**").contains("High-confidence marker **3.30**");
        assertThat(md).contains("| IN_CORPUS | 2 | 0 | 2 | 1 |");
        assertThat(md).contains("| OUT_FAR | 2 | 2 | 0 | 0 |");
        assertThat(md).contains("| OUT_AWS | 1 | 0 | 1 | 0 |");
    }

    @Test
    void aRunWithoutAFloorSaysSo() {
        CalibrationReport.Context none = new CalibrationReport.Context("t", "e", "r", "RANK", 20, 5, null, null);

        assertThat(CalibrationReport.render(sample(), none)).contains("No floor was configured for this run.");
    }

    @Test
    void extraRunsAreAppendedAsDetail() {
        CalibrationRun a = new CalibrationRun("baseline", "one", CTX, sample());
        CalibrationRun b = new CalibrationRun("stripped", "two", CTX, sample());
        CalibrationRun c = new CalibrationRun("final", "headers kept, floor set", CTX, sample());

        String md = CalibrationReport.renderComparison(a, b, "## Findings\n\ntext", List.of(c));

        assertThat(md).contains("# Detail: baseline").contains("# Detail: stripped").contains("# Detail: final");
        assertThat(md).contains("headers kept, floor set");
        assertThat(md.indexOf("# Detail: final")).isGreaterThan(md.indexOf("# Detail: stripped"));
        assertThat(md.indexOf("## Findings")).isLessThan(md.indexOf("## Side by side"));
    }
}
