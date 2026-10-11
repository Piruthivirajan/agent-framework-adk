package com.demo.agentfw.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Renders the calibration as Markdown: every score, the recall numbers for both rerank depths,
 * the threshold with the table it was picked from, the held-out check, and a side-by-side
 * comparison of two runs. A pure function of the outcomes, so the numbers in docs/CALIBRATION.md
 * can be reproduced and unit-tested.
 */
public final class CalibrationReport {

    /**
     * @param minRerankScore      the retrieval floor in force when the report was made; null when none
     * @param highConfidenceScore informational level recorded in the trace; null when none
     */
    public record Context(String generatedAt, String embeddingModel, String rerankModel, String fusion,
                          int fusedLimit, int finalTopN, Double minRerankScore, Double highConfidenceScore) {
    }

    private CalibrationReport() {
    }

    // ---- single run -----------------------------------------------------------------------------

    public static String render(List<QuestionOutcome> all, Context ctx) {
        StringBuilder md = new StringBuilder();
        md.append("# Grounding threshold calibration\n\n");
        header(md, ctx);
        body(md, all, ctx);
        return md.toString();
    }

    private static void header(StringBuilder md, Context ctx) {
        md.append("Generated ").append(ctx.generatedAt()).append(" by `CalibrationRunner` on the real corpus.\n\n");
        md.append("- Embedding model: `").append(ctx.embeddingModel()).append("`\n");
        md.append("- Reranker: `").append(ctx.rerankModel()).append("`\n");
        md.append("- Fusion: `").append(ctx.fusion()).append("`, fused top ").append(ctx.fusedLimit())
                .append(", final top ").append(ctx.finalTopN()).append("\n");
        md.append("- The score is the cross-encoder's raw logit (not 0 to 1). The threshold applies to the best score.\n");
        md.append("- Each question is retrieved once with all ").append(ctx.fusedLimit())
                .append(" fused candidates scored; the \"depth 12\" columns use only the fused top 12 of those same scores.\n\n");
    }

    private static void body(StringBuilder md, List<QuestionOutcome> all, Context ctx) {
        scoresTable(md, all);
        recall(md, all);
        gateAtFloor(md, all, ctx);
        List<QuestionOutcome> calibration = all.stream().filter(o -> !o.holdout()).toList();
        List<QuestionOutcome> holdout = all.stream().filter(QuestionOutcome::holdout).toList();
        depthDecision(md, all);
        threshold(md, "depth 20", calibration, holdout, QuestionOutcome::top1Depth20);
        threshold(md, "depth 12", calibration, holdout, QuestionOutcome::top1Depth12);
        negatives(md, all);
    }

    // ---- two runs -------------------------------------------------------------------------------

    public static String renderComparison(CalibrationRun baseline, CalibrationRun variant) {
        return renderComparison(baseline, variant, null);
    }

    /**
     * @param findings hand-written interpretation (Markdown) placed first, or null; the numbers below it
     *                 are generated, the reading of them is not
     */
    public static String renderComparison(CalibrationRun baseline, CalibrationRun variant, String findings) {
        return renderComparison(baseline, variant, findings, List.of());
    }

