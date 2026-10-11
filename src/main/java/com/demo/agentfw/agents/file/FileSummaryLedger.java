package com.demo.agentfw.agents.file;

import com.demo.agentfw.files.MapReduceSummarizer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the file agent did in a turn, kept in code under the turn's request id. {@code ChatService}
 * reads the summary from here, not from the model's prose, exactly as it reads retrieved chunks from the
 * {@code RetrievalLedger}: the answer the user sees is produced by code the application controls.
 */
@Component
public class FileSummaryLedger {

    public enum Status { SUMMARIZED, NO_FILE, FAILED }

    /**
     * @param message   for NO_FILE and FAILED: the text for the user
     * @param summary   the summary, for SUMMARIZED
     */
    public record Outcome(Status status, String message, String summary, String fileId, String fileName,
                          String contentType, int chars, MapReduceSummarizer.Strategy strategy, int segments,
                          int modelCalls, long millis) {

        public static Outcome noFile(String message) {
            return new Outcome(Status.NO_FILE, message, null, null, null, null, 0, null, 0, 0, 0);
        }

        public static Outcome failed(String message) {
            return new Outcome(Status.FAILED, message, null, null, null, null, 0, null, 0, 0, 0);
        }
    }

    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();

    public void record(String requestId, Outcome outcome) {
        outcomes.put(requestId, outcome);
    }

    /** The turn's outcome, removed from the ledger. */
    public Optional<Outcome> take(String requestId) {
        return Optional.ofNullable(outcomes.remove(requestId));
    }

    public void clear(String requestId) {
        outcomes.remove(requestId);
    }
}
