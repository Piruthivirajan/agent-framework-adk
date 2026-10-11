package com.demo.agentfw.core;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingConfig;
import org.springframework.stereotype.Component;

import java.util.List;

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

    public synchronized void invalidate() {
        runner = null;
    }

    public RunConfig runConfig() {
        return RunConfig.builder().maxLlmCalls(props.maxLlmCalls()).build();
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
