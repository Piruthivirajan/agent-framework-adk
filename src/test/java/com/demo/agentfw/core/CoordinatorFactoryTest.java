package com.demo.agentfw.core;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No network: a scripted model plays the coordinator and the agents. */
class CoordinatorFactoryTest {

    /** The smallest possible plugin: one class, nothing else changes. */
    static class DummyPlugin implements AgentPlugin {
        private final String name;
        private final boolean finalAnswer;

        DummyPlugin(String name, boolean finalAnswer) {
            this.name = name;
            this.finalAnswer = finalAnswer;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Handles the " + name + " requests.";
        }

        @Override
        public BaseAgent build(AgentContext context) {
            return LlmAgent.builder().name(name).description(description()).model(context.model())
                    .instruction("Answer briefly.").generateContentConfig(context.generateContentConfig()).build();
        }

        @Override
        public boolean finalAnswer() {
            return finalAnswer;
        }
    }

    private static ModelFactory factoryFor(ScriptedLlm llm) {
        return new ModelFactory() {
            @Override
            public BaseLlm create() {
                return llm;
            }

            @Override
            public String modelName() {
                return "scripted";
            }
        };
    }

    private static LlmProperties props(Integer coordinatorBudget, Integer agentBudget) {
        return new LlmProperties("fake", "scripted", "", coordinatorBudget, agentBudget, 12);
    }

    private static List<Event> ask(InMemoryRunner runner, CoordinatorFactory factory, String question) {
        String session = runner.sessionService().createSession(runner.appName(), "user-1").blockingGet().id();
        return runner.runAsync("user-1", session, Content.fromParts(Part.fromText(question)), factory.runConfig())
                .toList().blockingGet();
    }

    /** Coordinator model: call the first tool it is offered; agent model: answer with fixed text. */
    private static ScriptedLlm routingModel() {
        return new ScriptedLlm().script(req -> {
            boolean coordinator = req.tools().keySet().stream().anyMatch(n -> n.startsWith("dummy"));
            if (!coordinator) {
                return ScriptedLlm.text("agent-answer");
            }
            if (ScriptedLlm.hasToolResult(req)) {
                return ScriptedLlm.text("coordinator-rephrase");
            }
            String first = req.tools().keySet().stream().sorted().findFirst().orElseThrow();
            return ScriptedLlm.call(first, Map.of("request", "the question"));
        });
    }

    @Test
    void coordinatorGetsOneToolPerPluginAndKnowsTheirDescriptions() {
        ScriptedLlm llm = routingModel();
        AgentRegistry registry = new AgentRegistry(List.of(new DummyPlugin("dummy_b", true), new DummyPlugin("dummy_a", true)));
        CoordinatorFactory factory = new CoordinatorFactory(registry, factoryFor(llm), props(null, null));

        ask(factory.runner(), factory, "hello");

        LlmRequest coordinatorRequest = llm.requests().get(0);
        assertThat(coordinatorRequest.tools().keySet()).containsExactlyInAnyOrder("dummy_a", "dummy_b");
        String instruction = String.join("\n", coordinatorRequest.getSystemInstructions());
        assertThat(instruction).contains("dummy_a: Handles the dummy_a requests.")
                .contains("dummy_b: Handles the dummy_b requests.");
        assertThat(instruction.indexOf("dummy_a")).isLessThan(instruction.indexOf("dummy_b"));   // sorted, deterministic
    }

    @Test
    void aThirdAgentIsOneNewClassAndNothingElseChanges() {
        ScriptedLlm llm = routingModel();
        List<AgentPlugin> two = List.of(new DummyPlugin("dummy_rag", true), new DummyPlugin("dummy_file", true));
        List<AgentPlugin> three = new java.util.ArrayList<>(two);
        three.add(new DummyPlugin("dummy_third", true));   // the only change: one more bean

        CoordinatorFactory withTwo = new CoordinatorFactory(new AgentRegistry(two), factoryFor(llm), props(null, null));
        ask(withTwo.runner(), withTwo, "q");
        int toolsWithTwo = llm.requests().get(0).tools().size();
        llm.reset();
        CoordinatorFactory withThree = new CoordinatorFactory(new AgentRegistry(three), factoryFor(llm), props(null, null));
        ask(withThree.runner(), withThree, "q");

        assertThat(toolsWithTwo).isEqualTo(2);
        assertThat(llm.requests().get(0).tools().keySet())
                .containsExactlyInAnyOrder("dummy_rag", "dummy_file", "dummy_third");
    }

