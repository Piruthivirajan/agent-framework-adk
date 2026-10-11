package com.demo.agentfw.agents.rag;

import com.demo.agentfw.core.AgentContext;
import com.demo.agentfw.core.CoordinatorFactory;
import com.demo.agentfw.core.ScriptedLlm;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The RAG agent run on its own with a scripted model: tool declaration, tool result, ledger. */
class RagAgentPluginTest {

    private static final String ANSWER = "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"The tool reviews workloads.\","
            + "\"ref\":\"r1c1\",\"quote\":\"provides a consistent process for you to review and measure your workloads\"}]}";

    private final RetrievalLedger ledger = new RetrievalLedger();

    private BaseAgent agent(ScriptedLlm llm, double scoreOfFirst) {
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> scoreOfFirst - i, null), ledger);
        return new RagAgentPlugin(tool).build(new AgentContext(llm, GenerateContentConfig.builder().build()));
    }

    private static List<Event> run(BaseAgent agent, String requestId) {
        InMemoryRunner runner = new InMemoryRunner(agent);
        String session = runner.sessionService().createSession(runner.appName(), "u").blockingGet().id();
        return runner.runAsync("u", session, Content.fromParts(Part.fromText("What is the AWS Well-Architected Tool?")),
                RunConfig.builder().maxLlmCalls(6).build(), Map.of(SearchDocumentsTool.REQUEST_ID_KEY, requestId))
                .toList().blockingGet();
    }

    private static ScriptedLlm searchThenAnswer(String finalText) {
        return new ScriptedLlm().script(req -> ScriptedLlm.hasToolResult(req)
                ? ScriptedLlm.text(finalText)
                : ScriptedLlm.call("searchDocuments", Map.of("query", "AWS Well-Architected Tool")));
    }

    @Test
    void declaresASearchToolWithOnlyAQueryParameter() {
        ScriptedLlm llm = searchThenAnswer(ANSWER);

        run(agent(llm, 5.0), "req-1");

        FunctionDeclaration declaration = llm.requests().get(0).tools().get("searchDocuments").declaration().orElseThrow();
        assertThat(declaration.parameters().orElseThrow().properties().orElseThrow().keySet())
                .containsExactly("query");   // the framework context is injected, never offered to the model
    }

    @Test
    void theModelIsGivenTheChunksWithRefsAndTheLedgerKeepsThem() {
        ScriptedLlm llm = searchThenAnswer(ANSWER);

        List<Event> events = run(agent(llm, 5.0), "req-1");

        LlmRequest followUp = llm.requests().get(1);
        String toolResult = followUp.contents().stream()
                .flatMap(c -> c.parts().orElse(List.of()).stream())
                .filter(p -> p.functionResponse().isPresent())
                .map(p -> p.functionResponse().get().response().orElseThrow().toString())
                .findFirst().orElseThrow();
        assertThat(toolResult).contains("r1c1").contains("wellarchitected-framework.pdf")
                .contains("consistent process for you to review and measure your workloads");
        assertThat(ledger.chunk("req-1", "r1c1")).isPresent();
        assertThat(events.get(events.size() - 1).stringifyContent()).contains("ANSWERED");
    }

    @Test
    void belowTheFloorTheModelOnlyHearsNoRelevantContext() {
        ScriptedLlm llm = searchThenAnswer("{\"status\":\"NOT_FOUND\"}");

        run(agent(llm, -20.0), "req-2");

        String toolResult = llm.requests().get(1).contents().stream()
                .flatMap(c -> c.parts().orElse(List.of()).stream())
                .filter(p -> p.functionResponse().isPresent())
                .map(p -> p.functionResponse().get().response().orElseThrow().toString())
                .findFirst().orElseThrow();
        assertThat(toolResult).contains("NO_RELEVANT_CONTEXT").doesNotContain("consistent process");
        assertThat(ledger.chunk("req-2", "r1c1")).isEmpty();
    }

    @Test
    void theInstructionStatesTheContractTheVerifierDependsOn() {
        ScriptedLlm llm = searchThenAnswer(ANSWER);

        run(agent(llm, 5.0), "req-1");

        String instruction = String.join("\n", llm.requests().get(0).getSystemInstructions());
        assertThat(instruction)
                .contains("at most 3 calls")
                .contains("ONLY the text of the returned chunks")
                .contains("{\"status\":\"NOT_FOUND\"}")
                .contains("\"quote\"")
                .contains("verbatim")
                .contains("Any number in a claim's text must also appear in its quote");
    }

    @Test
    void theAgentIsRegisteredUnderTheNameTheCoordinatorUses() {
        RagAgentPlugin plugin = new RagAgentPlugin(new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 1.0, null), ledger));

        assertThat(plugin.name()).isEqualTo("rag_agent");
        assertThat(plugin.finalAnswer()).isTrue();
        assertThat(plugin.description()).contains("verbatim quote");
        assertThat(CoordinatorFactory.COORDINATOR_NAME).isEqualTo("coordinator");
    }
}
