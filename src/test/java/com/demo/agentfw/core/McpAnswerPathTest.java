package com.demo.agentfw.core;

import com.demo.agentfw.agents.file.FileAgentPlugin;
import com.demo.agentfw.agents.file.FileSummaryLedger;
import com.demo.agentfw.agents.mcp.McpAgentPlugin;
import com.demo.agentfw.agents.rag.GroundingVerifier;
import com.demo.agentfw.agents.rag.MalformedAnswerRepair;
import com.demo.agentfw.agents.rag.RagAgentPlugin;
import com.demo.agentfw.agents.rag.RagProperties;
import com.demo.agentfw.agents.rag.RetrievalLedger;
import com.demo.agentfw.agents.rag.SearchDocumentsTool;
import com.demo.agentfw.agents.rag.TestRetrieval;
import com.demo.agentfw.files.FileProperties;
import com.demo.agentfw.files.InMemoryUploadStore;
import com.demo.agentfw.files.MapReduceSummarizer;
import com.demo.agentfw.files.SummaryProperties;
import com.demo.agentfw.files.TextExtractor;
import com.demo.agentfw.files.UploadService;
import com.demo.agentfw.mcp.DemoMcpServer;
import com.demo.agentfw.mcp.McpCallLedger;
import com.demo.agentfw.mcp.McpRegistry;
import com.demo.agentfw.mcp.McpServerConfig;
import com.demo.agentfw.mcp.McpTransport;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP answer path end to end: a real MCP client and the demo server, a scripted model. It proves
 * who answered is read from the call records, that "not found in the documents, then the tools" happens
 * in code, and that tools configured at runtime are used without a restart.
 */
class McpAnswerPathTest {

    private static final String TOKEN = "path-test-token";
    private static final TextExtractor EXTRACTOR = new TextExtractor();

    private static DemoMcpServer server;
    private static String url;

    @BeforeAll
    static void startServer() throws IOException {
        server = new DemoMcpServer(0, TOKEN);
        server.start();
        url = "http://127.0.0.1:" + server.port() + "/mcp";
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    private static final class Rig {
        final ScriptedLlm llm = new ScriptedLlm();
        final McpCallLedger mcpLedger = new McpCallLedger();
        final McpRegistry registry;
        final CoordinatorFactory coordinators;
        final ChatService chat;
        final AtomicReference<String> coordinatorTool = new AtomicReference<>("mcp_agent");
        final AtomicReference<String> ragFinal = new AtomicReference<>("{\"status\":\"NOT_FOUND\"}");
        final AtomicReference<String> mcpTool = new AtomicReference<>("lookup_order");
        final AtomicReference<Map<String, Object>> mcpArgs = new AtomicReference<>(Map.of("order_id", "ORD-1001"));
        final AtomicReference<String> mcpFinal = new AtomicReference<>(
                "Order ORD-1001 has shipped with BlueFreight and is due on 2026-10-14.");
        final List<LlmRequest> coordinatorRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<LlmRequest> mcpAgentRequests = new java.util.concurrent.CopyOnWriteArrayList<>();

        Rig(boolean registerServer) {
            registry = new McpRegistry(e -> { }, mcpLedger);
            RetrievalLedger ledger = new RetrievalLedger();
            RagProperties rag = RagProperties.defaults();
            RagAgentPlugin ragPlugin = new RagAgentPlugin(new SearchDocumentsTool(
                    TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, null), ledger, rag), rag);
            FileSummaryLedger fileLedger = new FileSummaryLedger();
            FileAgentPlugin filePlugin = new FileAgentPlugin(
                    new UploadService(EXTRACTOR, new InMemoryUploadStore(), FileProperties.defaults()),
                    new MapReduceSummarizer((s, u) -> "summary", SummaryProperties.defaults()), fileLedger);
            ModelFactory factory = new ModelFactory() {
                @Override
                public BaseLlm create() {
                    return llm;
                }

                @Override
                public String modelName() {
                    return "scripted";
                }
            };
            coordinators = new CoordinatorFactory(
                    new AgentRegistry(List.of(ragPlugin, filePlugin, new McpAgentPlugin(registry))), factory,
                    new LlmProperties("fake", "scripted", "", null, null, 12));
            chat = new ChatService(coordinators, ledger, new GroundingVerifier(ledger),
                    new MalformedAnswerRepair((s, u) -> "{}"), fileLedger, new ChatHistory(ChatProperties.defaults()),
                    new FollowUpRewriter((s, u) -> u), new ChatProperties(false, 4, 100), new TurnProgress(),
                    mcpLedger, registry);
            llm.script(this::respond);
            if (registerServer) {
                connect();
            }
        }

        void connect() {
            registry.add(new McpServerConfig(null, "Orders", url, McpTransport.STREAMABLE_HTTP,
                    Map.of("Authorization", "Bearer " + TOKEN), true));
            coordinators.invalidate();      // what the McpConfigChanged event does in the running application
        }

        Content respond(LlmRequest req) {
            if (req.tools().containsKey("rag_agent") || req.tools().containsKey("mcp_agent")
                    || req.tools().containsKey("file_agent")) {                    // the coordinator
                coordinatorRequests.add(req);
                return ScriptedLlm.hasToolResult(req) ? ScriptedLlm.text("coordinator prose")
                        : ScriptedLlm.call(coordinatorTool.get(), Map.of("request", lastUser(req)));
            }
            if (req.tools().containsKey("searchDocuments")) {                      // the RAG agent
                return ScriptedLlm.hasToolResult(req) ? ScriptedLlm.text(ragFinal.get())
                        : ScriptedLlm.call("searchDocuments", Map.of("query", "orders"));
            }
            if (!req.tools().isEmpty()) {                                          // the MCP agent
                mcpAgentRequests.add(req);
                if (ScriptedLlm.hasToolResult(req)) {
                    return ScriptedLlm.text(mcpFinal.get());
                }
                return mcpTool.get() == null ? ScriptedLlm.text("NO_TOOL_FITS")
                        : ScriptedLlm.call(mcpTool.get(), mcpArgs.get());
            }
            return ScriptedLlm.text("unexpected");
        }

        private static String lastUser(LlmRequest req) {
            return req.contents().stream().filter(c -> "user".equals(c.role().orElse("")))
                    .map(c -> c.parts().orElse(List.of()).stream().map(p -> p.text().orElse("")).findFirst().orElse(""))
                    .filter(t -> !t.isEmpty()).reduce((a, b) -> b).orElse("");
        }
    }

