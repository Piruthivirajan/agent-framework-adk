package com.demo.agentfw.agents.rag;

import com.demo.agentfw.retrieval.GroundingDecision;
import com.demo.agentfw.retrieval.RetrievalResult;
import com.demo.agentfw.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalLedgerTest {

    private static RetrievalResult passing() {
        RetrievalService svc = TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, null);
        return svc.retrieve("what is the tool?");
    }

    private static RetrievalResult belowFloor() {
        RetrievalService svc = TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> -9.0, null);
        return svc.retrieve("unrelated question");
    }

    @Test
    void assignsShortReferencesPerRetrievalWithinARequest() {
        RetrievalLedger ledger = new RetrievalLedger();

        RetrievalLedger.Retrieval first = ledger.register("req-1", "q1", passing());
        RetrievalLedger.Retrieval second = ledger.register("req-1", "q2", passing());

        assertThat(first.id()).isEqualTo("r1");
        assertThat(first.chunks()).extracting(RetrievalLedger.Entry::ref).containsExactly("r1c1", "r1c2", "r1c3");
        assertThat(second.chunks()).extracting(RetrievalLedger.Entry::ref).containsExactly("r2c1", "r2c2", "r2c3");
        assertThat(ledger.retrievals("req-1")).hasSize(2);
    }

    @Test
    void resolvesAReferenceToFilePageAndText() {
        RetrievalLedger ledger = new RetrievalLedger();
        ledger.register("req-1", "q", passing());

        RetrievalLedger.Entry e = ledger.chunk("req-1", "r1c2").orElseThrow();

        assertThat(e.file()).isEqualTo("wellarchitected-security-pillar.pdf");
        assertThat(e.page()).isEqualTo(9);
        assertThat(e.text()).contains("shared responsibility");
    }

    @Test
    void aRequestCannotSeeAnotherRequestsChunks() {
        RetrievalLedger ledger = new RetrievalLedger();
        ledger.register("req-1", "q", passing());

        assertThat(ledger.chunk("req-2", "r1c1")).isEmpty();
        assertThat(ledger.chunk("req-1", "r9c9")).isEmpty();
        assertThat(ledger.retrievals("req-2")).isEmpty();
    }

    @Test
    void aRetrievalBelowTheFloorIsRecordedWithoutChunks() {
        RetrievalLedger ledger = new RetrievalLedger();

        RetrievalLedger.Retrieval r = ledger.register("req-1", "q", belowFloor());

        assertThat(r.decision()).isEqualTo(GroundingDecision.NO_RELEVANT_CONTEXT);
        assertThat(r.chunks()).isEmpty();
        assertThat(r.trace().grounding().decision()).isEqualTo(GroundingDecision.NO_RELEVANT_CONTEXT);
        assertThat(ledger.chunk("req-1", "r1c1")).isEmpty();
    }

    @Test
    void finishedRequestsCanBeClearedAndTheLedgerStaysBounded() {
        RetrievalLedger ledger = new RetrievalLedger();
        ledger.register("done", "q", passing());
        ledger.clear("done");
        assertThat(ledger.retrievals("done")).isEmpty();

        RetrievalResult result = passing();
        for (int i = 0; i < RetrievalLedger.MAX_REQUESTS + 40; i++) {
            ledger.register("req-" + i, "q", result);
        }

        assertThat(ledger.retrievals("req-0")).isEmpty();                                   // oldest dropped
        assertThat(ledger.retrievals("req-" + (RetrievalLedger.MAX_REQUESTS + 39))).hasSize(1);   // newest kept
    }
}
