package com.demo.agentfw.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Chooses the store: MongoDB with encryption when a valid key is configured, memory only otherwise.
 * It never falls back to writing credentials unencrypted.
 */
@Configuration(proxyBeanMethods = false)
class McpStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpStoreConfiguration.class);

    @Bean
    McpConfigStore mcpConfigStore(McpSecretProperties props, MongoTemplate template) {
        if (props.secretKey().isEmpty()) {
            log.info("mcp config store: memory only (AGENTFW_MCP_SECRET_KEY is not set)");
            return new MemoryOnlyMcpConfigStore(MemoryOnlyMcpConfigStore.NO_KEY_NOTE);
        }
        try {
            McpSecretCipher cipher = McpSecretCipher.fromBase64(props.secretKey());
            log.info("mcp config store: MongoDB collection {}, values encrypted", MongoMcpConfigStore.COLLECTION);
            return new MongoMcpConfigStore(template.getDb(), cipher);
        } catch (IllegalArgumentException e) {
            log.warn("mcp config store: memory only, AGENTFW_MCP_SECRET_KEY is invalid: {}", e.getMessage());
            return new MemoryOnlyMcpConfigStore(MemoryOnlyMcpConfigStore.BAD_KEY_NOTE);
        }
    }
}
