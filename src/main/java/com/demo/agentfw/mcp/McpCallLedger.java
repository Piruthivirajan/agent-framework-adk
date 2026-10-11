package com.demo.agentfw.mcp;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every MCP tool call of a turn, kept in code under the turn's request id: which server and tool, with
 * which arguments, what came back raw, and how long it took. The answer path and the trace panel read
 * it from here; the model's prose is never the record of what a tool returned.
 */
@Component
public class McpCallLedger {

    /**
     * @param result  the raw tool result as JSON text, cut at {@link #MAX_RESULT_CHARS}
     * @param error   true when the call failed or the tool reported an error
     * @param message for an error: the tool's own message or a short description
     */
    public record Call(String serverId, String serverName, String tool, Map<String, Object> arguments,
                       String result, boolean error, String message, long millis) {
    }

    public static final int MAX_RESULT_CHARS = 6000;

    private final Map<String, List<Call>> calls = new ConcurrentHashMap<>();

    public void record(String requestId, Call call) {
        calls.computeIfAbsent(requestId, id -> new CopyOnWriteArrayList<>()).add(call);
    }

    public List<Call> calls(String requestId) {
        List<Call> list = calls.get(requestId);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    public void clear(String requestId) {
        calls.remove(requestId);
    }
}
