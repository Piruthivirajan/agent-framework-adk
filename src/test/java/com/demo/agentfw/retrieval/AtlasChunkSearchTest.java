package com.demo.agentfw.retrieval;

import com.demo.agentfw.ingestion.ChunkIndexInitializer;
import com.demo.agentfw.ingestion.MongoChunkStore;
import com.demo.agentfw.ingestion.StoredChunk;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three fusion strategies against the local Atlas container, on the three toy chunks from the
 * Day 1 spike, in a throwaway database. Skipped when MongoDB or Atlas Search is not available.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AtlasChunkSearchTest {

    private static final String URI =
            "mongodb://localhost:27017/?directConnection=true&serverSelectionTimeoutMS=2000";
    private static final int DIMS = 3;

    private MongoClient client;
    private MongoDatabase db;
    private AtlasChunkSearch search;

    private static final float[] QUERY_VECTOR = {0.85f, 0.15f, 0.0f};   // closest to the refund chunk

    private static final RetrievalProperties BASE = new RetrievalProperties(
            FusionStrategy.RANK, 20, 3, 3, 3, 3, 1.0, 1.0, "sigmoid", 60, null, null);

    @BeforeAll
    void setUp() throws Exception {
        MongoClient candidate = MongoClients.create(URI);
        try {
            Document hello = candidate.getDatabase("admin").runCommand(new Document("hello", 1));
            assumeTrue(hello.containsKey("setName"), "not a replica set");
        } catch (MongoException e) {
            candidate.close();
            System.out.println("SKIPPED AtlasChunkSearchTest: MongoDB not reachable (" + e.getClass().getSimpleName() + ")");
            assumeTrue(false, "MongoDB not reachable");
        }
        client = candidate;
        db = client.getDatabase("agentfw_test_" + UUID.randomUUID().toString().substring(0, 8));

        MongoChunkStore store = new MongoChunkStore(client, db, "chunks");
        try {
            new ChunkIndexInitializer(store, DIMS).ensureIndexes();
        } catch (MongoException e) {
            System.out.println("SKIPPED AtlasChunkSearchTest: Atlas Search unavailable (" + e.getMessage() + ")");
            assumeTrue(false, "Atlas Search unavailable");
        }
        store.replaceDocument("demo", "d1", List.of(chunk("d1", "Refund requests are processed within 14 days.", 1, new float[]{0.9f, 0.1f, 0.0f})));
        store.replaceDocument("demo", "d2", List.of(chunk("d2", "Invoices are emailed on the first business day.", 3, new float[]{0.1f, 0.9f, 0.0f})));
        store.replaceDocument("demo", "d3", List.of(chunk("d3", "Error code E-401 means the token has expired.", 2, new float[]{0.0f, 0.2f, 0.9f})));
        // another tenant's chunk that would match the query: it must never come back
        store.replaceDocument("other", "x1", List.of(new StoredChunk("other", "x1", 0, "x1.pdf", 1,
                "Refund requests are processed within 14 days for other tenant.", "h", "m", new float[]{0.9f, 0.1f, 0.0f})));

        search = new AtlasChunkSearch(store.collection(), "demo");

        // wait until both indexes are queryable and both see the documents
        long deadline = System.currentTimeMillis() + 180_000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline && !ready) {
            try {
                SearchOutcome o = search.search("refund days", QUERY_VECTOR, BASE);
                ready = o.vectorHits() == 3 && o.textHits() >= 1;
            } catch (MongoException ignored) {
                // index still building
            }
            if (!ready) {
                Thread.sleep(2000);
            }
        }
        assumeTrue(ready, "search indexes did not become ready in time");
    }

    @AfterAll
    void cleanUp() {
        if (client != null) {
            db.drop();
            client.close();
        }
    }

    private static StoredChunk chunk(String doc, String text, int page, float[] embedding) {
        return new StoredChunk("demo", doc, 0, doc + ".pdf", page, text, "hash-" + doc, "test-model", embedding);
    }

    @Test
    void rankFusionPutsTheRefundChunkFirstWithPerPipelineEvidence() {
        SearchOutcome out = search.search("refund days", QUERY_VECTOR, BASE.withFusion(FusionStrategy.RANK));

        Candidate top = out.candidates().get(0);
        assertThat(top.docId()).isEqualTo("d1");
        assertThat(top.vectorRank()).isEqualTo(1);
        assertThat(top.textRank()).isEqualTo(1);
        assertThat(top.fusedRank()).isEqualTo(1);
        assertThat(top.fusedScore()).isCloseTo(2.0 / 61, offset(1e-6));   // same number as the Day 1 spike
        assertThat(out.vectorHits()).isEqualTo(3);
        assertThat(out.textHits()).isLessThan(3);   // d3 has no word in common with the query
        assertThat(out.unionSize()).isEqualTo(3);
        assertThat(out.stageMillis()).containsKey("search_and_fusion");
    }

    @Test
    void scoreFusionAlsoRanksTheRefundChunkFirstAndDerivesRanks() {
        SearchOutcome out = search.search("refund days", QUERY_VECTOR, BASE.withFusion(FusionStrategy.SCORE));

        Candidate top = out.candidates().get(0);
        assertThat(top.docId()).isEqualTo("d1");
        assertThat(top.vectorRank()).isEqualTo(1);
        assertThat(top.textRank()).isEqualTo(1);
        assertThat(top.fusedScore()).isBetween(0.0, 1.0);   // normalized scores averaged
        assertThat(out.candidates().get(0).fusedScore()).isGreaterThan(out.candidates().get(1).fusedScore());
    }

    @Test
    void javaRrfMatchesNativeRankFusionAndReportsEachStage() {
        SearchOutcome nativeOut = search.search("refund days", QUERY_VECTOR, BASE.withFusion(FusionStrategy.RANK));
        SearchOutcome javaOut = search.search("refund days", QUERY_VECTOR, BASE.withFusion(FusionStrategy.JAVA_RRF));

        assertThat(javaOut.candidates()).extracting(Candidate::docId)
                .containsExactlyElementsOf(nativeOut.candidates().stream().map(Candidate::docId).toList());
        for (int i = 0; i < javaOut.candidates().size(); i++) {
            assertThat(javaOut.candidates().get(i).fusedScore())
                    .isCloseTo(nativeOut.candidates().get(i).fusedScore(), offset(1e-6));
        }
        assertThat(javaOut.stageMillis()).containsKeys("vector_search", "text_search", "fusion");
        assertThat(javaOut.vectorHits()).isEqualTo(nativeOut.vectorHits());
        assertThat(javaOut.textHits()).isEqualTo(nativeOut.textHits());
    }

    @Test
    void anotherTenantsChunksAreNeverReturned() {
        for (FusionStrategy strategy : FusionStrategy.values()) {
            SearchOutcome out = search.search("refund days", QUERY_VECTOR, BASE.withFusion(strategy));
            assertThat(out.candidates()).as(strategy.name()).extracting(Candidate::docId).doesNotContain("x1");
        }
    }

    @Test
    void fusedLimitCutsTheListButNotTheHitCounts() {
        RetrievalProperties one = new RetrievalProperties(FusionStrategy.RANK, 20, 3, 1, 1, 1, 1.0, 1.0, "sigmoid", 60, null, null);

        SearchOutcome out = search.search("refund days", QUERY_VECTOR, one);

        assertThat(out.candidates()).hasSize(1);
        assertThat(out.unionSize()).isEqualTo(3);
        assertThat(out.vectorHits()).isEqualTo(3);
    }

    @Test
    void aQueryWithNoLexicalMatchStillGetsVectorCandidates() {
        SearchOutcome out = search.search("zzzz qqqq", QUERY_VECTOR, BASE.withFusion(FusionStrategy.RANK));

        assertThat(out.textHits()).isZero();
        assertThat(out.vectorHits()).isEqualTo(3);
        assertThat(out.candidates()).isNotEmpty();
        assertThat(out.candidates().get(0).textRank()).isNull();
        assertThat(new ArrayList<>(out.candidates()).get(0).docId()).isEqualTo("d1");
    }
}