    @Test
    void theCoordinatorCanRouteToTheToolsAndTheAnswerNamesServerToolAndRawResult() {
        Rig rig = new Rig(true);

        ChatResult r = rig.chat.chat("tab-1", "Where is order ORD-1001?");

        assertThat(r.route()).isEqualTo("mcp_agent");
        assertThat(r.grounded()).isFalse();
        assertThat(r.answer()).isEqualTo("Order ORD-1001 has shipped with BlueFreight and is due on 2026-10-14.");
        assertThat(r.mcp().calls()).hasSize(1);
        McpCallLedger.Call call = r.mcp().calls().get(0);
        assertThat(call.serverName()).isEqualTo("Orders");
        assertThat(call.tool()).isEqualTo("lookup_order");
        assertThat(call.arguments()).containsEntry("order_id", "ORD-1001");
        assertThat(call.result()).contains("BlueFreight").contains("2026-10-14");   // the raw result, not the prose
        assertThat(call.error()).isFalse();
        assertThat(rig.mcpLedger.calls(r.requestId())).isEmpty();                    // cleared after the turn
    }

    @Test
    void whenTheDocumentsHaveNoAnswerTheToolsAreTriedByCode() {
        Rig rig = new Rig(true);
        rig.coordinatorTool.set("rag_agent");               // the coordinator routes to the documents first

        ChatResult r = rig.chat.chat("tab-1", "Where is order ORD-1001?");

        assertThat(r.toolCalls()).containsExactly("rag_agent", "mcp_agent");
        assertThat(r.route()).isEqualTo("mcp_agent");
        assertThat(r.answer()).contains("BlueFreight");
        assertThat(r.retrievals()).hasSize(1);               // the failed search stays visible in the trace
        assertThat(r.mcp().calls().get(0).tool()).isEqualTo("lookup_order");
    }

