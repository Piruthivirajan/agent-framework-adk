package com.demo.agentfw.eval;

import java.util.List;

/**
 * One line of {@code eval/calibration-questions.json}.
 *
 * @param type     {@code IN_CORPUS}, {@code OUT_FAR} (unrelated topic) or {@code OUT_NEAR} (same domain, not covered)
 * @param split    {@code calibration} (used to choose the threshold) or {@code holdout} (only used to check it)
 * @param expected every page in the corpus that contains the answer; empty for out-of-corpus questions
 */
public record CalibrationQuestion(String id, String type, String question, String split, List<Expected> expected) {

    public record Expected(String file, List<Integer> pages) {
    }

    public boolean inCorpus() {
        return "IN_CORPUS".equals(type);
    }

    public boolean holdout() {
        return "holdout".equals(split);
    }

    public boolean isExpected(String file, int page) {
        if (expected == null) {
            return false;
        }
        return expected.stream().anyMatch(e -> e.file().equals(file) && e.pages().contains(page));
    }
}
