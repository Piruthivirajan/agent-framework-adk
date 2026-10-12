package com.demo.agentfw.agents.rag;

import com.demo.agentfw.core.TurnProgress;
import com.demo.agentfw.retrieval.GroundingDecision;
import com.demo.agentfw.retrieval.RetrievalResult;
import com.demo.agentfw.retrieval.RetrievalService;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only way the RAG agent sees the documents. It runs the full retrieval funnel and returns the
 * chunks with a reference each; the trace (scores, ranks, timings) stays in the ledger and is never
 * sent to the model.
 *
 * <p>The request id comes from the session state of the top-level run (ADK copies it into the nested
 * agent); a missing id falls back to the nested session id so a stand-alone run still works.
 */
@Component
public class SearchDocumentsTool {

    public static final String REQUEST_ID_KEY = com.demo.agentfw.core.TurnState.REQUEST_ID;
    static final String METHOD = "searchDocuments";

    private static final Logger log = LoggerFactory.getLogger(SearchDocumentsTool.class);

    private final RetrievalService retrieval;
    private final RetrievalLedger ledger;
    private final RagProperties props;
    private final TurnProgress progress;

    @org.springframework.beans.factory.annotation.Autowired
    public SearchDocumentsTool(RetrievalService retrieval, RetrievalLedger ledger, RagProperties props,
                               TurnProgress progress) {
        this.retrieval = retrieval;
        this.ledger = ledger;
        this.props = props;
        this.progress = progress;
    }

    private SpeculativeSearch speculative;

    /** Optional: when present, a search that was started with the turn is used instead of a new one. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSpeculativeSearch(SpeculativeSearch speculative) {
        this.speculative = speculative;
    }

    /** Without progress reporting (tests that do not care about stages). */
    public SearchDocumentsTool(RetrievalService retrieval, RetrievalLedger ledger, RagProperties props) {
        this(retrieval, ledger, props, new TurnProgress());
    }

    @Annotations.Schema(name = METHOD, description = "Searches the reference documents and returns the most "
            + "relevant passages, each with a ref. Returns status NO_RELEVANT_CONTEXT when nothing relevant exists.")
    public Map<String, Object> searchDocuments(
            @Annotations.Schema(name = "query", description = "A short search query in the words the documents "
                    + "would use. Rewrite it if the first results do not contain the answer.") String query,
            @Annotations.Schema(name = "toolContext", description = "framework context") ToolContext toolContext) {
        Object id = toolContext.state().get(REQUEST_ID_KEY);
        String requestId = id != null ? id.toString() : "session:" + toolContext.sessionId();
        return search(requestId, query);
    }

    /**
     * True while this request has neither searched nor failed to search: the moment at which the first
     * search can be made in code instead of by a model call. After a failure it is false, so a broken
     * search is never retried in a loop.
     */
    boolean noSearchYet(String requestId) {
        return ledger.retrievals(requestId).isEmpty() && ledger.failure(requestId).isEmpty();
    }

    /** The tool's logic without the framework context, so it can be tested directly. */
    Map<String, Object> search(String requestId, String query) {
        List<RetrievalLedger.Retrieval> prior = ledger.retrievals(requestId);
        if (prior.size() >= props.maxSearches()) {
            log.info("search_documents request={} refused: search limit {} reached", requestId, props.maxSearches());
            return refusal("SEARCH_LIMIT_REACHED", "The search limit for this question was reached. Do not search "
                    + "again: answer from the chunks already returned, or reply {\"status\":\"NOT_FOUND\"}.");
        }
        if (props.stopAfterFloorMiss() && !prior.isEmpty()
                && prior.get(0).decision() == GroundingDecision.NO_RELEVANT_CONTEXT) {
            log.info("search_documents request={} refused: the first search found nothing relevant", requestId);
            return refusal("NO_RELEVANT_CONTEXT", "The first search found nothing relevant in the documents. "
                    + "Do not search again: reply {\"status\":\"NOT_FOUND\"}.");
        }
        RetrievalResult result;
        try {
            java.util.Optional<RetrievalResult> early = speculative == null ? java.util.Optional.empty()
                    : speculative.take(requestId, query);
            if (early.isPresent()) {
                result = early.get();
                progress.emit(requestId, "searching", "");        // the work is done; show the stages in order
                progress.emit(requestId, "reranking", "");
            } else {
                result = retrieval.retrieve(query, stage -> progress.emit(requestId, stage, ""));
            }
        } catch (RuntimeException e) {
            // never let this look like "the documents have no answer": the chat service reads the ledger
            ledger.recordFailure(requestId, e);
            log.warn("search_documents request={} failed: {}", requestId,
                    com.demo.agentfw.core.Secrets.mask(e.getClass().getSimpleName()));
            return refusal("SEARCH_FAILED", "The search could not be run. Do not search again and do not answer "
                    + "from your own knowledge: reply {\"status\":\"NOT_FOUND\"}.");
        }
        progress.emit(requestId, "answering", "");
        RetrievalLedger.Retrieval recorded = ledger.register(requestId, query, result);
        log.info("search_documents request={} retrieval={} decision={} chunks={}", requestId, recorded.id(),
                result.decision(), recorded.chunks().size());

        Map<String, Object> out = new LinkedHashMap<>();
        if (result.decision() == GroundingDecision.NO_RELEVANT_CONTEXT || recorded.chunks().isEmpty()) {
            out.put("status", "NO_RELEVANT_CONTEXT");
            if (props.stopAfterFloorMiss() && prior.isEmpty()) {
                out.put("message", "Nothing relevant was found in the documents. Do not search again: "
                        + "reply {\"status\":\"NOT_FOUND\"}.");
            }
            return out;
        }
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (RetrievalLedger.Entry e : recorded.chunks()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("ref", e.ref());
            c.put("file", e.file());
            c.put("page", e.page());
            c.put("text", e.text());
            chunks.add(c);
        }
        out.put("status", "OK");
        out.put("chunks", chunks);
        return out;
    }

    private static Map<String, Object> refusal(String status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("message", message);
        return out;
    }
}