    @Test
    void aFinalAnswerAgentEndsTheTurnWithoutAnotherCoordinatorCall() {
        ScriptedLlm llm = routingModel();
        CoordinatorFactory factory = new CoordinatorFactory(
                new AgentRegistry(List.of(new DummyPlugin("dummy_a", true))), factoryFor(llm), props(null, null));

        List<Event> events = ask(factory.runner(), factory, "q");

        long coordinatorCalls = llm.requests().stream().filter(r -> r.tools().containsKey("dummy_a")).count();
        assertThat(coordinatorCalls).isEqualTo(1);
        Event last = events.get(events.size() - 1);
        assertThat(last.functionResponses()).isNotEmpty();
        assertThat(events.stream().map(Event::stringifyContent).collect(Collectors.joining()))
                .doesNotContain("coordinator-rephrase");
    }

    @Test
    void anAgentThatIsNotFinalLetsTheCoordinatorSpeakAgain() {
        ScriptedLlm llm = routingModel();
        CoordinatorFactory factory = new CoordinatorFactory(
                new AgentRegistry(List.of(new DummyPlugin("dummy_a", false))), factoryFor(llm), props(null, null));

        List<Event> events = ask(factory.runner(), factory, "q");

        long coordinatorCalls = llm.requests().stream().filter(r -> r.tools().containsKey("dummy_a")).count();
        assertThat(coordinatorCalls).isEqualTo(2);
        assertThat(events.get(events.size() - 1).stringifyContent()).contains("coordinator-rephrase");
    }

    @Test
    void thinkingBudgetsAreAppliedPerRole() {
        ScriptedLlm llm = routingModel();
        CoordinatorFactory factory = new CoordinatorFactory(
                new AgentRegistry(List.of(new DummyPlugin("dummy_a", true))), factoryFor(llm), props(64, 2048));

        ask(factory.runner(), factory, "q");

        LlmRequest coordinator = llm.requests().stream().filter(r -> r.tools().containsKey("dummy_a")).findFirst().orElseThrow();
        LlmRequest agent = llm.requests().stream().filter(r -> !r.tools().containsKey("dummy_a")).findFirst().orElseThrow();
        assertThat(coordinator.config().orElseThrow().thinkingConfig().orElseThrow().thinkingBudget()).hasValue(64);
        assertThat(agent.config().orElseThrow().thinkingConfig().orElseThrow().thinkingBudget()).hasValue(2048);
    }

    @Test
    void noBudgetLeavesTheModelDefault() {
        ScriptedLlm llm = routingModel();
        CoordinatorFactory factory = new CoordinatorFactory(
                new AgentRegistry(List.of(new DummyPlugin("dummy_a", true))), factoryFor(llm), props(null, null));

        ask(factory.runner(), factory, "q");

        LlmRequest coordinator = llm.requests().get(0);
        assertThat(coordinator.config().flatMap(c -> c.thinkingConfig())).isEmpty();
    }

    @Test
    void runnerIsCachedUntilInvalidated() {
        CoordinatorFactory factory = new CoordinatorFactory(
                new AgentRegistry(List.of(new DummyPlugin("dummy_a", true))), factoryFor(routingModel()), props(null, null));

        InMemoryRunner first = factory.runner();

        assertThat(factory.runner()).isSameAs(first);
        factory.invalidate();
        assertThat(factory.runner()).isNotSameAs(first);
    }

    @Test
    void registryRejectsBadPlugins() {
        assertThatThrownBy(() -> new AgentRegistry(List.of(new DummyPlugin("same", true), new DummyPlugin("same", true))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Two agent plugins");
        assertThatThrownBy(() -> new AgentRegistry(List.of(new DummyPlugin("has space", true))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid agent plugin name");
        AgentPlugin noDescription = new DummyPlugin("silent", true) {
            @Override
            public String description() {
                return " ";
            }
        };
        assertThatThrownBy(() -> new AgentRegistry(List.of(noDescription)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no description");
    }

    @Test
    void geminiFactoryFailsFastWithoutAKeyButOnlyWhenAskedForAModel() {
        GeminiModelFactory factory = new GeminiModelFactory(new LlmProperties("gemini", "gemini-3.8-flash", "", null, null, 12));

        assertThat(factory.modelName()).isEqualTo("gemini-3.8-flash");   // no key needed to construct or describe
        assertThatThrownBy(factory::create).isInstanceOf(IllegalStateException.class).hasMessageContaining("GOOGLE_API_KEY");
    }

    @Test
    void llmPropertiesNeverPrintTheKey() {
        LlmProperties p = new LlmProperties("gemini", "m", "super-secret", 1, 2, 3);
        assertThat(p.toString()).doesNotContain("super-secret");
    }
}
