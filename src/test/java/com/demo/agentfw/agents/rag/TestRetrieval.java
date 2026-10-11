package com.demo.agentfw.agents.rag;

import com.demo.agentfw.ingestion.FakeEmbeddingService;
import com.demo.agentfw.retrieval.Candidate;
import com.demo.agentfw.retrieval.ChunkSearch;
import com.demo.agentfw.retrieval.FusionStrategy;
import com.demo.agentfw.retrieval.Reranker;
import com.demo.agentfw.retrieval.RetrievalProperties;
import com.demo.agentfw.retrieval.RetrievalService;
import com.demo.agentfw.retrieval.SearchOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A real {@link RetrievalService} over canned candidates, so agent tests need no MongoDB or model. */
public final class TestRetrieval {

    static final double FLOOR = -6.5;

    private TestRetrieval() {
    }

    public static Candidate candidate(int rank, String file, int page, String text) {
        return new Candidate("id" + rank, "doc", file, page, rank, text, rank, 0.5, null, null, 1.0 / (60 + rank), rank);
    }

    /**
     * @param candidates fused candidates, best first
     * @param score      reranker score of the candidate at that index (0-based)
     * @param onSearch   called with each query, or null
     */
    public static RetrievalService service(List<Candidate> candidates, java.util.function.IntToDoubleFunction score,
                                    Consumer<String> onSearch) {
        ChunkSearch search = (query, vector, settings) -> {
            if (onSearch != null) {
                onSearch.accept(query);
            }
            return new SearchOutcome(FusionStrategy.RANK, candidates, 20, 5, candidates.size(), Map.of("search", 1L));
        };
        Reranker reranker = new Reranker() {
            @Override
            public Result rerank(String query, List<String> passages) {
                float[] s = new float[passages.size()];
                for (int i = 0; i < s.length; i++) {
                    s[i] = (float) score.applyAsDouble(i);
                }
                return new Result(s, 0);
            }

            @Override
            public String modelName() {
                return "scripted-reranker";
            }
        };
        RetrievalProperties props = new RetrievalProperties(FusionStrategy.RANK, 400, 20, 20, 12, 5, 1.0, 1.0,
                "sigmoid", 60, FLOOR, 3.3);
        return new RetrievalService(new FakeEmbeddingService(8, "fake-embed"), search, reranker, props);
    }

    public static List<Candidate> sampleCandidates() {
        List<Candidate> out = new ArrayList<>();
        out.add(candidate(1, "wellarchitected-framework.pdf", 7,
                "The AWS Well-Architected Tool (AWS WA Tool) is a service in the cloud that provides a consistent "
                        + "process for you to review and measure your workloads."));
        out.add(candidate(2, "wellarchitected-security-pillar.pdf", 9,
                "Security and Compliance is a shared responsibility between AWS and the customer."));
        out.add(candidate(3, "wellarchitected-reliability-pillar.pdf", 18,
                "RTO is the maximum acceptable delay between the interruption of service and restoration of service."));
        return out;
    }
}
