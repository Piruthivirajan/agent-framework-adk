package com.demo.agentfw.eval;

import java.util.List;

/**
 * One line of {@code eval/calibration-questions.json}.
 *
 * @param type     {@code IN_CORPUS}, {@code OUT_FAR} (unrelated topic), {@code OUT_NEAR} (same domain, not
 *                 AWS content) or {@code OUT_AWS} (an AWS question whose specific answer is not in the PDFs)
 * @param split    {@code calibration} (used to choose the threshold) or {@code holdout} (only used to check it)
 * @param expected every page in the corpus that contains the answer; empty for out-of-corpus questions
 * @param tags     optional labels; {@code paraphrase} marks a question with little keyword overlap with its page
 */
public record CalibrationQuestion(String id, String type, String question, String split, List<Expected> expected,
                                  List<String> tags) {

    public record Expected(String file, List<Integer> pages) {
    }

    public boolean inCorpus() {
        return "IN_CORPUS".equals(type);
    }

    public boolean holdout() {
        return "holdout".equals(split);
    }

    public boolean paraphrase() {
        return tags != null && tags.contains("paraphrase");
    }

    public boolean isExpected(String file, int page) {
        if (expected == null) {
            return false;
        }
        return expected.stream().anyMatch(e -> e.file().equals(file) && e.pages().contains(page));
    }
}
