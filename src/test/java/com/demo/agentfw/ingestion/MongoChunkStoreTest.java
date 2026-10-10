package com.demo.agentfw.ingestion;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against the local Atlas container (docker compose up -d), in a throwaway
 * database that is dropped afterwards. If MongoDB is not reachable the tests are skipped, so
 * {@code ./mvnw verify} still works without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoChunkStoreTest {

    private static final String URI =
            "mongodb://localhost:27017/?directConnection=true&serverSelectionTimeoutMS=2000";
    private static final int DIMS = 8;

    private MongoClient client;
    private MongoDatabase db;
    private boolean replicaSet;
    private boolean searchSupported;
    private MongoChunkStore store;

    @BeforeAll
    void connect() {
        MongoClient candidate = MongoClients.create(URI);
        try {
            Document hello = candidate.getDatabase("admin").runCommand(new Document("hello", 1));
            replicaSet = hello.containsKey("setName");
        } catch (MongoException e) {
            candidate.close();
            System.out.println("SKIPPED MongoChunkStoreTest: MongoDB not reachable at localhost:27017 (" +
                    e.getClass().getSimpleName() + "). Start it with: docker compose up -d");
            assumeTrue(false, "MongoDB not reachable");
            return;
        }
        client = candidate;
        db = client.getDatabase("agentfw_test_" + UUID.randomUUID().toString().substring(0, 8));
        assumeTrue(replicaSet, "MongoDB is not a replica set, transactions unavailable");

        try {
            db.createCollection("probe");
            db.getCollection("probe").listSearchIndexes().into(new ArrayList<>());
            searchSupported = true;
        } catch (MongoException e) {
            searchSupported = false;
        }
    }

    @AfterAll
    void cleanUp() {
        if (client != null) {
            db.drop();
            client.close();
        }
    }

    @BeforeEach
    void freshCollection() {
        store = new MongoChunkStore(client, db, "chunks");
        store.collection().drop();
        store.ensureSchema();
    }

    private static StoredChunk chunk(String doc, int index, String hash) {
        float[] v = new float[DIMS];
        v[index % DIMS] = 1f;
        return new StoredChunk("demo", doc, index, doc + ".pdf", 1 + index / 2, "text " + doc + " " + index,
                hash, "gemini-embedding-2", v);
    }

    @Test
    void storesChunksWithTheDocumentedSchema() {
        store.replaceDocument("demo", "handbook", List.of(chunk("handbook", 0, "h1"), chunk("handbook", 1, "h1")));

        Document stored = store.collection().find(Filters.eq("chunkIndex", 1)).first();
        assertThat(stored).isNotNull();
        assertThat(stored.keySet()).contains("tenantId", "docId", "chunkIndex", "file", "page", "text",
                "contentHash", "embeddingModel", "embedding");
        assertThat(stored.getString("docId")).isEqualTo("handbook");
        assertThat(stored.getList("embedding", Double.class)).hasSize(DIMS);

        assertThat(store.docStates("demo").get("handbook"))
                .isEqualTo(new ChunkStore.DocState(Set.of("h1"), Set.of("gemini-embedding-2"), 2));
    }

    @Test
    void replaceSwapsTheWholeDocumentAndLeavesOthersAlone() {
        store.replaceDocument("demo", "a", List.of(chunk("a", 0, "a1"), chunk("a", 1, "a1"), chunk("a", 2, "a1")));
        store.replaceDocument("demo", "b", List.of(chunk("b", 0, "b1")));

        store.replaceDocument("demo", "a", List.of(chunk("a", 0, "a2")));

        var states = store.docStates("demo");
        assertThat(states.get("a")).isEqualTo(new ChunkStore.DocState(Set.of("a2"), Set.of("gemini-embedding-2"), 1));
        assertThat(states.get("b").chunkCount()).isEqualTo(1);
    }

    @Test
    void failedReplaceRollsBackAndKeepsTheOldChunks() {
        store.replaceDocument("demo", "a", List.of(chunk("a", 0, "old"), chunk("a", 1, "old")));

        // two chunks with the same index violate the unique index, mid-transaction
        List<StoredChunk> broken = List.of(chunk("a", 0, "new"), chunk("a", 0, "new"));
        assertThatThrownBy(() -> store.replaceDocument("demo", "a", broken)).isInstanceOf(MongoException.class);

        assertThat(store.docStates("demo").get("a"))
                .isEqualTo(new ChunkStore.DocState(Set.of("old"), Set.of("gemini-embedding-2"), 2));
    }

    @Test
    void uniqueIndexRejectsDuplicateChunkPositions() {
        store.replaceDocument("demo", "a", List.of(chunk("a", 0, "h")));

        assertThatThrownBy(() -> store.collection().insertOne(MongoChunkStore.toDocument(chunk("a", 0, "h"))))
                .isInstanceOf(MongoException.class)
                .hasMessageContaining("E11000");
    }

    @Test
    void deleteDocumentsIsScopedToTenantAndDocIds() {
        store.replaceDocument("demo", "a", List.of(chunk("a", 0, "h"), chunk("a", 1, "h")));
        store.replaceDocument("demo", "b", List.of(chunk("b", 0, "h")));
        StoredChunk other = new StoredChunk("other", "a", 0, "a.pdf", 1, "t", "h", "m", new float[DIMS]);
        store.replaceDocument("other", "a", List.of(other));

        long deleted = store.deleteDocuments("demo", Set.of("a"));

        assertThat(deleted).isEqualTo(2);
        assertThat(store.docStates("demo")).containsOnlyKeys("b");
        assertThat(store.docStates("other")).containsOnlyKeys("a");
        assertThat(store.deleteDocuments("demo", Set.of())).isZero();
    }

    @Test
    void ensureSchemaIsIdempotent() {
        store.ensureSchema();
        store.ensureSchema();
        assertThat(store.collection().listIndexes().into(new ArrayList<>())).hasSize(2);   // _id + unique
    }

    @Test
    void searchIndexesAreCreatedOnceAndDimensionMismatchIsRejected() {
        assumeTrue(searchSupported, "Atlas Search is not available on this MongoDB");

        ChunkIndexInitializer initializer = new ChunkIndexInitializer(store, DIMS);
        initializer.ensureIndexes();
        initializer.ensureIndexes();   // second call must not create duplicates or fail

        List<Document> indexes = store.collection().listSearchIndexes().into(new ArrayList<>());
        assertThat(indexes).extracting(d -> d.getString("name"))
                .containsExactlyInAnyOrder(ChunkIndexInitializer.VECTOR_INDEX, ChunkIndexInitializer.TEXT_INDEX);

        assertThatThrownBy(() -> new ChunkIndexInitializer(store, DIMS + 1).ensureIndexes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("numDimensions=" + DIMS)
                .hasMessageContaining("re-ingest");
    }
}
