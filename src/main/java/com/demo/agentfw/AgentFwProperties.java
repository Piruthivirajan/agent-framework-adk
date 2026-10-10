package com.demo.agentfw;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cross-cutting settings. {@code tenantId} scopes every chunk and every query; the demo runs a
 * single tenant, but the field is in the schema and the vector index filter from day one.
 */
@ConfigurationProperties("agentfw")
public record AgentFwProperties(@DefaultValue("demo") String tenantId) {
}
