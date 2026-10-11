package com.demo.agentfw.eval;

import java.util.List;

/**
 * One line of {@code eval/calibration-questions.json}.
 *
 * @param type            {@code IN_CORPUS}, {@code OUT_FAR} (unrelated topic), {@code OUT_NEAR} (same domain, not
 *                        AWS content) or {@code OUT_AWS} (an AWS question whose specific answer is not in the PDFs)
 * @param split           {@code calibration} (used to choose the threshold) or {@code holdout} (only used to check it)
 * @param expected        STRICT: every page that contains the question's evidence phrase; fixed before the first run
 * @param tags            optional labels; {@code paraphrase} marks a question with little keyword overlap with its page
 * @param expectedWidened pages added afterwards because they also answer the question (adjacent page of the same
 *                        passage, a near-duplicate page, or a page that states the answer in other words)
 * @param widenedNotes    why each widened page was added, one line each, so the widening can be audited
 */
public record CalibrationQuestion(String id, String type, String question, String split, List<Expected> expected,
                                  List<String> tags, List<Expected> expectedWidened, List<String> widenedNotes) {

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

    /** Strict match: the page contains the evidence phrase. */
    public boolean isExpected(String file, int page) {
        return contains(expected, file, page);
    }

    /** Widened match: strict, or one of the pages added later with a documented reason. */
    public boolean isAcceptable(String file, int page) {
        return contains(expected, file, page) || contains(expectedWidened, file, page);
    }

    private static boolean contains(List<Expected> list, String file, int page) {
        if (list == null) {
            return false;
        }
        return list.stream().anyMatch(e -> e.file().equals(file) && e.pages().contains(page));
    }
}
