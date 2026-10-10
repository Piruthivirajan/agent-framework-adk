package com.demo.agentfw.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Renders the calibration as Markdown: every score, the recall numbers for both rerank depths,
 * the threshold with the table it was picked from, and the held-out check. Pure function of the
 * outcomes, so the numbers in docs/CALIBRATION.md can be reproduced and unit-tested.
 */
public final class CalibrationReport {

    public record Context(String generatedAt, String embeddingModel, String rerankModel, String fusion,
                          int fusedLimit, int finalTopN) {
    }

    private CalibrationReport() {
    }

    public static String render(List<QuestionOutcome> all, Context ctx) {
        StringBuilder md = new StringBuilder();
        md.append("# Grounding threshold calibration\n\n");
        md.append("Generated ").append(ctx.generatedAt()).append(" by `CalibrationRunner` on the real corpus.\n\n");
        md.append("- Embedding model: `").append(ctx.embeddingModel()).append("`\n");
        md.append("- Reranker: `").append(ctx.rerankModel()).append("`\n");
        md.append("- Fusion: `").append(ctx.fusion()).append("`, fused top ").append(ctx.fusedLimit())
                .append(", final top ").append(ctx.finalTopN()).append("\n");
        md.append("- The score is the cross-encoder's raw logit (not 0 to 1). The threshold applies to the best score.\n");
        md.append("- Each question is retrieved once with all ").append(ctx.fusedLimit())
                .append(" fused candidates scored; the \"depth 12\" columns use only the fused top 12 of those same scores.\n\n");

        scoresTable(md, all);
        recall(md, all);
        List<QuestionOutcome> calibration = all.stream().filter(o -> !o.holdout()).toList();
        List<QuestionOutcome> holdout = all.stream().filter(QuestionOutcome::holdout).toList();
        depthDecision(md, all);
        threshold(md, "depth 20", calibration, holdout, QuestionOutcome::top1Depth20);
        threshold(md, "depth 12", calibration, holdout, QuestionOutcome::top1Depth12);
        negatives(md, all);
        return md.toString();
    }

    // ---- sections -------------------------------------------------------------------------------

    private static void scoresTable(StringBuilder md, List<QuestionOutcome> all) {
        md.append("## Every question\n\n");
        md.append("| id | split | type | top-1 d20 | top-1 d12 | best passage | expected at fused rank | in top 5 d20 | in top 5 d12 | truncated | question |\n");
        md.append("| --- | --- | --- | ---: | ---: | --- | ---: | :---: | :---: | ---: | --- |\n");
        for (QuestionOutcome o : all) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %.2f | %.2f | %s | %s | %s | %s | %d | %s |%n",
                    o.id(), o.split(), o.type(), o.top1Depth20(), o.top1Depth12(), o.top1Where(),
                    o.inCorpus() ? (o.expectedFusedRank() == null ? "not in fused 20" : String.valueOf(o.expectedFusedRank())) : "-",
                    o.inCorpus() ? yes(o.inTop5Depth20()) : "-",
                    o.inCorpus() ? yes(o.inTop5Depth12()) : "-",
                    o.truncatedPairs(), o.question()));
        }
        md.append("\n");
    }

    private static void recall(StringBuilder md, List<QuestionOutcome> all) {
        md.append("## Recall of the answer-bearing page (in-corpus questions)\n\n");
        md.append("| set | n | in fused top 20 | in fused top 12 | in final top 5, depth 20 | in final top 5, depth 12 |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (var group : List.of(
                new Group("calibration", o -> o.inCorpus() && !o.holdout()),
                new Group("holdout", o -> o.inCorpus() && o.holdout()),
                new Group("all", QuestionOutcome::inCorpus))) {
            List<QuestionOutcome> g = all.stream().filter(group.filter()).toList();
            long f20 = g.stream().filter(o -> o.expectedFusedRank() != null).count();
            long f12 = g.stream().filter(o -> o.expectedFusedRank() != null && o.expectedFusedRank() <= 12).count();
            long t20 = g.stream().filter(QuestionOutcome::inTop5Depth20).count();
            long t12 = g.stream().filter(QuestionOutcome::inTop5Depth12).count();
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d |%n",
                    group.name(), g.size(), f20, f12, t20, t12));
        }
        md.append("\n");
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
        md.append("| type | id | top-1 | best passage | question |\n| --- | --- | ---: | --- | --- |\n");
        all.stream().filter(o -> !o.inCorpus())
                .sorted(Comparator.comparingDouble(QuestionOutcome::top1Depth20).reversed())
                .forEach(o -> md.append(String.format(Locale.ROOT, "| %s | %s | %.2f | %s | %s |%n",
                        o.type(), o.id(), o.top1Depth20(), o.top1Where(), o.question())));
        md.append("\n");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private record Group(String name, Predicate<QuestionOutcome> filter) {
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
