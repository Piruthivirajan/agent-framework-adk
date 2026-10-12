package com.demo.agentfw.agents.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SpeculativeSearchTest {

    private final List<String> searched = new CopyOnWriteArrayList<>();
    private final RetrievalLedger ledger = new RetrievalLedger();

    private SpeculativeSearch speculative(boolean enabled) {
        return new SpeculativeSearch(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, searched::add), enabled);
    }

    private SearchDocumentsTool tool(SpeculativeSearch s) {
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, searched::add), ledger,
                RagProperties.defaults());
        tool.setSpeculativeSearch(s);
        return tool;
    }

    @Test
    void theResultOfTheEarlySearchIsUsedWhenTheQueryIsTheSameAndNothingIsSearchedTwice() {
        SpeculativeSearch s = speculative(true);
        s.start("req-1", "What is the AWS Well-Architected Tool?");

        Map<String, Object> out = tool(s).search("req-1", "What is the AWS Well-Architected Tool?");

        assertThat(out.get("status")).isEqualTo("OK");
        assertThat(searched).containsExactly("What is the AWS Well-Architected Tool?");   // one search, not two
        assertThat(ledger.chunk("req-1", "r1c1")).isPresent();                           // refs as for any search
        assertThat(ledger.retrievals("req-1")).hasSize(1);
    }

    @Test
    void aDifferentQueryIgnoresTheEarlyResultAndSearchesNormally() {
        SpeculativeSearch s = speculative(true);
        s.start("req-1", "tell me about security");

        Map<String, Object> out = tool(s).search("req-1", "AWS security pillar");

        assertThat(out.get("status")).isEqualTo("OK");
        assertThat(searched).containsExactlyInAnyOrder("tell me about security", "AWS security pillar");
        assertThat(ledger.retrievals("req-1").get(0).query()).isEqualTo("AWS security pillar");
    }

    @Test
    void anEarlyResultIsUsedOnlyOnceAndOnlyForItsOwnRequest() {
        SpeculativeSearch s = speculative(true);
        s.start("req-1", "same text");

        assertThat(s.take("req-2", "same text")).isEmpty();
        assertThat(s.take("req-1", "same text")).isPresent();
        assertThat(s.take("req-1", "same text")).isEmpty();
    }

    @Test
    void switchedOffNothingIsStarted() {
        SpeculativeSearch s = speculative(false);

        s.start("req-1", "text");

        assertThat(s.take("req-1", "text")).isEmpty();
        assertThat(searched).isEmpty();
    }

    @Test
    void aFailedEarlySearchIsNotUsedAndTheRealSearchRunsAndDecidesForItself() {
        SpeculativeSearch failing = new SpeculativeSearch(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, q -> {
                    throw new IllegalStateException("database down");
                }), true);
        failing.start("req-1", "text");

        assertThat(failing.take("req-1", "text")).isEmpty();
        assertThat(ledger.failure("req-1")).isEmpty();      // only the real search may record a failure
    }

    @Test
    void clearDropsAnUnusedEarlySearch() {
        SpeculativeSearch s = speculative(true);
        s.start("req-1", "text");

        s.clear("req-1");

        assertThat(s.take("req-1", "text")).isEmpty();
    }
}