    @Test
    void ifNoToolFitsTheHonestNotFoundAnswerStands() {
        Rig rig = new Rig(true);
        rig.coordinatorTool.set("rag_agent");
        rig.mcpTool.set(null);                                // the agent replies NO_TOOL_FITS without a call

        ChatResult r = rig.chat.chat("tab-1", "Who won the 2018 World Cup?");

        assertThat(r.answer()).isEqualTo(GroundingVerifier.NOT_FOUND);
        assertThat(r.route()).isEqualTo("rag_agent");
        assertThat(r.mcp()).isNull();
    }

    @Test
    void aToolErrorIsReportedAsAToolErrorWithTheToolsOwnMessage() {
        Rig rig = new Rig(true);
        rig.mcpTool.set("billing_report");
        rig.mcpArgs.set(Map.of("month", "2026-09"));
        rig.mcpFinal.set("Here is your billing report: it totals one million dollars.");   // the model must not win

        ChatResult r = rig.chat.chat("tab-1", "Show me last month's billing report");

        assertThat(r.mcp().calls().get(0).error()).isTrue();
        assertThat(r.answer()).startsWith("The tool billing_report on Orders reported an error:")
                .contains("503").doesNotContain("million");
    }

    @Test
    void aDocumentAnswerNeverTriggersTheTools() {
        Rig rig = new Rig(true);
        rig.coordinatorTool.set("rag_agent");
        rig.ragFinal.set("{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"The tool reviews workloads.\","
                + "\"ref\":\"r1c1\",\"quote\":\"provides a consistent process for you to review and measure your "
                + "workloads\"}]}");

        ChatResult r = rig.chat.chat("tab-1", "What is the Well-Architected Tool?");

        assertThat(r.grounded()).isTrue();
        assertThat(r.mcp()).isNull();
        assertThat(r.toolCalls()).containsExactly("rag_agent");
    }

    private static final String UNVERIFIABLE_ANSWER = "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"Orders ship "
            + "in one day.\",\"ref\":\"r1c1\",\"quote\":\"a sentence that is nowhere in the retrieved chunk text\"}]}";

    @Test
    void anAnswerTheVerifierRefusesIsTreatedLikeNotFoundAndTheToolsAreTried() {
        Rig rig = new Rig(true);
        rig.coordinatorTool.set("rag_agent");
        rig.ragFinal.set(UNVERIFIABLE_ANSWER);                 // fail closed: the quote is not in the chunk

        ChatResult r = rig.chat.chat("tab-1", "Where is order ORD-1001?");

        assertThat(r.toolCalls()).containsExactly("rag_agent", "mcp_agent");
        assertThat(r.route()).isEqualTo("mcp_agent");
        assertThat(r.answer()).contains("BlueFreight");
        assertThat(r.grounded()).isFalse();
        assertThat(r.mcp().calls().get(0).tool()).isEqualTo("lookup_order");
        // the trace still says why the documents' answer was refused
        assertThat(r.verification().problems()).isNotEmpty();
        assertThat(r.retrievals()).hasSize(1);
    }

    @Test
    void ifNoToolFitsARefusedAnswerStillEndsAsNotFoundNeverAsTheUnverifiedText() {
        Rig rig = new Rig(true);
        rig.coordinatorTool.set("rag_agent");
        rig.ragFinal.set(UNVERIFIABLE_ANSWER);
        rig.mcpTool.set(null);

        ChatResult r = rig.chat.chat("tab-1", "Do orders ship in one day?");

        assertThat(r.answer()).isEqualTo(GroundingVerifier.NOT_FOUND).doesNotContain("one day");
        assertThat(r.route()).isEqualTo("rag_agent");
        assertThat(r.verification().problems()).isNotEmpty();
        assertThat(r.mcp()).isNull();
    }

    @Test
    void withoutAnyServerTheCoordinatorIsNotOfferedTheToolsAndNothingIsTried() {
        Rig rig = new Rig(false);
        rig.coordinatorTool.set("rag_agent");

        ChatResult r = rig.chat.chat("tab-1", "Where is order ORD-1001?");

        assertThat(r.answer()).isEqualTo(GroundingVerifier.NOT_FOUND);
        assertThat(rig.coordinatorRequests.get(0).tools()).containsKeys("rag_agent", "file_agent")
                .doesNotContainKey("mcp_agent");
        assertThat(r.mcp()).isNull();
    }

