package com.demo.agentfw.api;

import com.demo.agentfw.agents.file.FileSummaryLedger;
import com.demo.agentfw.agents.rag.GroundingVerifier;
import com.demo.agentfw.agents.rag.MalformedAnswerRepair;
import com.demo.agentfw.agents.rag.RagAgentPlugin;
import com.demo.agentfw.agents.rag.RagProperties;
import com.demo.agentfw.agents.rag.RetrievalLedger;
import com.demo.agentfw.agents.rag.SearchDocumentsTool;
import com.demo.agentfw.core.AgentRegistry;
import com.demo.agentfw.core.ChatHistory;
import com.demo.agentfw.core.ChatProperties;
import com.demo.agentfw.core.ChatService;
import com.demo.agentfw.core.CoordinatorFactory;
import com.demo.agentfw.core.FollowUpRewriter;
import com.demo.agentfw.core.LlmProperties;
import com.demo.agentfw.core.ModelFactory;
import com.demo.agentfw.core.ScriptedLlm;
import com.demo.agentfw.ingestion.FakeEmbeddingService;
import com.demo.agentfw.retrieval.ChunkSearch;
import com.demo.agentfw.retrieval.FusionStrategy;
import com.demo.agentfw.retrieval.Reranker;
import com.demo.agentfw.retrieval.RetrievalProperties;
import com.demo.agentfw.retrieval.RetrievalService;
import com.google.adk.models.BaseLlm;
import com.mongodb.MongoTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMappingTest {

    private static final MongoTimeoutException DB_DOWN = new MongoTimeoutException(
            "Timed out after 30000 ms while waiting to connect. Client view of cluster state is {type=UNKNOWN}");

    @Test
    void aDatabaseFailureWrappedInAChatFailureIsADatabaseProblemNotAModelTimeout() {
        ChatService.ChatFailedException wrapped = new ChatService.ChatFailedException(
                "The model could not complete the request: Failed to call tool: rag_agent",
                new RuntimeException("Failed to call tool: rag_agent", new RuntimeException("tool failed", DB_DOWN)));

        ErrorMapping.Mapped mapped = ErrorMapping.of(wrapped);

        assertThat(mapped.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(mapped.error().code()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(mapped.error().message()).doesNotContain("language model").doesNotContain("Timed out");
    }

    @Test
    void springDataAccessFailuresInTheCauseChainCountToo() {
        Throwable t = new ChatService.ChatFailedException("x", new IllegalStateException(
                new DataAccessResourceFailureException("no connection")));

        assertThat(ErrorMapping.of(t).error().code()).isEqualTo("DATABASE_UNAVAILABLE");
    }

    @Test
    void aGeneralModelTimeoutIsStillAModelTimeout() {
        Throwable t = new ChatService.ChatFailedException("x", new RuntimeException("DEADLINE_EXCEEDED timed out"));

        ErrorMapping.Mapped mapped = ErrorMapping.of(t);

        assertThat(mapped.error().code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(mapped.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void aRateLimitIsStillARateLimit() {
        Throwable t = new ChatService.ChatFailedException("x", new RuntimeException("429 RESOURCE_EXHAUSTED"));

        assertThat(ErrorMapping.of(t).error().code()).isEqualTo("MODEL_RATE_LIMITED");
    }

    @Test
    void aCycleInTheCauseChainDoesNotLoopForever() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(ErrorMapping.hasDatabaseCause(a)).isFalse();
    }

    /** A real chat turn through ADK whose search throws {@code failure}; returns the exception the turn ends with. */
    private static ChatService.ChatFailedException failedTurn(RuntimeException failure) {
        ScriptedLlm llm = new ScriptedLlm();
        ChunkSearch brokenSearch = (query, vector, settings) -> {
            throw failure;
        };
        Reranker reranker = new Reranker() {
            @Override
            public Result rerank(String query, List<String> passages) {
                return new Result(new float[passages.size()], 0);
            }

            @Override
            public String modelName() {
                return "unused";
            }
        };
        RetrievalProperties props = new RetrievalProperties(FusionStrategy.RANK, 400, 20, 20, 12, 5, 1.0, 1.0,
                "sigmoid", 60, -6.5, 3.3);
        RetrievalService retrieval = new RetrievalService(new FakeEmbeddingService(8, "fake"), brokenSearch, reranker,
                props);
        RetrievalLedger ledger = new RetrievalLedger();
        RagAgentPlugin rag = new RagAgentPlugin(
                new SearchDocumentsTool(retrieval, ledger, RagProperties.defaults()), RagProperties.defaults());
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
        CoordinatorFactory coordinators = new CoordinatorFactory(new AgentRegistry(List.of(rag)), factory,
                new LlmProperties("fake", "scripted", "", null, null, 12));
        ChatService chat = new ChatService(coordinators, ledger, new GroundingVerifier(ledger),
                new MalformedAnswerRepair((system, user) -> "{}"), new FileSummaryLedger(),
                new ChatHistory(ChatProperties.defaults()), new FollowUpRewriter((system, user) -> user),
                ChatProperties.defaults());
        // the model behaves as instructed after an error: it answers NOT_FOUND. That must not reach the user.
        llm.script(req -> {
            if (req.tools().containsKey("rag_agent")) {
                return ScriptedLlm.hasToolResult(req) ? ScriptedLlm.text("coordinator prose")
                        : ScriptedLlm.call("rag_agent", Map.of("request", "What is S3?"));
            }
            return ScriptedLlm.hasToolResult(req) ? ScriptedLlm.text("{\"status\":\"NOT_FOUND\"}")
                    : ScriptedLlm.call("searchDocuments", Map.of("query", "s3"));
        });
        return org.assertj.core.api.Assertions.catchThrowableOfType(ChatService.ChatFailedException.class,
                () -> chat.chat("tab-1", "What is S3?"));
    }

    @Test
    void aDeadDatabaseDuringASearchIsNotReportedAsNotFoundButAsDatabaseUnavailable() {
        ChatService.ChatFailedException e = failedTurn(DB_DOWN);

        assertThat(e).as("the turn must fail instead of answering 'not found'").isNotNull();
        ErrorMapping.Mapped mapped = ErrorMapping.of(e);
        assertThat(mapped.error().code()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(mapped.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aRateLimitedEmbeddingDuringASearchIsARateLimitNotNotFound() {
        ChatService.ChatFailedException e = failedTurn(new IllegalStateException("429 RESOURCE_EXHAUSTED"));

        assertThat(e).isNotNull();
        assertThat(ErrorMapping.of(e).error().code()).isEqualTo("MODEL_RATE_LIMITED");
    }
}
