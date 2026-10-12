package com.demo.agentfw.mcp;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which store the application gets: encrypted MongoDB with a valid key, memory only otherwise. */
class McpStoreConfigurationTest {

    private final McpStoreConfiguration configuration = new McpStoreConfiguration();

    private static MongoTemplate template() {
        MongoTemplate template = mock(MongoTemplate.class);
        when(template.getDb()).thenReturn(mock(MongoDatabase.class));
        return template;
    }

    @Test
    void noKeyMeansMemoryOnlyAndTheNoteNamesTheVariable() {
        McpConfigStore store = configuration.mcpConfigStore(new McpSecretProperties(""), template());

        assertThat(store.persistent()).isFalse();
        assertThat(store.note()).contains("memory only").contains("AGENTFW_MCP_SECRET_KEY");
    }

    @Test
    void anInvalidKeyIsNotUsedAndNothingIsWrittenUnencrypted() {
        McpConfigStore store = configuration.mcpConfigStore(new McpSecretProperties("not-a-key"), template());

        assertThat(store.persistent()).isFalse();
        assertThat(store.note()).contains("not a valid key");
        assertThat(store.note()).doesNotContain("not-a-key");
    }

    @Test
    void aValidKeyGivesTheEncryptedMongoStore() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        McpConfigStore store = configuration.mcpConfigStore(new McpSecretProperties(key), template());

        assertThat(store).isInstanceOf(MongoMcpConfigStore.class);
        assertThat(store.persistent()).isTrue();
        assertThat(store.note()).contains("encrypted").doesNotContain(key);
    }
}
