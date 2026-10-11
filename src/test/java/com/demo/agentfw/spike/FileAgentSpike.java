package com.demo.agentfw.spike;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import io.reactivex.rxjava3.core.Flowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Day 5 step 0 spike, not part of the build: which shape should the file agent have?
 * A = LlmAgent with one tool (the model picks the tool arguments, then relays the result).
 * B = custom BaseAgent that runs the summarizer directly (no model call of its own).
 * Reads GOOGLE_API_KEY from the environment or ./.env and never prints it.
 */
public class FileAgentSpike {

    static final String MODEL = "gemini-3.8-flash";
    static final String SYSTEM = "You summarize the text you are given. Reply with the summary only.";
    static final String TEXT = ("AWS Lambda lets you run code without provisioning servers. Concurrency is the number of "
            + "requests your function is serving at any given time. Reserved concurrency guarantees capacity for a "
            + "function and also caps it, while provisioned concurrency keeps execution environments initialized to "
            + "remove cold starts. Throttling happens when the account or function limit is reached; callers receive "
            + "a 429 and asynchronous invocations are retried. ").repeat(6);

    static String apiKey() throws Exception {
        String k = System.getenv("GOOGLE_API_KEY");
        if (k != null && !k.isBlank()) {
            return k;
        }
        for (String line : Files.readAllLines(Path.of(".env"))) {
            if (line.startsWith("GOOGLE_API_KEY=")) {
                return line.substring("GOOGLE_API_KEY=".length()).trim();
            }
        }
        throw new IllegalStateException("no key");
    }