    @Test
    void aServerAddedAtRuntimeIsUsedByTheNextMessageWithoutARestart() {
        Rig rig = new Rig(false);
        rig.coordinatorTool.set("rag_agent");
        ChatResult before = rig.chat.chat("tab-1", "Where is order ORD-1001?");
        assertThat(before.mcp()).isNull();

        rig.connect();                                         // the user saves the server in the settings page
        ChatResult after = rig.chat.chat("tab-1", "Where is order ORD-1001?");

        assertThat(after.route()).isEqualTo("mcp_agent");
        assertThat(after.answer()).contains("BlueFreight");
        assertThat(rig.coordinatorRequests.get(rig.coordinatorRequests.size() - 1).tools()).containsKey("mcp_agent");
    }

    @Test
    void theConversationSurvivesAddingAServerBecauseTheSessionsAreKeptAcrossTheRebuild() {
        Rig rig = new Rig(false);
        rig.coordinatorTool.set("rag_agent");
        rig.chat.chat("tab-1", "What is the AWS Well-Architected Tool?");

        rig.connect();                                         // the coordinator is rebuilt with the new tools
        rig.chat.chat("tab-1", "and who is its audience?");

        LlmRequest afterRebuild = rig.coordinatorRequests.get(rig.coordinatorRequests.size() - 1);
        assertThat(afterRebuild.tools()).containsKey("mcp_agent");               // really the rebuilt coordinator
        String history = afterRebuild.contents().stream()
                .flatMap(c -> c.parts().orElse(List.of()).stream()).map(p -> p.text().orElse(""))
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(history).contains("What is the AWS Well-Architected Tool?").contains("and who is its audience?");
    }

    @Test
    void anotherSessionsHistoryIsNotVisibleAfterTheRebuild() {
        Rig rig = new Rig(false);
        rig.coordinatorTool.set("rag_agent");
        rig.chat.chat("tab-1", "A secret question of the first tab");

        rig.connect();
        rig.chat.chat("tab-2", "A question of the second tab");

        LlmRequest last = rig.coordinatorRequests.get(rig.coordinatorRequests.size() - 1);
        String history = last.contents().stream()
                .flatMap(c -> c.parts().orElse(List.of()).stream()).map(p -> p.text().orElse(""))
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(history).contains("A question of the second tab").doesNotContain("secret question");
    }

    @Test
    void aCredentialTheServerEchoesBackNeverReachesTheTraceTheModelOrTheAnswer() {
        Rig rig = new Rig(true);
        // the demo server answers "No order with the id <id> exists.", so this id makes it echo the token
        rig.mcpArgs.set(Map.of("order_id", TOKEN));
        rig.mcpFinal.set("The server says there is no order with the id " + TOKEN + ".");   // the model repeats it

        ChatResult r = rig.chat.chat("tab-1", "Where is order 12345?");

        assertThat(r.answer()).doesNotContain(TOKEN).contains("no order with the id");
        McpCallLedger.Call call = r.mcp().calls().get(0);
        assertThat(call.result()).doesNotContain(TOKEN).contains("No order with the id ***");
        assertThat(call.arguments().toString()).doesNotContain(TOKEN);
        // what the language model was shown as the tool's result
        String toolResultsSeenByModel = rig.mcpAgentRequests.stream()
                .flatMap(req -> req.contents().stream())
                .flatMap(c -> c.parts().orElse(List.of()).stream())
                .flatMap(p -> p.functionResponse().stream())
                .map(fr -> String.valueOf(fr.response().orElse(Map.of())))
                .reduce("", String::concat);
        assertThat(toolResultsSeenByModel).contains("No order with the id").doesNotContain(TOKEN);
    }

    @Test
    void theCoordinatorSeesTheToolNamesInTheAgentDescriptionNotTheCredentials() {
        Rig rig = new Rig(true);

        rig.chat.chat("tab-1", "Where is order ORD-1001?");

        String everything = rig.coordinatorRequests.get(0).tools().values().stream()
                .map(t -> t.name() + " " + t.description() + " " + t.declaration().map(Object::toString).orElse(""))
                .reduce("", String::concat) + String.join(" ", rig.coordinatorRequests.get(0).getSystemInstructions());
        assertThat(everything).contains("lookup_order").doesNotContain(TOKEN).doesNotContain("Bearer");
    }
}
