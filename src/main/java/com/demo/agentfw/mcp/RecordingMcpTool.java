package com.demo.agentfw.mcp;

import com.demo.agentfw.core.TurnState;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Wraps an MCP tool so that every call is recorded in the {@link McpCallLedger} and a failure becomes
 * a result the model can see instead of an exception that ends the turn. The wrapped tool still does
 * the call, with the credentials of its own connection; this class never sees them.
 *
 * <p>The exposed name can differ from the server's tool name (a prefix is added when two servers have
 * a tool of the same name); the call goes to the server under its own name.
 */
final class RecordingMcpTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(RecordingMcpTool.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BaseTool delegate;
    private final McpServerConfig server;
    private final String serverToolName;
    private final McpCallLedger ledger;

    RecordingMcpTool(BaseTool delegate, McpServerConfig server, String exposedName, McpCallLedger ledger) {
        super(exposedName, delegate.description());
        this.delegate = delegate;
        this.server = server;
        this.serverToolName = delegate.name();
        this.ledger = ledger;
    }

    String serverToolName() {
        return serverToolName;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return delegate.declaration().map(d -> d.toBuilder().name(name()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        Object id = toolContext.state().get(TurnState.REQUEST_ID);
        String requestId = id == null ? "" : id.toString();
        long start = System.nanoTime();
        return delegate.runAsync(args, toolContext).map(result -> {
            boolean error = looksLikeError(result);
            ledger.record(requestId, new McpCallLedger.Call(server.id(), server.name(), serverToolName, args,
                    json(result), error, error ? errorText(result) : null, millis(start)));
            return result;
        }).onErrorReturn(e -> {
            McpFailure failure = McpFailure.classify(e, server.safeUrl());
            log.warn("mcp tool {} on {} failed: {}", serverToolName, server.name(), failure.code());
            ledger.record(requestId, new McpCallLedger.Call(server.id(), server.name(), serverToolName, args, "",
                    true, failure.message(), millis(start)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", failure.message());
            return out;
        });
    }

    /** MCP reports a tool-level failure as {@code isError: true} in the result. */
    static boolean looksLikeError(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get("isError")) || result.containsKey("error");
    }

    static String errorText(Map<String, Object> result) {
        Object e = result.get("error");
        return e != null ? e.toString() : text(result);
    }

    /** The human-readable text of a result: the text parts of {@code content}, else the raw map. */
    @SuppressWarnings("unchecked")
    static String text(Map<String, Object> result) {
        Object content = result.get("content");
        if (content instanceof Iterable<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                if (p instanceof Map<?, ?> m && m.get("text") != null) {
                    sb.append(m.get("text")).append('\n');
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString().strip();
            }
        }
        return json(result);
    }

    private static String json(Map<String, Object> result) {
        String s;
        try {
            s = JSON.writeValueAsString(result);
        } catch (RuntimeException e) {
            s = String.valueOf(result);
        }
        return s.length() > McpCallLedger.MAX_RESULT_CHARS ? s.substring(0, McpCallLedger.MAX_RESULT_CHARS) + "..." : s;
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
