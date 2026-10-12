package com.demo.agentfw.mcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The registry with a persistent store (a map standing in for MongoDB) and the real demo MCP server. */
class McpRegistryPersistenceTest {

    private static final String TOKEN = "persist-test-token-9";

    private static DemoMcpServer server;
    private static String url;

    @BeforeAll
    static void start() throws IOException {
        server = new DemoMcpServer(0, TOKEN);
        server.start();
        url = "http://127.0.0.1:" + server.port() + "/mcp";
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    /** What the Mongo store does, without the database. */
    private static final class MapStore implements McpConfigStore {
        final Map<String, McpServerConfig> saved = new LinkedHashMap<>();
        boolean failSaves;
        int unreadable;
        boolean failLoads;
        int loads;

        @Override
        public boolean persistent() {
            return true;
        }

        @Override
        public String note() {
            return "Saved servers are stored in the database, encrypted.";
        }

        @Override
        public LoadResult loadAll() {
            loads++;
            if (failLoads) {
                throw new IllegalStateException("database down");
            }
            return new LoadResult(new ArrayList<>(saved.values()), unreadable);
        }

        @Override
        public void save(McpServerConfig config) {
            if (failSaves) {
                throw new IllegalStateException("database down");
            }
            saved.put(config.id(), config);
        }

        @Override
        public void delete(String id) {
            if (failSaves) {
                throw new IllegalStateException("database down");
            }
            saved.remove(id);
        }
    }

    private final List<Object> events = new ArrayList<>();

    private McpRegistry registry(MapStore store) {
        return new McpRegistry(events::add, new McpCallLedger(), McpUrlGuard.allowAll(), store);
    }

    private static McpServerConfig demo() {
        return new McpServerConfig(null, "Orders", url, McpTransport.STREAMABLE_HTTP,
                Map.of("Authorization", "Bearer " + TOKEN), true);
    }

    @Test
    void aSavedServerIsStoredAndTheNextStartConnectsItWithoutTheUser() {
        MapStore store = new MapStore();
        McpRegistry first = registry(store);
        String id = first.add(demo()).id();
        assertThat(store.saved.get(id).headers()).containsEntry("Authorization", "Bearer " + TOKEN);

        events.clear();
        McpRegistry afterRestart = registry(store);          // a new process: empty memory, same database
        afterRestart.restoreSaved();

        assertThat(afterRestart.list()).hasSize(1);
        McpRegistry.ServerView view = afterRestart.list().get(0);
        assertThat(view.id()).isEqualTo(id);
        assertThat(view.status()).isEqualTo("CONNECTED");
        assertThat(view.tools()).extracting(McpRegistry.ToolInfo::name).contains("lookup_order");
        assertThat(afterRestart.hasTools()).isTrue();
        assertThat(events).hasSize(1).first().isInstanceOf(McpRegistry.McpConfigChanged.class);
        assertThat(afterRestart.storage().persistent()).isTrue();
    }

    @Test
    void restoringTwiceDoesNotDuplicateServers() {
        MapStore store = new MapStore();
        registry(store).add(demo());
        McpRegistry r = registry(store);

        r.restoreSaved();
        r.restoreSaved();

        assertThat(r.list()).hasSize(1);
        assertThat(store.loads).isEqualTo(1);
    }

    @Test
    void updatesAndDeletesAreWrittenToTheStoreToo() {
        MapStore store = new MapStore();
        McpRegistry r = registry(store);
        String id = r.add(demo()).id();

        r.update(id, "Orders v2", null, null, Map.of("Authorization", "Bearer " + TOKEN, "X-Team", "ops"), null);
        assertThat(store.saved.get(id).name()).isEqualTo("Orders v2");
        assertThat(store.saved.get(id).headers()).containsKey("X-Team");

        r.update(id, null, null, null, null, false);          // headers null: the stored ones are kept
        assertThat(store.saved.get(id).enabled()).isFalse();
        assertThat(store.saved.get(id).headers()).containsEntry("Authorization", "Bearer " + TOKEN);

        r.remove(id);
        assertThat(store.saved).isEmpty();
    }

    @Test
    void ifTheDatabaseRefusesTheWriteNothingChangesInMemory() {
        MapStore store = new MapStore();
        McpRegistry r = registry(store);
        store.failSaves = true;

        assertThatThrownBy(() -> r.add(demo())).isInstanceOf(IllegalStateException.class);
        assertThat(r.list()).isEmpty();

        store.failSaves = false;
        String id = r.add(demo()).id();
        store.failSaves = true;
        assertThatThrownBy(() -> r.remove(id)).isInstanceOf(IllegalStateException.class);
        assertThat(r.list()).hasSize(1);
    }

    @Test
    void anUnreachableDatabaseAtStartIsRetriedLater() {
        MapStore store = new MapStore();
        registry(store).add(demo());
        McpRegistry r = registry(store);
        store.failLoads = true;

        r.restoreSaved();
        assertThat(r.storage().loaded()).isFalse();

        store.failLoads = false;
        r.restoreSaved();
        assertThat(r.list()).hasSize(1);
        assertThat(r.storage().loaded()).isTrue();
    }

    @Test
    void serversThatCouldNotBeDecryptedAreReportedByCountOnly() {
        MapStore store = new MapStore();
        store.unreadable = 2;
        McpRegistry r = registry(store);

        r.restoreSaved();

        assertThat(r.storage().unreadable()).isEqualTo(2);
        assertThat(r.list()).isEmpty();
    }

    @Test
    void withoutAKeyTheRegistryIsMemoryOnlyAndSaysSo() {
        McpRegistry r = new McpRegistry(events::add, new McpCallLedger());

        McpRegistry.StorageInfo info = r.storage();

        assertThat(info.persistent()).isFalse();
        assertThat(info.note()).contains("memory only").contains("AGENTFW_MCP_SECRET_KEY");
        r.restoreSaved();                                       // harmless
        assertThat(r.list()).isEmpty();
    }

    @Test
    void noViewOrStorageInfoEverContainsACredentialValue() {
        MapStore store = new MapStore();
        McpRegistry r = registry(store);
        r.add(demo());

        String everything = r.list() + " " + r.storage();

        assertThat(everything).doesNotContain(TOKEN).doesNotContain("Bearer");
    }
}
