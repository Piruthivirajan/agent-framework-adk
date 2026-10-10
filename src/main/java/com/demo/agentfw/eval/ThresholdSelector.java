package com.demo.agentfw.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Chooses the grounding threshold from observed scores, not from intuition.
 *
 * <p>Inputs are the best rerank score of each in-corpus question (should be accepted) and of each
 * out-of-corpus question (should be rejected). Every midpoint between two neighbouring observed
 * scores is a candidate; the one with the highest balanced accuracy wins, and among equals the one
 * with the widest empty gap around it (most robust to a slightly different question).
 */
public final class ThresholdSelector {

    /** Counts when a score at or above {@code threshold} is accepted. */
    public record Point(double threshold, int truePositives, int falseNegatives, int falsePositives,
                        int trueNegatives, double balancedAccuracy, double gapWidth) {
    }

    /**
     * @param separable  true when every in-corpus score is above every out-of-corpus score
     * @param chosen     the selected point
     * @param candidates all evaluated points, ascending threshold
     */
    public record Selection(boolean separable, double lowestInCorpus, double highestOutOfCorpus, Point chosen,
                            List<Point> candidates) {
    }

    private ThresholdSelector() {
    }

    public static Selection choose(List<Double> inCorpusScores, List<Double> outOfCorpusScores) {
        if (inCorpusScores.isEmpty() || outOfCorpusScores.isEmpty()) {
            throw new IllegalArgumentException("need at least one in-corpus and one out-of-corpus score");
        }
        TreeSet<Double> distinct = new TreeSet<>();
        distinct.addAll(inCorpusScores);
        distinct.addAll(outOfCorpusScores);
        List<Double> sorted = new ArrayList<>(distinct);

        List<Point> points = new ArrayList<>();
        if (sorted.size() == 1) {
            points.add(evaluate(sorted.get(0), inCorpusScores, outOfCorpusScores, 0));
        }
        for (int i = 0; i + 1 < sorted.size(); i++) {
            double lo = sorted.get(i);
            double hi = sorted.get(i + 1);
            points.add(evaluate((lo + hi) / 2.0, inCorpusScores, outOfCorpusScores, hi - lo));
        }

        Point best = points.get(0);
        for (Point p : points) {
            if (p.balancedAccuracy() > best.balancedAccuracy() + 1e-12
                    || (Math.abs(p.balancedAccuracy() - best.balancedAccuracy()) <= 1e-12
                    && p.gapWidth() > best.gapWidth())) {
                best = p;
            }
        }
        double lowestIn = inCorpusScores.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double highestOut = outOfCorpusScores.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        return new Selection(lowestIn > highestOut, lowestIn, highestOut, best, points);
    }

    /** Same counts for an existing threshold, used on the held-out questions. */
    public static Point evaluate(double threshold, List<Double> inCorpusScores, List<Double> outOfCorpusScores,
                                 double gapWidth) {
        int tp = (int) inCorpusScores.stream().filter(s -> s >= threshold).count();
        int fn = inCorpusScores.size() - tp;
        int fp = (int) outOfCorpusScores.stream().filter(s -> s >= threshold).count();
        int tn = outOfCorpusScores.size() - fp;
        double tpr = inCorpusScores.isEmpty() ? 0 : (double) tp / inCorpusScores.size();
        double tnr = outOfCorpusScores.isEmpty() ? 0 : (double) tn / outOfCorpusScores.size();
        return new Point(threshold, tp, fn, fp, tn, (tpr + tnr) / 2.0, gapWidth);
    }
}
