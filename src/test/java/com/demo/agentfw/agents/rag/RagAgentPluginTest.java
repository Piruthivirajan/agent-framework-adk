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
        return agent(llm, scoreOfFirst, RagProperties.defaults());
    }

    private BaseAgent agent(ScriptedLlm llm, double scoreOfFirst, RagProperties props) {
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> scoreOfFirst - i, null), ledger, props);
        return new RagAgentPlugin(tool, props).build(new AgentContext(llm, GenerateContentConfig.builder().build()));
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

    /** The model request that already carries the search result (the first one when the search is prefetched). */
    private static LlmRequest requestWithToolResult(ScriptedLlm llm) {
        return llm.requests().stream().filter(ScriptedLlm::hasToolResult).findFirst().orElseThrow();
    }

    private static final String QUESTION = "What is the AWS Well-Architected Tool?";

    @Test
    void theFirstSearchIsRunInCodeWithTheRequestAsTheQueryAndTheModelIsCalledOnlyOnce() {
        List<String> queries = new java.util.ArrayList<>();
        ScriptedLlm llm = searchThenAnswer(ANSWER);
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, queries::add), ledger,
                RagProperties.defaults());
        BaseAgent agent = new RagAgentPlugin(tool, RagProperties.defaults())
                .build(new AgentContext(llm, GenerateContentConfig.builder().build()));

        List<Event> events = run(agent, "req-1");

        assertThat(queries).containsExactly(QUESTION);                 // the request text, not the model's wording
        assertThat(llm.requests()).hasSize(1);                          // the "call the tool" model call was skipped
        // the call that was not made by the model carries the placeholder signature Gemini 3 requires
        var injectedCall = llm.requests().get(0).contents().stream()
                .flatMap(c -> c.parts().orElse(List.of()).stream())
                .filter(p -> p.functionCall().isPresent()).findFirst().orElseThrow();
        assertThat(new String(injectedCall.thoughtSignature().orElseThrow(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("skip_thought_signature_validator");
        assertThat(ScriptedLlm.hasToolResult(llm.requests().get(0))).isTrue();
        assertThat(ledger.chunk("req-1", "r1c1")).isPresent();         // same refs, same ledger as before
        assertThat(events.get(events.size() - 1).stringifyContent()).contains("ANSWERED");
    }

    @Test
    void withoutPrefetchTheModelWritesTheFirstQueryAsBefore() {
        List<String> queries = new java.util.ArrayList<>();
        ScriptedLlm llm = searchThenAnswer(ANSWER);
        RagProperties props = new RagProperties(2, true, "", false);
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, queries::add), ledger, props);
        BaseAgent agent = new RagAgentPlugin(tool, props)
                .build(new AgentContext(llm, GenerateContentConfig.builder().build()));

        run(agent, "req-1");

        assertThat(queries).containsExactly("AWS Well-Architected Tool");
        assertThat(llm.requests()).hasSize(2);
    }

    @Test
    void theModelMaySearchOnceMoreAfterThePrefetchedSearchButNotBeyondTheCap() {
        List<String> queries = new java.util.ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm().script(req -> ScriptedLlm.call("searchDocuments",
                Map.of("query", "keep searching")));                        // never answers
        SearchDocumentsTool tool = new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, queries::add), ledger,
                RagProperties.defaults());
        BaseAgent agent = new RagAgentPlugin(tool, RagProperties.defaults())
                .build(new AgentContext(llm, GenerateContentConfig.builder().build()));

        try {
            run(agent, "req-1");
        } catch (RuntimeException expectedLoopGuard) {
            // the model never answers; the call limit ends the run
        }

        assertThat(queries).containsExactly(QUESTION, "keep searching");   // prefetched search + one more, then refused
    }

    @Test
    void aBrokenSearchIsNotRetriedInALoopByThePrefetch() {
        ScriptedLlm llm = new ScriptedLlm().script(req -> ScriptedLlm.text("{\"status\":\"NOT_FOUND\"}"));
        com.demo.agentfw.retrieval.ChunkSearch broken = (query, vector, settings) -> {
            throw new IllegalStateException("database down");
        };
        com.demo.agentfw.retrieval.RetrievalService retrieval = new com.demo.agentfw.retrieval.RetrievalService(
                new com.demo.agentfw.ingestion.FakeEmbeddingService(8, "fake"), broken,
                new com.demo.agentfw.retrieval.Reranker() {
                    @Override
                    public Result rerank(String query, List<String> passages) {
                        return new Result(new float[passages.size()], 0);
                    }

                    @Override
                    public String modelName() {
                        return "unused";
                    }
                },
                new com.demo.agentfw.retrieval.RetrievalProperties(com.demo.agentfw.retrieval.FusionStrategy.RANK,
                        400, 20, 20, 12, 5, 1.0, 1.0, "sigmoid", 60, -6.5, 3.3));
        SearchDocumentsTool tool = new SearchDocumentsTool(retrieval, ledger, RagProperties.defaults());
        BaseAgent agent = new RagAgentPlugin(tool, RagProperties.defaults())
                .build(new AgentContext(llm, GenerateContentConfig.builder().build()));

        run(agent, "req-9");

        assertThat(ledger.failure("req-9")).isPresent();
        assertThat(llm.requests()).hasSize(1);       // the model was called once, after the failed search
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

        LlmRequest followUp = requestWithToolResult(llm);
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

        String toolResult = requestWithToolResult(llm).contents().stream()
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
                .contains("at most 2 calls")
                .contains("ONLY the text of the returned chunks")
                .contains("{\"status\":\"NOT_FOUND\"}")
                .contains("\"quote\"")
                .contains("verbatim")
                .contains("Every number in a claim's text must appear in that claim's quote")
                .contains("EC2 or Route 53");
    }

    @Test
    void theSearchLimitInTheInstructionIsTheConfiguredOne() {
        ScriptedLlm llm = searchThenAnswer(ANSWER);

        run(agent(llm, 5.0, new RagProperties(4, false, "", false)), "req-1");

        String instruction = String.join("\n", llm.requests().get(0).getSystemInstructions());
        assertThat(instruction).contains("at most 4 calls").doesNotContain("at most 2 calls")
                .contains("SEARCH_LIMIT_REACHED");
    }

    @Test
    void theInstructionSuffixIsAppendedOnlyWhenConfigured() {
        ScriptedLlm plain = searchThenAnswer(ANSWER);
        ScriptedLlm suffixed = searchThenAnswer(ANSWER);

        run(agent(plain, 5.0), "req-1");
        run(agent(suffixed, 5.0, new RagProperties(2, true, "EXPERIMENT SUFFIX", false)), "req-2");

        assertThat(String.join("\n", plain.requests().get(0).getSystemInstructions()))
                .doesNotContain("EXPERIMENT SUFFIX");
        assertThat(String.join("\n", suffixed.requests().get(0).getSystemInstructions()))
                .contains("EXPERIMENT SUFFIX");
    }

    @Test
    void theAgentIsRegisteredUnderTheNameTheCoordinatorUses() {
        RagAgentPlugin plugin = new RagAgentPlugin(new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 1.0, null), ledger,
                RagProperties.defaults()), RagProperties.defaults());

        assertThat(plugin.name()).isEqualTo("rag_agent");
        assertThat(plugin.finalAnswer()).isTrue();
        assertThat(plugin.description()).contains("verbatim quote");
        assertThat(CoordinatorFactory.COORDINATOR_NAME).isEqualTo("coordinator");
    }
}
