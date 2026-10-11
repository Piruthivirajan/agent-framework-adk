package com.demo.agentfw.core;

import com.demo.agentfw.agents.rag.GroundingVerifier;
import com.demo.agentfw.agents.rag.RagAgentPlugin;
import com.demo.agentfw.agents.rag.RetrievalLedger;
import com.demo.agentfw.agents.rag.SearchDocumentsTool;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One chat turn, end to end. The coordinator model only routes; what the user sees is decided here
 * in code:
 * <ul>
 *   <li>the RAG agent's result (read from the coordinator's function response) goes through the
 *       {@link GroundingVerifier}, and the verified text is the answer; whatever prose the coordinator
 *       model wrote is ignored;
 *   <li>if no tool was called at all, the model would be answering from its own knowledge, so the fixed
 *       {@link #NO_TOOL_REPLY} is returned instead.
 * </ul>
 *
 * <p>This rule is for RAG answers only. Answers of other tools (MCP, the file agent) will need their
 * own path; until they have one, a turn that used only such a tool fails closed with the not-found text
 * rather than passing unverified prose through.
 */
@Service
public class ChatService {

    public static final String NO_TOOL_REPLY = "I can answer questions about the reference documents, "
            + "summarize files, or use connected tools. I couldn't find that in the reference documents.";

    static final String USER_ID = "web-user";

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final CoordinatorFactory coordinators;
    private final RetrievalLedger ledger;
    private final GroundingVerifier verifier;

    public ChatService(CoordinatorFactory coordinators, RetrievalLedger ledger, GroundingVerifier verifier) {
        this.coordinators = coordinators;
        this.ledger = ledger;
        this.verifier = verifier;
    }

    /** A turn failed before an answer existed (the model could not be reached, a limit was hit). */
    public static class ChatFailedException extends RuntimeException {
        public ChatFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ChatResult chat(String sessionId, String text) {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        try {
            InMemoryRunner runner = coordinators.runner();
            ensureSession(runner, sessionId);
            List<Event> events = runner.runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(text)),
                            coordinators.runConfig(), Map.of(SearchDocumentsTool.REQUEST_ID_KEY, requestId))
                    .toList().blockingGet();
            return decide(events, requestId, start);
        } catch (ChatFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("chat request={} failed: {}", requestId, e.toString());
            throw new ChatFailedException("The model could not complete the request: " + e.getMessage(), e);
        } finally {
            ledger.clear(requestId);
        }
    }

    private ChatResult decide(List<Event> events, String requestId, long start) {
        List<String> toolCalls = new ArrayList<>();
        Optional<String> ragOutput = Optional.empty();
        for (Event event : events) {
            event.functionCalls().forEach(fc -> fc.name().ifPresent(toolCalls::add));
            for (FunctionResponse response : event.functionResponses()) {
                if (RagAgentPlugin.NAME.equals(response.name().orElse(""))) {
                    ragOutput = Optional.of(resultText(response));
                }
            }
        }
        List<com.demo.agentfw.retrieval.RetrievalTrace> traces = ledger.retrievals(requestId).stream()
                .map(RetrievalLedger.Retrieval::trace).toList();

        ChatResult result;
        if (ragOutput.isPresent()) {
            GroundingVerifier.Verified v = verifier.verify(ragOutput.get(), requestId);
            result = new ChatResult(v.answer(), RagAgentPlugin.NAME, v.grounded(), v.sources(), traces,
                    new ChatResult.Verification(v.problems(), v.modelSaidNotFound()), toolCalls, ragOutput.get(),
                    requestId, millisSince(start));
        } else if (toolCalls.isEmpty()) {
            result = new ChatResult(NO_TOOL_REPLY, "none", false, List.of(), traces,
                    new ChatResult.Verification(List.of("no tool was called, so any answer would come from the model's "
                            + "own knowledge"), false), toolCalls, null, requestId, millisSince(start));
        } else {
            result = new ChatResult(GroundingVerifier.NOT_FOUND, toolCalls.get(0), false, List.of(), traces,
                    new ChatResult.Verification(List.of("the answer of tool '" + toolCalls.get(0)
                            + "' has no verification path yet"), false), toolCalls, null, requestId, millisSince(start));
        }
        log.info("chat request={} route={} grounded={} problems={} toolCalls={} retrievals={} ms={}", requestId,
                result.route(), result.grounded(), result.verification().problems().size(), toolCalls, traces.size(),
                result.totalMillis());
        return result;
    }

    /** The text of an AgentTool result: {@code {result=<text>}}; falls back to the first value. */
    private static String resultText(FunctionResponse response) {
        Map<String, Object> map = response.response().orElse(Map.of());
        Object value = map.containsKey("result") ? map.get("result") : map.values().stream().findFirst().orElse("");
        return String.valueOf(value);
    }

    private static void ensureSession(InMemoryRunner runner, String sessionId) {
        boolean exists = runner.sessionService()
                .getSession(runner.appName(), USER_ID, sessionId, Optional.empty()).blockingGet() != null;
        if (!exists) {
            runner.sessionService().createSession(runner.appName(), USER_ID, new HashMap<>(), sessionId).blockingGet();
        }
    }

    private static long millisSince(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000;
    }
}