    /**
     * @param alsoDetail further runs whose full detail (all sections, including strict and widened recall and the
     *                   retrieval floor) is appended after the two compared runs
     */
    public static String renderComparison(CalibrationRun baseline, CalibrationRun variant, String findings,
                                          List<CalibrationRun> alsoDetail) {
        Summary a = Summary.of(baseline.outcomes());
        Summary b = Summary.of(variant.outcomes());
        StringBuilder md = new StringBuilder();
        md.append("# Grounding threshold calibration: ").append(baseline.label()).append(" vs ")
                .append(variant.label()).append("\n\n");
        if (findings != null && !findings.isBlank()) {
            md.append(findings.strip()).append("\n\n");
        }
        md.append("| | ").append(baseline.label()).append(" | ").append(variant.label()).append(" |\n| --- | --- | --- |\n");
        md.append("| What differs | ").append(baseline.note()).append(" | ").append(variant.note()).append(" |\n");
        md.append("| Generated | ").append(baseline.context().generatedAt()).append(" | ")
                .append(variant.context().generatedAt()).append(" |\n");
        md.append("| Embedding / reranker | `").append(baseline.context().embeddingModel()).append("` / `")
                .append(baseline.context().rerankModel()).append("` | `").append(variant.context().embeddingModel())
                .append("` / `").append(variant.context().rerankModel()).append("` |\n\n");

        md.append("## Side by side\n\n");
        md.append("| metric | ").append(baseline.label()).append(" | ").append(variant.label()).append(" | change |\n");
        md.append("| --- | ---: | ---: | ---: |\n");
        row(md, "in-corpus questions", a.inN, b.inN);
        row(md, "answer page in fused top 20", a.fused20, b.fused20);
        row(md, "answer page in fused top 12", a.fused12, b.fused12);
        row(md, "answer page in final top 5 (rerank 20)", a.top5d20, b.top5d20);
        row(md, "answer page in final top 5 (rerank 12)", a.top5d12, b.top5d12);
        row(md, "paraphrased questions: " + a.paraN + ", in final top 5 (rerank 20)", a.paraTop5d20, b.paraTop5d20);
        rowD(md, "mean top-1 score, in-corpus (depth 20)", a.meanIn20, b.meanIn20);
        for (String type : List.of("OUT_FAR", "OUT_NEAR", "OUT_AWS")) {
            rowD(md, "highest top-1 score, " + type, a.maxOut.getOrDefault(type, Double.NaN),
                    b.maxOut.getOrDefault(type, Double.NaN));
        }
        rowD(md, "threshold chosen (depth 20)", a.sel20 == null ? Double.NaN : a.sel20.chosen().threshold(),
                b.sel20 == null ? Double.NaN : b.sel20.chosen().threshold());
        rowD(md, "balanced accuracy at that threshold (depth 20)",
                a.sel20 == null ? Double.NaN : a.sel20.chosen().balancedAccuracy(),
                b.sel20 == null ? Double.NaN : b.sel20.chosen().balancedAccuracy());
        md.append("| groups separable at depth 20 | ").append(a.sel20 != null && a.sel20.separable() ? "yes" : "no")
                .append(" | ").append(b.sel20 != null && b.sel20.separable() ? "yes" : "no").append(" | |\n");
        row(md, "held-out questions classified correctly (depth 20)", a.holdoutCorrect20, b.holdoutCorrect20);
        rowD(md, "mean rerank ms per question", a.meanRerankMs, b.meanRerankMs);
        md.append("\n");

        md.append("**Verdict on final top 5 (rerank 20):** ");
        if (b.top5d20 > a.top5d20) {
            md.append(variant.label()).append(" finds the answer page in ").append(b.top5d20 - a.top5d20)
                    .append(" more question(s) (").append(a.top5d20).append(" to ").append(b.top5d20)
                    .append(" of ").append(a.inN).append(").\n\n");
        } else if (b.top5d20 == a.top5d20) {
            md.append("no change (").append(a.top5d20).append(" of ").append(a.inN).append(").\n\n");
        } else {
            md.append(variant.label()).append(" is worse by ").append(a.top5d20 - b.top5d20)
                    .append(" question(s) (").append(a.top5d20).append(" to ").append(b.top5d20).append(").\n\n");
        }

        md.append("## Per question\n\n");
        md.append("| id | type | ").append(baseline.label()).append(" top-1 | ").append(variant.label())
                .append(" top-1 | ").append(baseline.label()).append(" fused rank | ").append(variant.label())
                .append(" fused rank | ").append(baseline.label()).append(" top 5 | ").append(variant.label())
                .append(" top 5 | question |\n| --- | --- | ---: | ---: | ---: | ---: | :---: | :---: | --- |\n");
        Map<String, QuestionOutcome> byId = new LinkedHashMap<>();
        variant.outcomes().forEach(o -> byId.put(o.id(), o));
        for (QuestionOutcome x : baseline.outcomes()) {
            QuestionOutcome y = byId.get(x.id());
            if (y == null) {
                continue;
            }
            md.append(String.format(Locale.ROOT, "| %s | %s%s | %.2f | %.2f | %s | %s | %s | %s | %s |%n",
                    x.id(), x.type(), x.paraphrase() ? " (paraphrase)" : "", x.top1Depth20(), y.top1Depth20(),
                    x.inCorpus() ? rank(x) : "-", y.inCorpus() ? rank(y) : "-",
                    x.inCorpus() ? yes(x.inTop5Depth20()) : "-", y.inCorpus() ? yes(y.inTop5Depth20()) : "-",
                    x.question()));
        }
        md.append("\n---\n\n# Detail: ").append(baseline.label()).append("\n\n");
        header(md, baseline.context());
        body(md, baseline.outcomes(), baseline.context());
        md.append("\n---\n\n# Detail: ").append(variant.label()).append("\n\n");
        header(md, variant.context());
        body(md, variant.outcomes(), variant.context());
        for (CalibrationRun extra : alsoDetail) {
            md.append("\n---\n\n# Detail: ").append(extra.label()).append("\n\n");
            if (extra.note() != null && !extra.note().isBlank()) {
                md.append(extra.note()).append("\n\n");
            }
            header(md, extra.context());
            body(md, extra.outcomes(), extra.context());
        }
        return md.toString();
    }

