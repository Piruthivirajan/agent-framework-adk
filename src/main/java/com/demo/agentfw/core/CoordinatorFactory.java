package com.demo.agentfw.core;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.models.BaseLlm;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingConfig;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the coordinator from the registered plugins and caches its runner. The coordinator only
 * routes: it is told to call one agent with the user's message and never to answer itself, and the
 * application ignores its prose anyway (see {@link ChatService}).
 *
 * <p>The runner is built on first use, so the application starts without a model key, and
 * {@link #invalidate()} makes the next request rebuild it (the hook for runtime tool changes).
 *
 * <p>All runners share one session service, so a rebuild changes the tools and not the conversation:
 * a session keeps its history across {@code invalidate()}. A session is keyed by application name, user
 * and session id, and the application name of a runner is the name of its root agent, which is the same
 * before and after a rebuild.
 */
@Component
public class CoordinatorFactory {

    public static final String COORDINATOR_NAME = "coordinator";

    private final AgentRegistry registry;
    private final ModelFactory models;
    private final LlmProperties props;
    private final BaseSessionService sessions = new InMemorySessionService();
    private Runner runner;
    private final Map<String, Runner> agentRunners = new HashMap<>();

    public CoordinatorFactory(AgentRegistry registry, ModelFactory models, LlmProperties props) {
        this.registry = registry;
        this.models = models;
        this.props = props;
    }

    public synchronized Runner runner() {
        if (runner == null) {
            runner = newRunner(build());
        }
        return runner;
    }

    /** A runner on the shared session service (what {@code InMemoryRunner} does, except the shared part). */
    private Runner newRunner(BaseAgent root) {
        return new Runner(root, root.name(), new InMemoryArtifactService(), sessions, new InMemoryMemoryService());
    }

    /**
     * A runner whose root is one plugin's agent, for the code rule that sends an attachment straight to
     * the file agent without a coordinator model call.
     */
    public synchronized Runner agentRunner(String pluginName) {
        return agentRunners.computeIfAbsent(pluginName, name -> {
            AgentPlugin plugin = registry.plugins().stream().filter(p -> p.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No agent plugin named '" + name + "'"));
            return newRunner(plugin.build(agentContext()));
        });
    }

    /**
     * The MCP configuration changed: the next request builds the coordinator with the new tools. The
     * sessions are kept (see the class comment), so a follow-up after the change still has its context.
     */
    @org.springframework.context.event.EventListener(com.demo.agentfw.mcp.McpRegistry.McpConfigChanged.class)
    public synchronized void invalidate() {
        runner = null;
        agentRunners.clear();
    }

    public RunConfig runConfig() {
        return RunConfig.builder().maxLlmCalls(props.maxLlmCalls()).build();
    }

    private AgentContext agentContext() {
        return new AgentContext(models.create(), config(props.agentThinkingBudget()));
    }

    LlmAgent build() {
        BaseLlm model = models.create();
        AgentContext agentContext = new AgentContext(model, config(props.agentThinkingBudget()));
        return LlmAgent.builder()
                .name(COORDINATOR_NAME)
                .description("Routes each user message to the right agent")
                .model(models.createForRouting())
                .instruction(instruction(registry.activePlugins()))
                .generateContentConfig(config(props.coordinatorThinkingBudget()))
                .tools(registry.agentTools(agentContext))
                .build();
    }

    static String instruction(List<AgentPlugin> plugins) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a router. For every user message, call exactly one of the agents below. Do not ")
                .append("answer yourself and do not use your own knowledge; if no agent seems to fit, call the ")
                .append("closest one anyway.\n\n")
                .append("The request you pass must make sense on its own, because the agent cannot see this ")
                .append("conversation. If the user's latest message points back to the conversation (it, that, ")
                .append("they, \"shorter\", \"and the cost one?\"), restate it as one complete message that names ")
                .append("what it points to, using only the conversation. If the message already names its own ")
                .append("subject, pass it exactly unchanged, even if the conversation was about something else; ")
                .append("never add the conversation's topic or file names to it, and never add facts.\n\n")
                .append("Agents:\n");
        for (AgentPlugin p : plugins) {
            sb.append("- ").append(p.name()).append(": ").append(p.description()).append('\n');
        }
        return sb.toString();
    }

    /** Generation settings with an explicit thinking budget, or the model default when it is null. */
    static GenerateContentConfig config(Integer thinkingBudget) {
        GenerateContentConfig.Builder b = GenerateContentConfig.builder();
        if (thinkingBudget != null) {
            b.thinkingConfig(ThinkingConfig.builder().thinkingBudget(thinkingBudget).build());
        }
        return b.build();
    }
}
