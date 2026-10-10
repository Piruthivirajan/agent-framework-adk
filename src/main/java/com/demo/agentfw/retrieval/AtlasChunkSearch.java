package com.demo.agentfw.retrieval;

import com.demo.agentfw.AgentFwProperties;
import com.demo.agentfw.ingestion.ChunkIndexInitializer;
import com.demo.agentfw.ingestion.MongoChunkStore;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vector + BM25 search over the chunks collection, fused either natively by Atlas
 * ({@code $rankFusion}, {@code $scoreFusion}) or in Java ({@link JavaRrfFusion}).
 *
 * <p>Native fusion cuts to twice the per-pipeline limit (the largest possible union) before
 * projecting, so every document of both pipelines is present and the per-pipeline hit counts read
 * from {@code scoreDetails} are exact. Only then is the list cut to {@code fusedLimit}.
 */
@Component
public class AtlasChunkSearch implements ChunkSearch {

    private final MongoCollection<Document> chunks;
    private final String tenantId;

    @Autowired
    public AtlasChunkSearch(MongoChunkStore store, AgentFwProperties app) {
        this(store.collection(), app.tenantId());
    }

    public AtlasChunkSearch(MongoCollection<Document> chunks, String tenantId) {
        this.chunks = chunks;
        this.tenantId = tenantId;
    }

    @Override
    public SearchOutcome search(String query, float[] queryVector, RetrievalProperties s) {
        return s.fusion() == FusionStrategy.JAVA_RRF
                ? javaRrf(query, queryVector, s)
                : nativeFusion(query, queryVector, s);
    }

    // ---- native: one aggregation, fusion inside Atlas -------------------------------------------