    // ---- sections -------------------------------------------------------------------------------

    private static void scoresTable(StringBuilder md, List<QuestionOutcome> all) {
        md.append("## Every question\n\n");
        md.append("| id | split | type | top-1 d20 | top-1 d12 | best passage | expected at fused rank | in top 5 d20 | in top 5 d12 | top 5 d12 widened | truncated | question |\n");
        md.append("| --- | --- | --- | ---: | ---: | --- | ---: | :---: | :---: | :---: | ---: | --- |\n");
        for (QuestionOutcome o : all) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s%s | %.2f | %.2f | %s | %s | %s | %s | %s | %d | %s |%n",
                    o.id(), o.split(), o.type(), o.paraphrase() ? " (paraphrase)" : "", o.top1Depth20(), o.top1Depth12(),
                    o.top1Where(), o.inCorpus() ? rank(o) : "-",
                    o.inCorpus() ? yes(o.inTop5Depth20()) : "-",
                    o.inCorpus() ? yes(o.inTop5Depth12()) : "-",
                    o.inCorpus() ? yes(o.inTop5Depth12Widened()) : "-",
                    o.truncatedPairs(), o.question()));
        }
        md.append("\n");
    }

    private static void recall(StringBuilder md, List<QuestionOutcome> all) {
        md.append("## Recall of the answer-bearing page (in-corpus questions), strict / widened\n\n");
        md.append("Strict: the page contains the question's evidence phrase (fixed before the first run). ")
                .append("Widened: also the adjacent, near-duplicate or equivalent pages added afterwards ")
                .append("(rules and reasons: Findings, section 2). Each cell reads strict / widened.\n\n");
        md.append("| set | n | in fused top 20 | in fused top 12 | in final top 5, depth 20 | in final top 5, depth 12 |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (var group : List.of(
                new Group("calibration", o -> o.inCorpus() && !o.holdout()),
                new Group("holdout", o -> o.inCorpus() && o.holdout()),
                new Group("paraphrased (semantic path)", o -> o.inCorpus() && o.paraphrase()),
                new Group("all", QuestionOutcome::inCorpus))) {
            List<QuestionOutcome> g = all.stream().filter(group.filter()).toList();
            long f20 = g.stream().filter(o -> o.expectedFusedRank() != null).count();
            long f20w = g.stream().filter(o -> o.expectedFusedRankWidened() != null).count();
            long f12 = g.stream().filter(o -> o.expectedFusedRank() != null && o.expectedFusedRank() <= 12).count();
            long f12w = g.stream()
                    .filter(o -> o.expectedFusedRankWidened() != null && o.expectedFusedRankWidened() <= 12).count();
            long t20 = g.stream().filter(QuestionOutcome::inTop5Depth20).count();
            long t20w = g.stream().filter(QuestionOutcome::inTop5Depth20Widened).count();
            long t12 = g.stream().filter(QuestionOutcome::inTop5Depth12).count();
            long t12w = g.stream().filter(QuestionOutcome::inTop5Depth12Widened).count();
            md.append(String.format(Locale.ROOT, "| %s | %d | %d / %d | %d / %d | %d / %d | %d / %d |%n",
                    group.name(), g.size(), f20, f20w, f12, f12w, t20, t20w, t12, t12w));
        }
        md.append("\n");
    }

    /** What the retrieval floor and the high-confidence marker do to each question type. */
    private static void gateAtFloor(StringBuilder md, List<QuestionOutcome> all, Context ctx) {
        md.append("## Retrieval floor and high-confidence marker (depth 12, the configured depth)\n\n");
        if (ctx.minRerankScore() == null) {
            md.append("No floor was configured for this run.\n\n");
            return;
        }
        double floor = ctx.minRerankScore();
        Double high = ctx.highConfidenceScore();
        md.append(String.format(Locale.ROOT, "Floor **%.2f**: a top score below it gives NO_RELEVANT_CONTEXT, at or above ", floor));
        md.append("it the chunks go to the answering step. ");
        if (high != null) {
            md.append(String.format(Locale.ROOT, "High-confidence marker **%.2f**: trace only, no behaviour change.", high));
        }
        md.append("\n\n| type | n | below floor (rejected here) | at or above floor (passed on) | of those, high confidence |\n");
        md.append("| --- | ---: | ---: | ---: | ---: |\n");
        for (String type : List.of("IN_CORPUS", "OUT_FAR", "OUT_NEAR", "OUT_AWS")) {
            List<QuestionOutcome> g = all.stream().filter(o -> o.type().equals(type)).toList();
            if (g.isEmpty()) {
                continue;
            }
            long below = g.stream().filter(o -> o.top1Depth12() < floor).count();
            long hc = high == null ? 0 : g.stream().filter(o -> o.top1Depth12() >= high).count();
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d |%n", type, g.size(), below,
                    g.size() - below, hc));
        }
        md.append("\nOut-of-corpus questions that pass the floor are not wrongly answered by retrieval: ")
                .append("they are handed to the answering step, whose citation and quote check must reject them.\n\n");
    }

    private static void depthDecision(StringBuilder md, List<QuestionOutcome> all) {
        List<QuestionOutcome> inCorpus = all.stream().filter(QuestionOutcome::inCorpus).toList();
        List<QuestionOutcome> lost = inCorpus.stream().filter(o -> o.inTop5Depth20() && !o.inTop5Depth12()).toList();
        List<QuestionOutcome> gained = inCorpus.stream().filter(o -> !o.inTop5Depth20() && o.inTop5Depth12()).toList();
        md.append("## Rerank depth: 12 or 20\n\n");
        md.append("Questions that lose the answer page from the final top 5 when only 12 candidates are reranked: ")
                .append(ids(lost)).append(".\n\n");
        if (!gained.isEmpty()) {
            md.append("Questions that gain it (a distractor from rank 13 to 20 no longer competes): ")
                    .append(ids(gained)).append(".\n\n");
        }
        md.append(lost.isEmpty()
                ? "**Recall holds at depth 12** on this set, so 12 is supported (it scores 40% fewer pairs).\n\n"
                : "**Recall does not hold at depth 12** on this set; keep 20.\n\n");
    }

    private static void threshold(StringBuilder md, String label, List<QuestionOutcome> calibration,
                                  List<QuestionOutcome> holdout, ToDoubleFunction<QuestionOutcome> score) {
        md.append("## Threshold at ").append(label).append("\n\n");
        List<Double> in = scores(calibration, true, score);
        List<Double> out = scores(calibration, false, score);
        if (in.isEmpty() || out.isEmpty()) {
            md.append("Not enough questions in the calibration split.\n\n");
            return;
        }
        ThresholdSelector.Selection sel = ThresholdSelector.choose(in, out);
        ThresholdSelector.Point c = sel.chosen();
        md.append(String.format(Locale.ROOT,
                "Calibration split: lowest in-corpus top-1 = **%.2f**, highest out-of-corpus top-1 = **%.2f** -> %s.%n%n",
                sel.lowestInCorpus(), sel.highestOutOfCorpus(),
                sel.separable() ? "the two groups are separable, gap " + String.format(Locale.ROOT, "%.2f",
                        sel.lowestInCorpus() - sel.highestOutOfCorpus())
                        : "the groups **overlap**, no threshold is error-free"));
        md.append(String.format(Locale.ROOT,
                "Chosen threshold: **%.2f** (balanced accuracy %.3f; in-corpus accepted %d/%d, out-of-corpus rejected %d/%d).%n%n",
                c.threshold(), c.balancedAccuracy(), c.truePositives(), c.truePositives() + c.falseNegatives(),
                c.trueNegatives(), c.trueNegatives() + c.falsePositives()));
        md.append("| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |\n");
        md.append("| ---: | ---: | ---: | ---: |\n");
        for (ThresholdSelector.Point p : sel.candidates()) {
            md.append(String.format(Locale.ROOT, "| %.2f%s | %d/%d | %d/%d | %.3f |%n", p.threshold(),
                    p == c ? " **(chosen)**" : "", p.truePositives(), p.truePositives() + p.falseNegatives(),
                    p.trueNegatives(), p.trueNegatives() + p.falsePositives(), p.balancedAccuracy()));
        }
        md.append("\n");

        md.append("Held-out check with threshold ").append(String.format(Locale.ROOT, "%.2f", c.threshold()))
                .append(":\n\n");
        List<QuestionOutcome> wrong = new ArrayList<>();
        int correct = 0;
        for (QuestionOutcome o : holdout) {
            boolean accepted = score.applyAsDouble(o) >= c.threshold();
            if (accepted == o.inCorpus()) {
                correct++;
            } else {
                wrong.add(o);
            }
        }
        md.append(String.format(Locale.ROOT, "- %d of %d held-out questions classified correctly.%n", correct, holdout.size()));
        for (QuestionOutcome o : wrong) {
            md.append(String.format(Locale.ROOT, "- **%s** (%s) scored %.2f and was %s: \"%s\"%n", o.id(), o.type(),
                    score.applyAsDouble(o), o.inCorpus() ? "wrongly rejected (false \"not found\")"
                            : "wrongly accepted (would answer an unanswerable question)", o.question()));
        }
        md.append("\n");
    }

    private static void negatives(StringBuilder md, List<QuestionOutcome> all) {
        md.append("## Out-of-corpus questions by score (depth 20)\n\n");
        md.append("| type | id | split | top-1 | best passage | question |\n| --- | --- | --- | ---: | --- | --- |\n");
        all.stream().filter(o -> !o.inCorpus())
                .sorted(Comparator.comparingDouble(QuestionOutcome::top1Depth20).reversed())
                .forEach(o -> md.append(String.format(Locale.ROOT, "| %s | %s | %s | %.2f | %s | %s |%n",
                        o.type(), o.id(), o.split(), o.top1Depth20(), o.top1Where(), o.question())));
        md.append("\n");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private record Group(String name, Predicate<QuestionOutcome> filter) {
    }

    /** Headline numbers of one run. */
    private static final class Summary {
        int inN;
        int fused20;
        int fused12;
        int top5d20;
        int top5d12;
        int paraN;
        int paraTop5d20;
        double meanIn20;
        double meanRerankMs;
        Map<String, Double> maxOut = new LinkedHashMap<>();
        ThresholdSelector.Selection sel20;
        int holdoutCorrect20;

        static Summary of(List<QuestionOutcome> all) {
            Summary s = new Summary();
            List<QuestionOutcome> in = all.stream().filter(QuestionOutcome::inCorpus).toList();
            s.inN = in.size();
            s.fused20 = (int) in.stream().filter(o -> o.expectedFusedRank() != null).count();
            s.fused12 = (int) in.stream().filter(o -> o.expectedFusedRank() != null && o.expectedFusedRank() <= 12).count();
            s.top5d20 = (int) in.stream().filter(QuestionOutcome::inTop5Depth20).count();
            s.top5d12 = (int) in.stream().filter(QuestionOutcome::inTop5Depth12).count();
            List<QuestionOutcome> para = in.stream().filter(QuestionOutcome::paraphrase).toList();
            s.paraN = para.size();
            s.paraTop5d20 = (int) para.stream().filter(QuestionOutcome::inTop5Depth20).count();
            s.meanIn20 = in.stream().mapToDouble(QuestionOutcome::top1Depth20).average().orElse(Double.NaN);
            s.meanRerankMs = all.stream().mapToLong(QuestionOutcome::rerankMillis).average().orElse(Double.NaN);
            for (QuestionOutcome o : all) {
                if (!o.inCorpus()) {
                    s.maxOut.merge(o.type(), o.top1Depth20(), Math::max);
                }
            }
            List<QuestionOutcome> calibration = all.stream().filter(o -> !o.holdout()).toList();
            List<Double> inScores = scores(calibration, true, QuestionOutcome::top1Depth20);
            List<Double> outScores = scores(calibration, false, QuestionOutcome::top1Depth20);
            if (!inScores.isEmpty() && !outScores.isEmpty()) {
                s.sel20 = ThresholdSelector.choose(inScores, outScores);
                double t = s.sel20.chosen().threshold();
                s.holdoutCorrect20 = (int) all.stream().filter(QuestionOutcome::holdout)
                        .filter(o -> (o.top1Depth20() >= t) == o.inCorpus()).count();
            }
            return s;
        }
    }

    private static void row(StringBuilder md, String label, int a, int b) {
        md.append(String.format(Locale.ROOT, "| %s | %d | %d | %+d |%n", label, a, b, b - a));
    }

    private static void rowD(StringBuilder md, String label, double a, double b) {
        md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |%n", label, fmt(a), fmt(b),
                Double.isNaN(a) || Double.isNaN(b) ? "" : String.format(Locale.ROOT, "%+.2f", b - a)));
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String rank(QuestionOutcome o) {
        return o.expectedFusedRank() == null ? "not in fused 20" : String.valueOf(o.expectedFusedRank());
    }

    private static List<Double> scores(List<QuestionOutcome> list, boolean inCorpus, ToDoubleFunction<QuestionOutcome> f) {
        return list.stream().filter(o -> o.inCorpus() == inCorpus).map(f::applyAsDouble).toList();
    }

    private static String ids(List<QuestionOutcome> list) {
        return list.isEmpty() ? "none" : String.join(", ", list.stream().map(QuestionOutcome::id).toList());
    }

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }
}
