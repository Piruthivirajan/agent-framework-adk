package com.demo.agentfw.files;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Against the local container in a throwaway database; skipped when MongoDB is not reachable. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoUploadStoreTest {

    private static final String URI =
            "mongodb://localhost:27017/?directConnection=true&serverSelectionTimeoutMS=2000";

    private MongoClient client;
    private MongoDatabase db;

    @BeforeAll
    void connect() {
        MongoClient candidate = MongoClients.create(URI);
        try {
            candidate.getDatabase("admin").runCommand(new Document("hello", 1));
        } catch (MongoException e) {
            candidate.close();
            System.out.println("SKIPPED MongoUploadStoreTest: MongoDB not reachable at localhost:27017");
            assumeTrue(false, "MongoDB not reachable");
            return;
        }
        client = candidate;
        db = client.getDatabase("agentfw_test_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterAll
    void cleanUp() {
        if (client != null) {
            db.drop();
            client.close();
        }
    }

    private static UploadedFile file(String id, String session, String name, String text) {
        return new UploadedFile(id, session, name, "text/plain", text.length(), null, text, Instant.now());
    }

    @Test
    void savesFindsOnlyWithinTheSessionAndListsWithoutText() {
        MongoUploadStore store = new MongoUploadStore(db, "uploads_a", 24);
        store.save(file("f1", "tab-1", "a.txt", "alpha text"));
        store.save(file("f2", "tab-1", "b.txt", "beta text"));
        store.save(file("f3", "tab-2", "c.txt", "gamma text"));

        assertThat(store.find("tab-1", "f1")).get().extracting(UploadedFile::text).isEqualTo("alpha text");
        assertThat(store.find("tab-2", "f1")).isEmpty();
        assertThat(store.summaries("tab-1")).extracting(UploadedFile.Summary::name).containsExactly("a.txt", "b.txt");
        assertThat(store.summaries("tab-1").get(0).chars()).isEqualTo(10);
    }

    @Test
    void createsATtlIndexMatchingTheConfiguredLifetimeAndReplacesItWhenItChanges() {
        new MongoUploadStore(db, "uploads_b", 24).save(file("f1", "s", "a.txt", "text"));
        assertThat(ttlSeconds("uploads_b")).isEqualTo(24 * 3600);

        new MongoUploadStore(db, "uploads_b", 2).ensureSchema();     // configuration changed
        assertThat(ttlSeconds("uploads_b")).isEqualTo(2 * 3600);
    }

    private long ttlSeconds(String collection) {
        for (Document index : db.getCollection(collection).listIndexes().into(new ArrayList<>())) {
            if ("uploads_ttl".equals(index.getString("name"))) {
                return ((Number) index.get("expireAfterSeconds")).longValue();
            }
        }
        throw new AssertionError("no TTL index on " + collection);
    }
}
