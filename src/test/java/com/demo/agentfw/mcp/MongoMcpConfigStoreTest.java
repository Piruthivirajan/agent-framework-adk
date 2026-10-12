package com.demo.agentfw.mcp;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Against the local container in a throwaway database; skipped when MongoDB is not reachable. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoMcpConfigStoreTest {

    private static final String URI =
            "mongodb://localhost:27017/?directConnection=true&serverSelectionTimeoutMS=2000";
    private static final McpSecretCipher CIPHER = McpSecretCipher.fromBase64(
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
    private static final McpSecretCipher OTHER = McpSecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));

    private MongoClient client;
    private MongoDatabase db;

    @BeforeAll
    void connect() {
        MongoClient candidate = MongoClients.create(URI);
        try {
            candidate.getDatabase("admin").runCommand(new Document("hello", 1));
        } catch (MongoException e) {
            candidate.close();
            System.out.println("SKIPPED MongoMcpConfigStoreTest: MongoDB not reachable at localhost:27017");
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

    private static McpServerConfig config(String id) {
        return new McpServerConfig(id, "Orders", "https://mcp.example.com/mcp?token=url-secret-77",
                McpTransport.STREAMABLE_HTTP, Map.of("Authorization", "Bearer header-secret-42"), true);
    }

    @Test
    void aSavedServerComesBackIntactAndNothingSecretIsStoredInTheClear() {
        MongoMcpConfigStore store = new MongoMcpConfigStore(db, "mcp_a", CIPHER);
        store.save(config("s1"));

        McpConfigStore.LoadResult loaded = store.loadAll();

        assertThat(loaded.unreadable()).isZero();
        assertThat(loaded.configs()).hasSize(1);
        McpServerConfig c = loaded.configs().get(0);
        assertThat(c.id()).isEqualTo("s1");
        assertThat(c.name()).isEqualTo("Orders");
        assertThat(c.url()).isEqualTo("https://mcp.example.com/mcp?token=url-secret-77");
        assertThat(c.headers()).containsEntry("Authorization", "Bearer header-secret-42");
        assertThat(c.transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        assertThat(c.enabled()).isTrue();

        String raw = db.getCollection("mcp_a").find().first().toJson();
        assertThat(raw).doesNotContain("header-secret-42").doesNotContain("url-secret-77")
                .doesNotContain("Bearer").doesNotContain("mcp.example.com");
        assertThat(raw).contains("Authorization");          // the header name is shown on the page anyway
    }

    @Test
    void savingAgainReplacesTheServerAndDeleteRemovesIt() {
        MongoMcpConfigStore store = new MongoMcpConfigStore(db, "mcp_b", CIPHER);
        store.save(config("s1"));
        store.save(new McpServerConfig("s1", "Renamed", "https://mcp.example.com/mcp", McpTransport.SSE,
                Map.of(), false));

        McpServerConfig c = store.loadAll().configs().get(0);
        assertThat(store.loadAll().configs()).hasSize(1);
        assertThat(c.name()).isEqualTo("Renamed");
        assertThat(c.headers()).isEmpty();
        assertThat(c.enabled()).isFalse();
        assertThat(c.transport()).isEqualTo(McpTransport.SSE);

        store.delete("s1");
        assertThat(store.loadAll().configs()).isEmpty();
    }

    @Test
    void aServerSavedUnderAnotherKeyIsCountedAsUnreadableAndLeftInPlace() {
        new MongoMcpConfigStore(db, "mcp_c", CIPHER).save(config("s1"));
        MongoMcpConfigStore withOtherKey = new MongoMcpConfigStore(db, "mcp_c", OTHER);

        McpConfigStore.LoadResult loaded = withOtherKey.loadAll();

        assertThat(loaded.configs()).isEmpty();
        assertThat(loaded.unreadable()).isEqualTo(1);
        assertThat(db.getCollection("mcp_c").countDocuments()).isEqualTo(1);   // not deleted, not rewritten
        assertThat(new MongoMcpConfigStore(db, "mcp_c", CIPHER).loadAll().configs()).hasSize(1);
    }
}
