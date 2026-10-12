package com.demo.agentfw.api;

import com.demo.agentfw.agents.file.FileAgentPlugin;
import com.demo.agentfw.agents.mcp.McpAgentPlugin;
import com.demo.agentfw.agents.rag.GroundingVerifier;
import com.demo.agentfw.agents.rag.RagAgentPlugin;
import com.demo.agentfw.core.ChatResult;
import com.demo.agentfw.core.StageEvent;
import com.demo.agentfw.retrieval.RetrievalTrace;

import java.util.List;

/**
 * What the browser gets for a turn. The raw model output is deliberately not included: the answer is
 * the verified text, the sources are the verified quotes, and everything about how it came to be is in
 * {@code retrievals}, {@code verification} and {@code stages} for the trace panel.
 *
 * @param kind {@code answer} (verified, cited), {@code tool_answer} (from a connected MCP tool, with the
 *             raw result in {@code mcp}; not checked against the documents), {@code tool_error} (the tool
 *             reported an error), {@code summary} (file summary, not quote-verified),
 *             {@code agent_answer} (an agent added as a plugin; shown as that agent's text, not verified
 *             against the documents), {@code not_found} (the question is not answered by the documents),
 *             {@code notice} (a message
 *             from the system, for example "no file uploaded" or "no route")
 */
public record ChatResponse(
        String kind,
        String answer,
        String route,
        boolean grounded,
        List<GroundingVerifier.Source> sources,
        ChatResult.FileSummary file,
        String rewrittenQuestion,
        ChatResult.Verification verification,
        List<RetrievalTrace> retrievals,
        List<String> toolCalls,
        List<StageEvent> stages,
        ChatResult.McpAnswer mcp,
        String requestId,
        long totalMillis) {

    public static ChatResponse from(ChatResult r) {
        return new ChatResponse(kind(r), r.answer(), r.route(), r.grounded(), r.sources(), r.file(),
                r.rewrittenQuestion(), r.verification(), r.retrievals(), r.toolCalls(), r.stages(), r.mcp(),
                r.requestId(), r.totalMillis());
    }

    static String kind(ChatResult r) {
        if (r.file() != null) {
            return "summary";
        }
        if (r.mcp() != null) {
            return r.mcp().calls().stream().anyMatch(c -> !c.error()) ? "tool_answer" : "tool_error";
        }
        if (r.grounded()) {
            return "answer";
        }
        if (isOtherAgent(r.route()) && r.verification().problems().isEmpty()) {
            return "agent_answer";
        }
        if (r.answer() != null && r.answer().startsWith(GroundingVerifier.NOT_FOUND)) {
            return "not_found";
        }
        return "notice";
    }

    /** A route that is none of the built-in agents: an agent added as a plugin. */
    private static boolean isOtherAgent(String route) {
        return route != null && !route.isBlank() && !"none".equals(route)
                && !RagAgentPlugin.NAME.equals(route) && !FileAgentPlugin.NAME.equals(route)
                && !McpAgentPlugin.NAME.equals(route);
    }
}
