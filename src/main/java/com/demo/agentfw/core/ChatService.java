package com.demo.agentfw.core;

import com.demo.agentfw.agents.file.FileAgentPlugin;
import com.demo.agentfw.agents.file.FileSummaryLedger;
import com.demo.agentfw.agents.mcp.McpAgentPlugin;
import com.demo.agentfw.mcp.McpCallLedger;
import com.demo.agentfw.mcp.McpRegistry;
import com.demo.agentfw.agents.rag.GroundingVerifier;
import com.demo.agentfw.agents.rag.MalformedAnswerRepair;
import com.demo.agentfw.agents.rag.RagAgentPlugin;
import com.demo.agentfw.agents.rag.RetrievalLedger;
import com.demo.agentfw.agents.rag.SearchDocumentsTool;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
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
import java.util.function.Consumer;

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
    private final MalformedAnswerRepair repair;
    private final FileSummaryLedger fileLedger;
    private final ChatHistory history;
    private final FollowUpRewriter rewriter;
    private final ChatProperties chatProps;
    private final TurnProgress progress;
    private final McpCallLedger mcpLedger;
    private final McpRegistry mcp;
    private final Object[] sessionLocks = newLocks(64);

    @org.springframework.beans.factory.annotation.Autowired
    public ChatService(CoordinatorFactory coordinators, RetrievalLedger ledger, GroundingVerifier verifier,
                       MalformedAnswerRepair repair, FileSummaryLedger fileLedger, ChatHistory history,
                       FollowUpRewriter rewriter, ChatProperties chatProps, TurnProgress progress,
                       McpCallLedger mcpLedger, McpRegistry mcp) {
        this.mcpLedger = mcpLedger;
        this.mcp = mcp;
        this.progress = progress;
        this.coordinators = coordinators;
        this.ledger = ledger;
        this.verifier = verifier;
        this.repair = repair;
        this.fileLedger = fileLedger;
        this.history = history;
        this.rewriter = rewriter;
        this.chatProps = chatProps;
    }

    /** A turn failed before an answer existed (the model could not be reached, a limit was hit). */
    public static class ChatFailedException extends RuntimeException {
        private final boolean userMessage;

        /** A failure of the model call itself: the message carries the provider's text and is classified. */
        public ChatFailedException(String message, Throwable cause) {
            this(message, cause, false);
        }

        /** {@code userMessage}: the message is already a sentence for the user and is shown as it is. */
        public ChatFailedException(String message, Throwable cause, boolean userMessage) {
            super(message, cause);
            this.userMessage = userMessage;
        }

        public boolean isUserMessage() {
            return userMessage;
        }
    }

    /** Without MCP (tests that do not use tools). */
    public ChatService(CoordinatorFactory coordinators, RetrievalLedger ledger, GroundingVerifier verifier,
                       MalformedAnswerRepair repair, FileSummaryLedger fileLedger, ChatHistory history,
                       FollowUpRewriter rewriter, ChatProperties chatProps, TurnProgress progress) {
        this(coordinators, ledger, verifier, repair, fileLedger, history, rewriter, chatProps, progress,
                new McpCallLedger(), null);
    }

    /** Without a progress listener (tests, the end-to-end runner). */
    public ChatService(CoordinatorFactory coordinators, RetrievalLedger ledger, GroundingVerifier verifier,
                       MalformedAnswerRepair repair, FileSummaryLedger fileLedger, ChatHistory history,
                       FollowUpRewriter rewriter, ChatProperties chatProps) {
        this(coordinators, ledger, verifier, repair, fileLedger, history, rewriter, chatProps, new TurnProgress());
    }

    private static Object[] newLocks(int n) {
        Object[] locks = new Object[n];
        for (int i = 0; i < n; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /** A text-only turn: the coordinator model chooses the agent. */
    public ChatResult chat(String sessionId, String text) {
        return chat(sessionId, text, null);
    }

    /**
     * One turn. With {@code fileId} (an attachment) the file agent is called by a code rule and no
     * coordinator model call is made; without it the coordinator model routes the text.
     */
    public ChatResult chat(String sessionId, String text, String fileId) {
        return chat(sessionId, text, fileId, null);
    }

    /**
     * Same, reporting each stage as it starts to {@code listener} (may be null). Turns of one session
     * run one at a time, so a double click cannot interleave two turns in the same ADK session.
     */
    public ChatResult chat(String sessionId, String text, String fileId, Consumer<StageEvent> listener) {
        synchronized (sessionLocks[Math.floorMod(sessionId.hashCode(), sessionLocks.length)]) {
            return run(sessionId, text, fileId, listener);
        }
    }

    private ChatResult run(String sessionId, String text, String fileId, Consumer<StageEvent> listener) {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        boolean attachment = fileId != null && !fileId.isBlank();
        progress.register(requestId, listener);
        try {
            Runner runner = attachment ? coordinators.agentRunner(FileAgentPlugin.NAME)
                    : coordinators.runner();
            ensureSession(runner, sessionId);
            // a follow-up is made standalone before routing; an attachment's text is an instruction, not a question
            boolean rewrite = !attachment && chatProps.rewriteFollowUps()
                    && !history.recent(sessionId).isEmpty();
            if (rewrite) {
                progress.emit(requestId, "understanding", "");
            }
            String effective = rewrite ? rewriter.rewrite(history.recent(sessionId), text) : text;
            if (!effective.equals(text)) {
                log.info("chat request={} follow-up rewritten", requestId);
            }
            Map<String, Object> state = new HashMap<>();
            state.put(TurnState.REQUEST_ID, requestId);
            state.put(TurnState.SESSION_ID, sessionId);
            if (attachment) {
                state.put(TurnState.FILE_ID, fileId);
            }
            if (!attachment) {
                progress.emit(requestId, "routing", "");
            }
            List<Event> events = runner.runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(effective)),
                            coordinators.runConfig(), state)
                    .toList().blockingGet();
            ChatResult result = decide(events, requestId, start, attachment, text);
            if (!attachment && shouldTryTools(result)) {
                String request = result.rewrittenQuestion() != null ? result.rewrittenQuestion() : effective;
                result = tryConnectedTools(result, request, sessionId, state, requestId, start);
            }
            history.add(sessionId, text, result.answer());
            result = result.withStages(progress.snapshot(requestId));
            return effective.equals(text) ? result : result.withRewrittenQuestion(effective);
        } catch (ChatFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("chat request={} failed: {}", requestId, Secrets.mask(e.toString()));
            throw new ChatFailedException("The model could not complete the request: " + e.getMessage(), e);
        } finally {
            ledger.clear(requestId);
            fileLedger.clear(requestId);
            mcpLedger.clear(requestId);
            progress.unregister(requestId);
        }
    }

    private ChatResult decide(List<Event> events, String requestId, long start, boolean attachment, String text) {
        List<String> toolCalls = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> routedRequest = new java.util.concurrent.atomic.AtomicReference<>();
        Optional<String> ragOutput = Optional.empty();
        Optional<String> mcpOutput = Optional.empty();
        String otherAgent = null;                     // the last agent besides rag/file/mcp that answered, and its text
        String otherOutput = null;
        for (Event event : events) {
            event.functionCalls().forEach(fc -> {
                fc.name().ifPresent(toolCalls::add);
                if (routedRequest.get() == null) {
                    fc.args().map(a -> a.get("request")).map(String::valueOf).ifPresent(routedRequest::set);
                }
            });
            for (FunctionResponse response : event.functionResponses()) {
                String responder = response.name().orElse("");
                if (RagAgentPlugin.NAME.equals(responder)) {
                    ragOutput = Optional.of(resultText(response));
                } else if (McpAgentPlugin.NAME.equals(responder)) {
                    mcpOutput = Optional.of(resultText(response));
                } else if (!FileAgentPlugin.NAME.equals(responder) && !responder.isBlank()) {
                    otherAgent = responder;
                    otherOutput = resultText(response);
                }
            }
        }
        List<com.demo.agentfw.retrieval.RetrievalTrace> traces = ledger.retrievals(requestId).stream()
                .map(RetrievalLedger.Retrieval::trace).toList();

        ChatResult result;
        Optional<FileSummaryLedger.Outcome> fileOutcome = fileLedger.take(requestId);
        if (attachment && toolCalls.isEmpty()) {
            toolCalls.add(FileAgentPlugin.NAME);          // the code rule, not a model call, chose the agent
        }
        if (fileOutcome.isPresent()) {
            result = fileResult(fileOutcome.get(), traces, toolCalls, requestId, start);
        } else if (ragOutput.isPresent()) {
            String output = ragOutput.get();
            boolean repaired = false;
            if (verifier.isMalformedJson(output)) {
                // the one retry: only for output that is not the JSON object at all
                progress.emit(requestId, "repairing", "");
                Optional<String> fixed = repair.repair(output);
                log.info("chat request={} malformed model output, repair {}", requestId,
                        fixed.isPresent() ? "returned text" : "failed");
                if (fixed.isPresent()) {
                    output = fixed.get();
                    repaired = true;
                }
            }
            progress.emit(requestId, "verifying", "");
            GroundingVerifier.Verified v = verifier.verify(output, requestId);
            result = new ChatResult(v.answer(), RagAgentPlugin.NAME, v.grounded(), v.sources(), traces,
                    new ChatResult.Verification(v.problems(), v.modelSaidNotFound(), repaired), toolCalls, output,
                    requestId, millisSince(start));
        } else if (mcpOutput.isPresent()) {
            result = mcpResult(mcpOutput.get(), mcpLedger.calls(requestId), traces, toolCalls, requestId, start);
        } else if (toolCalls.isEmpty()) {
            result = new ChatResult(NO_TOOL_REPLY, "none", false, List.of(), traces,
                    new ChatResult.Verification(List.of("no tool was called, so any answer would come from the model's "
                            + "own knowledge"), false), toolCalls, null, requestId, millisSince(start));
        } else if (otherAgent != null && otherOutput != null && !otherOutput.isBlank()) {
            // any other pluggable agent: its text is shown as that agent's answer, labelled by the UI as not
            // verified against the documents (kind agent_answer). It is never presented as a cited answer.
            result = new ChatResult(Secrets.mask(otherOutput.strip()), otherAgent, false, List.of(), traces,
                    new ChatResult.Verification(List.of(), false), toolCalls, null, requestId, millisSince(start));
        } else {
            // an agent was called but returned nothing usable: fail closed, with the reason in the trace
            String agent = toolCalls.get(0);
            result = new ChatResult(GroundingVerifier.NOT_FOUND, agent, false, List.of(), traces,
                    new ChatResult.Verification(List.of("the agent '" + agent + "' returned no answer text"), false),
                    toolCalls, null, requestId, millisSince(start));
        }
        String routed = routedRequest.get();
        if (!attachment && routed != null && !routed.isBlank() && !routed.strip().equals(text.strip())) {
            result = result.withRewrittenQuestion(routed.strip());      // the coordinator restated a follow-up
        }
        log.info("chat request={} route={} grounded={} problems={} toolCalls={} retrievals={} ms={}", requestId,
                result.route(), result.grounded(), result.verification().problems().size(), toolCalls, traces.size(),
                result.totalMillis());
        return result;
    }

    // ---- connected tools (MCP) ------------------------------------------------------------------

    static final String NO_TOOL_ANSWER = "None of the connected tools could answer that.";

    /**
     * The reference documents did not contain the answer (the model itself said so) and tools are
     * connected: the question may be about their data. This is done in code because a RAG answer ends the
     * coordinator's turn. A verification failure is not a reason: the documents had an answer.
     */
    private boolean shouldTryTools(ChatResult r) {
        return mcp != null && mcp.hasTools() && RagAgentPlugin.NAME.equals(r.route()) && !r.grounded()
                && r.verification().modelSaidNotFound();
    }

    private ChatResult tryConnectedTools(ChatResult ragResult, String request, String sessionId,
                                         Map<String, Object> state, String requestId, long start) {
        progress.emit(requestId, "tools", "");
        try {
            Runner runner = coordinators.agentRunner(McpAgentPlugin.NAME);
            ensureSession(runner, sessionId);
            List<Event> events = runner.runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(request)),
                    coordinators.runConfig(), state).toList().blockingGet();
            List<McpCallLedger.Call> calls = mcpLedger.calls(requestId);
            if (calls.isEmpty()) {
                return ragResult;                      // no tool fits: the not-found answer stands
            }
            List<String> toolCalls = new ArrayList<>(ragResult.toolCalls());
            toolCalls.add(McpAgentPlugin.NAME);
            ChatResult viaTools = mcpResult(lastText(events), calls, ragResult.retrievals(), toolCalls, requestId, start);
            return viaTools.withRewrittenQuestion(ragResult.rewrittenQuestion());
        } catch (RuntimeException e) {
            // the tools are a second chance: if they fail, the user still gets the honest not-found answer
            log.warn("chat request={} connected tools failed after a not-found answer: {}", requestId,
                    Secrets.mask(e.toString()));
            return ragResult;
        }
    }

    /**
     * The answer of the tools. What happened is read from the call records, not from the agent's prose:
     * no call means no answer; only failed calls are a tool error with the tool's own message; otherwise
     * the agent's sentence is shown, labelled by the UI as coming from that tool and not checked against
     * the documents, next to the raw result in the trace.
     */
    private ChatResult mcpResult(String agentText, List<McpCallLedger.Call> calls,
                                 List<com.demo.agentfw.retrieval.RetrievalTrace> traces, List<String> toolCalls,
                                 String requestId, long start) {
        ChatResult.McpAnswer mcpTrace = new ChatResult.McpAnswer(calls);
        if (calls.isEmpty()) {
            return new ChatResult(NO_TOOL_ANSWER, McpAgentPlugin.NAME, false, List.of(), traces,
                    new ChatResult.Verification(List.of("the agent called no connected tool"), false), toolCalls, null,
                    requestId, millisSince(start));
        }
        McpCallLedger.Call firstOk = calls.stream().filter(c -> !c.error()).findFirst().orElse(null);
        String answer;
        if (firstOk == null) {
            McpCallLedger.Call last = calls.get(calls.size() - 1);
            answer = "The tool " + last.tool() + " on " + last.serverName() + " reported an error: "
                    + Secrets.mask(last.message() == null ? "no details" : last.message());
        } else {
            String text = agentText == null ? "" : agentText.strip();
            answer = text.isEmpty() || text.equals(McpAgentPlugin.NO_TOOL_FITS)
                    ? "The tool " + firstOk.tool() + " on " + firstOk.serverName() + " returned: " + firstOk.result()
                    : text;
        }
        return new ChatResult(answer, McpAgentPlugin.NAME, false, List.of(), traces,
                new ChatResult.Verification(List.of(), false), toolCalls, null, requestId, millisSince(start))
                .withMcp(mcpTrace);
    }

    private static String lastText(List<Event> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            Event e = events.get(i);
            if (e.finalResponse() && e.content().isPresent()) {
                return e.stringifyContent();
            }
        }
        return "";
    }

    /**
     * The file agent's result. The summary is read from the ledger, not from the model's prose. It is
     * labelled and not quote-verified ({@code grounded} false); a failure is thrown so the API can say
     * why in plain words.
     */
    private ChatResult fileResult(FileSummaryLedger.Outcome o, List<com.demo.agentfw.retrieval.RetrievalTrace> traces,
                                  List<String> toolCalls, String requestId, long start) {
        switch (o.status()) {
            case FAILED -> throw new ChatFailedException(o.message(), null, true);
            case NO_FILE -> {
                return new ChatResult(o.message(), FileAgentPlugin.NAME, false, List.of(), traces,
                        new ChatResult.Verification(List.of("no uploaded file to work on"), false), toolCalls, null,
                        requestId, millisSince(start));
            }
            default -> {
                ChatResult.FileSummary info = new ChatResult.FileSummary(o.fileId(), o.fileName(), o.contentType(),
                        o.chars(), o.strategy().name(), o.segments(), o.modelCalls(), o.millis());
                log.info("chat request={} route=file_agent strategy={} segments={} ms={}", requestId, o.strategy(),
                        o.segments(), millisSince(start));
                return new ChatResult(o.summary(), FileAgentPlugin.NAME, false, List.of(), traces,
                        new ChatResult.Verification(List.of(), false), toolCalls, null, requestId,
                        millisSince(start), info);
            }
        }
    }

    /** The text of an AgentTool result: {@code {result=<text>}}; falls back to the first value. */
    private static String resultText(FunctionResponse response) {
        Map<String, Object> map = response.response().orElse(Map.of());
        Object value = map.containsKey("result") ? map.get("result") : map.values().stream().findFirst().orElse("");
        return String.valueOf(value);
    }

    private static void ensureSession(Runner runner, String sessionId) {
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
