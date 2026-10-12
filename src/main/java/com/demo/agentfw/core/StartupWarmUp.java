package com.demo.agentfw.core;

import com.demo.agentfw.agents.rag.RagAgentPlugin;
import com.demo.agentfw.agents.rag.RetrievalLedger;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one throwaway RAG turn in the background right after startup, so the first user does not pay for
 * the one-time costs: the TLS connection to the model API, class loading and JIT of the ADK and client
 * code, the MongoDB connection pool, the ONNX session of the reranker, the embedding client. Measured on
 * the first question after a start: about 8 s before the search began, against 4 to 5 s on the next
 * ones (see docs/PROGRESS.md).
 *
 * <p>It goes through the RAG agent's own runner, not through {@link ChatService}: no history, no
 * coordinator decision, and no chance of touching a connected MCP tool. It costs one embedding call and
 * one model call per start. Everything is best effort: a missing key, an unreachable database or any other
 * failure is logged in one line and the application carries on. {@code agentfw.warmup.enabled=false}
 * switches it off (the tests do).
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.warmup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StartupWarmUp {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmUp.class);
    private static final String QUESTION = "What is Amazon S3?";
    private static final String USER_ID = "warm-up";

    private final CoordinatorFactory coordinators;
    private final RetrievalLedger ledger;

    public StartupWarmUp(CoordinatorFactory coordinators, RetrievalLedger ledger) {
        this.coordinators = coordinators;
        this.ledger = ledger;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread t = new Thread(this::run, "warm-up");
        t.setDaemon(true);
        t.start();
    }

    void run() {
        long start = System.nanoTime();
        String requestId = "warm-up-" + UUID.randomUUID();
        String sessionId = requestId;
        Runner runner = null;
        try {
            coordinators.runner();                                   // builds the coordinator and its tools
            runner = coordinators.agentRunner(RagAgentPlugin.NAME);
            runner.sessionService().createSession(runner.appName(), USER_ID, new HashMap<>(), sessionId)
                    .blockingGet();
            Map<String, Object> state = new HashMap<>();
            state.put(TurnState.REQUEST_ID, requestId);
            state.put(TurnState.SESSION_ID, sessionId);
            runner.runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(QUESTION)),
                    coordinators.runConfig(), state).toList().blockingGet();
            log.info("warm-up finished in {} ms", (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            log.info("warm-up skipped: {}", Secrets.mask(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            ledger.clear(requestId);
            if (runner != null) {
                try {
                    runner.sessionService().deleteSession(runner.appName(), USER_ID, sessionId).blockingAwait();
                } catch (RuntimeException ignored) {
                    // a leftover session of a throwaway user is harmless
                }
            }
        }
    }
}
