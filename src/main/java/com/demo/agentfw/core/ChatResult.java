package com.demo.agentfw.core;

import com.demo.agentfw.agents.rag.GroundingVerifier;
import com.demo.agentfw.retrieval.RetrievalTrace;

import java.util.List;

/**
 * The API response of one chat turn.
 *
 * @param answer      the text to show: verified claims with citations, or one of the two fixed sentences
 * @param route       {@code rag_agent}, or {@code none} when no agent was called
 * @param grounded    true only when the answer passed the grounding verifier
 * @param sources     the supporting quotes, with file and page, for the expandable sources list; quotes are
 *                    never inline in {@code answer}
 * @param retrievals  one trace per search the RAG agent ran (scores, ranks, timings); empty without a search
 * @param verification why the answer was replaced, for the trace panel and the end-to-end report
 * @param toolCalls   names of the tools the coordinator called, in order
 * @param modelOutput the RAG agent's raw output, kept for debugging and the report; null without one
 * @param requestId   correlates this turn with the log lines
 * @param totalMillis wall time of the whole turn
 */
public record ChatResult(
        String answer,
        String route,
        boolean grounded,
        List<GroundingVerifier.Source> sources,
        List<RetrievalTrace> retrievals,
        Verification verification,
        List<String> toolCalls,
        String modelOutput,
        String requestId,
        long totalMillis) {

    /**
     * @param problems          reasons the verifier rejected the answer (empty when it passed or when the model
     *                          itself said NOT_FOUND)
     * @param modelSaidNotFound the model answered NOT_FOUND by itself
     */
    public record Verification(List<String> problems, boolean modelSaidNotFound) {
    }
}