    private SearchOutcome nativeFusion(String query, float[] queryVector, RetrievalProperties s) {
        Document pipelines = new Document()
                .append("vector", List.of(vectorStage(queryVector, s)))
                .append("text", List.of(textStage(query), new Document("$limit", s.perPipelineLimit())));
        Document weights = new Document("vector", s.vectorWeight()).append("text", s.textWeight());

        Document fusion;
        if (s.fusion() == FusionStrategy.SCORE) {
            fusion = new Document("$scoreFusion", new Document()
                    .append("input", new Document("pipelines", pipelines).append("normalization", s.normalization()))
                    .append("combination", new Document("weights", weights))
                    .append("scoreDetails", true));
        } else {
            fusion = new Document("$rankFusion", new Document()
                    .append("input", new Document("pipelines", pipelines))
                    .append("combination", new Document("weights", weights))
                    .append("scoreDetails", true));
        }

        long t0 = System.nanoTime();
        List<Document> rows = chunks.aggregate(List.of(
                fusion,
                new Document("$limit", 2 * s.perPipelineLimit()),
                new Document("$project", new Document("file", 1).append("page", 1).append("docId", 1)
                        .append("chunkIndex", 1).append("text", 1)
                        .append("score", new Document("$meta", "score"))
                        .append("details", new Document("$meta", "scoreDetails"))))).into(new ArrayList<>());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        List<Row> parsed = new ArrayList<>();
        for (Document d : rows) {
            parsed.add(parseNative(d, s.fusion()));
        }
        if (s.fusion() == FusionStrategy.SCORE) {
            rankByRawScore(parsed);
        }
        int vectorHits = (int) parsed.stream().filter(r -> r.vectorScore != null).count();
        int textHits = (int) parsed.stream().filter(r -> r.textScore != null).count();

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < parsed.size() && i < s.fusedLimit(); i++) {
            candidates.add(parsed.get(i).toCandidate(i + 1));
        }
        return new SearchOutcome(s.fusion(), candidates, vectorHits, textHits, parsed.size(),
                Map.of("search_and_fusion", ms));
    }

    private static Row parseNative(Document d, FusionStrategy strategy) {
        Row r = Row.of(d);
        r.fusedScore = ((Number) d.get("score")).doubleValue();
        Document details = d.get("details", Document.class);
        List<Document> perPipeline = details == null ? List.of() : details.getList("details", Document.class, List.of());
        for (Document p : perPipeline) {
            String name = p.getString("inputPipelineName");
            Integer rank = null;
            Double raw = null;
            if (strategy == FusionStrategy.RANK) {
                Object rk = p.get("rank");
                if (rk instanceof Number n) {          // "NA" when the pipeline did not return the chunk
                    rank = n.intValue();
                    raw = ((Number) p.get("value")).doubleValue();
                }
            } else {
                Object rawScore = p.get("inputPipelineRawScore");
                if (rawScore instanceof Number n && n.doubleValue() > 0) {   // 0 means "not in this pipeline"
                    raw = n.doubleValue();
                }
            }
            if ("vector".equals(name)) {
                r.vectorRank = rank;
                r.vectorScore = raw;
            } else if ("text".equals(name)) {
                r.textRank = rank;
                r.textScore = raw;
            }
        }
        return r;
    }

    /** {@code $scoreFusion} reports raw scores, not ranks; derive each pipeline's rank from them. */
    private static void rankByRawScore(List<Row> rows) {
        List<Row> byVector = rows.stream().filter(r -> r.vectorScore != null)
                .sorted(Comparator.comparingDouble((Row r) -> r.vectorScore).reversed()).toList();
        for (int i = 0; i < byVector.size(); i++) {
            byVector.get(i).vectorRank = i + 1;
        }
        List<Row> byText = rows.stream().filter(r -> r.textScore != null)
                .sorted(Comparator.comparingDouble((Row r) -> r.textScore).reversed()).toList();
        for (int i = 0; i < byText.size(); i++) {
            byText.get(i).textRank = i + 1;
        }
    }

    // ---- Java RRF: two queries, fusion here -----------------------------------------------------

    private SearchOutcome javaRrf(String query, float[] queryVector, RetrievalProperties s) {
        long t0 = System.nanoTime();
        List<Document> vectorRows = chunks.aggregate(List.of(
                vectorStage(queryVector, s),
                projection("vectorSearchScore"))).into(new ArrayList<>());
        long vectorMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        List<Document> textRows = chunks.aggregate(List.of(
                textStage(query),
                new Document("$limit", s.perPipelineLimit()),
                projection("searchScore"))).into(new ArrayList<>());
        long textMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        Map<String, Row> byId = new LinkedHashMap<>();
        List<String> vectorIds = new ArrayList<>();
        List<String> textIds = new ArrayList<>();
        for (int i = 0; i < vectorRows.size(); i++) {
            Document d = vectorRows.get(i);
            Row r = byId.computeIfAbsent(idOf(d), k -> Row.of(d));
            r.vectorRank = i + 1;
            r.vectorScore = ((Number) d.get("score")).doubleValue();
            vectorIds.add(idOf(d));
        }
        for (int i = 0; i < textRows.size(); i++) {
            Document d = textRows.get(i);
            Row r = byId.computeIfAbsent(idOf(d), k -> Row.of(d));
            r.textRank = i + 1;
            r.textScore = ((Number) d.get("score")).doubleValue();
            textIds.add(idOf(d));
        }
        List<JavaRrfFusion.Scored> fused = JavaRrfFusion.fuse(
                Map.of("vector", vectorIds, "text", textIds),
                Map.of("vector", s.vectorWeight(), "text", s.textWeight()), s.rrfK());
        long fusionMs = (System.nanoTime() - t0) / 1_000_000;

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < fused.size() && i < s.fusedLimit(); i++) {
            Row r = byId.get(fused.get(i).id());
            r.fusedScore = fused.get(i).score();
            candidates.add(r.toCandidate(i + 1));
        }
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("vector_search", vectorMs);
        timings.put("text_search", textMs);
        timings.put("fusion", fusionMs);
        return new SearchOutcome(FusionStrategy.JAVA_RRF, candidates, vectorRows.size(), textRows.size(),
                fused.size(), timings);
    }

    // ---- shared stages --------------------------------------------------------------------------

    private Document vectorStage(float[] queryVector, RetrievalProperties s) {
        List<Double> vector = new ArrayList<>(queryVector.length);
        for (float f : queryVector) {
            vector.add((double) f);
        }
        return new Document("$vectorSearch", new Document()
                .append("index", ChunkIndexInitializer.VECTOR_INDEX)
                .append("path", "embedding")
                .append("queryVector", vector)
                .append("numCandidates", s.numCandidates())
                .append("limit", s.perPipelineLimit())
                .append("filter", new Document("tenantId", tenantId)));
    }

    private Document textStage(String query) {
        return new Document("$search", new Document("index", ChunkIndexInitializer.TEXT_INDEX)
                .append("compound", new Document()
                        .append("must", List.of(new Document("text",
                                new Document("query", query).append("path", "text"))))
                        .append("filter", List.of(new Document("equals",
                                new Document("path", "tenantId").append("value", tenantId))))));
    }

    private static Document projection(String scoreMeta) {
        return new Document("$project", new Document("file", 1).append("page", 1).append("docId", 1)
                .append("chunkIndex", 1).append("text", 1)
                .append("score", new Document("$meta", scoreMeta)));
    }

    private static String idOf(Document d) {
        return d.get("_id", ObjectId.class).toHexString();
    }

    /** Mutable working copy of one result row. */
    private static final class Row {
        String chunkId;
        String docId;
        String file;
        int page;
        int chunkIndex;
        String text;
        Integer vectorRank;
        Double vectorScore;
        Integer textRank;
        Double textScore;
        double fusedScore;

        static Row of(Document d) {
            Row r = new Row();
            r.chunkId = idOf(d);
            r.docId = d.getString("docId");
            r.file = d.getString("file");
            r.page = d.getInteger("page");
            r.chunkIndex = d.getInteger("chunkIndex");
            r.text = d.getString("text");
            return r;
        }

        Candidate toCandidate(int fusedRank) {
            return new Candidate(chunkId, docId, file, page, chunkIndex, text,
                    vectorRank, vectorScore, textRank, textScore, fusedScore, fusedRank);
        }
    }
}
