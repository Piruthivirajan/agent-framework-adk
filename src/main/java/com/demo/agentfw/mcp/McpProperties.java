package com.demo.agentfw.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param allowedHosts host names or IP addresses that may be used as MCP server addresses even though
 *                     they resolve to a private, loopback or link-local network (for example the demo
 *                     MCP server, {@code demo-mcp} in Docker Compose, or {@code localhost} on a laptop).
 *                     Matched against the host exactly as typed in the URL, case-insensitively. Empty by
 *                     default: only public addresses are accepted.
 */
@ConfigurationProperties("agentfw.mcp")
public record McpProperties(@DefaultValue({}) List<String> allowedHosts) {

    public McpProperties {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream()
                .map(String::strip).filter(h -> !h.isEmpty()).toList();
    }
}
