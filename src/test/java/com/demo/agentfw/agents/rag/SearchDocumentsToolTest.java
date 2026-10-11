package com.demo.agentfw.agents.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchDocumentsToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void returnsChunksWithRefsAndNothingElseToTheModel() {
        RetrievalLedger ledger = new RetrievalLedger();
        List<String> queries = new ArrayList<>();
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, queries::add), ledger);

        Map<String, Object> out = tool.search("req-1", "Well-Architected Tool");

        assertThat(queries).containsExactly("Well-Architected Tool");
        assertThat(out).containsOnlyKeys("status", "chunks");
        assertThat(out.get("status")).isEqualTo("OK");
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) out.get("chunks");
        assertThat(chunks).hasSize(3);
        // exactly these four fields: no scores, no ranks, no trace
        assertThat(chunks.get(0)).containsOnlyKeys("ref", "file", "page", "text");
        assertThat(chunks.get(0).get("ref")).isEqualTo("r1c1");
        assertThat(chunks.get(0).get("file")).isEqualTo("wellarchitected-framework.pdf");
        assertThat(chunks.get(0).get("page")).isEqualTo(7);
        assertThat((String) chunks.get(0).get("text")).contains("AWS WA Tool");
        assertThat(out.toString()).doesNotContain("rerank").doesNotContain("score").doesNotContain("trace");
    }

    @Test
    void answersNoRelevantContextWhenRetrievalIsBelowTheFloor() {
        RetrievalLedger ledger = new RetrievalLedger();
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> -9.0, null), ledger);

        Map<String, Object> out = tool.search("req-1", "who won the world cup");

        assertThat(out).containsOnlyKeys("status");
        assertThat(out.get("status")).isEqualTo("NO_RELEVANT_CONTEXT");
        assertThat(ledger.retrievals("req-1")).hasSize(1);   // still recorded, for the trace
        assertThat(ledger.retrievals("req-1").get(0).trace()).isNotNull();
    }

    @Test
    void everyCallIsRecordedUnderItsRequestSoRewrittenQueriesAreAllVerifiable() {
        RetrievalLedger ledger = new RetrievalLedger();
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, null), ledger);

        tool.search("req-1", "first wording");
        tool.search("req-1", "second wording");
        tool.search("req-2", "another request");

        assertThat(ledger.retrievals("req-1")).extracting(RetrievalLedger.Retrieval::query)
                .containsExactly("first wording", "second wording");
        assertThat(ledger.chunk("req-1", "r2c1")).isPresent();
        assertThat(ledger.retrievals("req-2")).hasSize(1);
    }
}
