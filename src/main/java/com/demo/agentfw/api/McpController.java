package com.demo.agentfw.api;

import com.demo.agentfw.mcp.McpRegistry;
import com.demo.agentfw.mcp.McpServerConfig;
import com.demo.agentfw.mcp.McpTransport;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The MCP settings API. Credentials go in (the {@code headers} map) and are never returned: every
 * response is a {@link McpRegistry.ServerView} with header <em>names</em> only. Saving a server loads
 * its tools at once, and the coordinator picks them up on the next message.
 */
@RestController
@RequestMapping("/api/mcp/servers")
class McpController {

    /** The body of a create or a test. {@code transport} is {@code STREAMABLE_HTTP} or {@code SSE}. */
    record ServerRequest(String name, String url, McpTransport transport, Map<String, String> headers,
                         Boolean enabled) {
        McpServerConfig toConfig() {
            return new McpServerConfig(null, name, url, transport, headers, enabled == null || enabled);
        }
    }

    /** The body of an update. A null field is left as it is; null {@code headers} keeps the credentials. */
    record UpdateRequest(String name, String url, McpTransport transport, Map<String, String> headers,
                         Boolean enabled) {
    }

    private final McpRegistry registry;

    McpController(McpRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    List<McpRegistry.ServerView> list() {
        return registry.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    McpRegistry.ServerView add(@RequestBody ServerRequest request) {
        return registry.add(request.toConfig());
    }

    /** Connects with the given settings and lists the tools, without saving anything. */
    @PostMapping("/test")
    McpRegistry.TestResult test(@RequestBody ServerRequest request) {
        return registry.test(request.toConfig());
    }

    /** Connects a saved server again and returns its new state. */
    @PostMapping("/{id}/test")
    McpRegistry.ServerView retest(@PathVariable String id) {
        return registry.refresh(id);
    }

    @PutMapping("/{id}")
    McpRegistry.ServerView update(@PathVariable String id, @RequestBody UpdateRequest request) {
        return registry.update(id, request.name(), request.url(), request.transport(), request.headers(),
                request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String id) {
        registry.remove(id);
    }
}
