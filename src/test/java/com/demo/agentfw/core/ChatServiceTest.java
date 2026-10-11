package com.demo.agentfw.core;

import com.demo.agentfw.agents.file.FileAgentPlugin;
import com.demo.agentfw.agents.file.FileSummaryLedger;
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
import com.demo.agentfw.files.UploadedFile;
import com.demo.agentfw.retrieval.GroundingDecision;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scenarios of the plan, end to end through the real coordinator, plugin, tool, ledger and
 * verifier, with only the language model scripted. No network, no MongoDB.
 */
class ChatServiceTest {

    private static final String TOOL_QUOTE =
            "provides a consistent process for you to review and measure your workloads";
    private static final TextExtractor EXTRACTOR = new TextExtractor();   // slow to build, shared

    private static final String GOOD_ANSWER = "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"The AWS Well-Architected "
            + "Tool provides a consistent process to review workloads.\",\"ref\":\"r1c1\",\"quote\":\"" + TOOL_QUOTE + "\"}]}";

    /** The pieces of one scripted application. */
    private static final class Rig {
        final ScriptedLlm llm = new ScriptedLlm();
        final RetrievalLedger ledger = new RetrievalLedger();
        final AtomicReference<String> ragFinal = new AtomicReference<>("{\"status\":\"NOT_FOUND\"}");
        final AtomicReference<String> coordinatorProse = new AtomicReference<>("coordinator prose");
        final AtomicReference<String> coordinatorTool = new AtomicReference<>("rag_agent");
        final AtomicReference<String> coordinatorRequest = new AtomicReference<>("the user's question");
        final InMemoryUploadStore uploadStore = new InMemoryUploadStore();
        final UploadService uploads = new UploadService(EXTRACTOR, uploadStore, FileProperties.defaults());
        final AtomicReference<TextGenerator> fileModel = new AtomicReference<>(
                (system, user) -> "SUMMARY of: " + user.substring(user.indexOf("<document>") + 11,
                        Math.min(user.length(), user.indexOf("<document>") + 40)));
        final AtomicReference<String> repairReply = new AtomicReference<>("{\"status\":\"NOT_FOUND\"}");
        final java.util.concurrent.atomic.AtomicInteger repairCalls = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<String> repairInput = new AtomicReference<>();
        final ChatService chat;

        Rig(DoubleUnaryOperator score, boolean ragIsFinalAnswer) {
            SearchDocumentsTool tool = new SearchDocumentsTool(
                    TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> score.applyAsDouble(i), null), ledger,
                    RagProperties.defaults());
            RagAgentPlugin plugin = new RagAgentPlugin(tool, RagProperties.defaults()) {
                @Override
                public boolean finalAnswer() {
                    return ragIsFinalAnswer;
                }
            };
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
            FileSummaryLedger fileLedger = new FileSummaryLedger();
            FileAgentPlugin filePlugin = new FileAgentPlugin(uploads, new MapReduceSummarizer(
                    (system, user) -> fileModel.get().generate(system, user), SummaryProperties.defaults()),
                    fileLedger);
            CoordinatorFactory coordinators = new CoordinatorFactory(
                    new AgentRegistry(List.of(plugin, filePlugin)), factory,
                    new LlmProperties("fake", "scripted", "", null, null, 12));
            MalformedAnswerRepair repairer = new MalformedAnswerRepair((system, user) -> {
                repairCalls.incrementAndGet();
                repairInput.set(user);
                return repairReply.get();
            });
            chat = new ChatService(coordinators, ledger, new GroundingVerifier(ledger), repairer, fileLedger);
            llm.script(this::respond);
        }

