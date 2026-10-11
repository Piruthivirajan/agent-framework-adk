package com.demo.agentfw.agents.rag;

import com.demo.agentfw.retrieval.GroundingDecision;
import com.demo.agentfw.retrieval.RetrievalResult;
import com.demo.agentfw.retrieval.RetrievalTrace;
import com.demo.agentfw.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What retrieval actually returned during one request, kept outside the model so it can be checked
 * against what the model later claims. Every returned chunk gets a short reference ({@code r1c2}:
 * first retrieval of the request, second chunk); the model must cite chunks by that reference and the
 * verifier resolves it here, so file and page come from this ledger and never from the model.
 *
 * <p>Entries are grouped by a request id that the caller passes as session state on the top-level
 * run; ADK copies it into the nested agent's tool context. The ledger is bounded: the oldest
 * requests are dropped, and {@link #clear(String)} removes a finished one.
 */
@Component
public class RetrievalLedger {

    static final int MAX_REQUESTS = 256;

    /** One chunk handed to the model. */
    public record Entry(String ref, String file, int page, int chunkIndex, String text, double rerankScore) {
    }

    /** One call of the search tool within a request. */
    public record Retrieval(String id, String query, GroundingDecision decision, List<Entry> chunks,
                            RetrievalTrace trace) {
    }

    private final Map<String, List<Retrieval>> byRequest = new LinkedHashMap<>();

    /** Records a retrieval and assigns references to its chunks; returns what was recorded. */
    public synchronized Retrieval register(String requestId, String query, RetrievalResult result) {
        List<Retrieval> list = byRequest.computeIfAbsent(requestId, k -> new ArrayList<>());
        String id = "r" + (list.size() + 1);
        List<Entry> entries = new ArrayList<>();
        int n = 1;
        for (RetrievedChunk c : result.chunks()) {
            entries.add(new Entry(id + "c" + n++, c.file(), c.page(), c.chunkIndex(), c.text(), c.rerankScore()));
        }
        Retrieval retrieval = new Retrieval(id, query, result.decision(), List.copyOf(entries), result.trace());
        list.add(retrieval);
        while (byRequest.size() > MAX_REQUESTS) {
            byRequest.remove(byRequest.keySet().iterator().next());
        }
        return retrieval;
    }

    public synchronized Optional<Entry> chunk(String requestId, String ref) {
        return byRequest.getOrDefault(requestId, List.of()).stream()
                .flatMap(r -> r.chunks().stream())
                .filter(e -> e.ref().equals(ref))
                .findFirst();
    }

    /** The retrievals of a request in call order; empty when the search tool was never used. */
    public synchronized List<Retrieval> retrievals(String requestId) {
        return List.copyOf(byRequest.getOrDefault(requestId, List.of()));
    }

    public synchronized void clear(String requestId) {
        byRequest.remove(requestId);
    }
}
