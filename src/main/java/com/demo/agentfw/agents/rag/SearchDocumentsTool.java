package com.demo.agentfw.agents.rag;

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

    public static final String REQUEST_ID_KEY = "requestId";
    static final String METHOD = "searchDocuments";

    private static final Logger log = LoggerFactory.getLogger(SearchDocumentsTool.class);

    private final RetrievalService retrieval;
    private final RetrievalLedger ledger;

    public SearchDocumentsTool(RetrievalService retrieval, RetrievalLedger ledger) {
        this.retrieval = retrieval;
        this.ledger = ledger;
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

    /** The tool's logic without the framework context, so it can be tested directly. */
    Map<String, Object> search(String requestId, String query) {
        RetrievalResult result = retrieval.retrieve(query);
        RetrievalLedger.Retrieval recorded = ledger.register(requestId, query, result);
        log.info("search_documents request={} retrieval={} decision={} chunks={}", requestId, recorded.id(),
                result.decision(), recorded.chunks().size());

        Map<String, Object> out = new LinkedHashMap<>();
        if (result.decision() == GroundingDecision.NO_RELEVANT_CONTEXT || recorded.chunks().isEmpty()) {
            out.put("status", "NO_RELEVANT_CONTEXT");
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
}
