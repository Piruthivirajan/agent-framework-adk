package com.demo.agentfw.core;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.InMemoryRunner;
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
 */
@Component
public class CoordinatorFactory {

    public static final String COORDINATOR_NAME = "coordinator";

    private final AgentRegistry registry;
    private final ModelFactory models;
    private final LlmProperties props;
    private InMemoryRunner runner;
    private final Map<String, InMemoryRunner> agentRunners = new HashMap<>();

    public CoordinatorFactory(AgentRegistry registry, ModelFactory models, LlmProperties props) {
        this.registry = registry;
        this.models = models;
        this.props = props;
    }

    public synchronized InMemoryRunner runner() {
        if (runner == null) {
            runner = new InMemoryRunner(build());
        }
        return runner;
    }

    /**
     * A runner whose root is one plugin's agent, for the code rule that sends an attachment straight to
     * the file agent without a coordinator model call.
     */
    public synchronized InMemoryRunner agentRunner(String pluginName) {
        return agentRunners.computeIfAbsent(pluginName, name -> {
            AgentPlugin plugin = registry.plugins().stream().filter(p -> p.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No agent plugin named '" + name + "'"));
            return new InMemoryRunner(plugin.build(agentContext()));
        });
    }

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
                .model(model)
                .instruction(instruction(registry.plugins()))
                .generateContentConfig(config(props.coordinatorThinkingBudget()))
                .tools(registry.agentTools(agentContext))
                .build();
    }

    static String instruction(List<AgentPlugin> plugins) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a router. For every user message, call exactly one of the agents below and pass the ")
                .append("user's message unchanged as the request. Do not answer yourself and do not use your own ")
                .append("knowledge; if no agent seems to fit, call the closest one anyway.\n\nAgents:\n");
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