        Content respond(LlmRequest req) {
            if (req.tools().containsKey("rag_agent")) {                       // the coordinator
                return ScriptedLlm.hasToolResult(req)
                        ? ScriptedLlm.text(coordinatorProse.get())
                        : ScriptedLlm.call(coordinatorTool.get(), Map.of("request", coordinatorRequest.get()));
            }
            if (req.tools().containsKey("searchDocuments")) {                 // the RAG agent
                return ScriptedLlm.hasToolResult(req)
                        ? ScriptedLlm.text(ragFinal.get())
                        : ScriptedLlm.call("searchDocuments", Map.of("query", "well-architected tool"));
            }
            return ScriptedLlm.text("unexpected agent");
        }
    }

    // ---- the file agent ---------------------------------------------------------------------------

    private static final byte[] NOTES = ("Quarterly notes. The migration to queues finished in March. "
            + "Costs fell by twelve percent.").getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void scenarioC_anAttachmentGoesToTheFileAgentByCodeWithoutACoordinatorModelCall() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        UploadedFile file = rig.uploads.upload("tab-1", "notes.txt", NOTES);

        ChatResult r = rig.chat.chat("tab-1", "Summarize this file", file.id());

        assertThat(r.route()).isEqualTo("file_agent");
        assertThat(r.answer()).startsWith("SUMMARY of: ").contains("Quarterly notes");
        assertThat(r.grounded()).isFalse();                                   // labelled, not quote-verified
        assertThat(r.sources()).isEmpty();
        assertThat(r.file().name()).isEqualTo("notes.txt");
        assertThat(r.file().strategy()).isEqualTo("SINGLE_PASS");
        assertThat(r.toolCalls()).containsExactly("file_agent");
        assertThat(rig.llm.requests()).isEmpty();                             // no coordinator call at all
    }

    @Test
    void theCoordinatorCanSendAFollowUpToTheFileAgentAndItFindsTheSessionsFile() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.uploads.upload("tab-1", "notes.txt", NOTES);
        rig.coordinatorTool.set("file_agent");
        rig.coordinatorRequest.set("Make the summary of that file shorter");

        ChatResult r = rig.chat.chat("tab-1", "Make the summary of that file shorter");

        assertThat(r.route()).isEqualTo("file_agent");
        assertThat(r.answer()).contains("Quarterly notes");
        assertThat(r.toolCalls()).containsExactly("file_agent");
        assertThat(rig.llm.requests()).hasSize(1);                            // one routing call, then no rephrasing
    }

    @Test
    void aRequestThatNamesAFileUsesThatFileNotTheLatestOne() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.uploads.upload("tab-1", "budget.txt", "Budget file text about money.".getBytes());
        rig.uploads.upload("tab-1", "notes.txt", NOTES);
        rig.coordinatorTool.set("file_agent");
        rig.coordinatorRequest.set("What does budget.txt say?");

        ChatResult r = rig.chat.chat("tab-1", "What does budget.txt say?");

        assertThat(r.file().name()).isEqualTo("budget.txt");
    }

    @Test
    void aFileFromAnotherSessionIsNeverUsed() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.uploads.upload("tab-2", "private.txt", NOTES);
        rig.coordinatorTool.set("file_agent");

        ChatResult r = rig.chat.chat("tab-1", "Summarize my file");

        assertThat(r.file()).isNull();
        assertThat(r.answer()).contains("No file has been uploaded in this session");
    }

    @Test
    void anAttachmentThatHasExpiredSaysSoInsteadOfFallingBackToAnotherFile() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.uploads.upload("tab-1", "other.txt", NOTES);

        ChatResult r = rig.chat.chat("tab-1", "Summarize", "00000000-0000-0000-0000-000000000000");

        assertThat(r.file()).isNull();
        assertThat(r.answer()).contains("no longer available");
    }

    @Test
    void aSummarizationFailureIsAChatFailureWithTheReasonNotAStackTrace() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        UploadedFile file = rig.uploads.upload("tab-1", "notes.txt", NOTES);
        rig.fileModel.set((system, user) -> {
            throw new IllegalStateException("quota exhausted");
        });

        assertThatThrownBy(() -> rig.chat.chat("tab-1", "Summarize", file.id()))
                .isInstanceOf(ChatService.ChatFailedException.class)
                .hasMessageContaining("quota exhausted");
    }

    @Test
    void scenarioA_aQuestionAnsweredInTheDocumentsGetsACitedVerifiedAnswer() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.ragFinal.set(GOOD_ANSWER);

        ChatResult r = rig.chat.chat("tab-1", "What is the AWS Well-Architected Tool?");

        assertThat(r.grounded()).isTrue();
        assertThat(r.route()).isEqualTo("rag_agent");
        assertThat(r.answer()).isEqualTo(
                "The AWS Well-Architected Tool provides a consistent process to review workloads. "
                        + "[wellarchitected-framework.pdf, p. 7]");
        assertThat(r.sources()).containsExactly(new GroundingVerifier.Source(
                "r1c1", "wellarchitected-framework.pdf", 7, TOOL_QUOTE));
        assertThat(r.answer()).doesNotContain(TOOL_QUOTE);                    // quotes only in sources
        assertThat(r.retrievals()).hasSize(1);
        assertThat(r.retrievals().get(0).grounding().decision()).isEqualTo(GroundingDecision.PASSED_FLOOR);
        assertThat(r.retrievals().get(0).grounding().highConfidence()).isTrue();
        assertThat(r.toolCalls()).containsExactly("rag_agent");
        assertThat(r.verification().problems()).isEmpty();
        assertThat(r.totalMillis()).isGreaterThanOrEqualTo(0);
        assertThat(rig.ledger.retrievals(r.requestId())).isEmpty();           // the ledger is cleared after the turn
    }

    @Test
    void scenarioB_aQuestionNotInTheDocumentsGetsTheNotFoundSentence() {
        Rig rig = new Rig(i -> -20.0, true);                                  // everything below the floor
        rig.ragFinal.set("{\"status\":\"NOT_FOUND\"}");

        ChatResult r = rig.chat.chat("tab-1", "Who won the FIFA World Cup in 2018?");

        assertThat(r.grounded()).isFalse();
        assertThat(r.answer()).isEqualTo("Not found in the reference documents.");
        assertThat(r.sources()).isEmpty();
        assertThat(r.verification().modelSaidNotFound()).isTrue();
        assertThat(r.retrievals()).hasSize(1);
        assertThat(r.retrievals().get(0).grounding().decision()).isEqualTo(GroundingDecision.NO_RELEVANT_CONTEXT);
    }

    @Test
    void aFabricatedQuoteIsReplacedByTheNotFoundSentence() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.ragFinal.set(GOOD_ANSWER.replace(TOOL_QUOTE, "is a managed service that also stores your invoices forever"));

        ChatResult r = rig.chat.chat("tab-1", "What is the AWS Well-Architected Tool?");

        assertThat(r.grounded()).isFalse();
        assertThat(r.answer()).isEqualTo("Not found in the reference documents.");
        assertThat(r.sources()).isEmpty();
        assertThat(r.verification().problems()).hasSize(1);
        assertThat(r.verification().problems().get(0)).contains("quote not found in chunk r1c1");
        assertThat(r.verification().modelSaidNotFound()).isFalse();
        assertThat(r.modelOutput()).contains("stores your invoices");         // kept for the report, never shown
    }

    @Test
    void aFigureFromTheModelsMemoryIsReplacedEvenWhenTheQuoteIsReal() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.ragFinal.set("{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"The default limit is 1,000 concurrent "
                + "executions.\",\"ref\":\"r1c1\",\"quote\":\"" + TOOL_QUOTE + "\"}]}");

        ChatResult r = rig.chat.chat("tab-1", "What is the default Lambda concurrency limit?");

        assertThat(r.grounded()).isFalse();
        assertThat(r.answer()).isEqualTo("Not found in the reference documents.");
        assertThat(r.verification().problems().get(0)).contains("number(s) [1000]");
    }

    /** The slip seen live: the closing quote of "ref" is lost and the key name is swallowed into the value. */
    private static final String SLIPPED = "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"The AWS "
            + "Well-Architected Tool provides a consistent process to review workloads.\",\"ref\":\"r1c1,quote\":\""
            + TOOL_QUOTE + "\"}]}";

    @Test
    void malformedJsonIsRepairedOnceAndTheRepairedAnswerIsStillVerified() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.ragFinal.set(SLIPPED);
        rig.repairReply.set(GOOD_ANSWER);

        ChatResult r = rig.chat.chat("tab-1", "What is the AWS Well-Architected Tool?");

        assertThat(rig.repairCalls.get()).isEqualTo(1);
        assertThat(rig.repairInput.get()).contains("r1c1,quote");             // the model is shown its own output
        assertThat(r.grounded()).isTrue();
        assertThat(r.verification().repaired()).isTrue();
        assertThat(r.answer()).contains("[wellarchitected-framework.pdf, p. 7]");
    }

    @Test
    void aRepairCannotLaunderAFabricatedQuote() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.ragFinal.set(SLIPPED);
        rig.repairReply.set(GOOD_ANSWER.replace(TOOL_QUOTE, "is a managed service that also stores your invoices"));

        ChatResult r = rig.chat.chat("tab-1", "What is the AWS Well-Architected Tool?");

        assertThat(r.grounded()).isFalse();
        assertThat(r.answer()).isEqualTo("Not found in the reference documents.");
        assertThat(r.verification().repaired()).isTrue();
        assertThat(r.verification().problems().get(0)).contains("quote not found");
    }

    @Test
    void theRetryIsOneShotAndAStillMalformedRepairIsRejected() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.ragFinal.set(SLIPPED);
        rig.repairReply.set(SLIPPED);

        ChatResult r = rig.chat.chat("tab-1", "What is the AWS Well-Architected Tool?");

        assertThat(rig.repairCalls.get()).isEqualTo(1);
        assertThat(r.grounded()).isFalse();
        assertThat(r.answer()).isEqualTo("Not found in the reference documents.");
    }

    @Test
    void onlyMalformedJsonIsRetriedNotAFailedVerificationNorProse() {
        Rig badQuote = new Rig(i -> 5.0 - i, true);
        badQuote.ragFinal.set(GOOD_ANSWER.replace(TOOL_QUOTE, "is a managed service that also stores your invoices"));
        badQuote.chat.chat("tab-1", "q");
        Rig prose = new Rig(i -> 5.0 - i, true);
        prose.ragFinal.set("I could not find that in the documents.");
        ChatResult r = prose.chat.chat("tab-1", "q");

        assertThat(badQuote.repairCalls.get()).isZero();
        assertThat(prose.repairCalls.get()).isZero();
        assertThat(r.grounded()).isFalse();
        assertThat(r.verification().repaired()).isFalse();
    }

    @Test
    void coordinatorProseIsIgnoredEvenWhenItWouldSoundHelpful() {
        // the RAG agent is NOT a final-answer agent here, so the coordinator speaks again after the tool
        Rig rig = new Rig(i -> -20.0, false);
        rig.ragFinal.set("{\"status\":\"NOT_FOUND\"}");
        rig.coordinatorProse.set("AWS Lambda allows 1,000 concurrent executions by default.");

        ChatResult r = rig.chat.chat("tab-1", "What is the default Lambda concurrency limit?");

        assertThat(r.answer()).isEqualTo("Not found in the reference documents.");
        assertThat(r.answer()).doesNotContain("1,000");
        assertThat(r.route()).isEqualTo("rag_agent");
    }

    @Test
    void whenNoToolIsCalledTheFixedReplyIsReturned() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.llm.script(req -> ScriptedLlm.text("Paris is the capital of France."));   // coordinator answers by itself

        ChatResult r = rig.chat.chat("tab-1", "What is the capital of France?");

        assertThat(r.answer()).isEqualTo("I can answer questions about the reference documents, summarize files, or "
                + "use connected tools. I couldn't find that in the reference documents.");
        assertThat(r.route()).isEqualTo("none");
        assertThat(r.grounded()).isFalse();
        assertThat(r.toolCalls()).isEmpty();
        assertThat(r.answer()).doesNotContain("Paris");
    }

    /** A second, final-answer agent whose prose has no verification path (stands in for the file agent or MCP). */
    private static final class ProseAgent implements AgentPlugin {
        @Override
        public String name() {
            return "prose_agent";
        }

        @Override
        public String description() {
            return "Writes free prose.";
        }

        @Override
        public com.google.adk.agents.BaseAgent build(AgentContext context) {
            return com.google.adk.agents.LlmAgent.builder().name(name()).description(description())
                    .model(context.model()).instruction("write prose").build();
        }
    }

    @Test
    void anotherAgentsAnswerFailsClosedUntilItHasItsOwnVerificationPath() {
        ScriptedLlm llm = new ScriptedLlm();
        RetrievalLedger ledger = new RetrievalLedger();
        RagAgentPlugin rag = new RagAgentPlugin(new SearchDocumentsTool(
                TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> 5.0 - i, null), ledger,
                RagProperties.defaults()), RagProperties.defaults());
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
        CoordinatorFactory coordinators = new CoordinatorFactory(new AgentRegistry(List.of(rag, new ProseAgent())),
                factory, new LlmProperties("fake", "scripted", "", null, null, 12));
        ChatService chat = new ChatService(coordinators, ledger, new GroundingVerifier(ledger),
                new MalformedAnswerRepair((system, user) -> {
                    throw new IllegalStateException("no repair expected");
                }), new FileSummaryLedger());
        llm.script(req -> req.tools().containsKey("prose_agent")
                ? ScriptedLlm.call("prose_agent", Map.of("request", "q"))      // the coordinator picks the other agent
                : ScriptedLlm.text("Lambda allows 1,000 concurrent executions, unverified."));

        ChatResult r = chat.chat("tab-1", "What is the Lambda limit?");

        assertThat(r.answer()).isEqualTo("Not found in the reference documents.");
        assertThat(r.answer()).doesNotContain("unverified");
        assertThat(r.route()).isEqualTo("prose_agent");
        assertThat(r.grounded()).isFalse();
        assertThat(r.verification().problems().get(0)).contains("no verification path yet");
    }

    @Test
    void aTurnCannotCiteAnotherTurnsChunks() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.ragFinal.set(GOOD_ANSWER);
        ChatResult first = rig.chat.chat("tab-1", "What is the AWS Well-Architected Tool?");
        assertThat(first.grounded()).isTrue();

        // second turn: the model skips the search and re-uses the ref r1c1 from the first turn
        rig.llm.script(req -> req.tools().containsKey("rag_agent")
                ? (ScriptedLlm.hasToolResult(req) ? ScriptedLlm.text("done") : ScriptedLlm.call("rag_agent", Map.of("request", "q")))
                : ScriptedLlm.text(GOOD_ANSWER));
        ChatResult second = rig.chat.chat("tab-1", "And again?");

        assertThat(second.grounded()).isFalse();
        assertThat(second.verification().problems().get(0)).contains("was not returned by retrieval in this request");
    }

    @Test
    void aFailingModelIsAClearErrorNotAnAnswer() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.llm.script(req -> {
            throw new IllegalStateException("429 RESOURCE_EXHAUSTED");
        });

        assertThatThrownBy(() -> rig.chat.chat("tab-1", "q"))
                .isInstanceOf(ChatService.ChatFailedException.class)
                .hasMessageContaining("could not complete the request");
    }

    @Test
    void aRunawayModelIsStoppedByTheCallLimit() {
        Rig rig = new Rig(i -> 5.0 - i, true);
        rig.llm.script(req -> req.tools().containsKey("rag_agent")
                ? ScriptedLlm.call("rag_agent", Map.of("request", "q"))
                : ScriptedLlm.call("searchDocuments", Map.of("query", "again and again")));   // never answers

        assertThatThrownBy(() -> rig.chat.chat("tab-1", "q"))
                .isInstanceOf(ChatService.ChatFailedException.class)
                .hasMessageContaining("could not complete the request");
        // maxLlmCalls is 12: the loop was cut after about that many model requests, not left running
        assertThat(rig.llm.requests().size()).isBetween(12, 14);
    }
}