    static GenerateContentConfig cfg(int budget) {
        return GenerateContentConfig.builder()
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(budget).build()).build();
    }

    /** One plain model call, the way the summarizer will do it. */
    static String summarize(BaseLlm llm, String text, String instruction) {
        LlmRequest req = LlmRequest.builder()
                .model(llm.model())
                .contents(List.of(Content.builder().role("user")
                        .parts(Part.fromText(instruction + "\n\n---\n" + text)).build()))
                .config(GenerateContentConfig.builder()
                        .systemInstruction(Content.fromParts(Part.fromText(SYSTEM)))
                        .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build()).build())
                .build();
        List<LlmResponse> responses = llm.generateContent(req, false).toList().blockingGet();
        StringBuilder sb = new StringBuilder();
        for (LlmResponse r : responses) {
            r.content().flatMap(Content::parts).ifPresent(ps -> ps.forEach(p -> p.text().ifPresent(sb::append)));
        }
        return sb.toString();
    }

    /** Variant B. */
    static class DeterministicFileAgent extends BaseAgent {
        private final BaseLlm llm;
        final List<String> seenState = new ArrayList<>();

        DeterministicFileAgent(BaseLlm llm) {
            super("file_agent", "Summarizes a file uploaded in this session", List.of(), List.of(), List.of());
            this.llm = llm;
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
            return Flowable.fromCallable(() -> {
                String request = ctx.userContent().flatMap(Content::parts)
                        .map(ps -> ps.get(0).text().orElse("")).orElse("");
                seenState.add(String.valueOf(ctx.session().state().get("sessionFiles")));
                String summary = summarize(llm, TEXT, request);
                return Event.builder().id(Event.generateEventId()).invocationId(ctx.invocationId()).author(name())
                        .content(Content.builder().role("model").parts(Part.fromText(summary)).build()).build();
            });
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
            return Flowable.error(new UnsupportedOperationException("live mode is not used"));
        }
    }

    /** The tool of variant A, canned so only the agent's own overhead is measured. */
    public static class CannedSummaryTool {
        @Annotations.Schema(name = "summarizeFile", description = "Summarizes an uploaded file")
        public Map<String, Object> summarizeFile(
                @Annotations.Schema(name = "fileId", description = "id of the uploaded file") String fileId,
                @Annotations.Schema(name = "instruction", description = "what the user wants") String instruction) {
            return Map.of("status", "OK", "summary", "AWS Lambda runs code without servers; reserved and "
                    + "provisioned concurrency control capacity and cold starts; throttled callers get 429.");
        }
    }

    static BaseAgent variantA(BaseLlm llm) {
        return LlmAgent.builder().name("file_agent").description("Summarizes a file uploaded in this session")
                .model(llm)
                .instruction("Call summarizeFile once with the fileId and the user's instruction, then reply with "
                        + "the returned summary only.")
                .generateContentConfig(cfg(0))
                .tools(FunctionTool.create(new CannedSummaryTool(), "summarizeFile"))
                .build();
    }

    static long run(BaseAgent agent, String text, Map<String, Object> state, List<String> calls) {
        InMemoryRunner runner = new InMemoryRunner(agent);
        String session = runner.sessionService().createSession(runner.appName(), "u").blockingGet().id();
        long t0 = System.nanoTime();
        List<Event> events = runner.runAsync("u", session, Content.fromParts(Part.fromText(text)),
                RunConfig.builder().maxLlmCalls(10).build(), state).toList().blockingGet();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (calls != null) {
            events.forEach(e -> e.functionCalls().forEach(fc -> calls.add(fc.name().orElse("?"))));
        }
        return ms;
    }

    static BaseAgent coordinator(BaseLlm llm, BaseAgent child) {
        return LlmAgent.builder().name("coordinator").description("router").model(llm)
                .instruction("You are a router. Call file_agent with the user's message unchanged. Do not answer.")
                .generateContentConfig(cfg(128))
                .tools(AgentTool.create(child, true)).build();
    }

    static String med(List<Long> xs) {
        List<Long> s = new ArrayList<>(xs);
        s.sort(Long::compare);
        return s.get(s.size() / 2) + " ms (" + s + ")";
    }

    public static void main(String[] args) throws Exception {
        BaseLlm llm = new Gemini(MODEL, apiKey());
        int n = 5;
        String request = "Summarize the uploaded file in two sentences.";
        Map<String, Object> state = new HashMap<>(Map.of("sessionFiles", "f1:lambda-notes.txt"));

        List<Long> b = new ArrayList<>(), a = new ArrayList<>(), inner = new ArrayList<>();
        DeterministicFileAgent bAgent = new DeterministicFileAgent(llm);
        BaseAgent aAgent = variantA(llm);
        summarize(llm, TEXT, request);   // warm-up
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            summarize(llm, TEXT, request);
            inner.add((System.nanoTime() - t0) / 1_000_000);
            b.add(run(bAgent, request, state, null));
            List<String> calls = new ArrayList<>();
            a.add(run(aAgent, request + " (fileId=f1)", state, calls));
            if (i == 0) {
                System.out.println("variant A tool calls: " + calls);
            }
        }
        System.out.println("fast path, inner summarization call alone : " + med(inner));
        System.out.println("fast path, B custom BaseAgent (real call)  : " + med(b));
        System.out.println("fast path, A LlmAgent + canned tool        : " + med(a)
                + "   <- agent overhead only, add the inner call for a real summary");
        System.out.println("B saw session state in its invocation      : " + bAgent.seenState.get(0));

        List<Long> cb = new ArrayList<>(), ca = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<String> callsB = new ArrayList<>(), callsA = new ArrayList<>();
            cb.add(run(coordinator(llm, new DeterministicFileAgent(llm)), request + " (fileId=f1)", state, callsB));
            ca.add(run(coordinator(llm, variantA(llm)), request + " (fileId=f1)", state, callsA));
            if (i == 0) {
                System.out.println("coordinator -> B, coordinator-level calls: " + callsB);
                System.out.println("coordinator -> A, coordinator-level calls: " + callsA);
            }
        }
        System.out.println("coordinator path, B (real inner call)      : " + med(cb));
        System.out.println("coordinator path, A (canned tool)          : " + med(ca));
    }
}
