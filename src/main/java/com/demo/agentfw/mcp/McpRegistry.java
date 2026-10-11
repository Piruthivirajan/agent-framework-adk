package com.demo.agentfw.mcp;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.SseServerParameters;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The MCP servers the user configured in the settings page, and their loaded tools.
 *
 * <p><b>In memory only.</b> Nothing is written to the database or to a file, nothing is encrypted:
 * configuration is lost when the application restarts. That is a demo trade-off, stated in the UI; the
 * credentials therefore exist in exactly one place, this map, and the views returned by this class
 * contain header names but never values. Credentials are never logged ({@link McpServerConfig#toString}
 * hides them and failure messages are composed from a fixed vocabulary).
 *
 * <p>Tools are loaded when a server is saved or tested, and a {@link McpConfigChanged} event makes the
 * coordinator rebuild with the new tools on its next request: no restart.
 */
@Component
public class McpRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpRegistry.class);

    /** A change of the configuration or of the loaded tools; the coordinator rebuilds on it. */
    public record McpConfigChanged() {
    }

    public record ToolInfo(String name, String description) {
    }

    public record TestResult(boolean ok, List<ToolInfo> tools, McpFailure error) {
    }

    /** What the API shows for a server. {@code headerNames}, never values. */
    public record ServerView(String id, String name, String url, McpTransport transport, List<String> headerNames,
                             boolean enabled, String status, List<ToolInfo> tools, McpFailure error,
                             Instant checkedAt) {
    }

    private static final class Entry {
        McpServerConfig config;
        McpToolset toolset;
        List<BaseTool> tools = List.of();
        McpFailure error;
        Instant checkedAt;
    }

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_SERVERS = 10;

    private final ApplicationEventPublisher events;
    private final McpCallLedger ledger;
    private final McpUrlGuard guard;
    private final Map<String, Entry> servers = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public McpRegistry(ApplicationEventPublisher events, McpCallLedger ledger, McpUrlGuard guard) {
        this.events = events;
        this.ledger = ledger;
        this.guard = guard;
    }

    /** Without the address guard (tests that connect to a server on localhost). */
    public McpRegistry(ApplicationEventPublisher events, McpCallLedger ledger) {
        this(events, ledger, McpUrlGuard.allowAll());
    }

    /** The URL is for an address the application must not call (see {@link McpUrlGuard}). */
    public static class McpBlockedException extends IllegalArgumentException {
        public McpBlockedException(String message) {
            super(message);
        }
    }

    private void requireAllowed(McpServerConfig c) {
        guard.check(c.url()).ifPresent(f -> {
            throw new McpBlockedException(f.message());
        });
    }

    // ---- configuration (called by the settings API) ----------------------------------------------

    /** Connects without saving anything; used by "Test connection" before the form is saved. */
    public TestResult test(McpServerConfig config) {
        config.validate();
        Optional<McpFailure> blocked = guard.check(config.url());
        if (blocked.isPresent()) {
            return new TestResult(false, List.of(), blocked.get());
        }
        Connected c = null;
        try {
            c = connect(config);
            return new TestResult(true, infos(c.tools), null);
        } catch (McpConnectException e) {
            return new TestResult(false, List.of(), e.failure);
        } finally {
            if (c != null) {
                close(c.toolset);
            }
        }
    }

    /** Saves a server and loads its tools now. A server that cannot be reached is saved with its error. */
    public ServerView add(McpServerConfig input) {
        input.validate();
        requireAllowed(input);
        synchronized (servers) {
            if (servers.size() >= MAX_SERVERS) {
                throw new IllegalArgumentException("At most " + MAX_SERVERS + " MCP servers can be configured.");
            }
            String id = UUID.randomUUID().toString().substring(0, 8);
            Entry e = new Entry();
            e.config = new McpServerConfig(id, input.name().strip(), input.url().strip(), input.transport(),
                    input.headers(), input.enabled());
            servers.put(id, e);
            reconnect(e);
            publish();
            return view(e);
        }
    }

    /**
     * Updates a server. {@code headers == null} keeps the stored credentials (the form never receives
     * them back, so an empty form field must not wipe them); a map replaces them.
     */
    public ServerView update(String id, String name, String url, McpTransport transport, Map<String, String> headers,
                             Boolean enabled) {
        synchronized (servers) {
            Entry e = require(id);
            McpServerConfig old = e.config;
            McpServerConfig next = new McpServerConfig(id, name == null ? old.name() : name.strip(),
                    url == null ? old.url() : url.strip(), transport == null ? old.transport() : transport,
                    headers == null ? old.headers() : headers, enabled == null ? old.enabled() : enabled);
            next.validate();
            requireAllowed(next);
            e.config = next;
            reconnect(e);
            publish();
            return view(e);
        }
    }

    /** Connects again (the "Test" button of a saved server). */
    public ServerView refresh(String id) {
        synchronized (servers) {
            Entry e = require(id);
            reconnect(e);
            publish();
            return view(e);
        }
    }

    public void remove(String id) {
        synchronized (servers) {
            Entry e = require(id);
            close(e.toolset);
            servers.remove(id);
            publish();
        }
    }

    public List<ServerView> list() {
        synchronized (servers) {
            List<ServerView> out = new ArrayList<>();
            servers.values().forEach(e -> out.add(view(e)));
            return out;
        }
    }

    // ---- what the agents use ---------------------------------------------------------------------

    /** True when at least one enabled server is connected and offers a tool. */
    public boolean hasTools() {
        synchronized (servers) {
            return servers.values().stream().anyMatch(e -> e.config.enabled() && e.error == null
                    && !e.tools.isEmpty());
        }
    }

    /** The tools of every connected, enabled server, recording each call in the ledger. */
    public List<BaseTool> tools() {
        synchronized (servers) {
            Map<String, Integer> counts = new HashMap<>();
            servers.values().stream().filter(e -> e.config.enabled() && e.error == null)
                    .forEach(e -> e.tools.forEach(t -> counts.merge(t.name(), 1, Integer::sum)));
            List<BaseTool> out = new ArrayList<>();
            for (Entry e : servers.values()) {
                if (!e.config.enabled() || e.error != null) {
                    continue;
                }
                for (BaseTool t : e.tools) {
                    String exposed = counts.get(t.name()) > 1 ? slug(e.config.name()) + "_" + t.name() : t.name();
                    out.add(new RecordingMcpTool(t, e.config, exposed, ledger));
                }
            }
            return out;
        }
    }

    /** A sentence listing the available tools, for the coordinator's view of the agent. */
    public String toolSummary() {
        List<String> parts = new ArrayList<>();
        for (BaseTool t : tools()) {
            String d = t.description() == null ? "" : t.description().strip().replaceAll("\\s+", " ");
            parts.add(t.name() + (d.isEmpty() ? "" : " (" + (d.length() > 110 ? d.substring(0, 110) + "..." : d) + ")"));
            if (parts.size() >= 12) {
                break;
            }
        }
        return String.join("; ", parts);
    }

    @Override
    public void destroy() {
        synchronized (servers) {
            servers.values().forEach(e -> close(e.toolset));
        }
    }

    // ---- connection ------------------------------------------------------------------------------

    private record Connected(McpToolset toolset, List<BaseTool> tools) {
    }

    private static final class McpConnectException extends RuntimeException {
        final McpFailure failure;

        McpConnectException(McpFailure failure, Throwable cause) {
            super(failure.message(), cause);
            this.failure = failure;
        }
    }

    private void reconnect(Entry e) {
        close(e.toolset);
        e.toolset = null;
        e.tools = List.of();
        e.error = null;
        e.checkedAt = Instant.now();
        if (!e.config.enabled()) {
            return;
        }
        try {
            Connected c = connect(e.config);
            e.toolset = c.toolset;
            e.tools = c.tools;
            log.info("mcp server {} connected: {} tools", e.config, c.tools.size());
        } catch (McpConnectException ex) {
            e.error = ex.failure;
            log.warn("mcp server {} unavailable: {}", e.config, ex.failure.code());
        }
    }

    private Connected connect(McpServerConfig c) {
        Optional<McpFailure> blocked = guard.check(c.url());     // again: DNS or the allowlist may have changed
        if (blocked.isPresent()) {
            throw new McpConnectException(blocked.get(), null);
        }
        Optional<McpFailure> problem = McpProbe.check(c, Duration.ofSeconds(5), Duration.ofSeconds(8));
        if (problem.isPresent()) {
            throw new McpConnectException(problem.get(), null);
        }
        McpToolset toolset = null;
        try {
            toolset = c.transport() == McpTransport.SSE ? new McpToolset(sseParams(c)) : new McpToolset(streamableParams(c));
            McpToolset ts = toolset;
            CompletableFuture<List<BaseTool>> loading = CompletableFuture.supplyAsync(
                    () -> ts.getTools(null).toList().blockingGet());
            List<BaseTool> tools = loading.get(CONNECT_TIMEOUT.toMillis() + 5_000, TimeUnit.MILLISECONDS);
            return new Connected(toolset, tools);
        } catch (TimeoutException ex) {
            close(toolset);
            throw new McpConnectException(new McpFailure(McpFailure.TIMEOUT,
                    "The MCP server did not answer in time. It may be down or too slow."), ex);
        } catch (ExecutionException | RuntimeException ex) {
            close(toolset);
            Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            throw new McpConnectException(McpFailure.classify(cause, c.safeUrl()), cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            close(toolset);
            throw new McpConnectException(new McpFailure(McpFailure.OTHER, "The connection was interrupted."), ex);
        }
    }

    private static StreamableHttpServerParameters streamableParams(McpServerConfig c) {
        return StreamableHttpServerParameters.builder().url(c.url().strip()).headers(c.headers())
                .timeout(CONNECT_TIMEOUT).readTimeout(READ_TIMEOUT).build();
    }

    private static SseServerParameters sseParams(McpServerConfig c) {
        URI u = URI.create(c.url().strip());
        String base = u.getScheme() + "://" + u.getRawAuthority();
        String endpoint = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        Map<String, Object> headers = new LinkedHashMap<>(c.headers());
        return SseServerParameters.builder().url(base).sseEndpoint(endpoint).headers(headers)
                .timeout(CONNECT_TIMEOUT).sseReadTimeout(READ_TIMEOUT).build();
    }

    private static void close(McpToolset toolset) {
        if (toolset == null) {
            return;
        }
        try {
            toolset.close();
        } catch (RuntimeException e) {
            log.debug("closing an mcp toolset failed: {}", e.toString());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Entry require(String id) {
        Entry e = servers.get(id);
        if (e == null) {
            throw new NoSuchElementException("That MCP server is not configured (any more).");
        }
        return e;
    }

    /** Thrown for an unknown server id; the API maps it to 404. */
    public static class NoSuchElementException extends RuntimeException {
        public NoSuchElementException(String message) {
            super(message);
        }
    }

    private ServerView view(Entry e) {
        String status = !e.config.enabled() ? "DISABLED" : e.error != null ? "ERROR" : "CONNECTED";
        return new ServerView(e.config.id(), e.config.name(), e.config.safeUrl(), e.config.transport(),
                new ArrayList<>(e.config.headers().keySet()), e.config.enabled(), status, infos(e.tools), e.error,
                e.checkedAt);
    }

    private static List<ToolInfo> infos(List<BaseTool> tools) {
        List<ToolInfo> out = new ArrayList<>();
        tools.forEach(t -> out.add(new ToolInfo(t.name(), t.description() == null ? "" : t.description())));
        return out;
    }

    private void publish() {
        events.publishEvent(new McpConfigChanged());
    }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "server" : s;
    }
}
