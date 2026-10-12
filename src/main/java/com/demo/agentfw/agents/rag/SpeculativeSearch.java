package com.demo.agentfw.agents.rag;

import com.demo.agentfw.retrieval.RetrievalResult;
import com.demo.agentfw.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts the search for the user's message while the coordinator is still deciding where to send it.
 *
 * <p>The coordinator's model call (about 2 s) and the search (embedding, MongoDB, rerank; about 1.5 s) do
 * not depend on each other when the coordinator passes the message on unchanged, which its instruction
 * asks for. So the search is started at the beginning of the turn, and when the RAG agent's first search
 * asks for the same text the finished (or nearly finished) result is used instead of searching again.
 *
 * <p>Nothing about the answer changes: the result is registered in the ledger by {@link SearchDocumentsTool}
 * exactly as a search it ran itself (refs, cap, verification). If the text differs (a restated follow-up),
 * the search fails, or the route was not the RAG agent, the speculative result is simply not used: the
 * cost of a miss is one embedding call and one database query.
 */
@Component
public class SpeculativeSearch {

    private static final Logger log = LoggerFactory.getLogger(SpeculativeSearch.class);

    private record Pending(String query, CompletableFuture<RetrievalResult> result) {
    }

    private final RetrievalService retrieval;
    private final boolean enabled;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "speculative-search");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public SpeculativeSearch(RetrievalService retrieval,
                             @Value("${agentfw.rag.speculative-search:true}") boolean enabled) {
        this.retrieval = retrieval;
        this.enabled = enabled;
    }

    /** Begins searching for {@code query} for this request; a no-op when switched off or without text. */
    public void start(String requestId, String query) {
        if (!enabled || query == null || query.isBlank()) {
            return;
        }
        String q = query.strip();
        pending.put(requestId, new Pending(q, CompletableFuture.supplyAsync(() -> retrieval.retrieve(q), executor)));
    }

    /**
     * The result of the speculative search when it was for exactly this query. Waits for it if it is still
     * running (it started earlier than a search made now would). Empty on a different query or a failure;
     * the caller then searches itself and a real failure is recorded by the caller as before.
     */
    public Optional<RetrievalResult> take(String requestId, String query) {
        Pending p = pending.remove(requestId);
        if (p == null || query == null || !p.query().equals(query.strip())) {
            return Optional.empty();
        }
        try {
            return Optional.of(p.result().join());
        } catch (RuntimeException e) {
            log.info("speculative search request={} not used: {}", requestId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void clear(String requestId) {
        pending.remove(requestId);
    }
}
