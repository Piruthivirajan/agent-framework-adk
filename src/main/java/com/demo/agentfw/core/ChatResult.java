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
 * @param file        set when the file agent summarized a file: the answer is then a labelled summary that is
 *                    not quote-verified ({@code grounded} is false); null otherwise
 * @param rewrittenQuestion the standalone message the coordinator actually received when the user's message
 *                    was a follow-up that had to be rewritten from the chat history; null when it was used as
 *                    written
 * @param stages      when each stage of the turn started (routing, searching, reranking, ...), in order
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
        long totalMillis,
        FileSummary file,
        String rewrittenQuestion,
        List<StageEvent> stages) {

    public ChatResult {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    public ChatResult(String answer, String route, boolean grounded, List<GroundingVerifier.Source> sources,
                      List<RetrievalTrace> retrievals, Verification verification, List<String> toolCalls,
                      String modelOutput, String requestId, long totalMillis, FileSummary file,
                      String rewrittenQuestion) {
        this(answer, route, grounded, sources, retrievals, verification, toolCalls, modelOutput, requestId,
                totalMillis, file, rewrittenQuestion, List.of());
    }

    /** Without a file summary (every RAG and no-tool result). */
    public ChatResult(String answer, String route, boolean grounded, List<GroundingVerifier.Source> sources,
                      List<RetrievalTrace> retrievals, Verification verification, List<String> toolCalls,
                      String modelOutput, String requestId, long totalMillis) {
        this(answer, route, grounded, sources, retrievals, verification, toolCalls, modelOutput, requestId,
                totalMillis, null, null, List.of());
    }

    public ChatResult(String answer, String route, boolean grounded, List<GroundingVerifier.Source> sources,
                      List<RetrievalTrace> retrievals, Verification verification, List<String> toolCalls,
                      String modelOutput, String requestId, long totalMillis, FileSummary file) {
        this(answer, route, grounded, sources, retrievals, verification, toolCalls, modelOutput, requestId,
                totalMillis, file, null, List.of());
    }

    public ChatResult withRewrittenQuestion(String rewritten) {
        return new ChatResult(answer, route, grounded, sources, retrievals, verification, toolCalls, modelOutput,
                requestId, totalMillis, file, rewritten, stages);
    }

    public ChatResult withStages(List<StageEvent> timeline) {
        return new ChatResult(answer, route, grounded, sources, retrievals, verification, toolCalls, modelOutput,
                requestId, totalMillis, file, rewrittenQuestion, timeline);
    }

    /** What the file agent summarized and how, for the "summary of file X" label and the trace panel. */
    public record FileSummary(String fileId, String name, String contentType, int chars, String strategy,
                              int segments, int modelCalls, long millis) {
    }

    /**
     * @param problems          reasons the verifier rejected the answer (empty when it passed or when the model
     *                          itself said NOT_FOUND)
     * @param modelSaidNotFound the model answered NOT_FOUND by itself
     * @param repaired          the agent's first output was malformed JSON and the verified text is the
     *                          result of the one allowed repair
     */
    public record Verification(List<String> problems, boolean modelSaidNotFound, boolean repaired) {

        public Verification(List<String> problems, boolean modelSaidNotFound) {
            this(problems, modelSaidNotFound, false);
        }
    }
}
