package com.demo.agentfw.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secretKey base64 of 16, 24 or 32 random bytes (from {@code AGENTFW_MCP_SECRET_KEY}); encrypts the
 *                  stored MCP credentials. Empty: saved servers stay in memory and the settings page says so.
 */
@ConfigurationProperties("agentfw.mcp")
public record McpSecretProperties(@DefaultValue("") String secretKey) {

    public McpSecretProperties {
        secretKey = secretKey == null ? "" : secretKey.strip();
    }

    /** Never prints the key (a record's default toString would). */
    @Override
    public String toString() {
        return "McpSecretProperties[secretKey=" + (secretKey.isEmpty() ? "(not set)" : "(set)") + "]";
    }
}
