package com.demo.agentfw.eval;

import java.util.List;

/**
 * One question through the real coordinator, agent, retrieval and verifier.
 *
 * @param verdict        CORRECT, FALSE_ANSWER, FALSE_NOT_FOUND or ERROR. In-corpus: CORRECT = answered and cited an
 *                       acceptable page (widened rule); out-of-corpus: CORRECT = not found
 * @param strictCorrect  in-corpus only: answered and cited a page of the strict expected set
 * @param notFoundReason why an unanswered question was not answered: {@code model}, {@code retrieval-floor},
 *                       {@code verifier: <first problem>}, {@code no-tool} or empty
 * @param citedPages     the sources of the answer as "file p. N"
 */
public record E2eOutcome(
        String id,
        String type,
        String split,
        String question,
        String verdict,
        boolean strictCorrect,
        boolean answered,
        String answer,
        List<String> citedPages,
        String notFoundReason,
        String route,
        List<String> problems,
        int retrievalCount,
        Double topRerankScore,
        long totalMillis,
        String modelOutput,
        String error) {

    public boolean inCorpus() {
        return "IN_CORPUS".equals(type);
    }
}
