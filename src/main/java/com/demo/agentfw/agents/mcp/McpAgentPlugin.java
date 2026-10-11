package com.demo.agentfw.agents.mcp;

import com.demo.agentfw.core.AgentContext;
import com.demo.agentfw.core.AgentPlugin;
import com.demo.agentfw.mcp.McpRegistry;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import org.springframework.stereotype.Component;

/**
 * Answers a request with the tools of the connected MCP servers. It exists as a plugin like the other
 * agents, so the coordinator can route to it, and {@code ChatService} can also run it by code after
 * the RAG agent found nothing (the coordinator cannot do that itself: a RAG answer ends its turn).
 *
 * <p>The agent's prose is not trusted as the record of what happened. Every tool call is recorded by
 * code (server, tool, arguments, raw result) and the answer path in {@code ChatService} decides from
 * those records: no tool called means no answer from tools, a tool error is reported as a tool error.
 * The tool list is read when the agent is built; a change of the MCP configuration makes the
 * coordinator rebuild, so a new server is usable at the next message without a restart.
 */
@Component
public class McpAgentPlugin implements AgentPlugin {

    public static final String NAME = "mcp_agent";

    /** What the agent replies when no connected tool fits the request. */
    public static final String NO_TOOL_FITS = "NO_TOOL_FITS";

    static final String INSTRUCTION = """
            You answer the user's request using ONLY the connected tools you are given.
            1. Choose the tool that fits the request and call it with the arguments the request implies. \
            Copy identifiers (order ids, names, dates) exactly as the user wrote them.
            2. If no tool fits the request, or the tools cannot give the answer, reply exactly NO_TOOL_FITS \
            and nothing else. Never answer without calling a tool.
            3. After a tool returns, answer in one or two plain sentences using ONLY what the tool result \
            says. Copy numbers, names and dates exactly. Never add facts from your own knowledge and never \
            guess.
            4. If a tool returns an error, say that the tool reported an error and give its message.""";

    private final McpRegistry registry;

    public McpAgentPlugin(McpRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        if (!registry.hasTools()) {
            return "Uses tools of connected external MCP servers. None is connected right now.";
        }
        return "Answers questions that need data from the connected external tools, which are: "
                + registry.toolSummary() + ". Use it when the question is about data these tools provide "
                + "(for example orders, rosters, tickets, or other live or private records). Do not use it for "
                + "questions about the reference documents or about the user's uploaded files.";
    }

    @Override
    public boolean available() {
        return registry.hasTools();
    }

    @Override
    public BaseAgent build(AgentContext context) {
        return LlmAgent.builder()
                .name(NAME)
                .description(description())
                .model(context.model())
                .instruction(INSTRUCTION)
                .generateContentConfig(context.generateContentConfig())
                .tools(registry.tools())
                .build();
    }
}
