package com.demo.agentfw.retrieval;

import com.demo.agentfw.ingestion.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The retrieval funnel: embed the question, search and fuse (20), rerank (depth), keep the top 5,
 * and decide whether that is good enough to answer from.
 *
 * <p>Every stage is timed and recorded in the {@link RetrievalTrace}, and logs one line with
 * counts and milliseconds (never the question text or passages), which is what answers "a user got
 * no answer, why?".
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int SNIPPET_CHARS = 160;

    private final EmbeddingService embeddings;
    private final ChunkSearch search;
    private final Reranker reranker;
    private final RetrievalProperties defaults;

    public RetrievalService(EmbeddingService embeddings, ChunkSearch search, Reranker reranker,
                            RetrievalProperties defaults) {
        this.embeddings = embeddings;
        this.search = search;
        this.reranker = reranker;
        this.defaults = defaults;
        if (defaults.minRerankScore() == null) {
            log.warn("agentfw.retrieval.min-rerank-score is not set: the retrieval floor is NOT calibrated and "
                    + "will report NOT_CALIBRATED. Run the calibration (docs/CALIBRATION.md) and set it.");
        }
    }

    public RetrievalResult retrieve(String query) {
        return retrieve(query, defaults);
    }

    /** Same as {@link #retrieve(String)} with explicit settings; the eval and calibration use this. */
    public RetrievalResult retrieve(String query, RetrievalProperties s) {
        long start = System.nanoTime();

        long t0 = System.nanoTime();
        float[] queryVector = embeddings.embedQuery(query);
        long embedMs = millisSince(t0);
        log.info("retrieval stage=embed_query ms={}", embedMs);

        SearchOutcome found = search.search(query, queryVector, s);
        log.info("retrieval stage=search strategy={} vectorHits={} textHits={} union={} fused={} ms={}",
                found.strategy(), found.vectorHits(), found.textHits(), found.unionSize(),
                found.candidates().size(), found.stageMillis());

        List<Candidate> pool = found.candidates().subList(0, Math.min(s.rerankDepth(), found.candidates().size()));
        long rerankMs = 0;
        int truncated = 0;
        List<RetrievalTrace.ScoredPassage> scored = List.of();
        List<RetrievedChunk> top = List.of();
        if (!pool.isEmpty()) {
            t0 = System.nanoTime();
            Reranker.Result r = reranker.rerank(query, pool.stream().map(Candidate::text).toList());
            rerankMs = millisSince(t0);
            truncated = r.truncatedPairs();

            List<Integer> order = new ArrayList<>(IntStream.range(0, pool.size()).boxed().toList());
            order.sort(Comparator.comparingDouble((Integer i) -> r.scores()[i]).reversed()
                    .thenComparingInt(i -> pool.get(i).fusedRank()));
            scored = new ArrayList<>();
            for (int rank = 0; rank < order.size(); rank++) {
                Candidate c = pool.get(order.get(rank));
                scored.add(new RetrievalTrace.ScoredPassage(rank + 1, c.fusedRank(), c.file(), c.page(),
                        c.chunkIndex(), snippet(c.text()), r.scores()[order.get(rank)]));
            }
            top = new ArrayList<>();
            for (int rank = 0; rank < Math.min(s.finalTopN(), order.size()); rank++) {
                Candidate c = pool.get(order.get(rank));
                top.add(new RetrievedChunk(c.file(), c.page(), c.chunkIndex(), c.text(), r.scores()[order.get(rank)]));
            }
        }
        Double topScore = top.isEmpty() ? null : top.get(0).rerankScore();
        log.info("retrieval stage=rerank pairs={} truncated={} topScore={} ms={}",
                pool.size(), truncated, topScore, rerankMs);

        GroundingDecision decision = GroundingGate.decide(topScore, s.minRerankScore());
        Double margin = (topScore != null && s.minRerankScore() != null) ? topScore - s.minRerankScore() : null;
        boolean highConfidence = topScore != null && s.highConfidenceScore() != null
                && topScore >= s.highConfidenceScore();
        log.info("retrieval stage=grounding decision={} topScore={} floor={} margin={} highConfidence={}",
                decision, topScore, s.minRerankScore(), margin, highConfidence);

        RetrievalTrace trace = new RetrievalTrace(
                query,
                new RetrievalTrace.Config(s.fusion().name(), s.numCandidates(), s.perPipelineLimit(), s.fusedLimit(),
                        s.rerankDepth(), s.finalTopN(), s.vectorWeight(), s.textWeight(), s.normalization(),
                        s.minRerankScore(), s.highConfidenceScore(), embeddings.modelName(), embeddings.dimensions(),
                        reranker.modelName()),
                embedMs,
                new RetrievalTrace.Search(found.strategy().name(), found.vectorHits(), found.textHits(),
                        found.unionSize(), found.stageMillis(),
                        found.candidates().stream().map(RetrievalService::toTrace).toList()),
                new RetrievalTrace.Rerank(reranker.modelName(), pool.size(), truncated, rerankMs, scored, top.size()),
                new RetrievalTrace.Grounding(topScore, s.minRerankScore(), margin, decision, highConfidence),
                millisSince(start));

        List<RetrievedChunk> context = decision == GroundingDecision.NO_RELEVANT_CONTEXT ? List.of() : top;
        return new RetrievalResult(decision, context, trace);
    }

    private static RetrievalTrace.FusedCandidate toTrace(Candidate c) {
        return new RetrievalTrace.FusedCandidate(c.fusedRank(), c.file(), c.page(), c.chunkIndex(), snippet(c.text()),
                c.fusedScore(), c.vectorRank(), c.vectorScore(), c.textRank(), c.textScore());
    }

    private static String snippet(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }

    private static long millisSince(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000;
    }
}
