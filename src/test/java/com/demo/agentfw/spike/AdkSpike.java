package com.demo.agentfw.spike;

/*
 * Day 4 step 0: what ADK 1.11.0 actually does, checked with a scripted model (no network) and one live call.
 * Not a test (no JUnit, name outside Surefire patterns). Findings are recorded in docs/PROGRESS.md.
 *
 *   ./mvnw -q test-compile exec:java "-Dexec.mainClass=com.demo.agentfw.spike.AdkSpike"
 *       "-Dexec.classpathScope=test" "-Dexec.args=gemini-3.8-flash"     (the argument enables the live call)
 */
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.Gemini;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.GenerateContentConfig;
import io.reactivex.rxjava3.core.Flowable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AdkSpike {

    static final Map<String, AtomicInteger> LLM_CALLS = new TreeMap<>();
    static final List<String> TOOL_SEEN = new ArrayList<>();

    /** Scripted model: routes by which tools the calling agent has. */
    static class ScriptedLlm extends BaseLlm {
        ScriptedLlm() { super("scripted"); }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest req, boolean stream) {
            boolean coordinator = req.tools().containsKey("rag_agent");
            boolean rag = req.tools().containsKey("searchDocuments");
            String who = coordinator ? "coordinator" : rag ? "rag_agent" : "other";
            LLM_CALLS.computeIfAbsent(who, k -> new AtomicInteger()).incrementAndGet();
            boolean hasResponse = req.contents().stream().flatMap(c -> c.parts().orElse(List.of()).stream())
                    .anyMatch(p -> p.functionResponse().isPresent());
            Content out;
            if (coordinator) {
                out = hasResponse ? text("COORDINATOR PROSE THAT MUST BE IGNORED")
                        : call("rag_agent", Map.of("request", "What is the AWS Well-Architected Tool?"));
            } else if (rag) {
                out = hasResponse ? text("{\"status\":\"NOT_FOUND\"}")
                        : call("searchDocuments", Map.of("query", "AWS Well-Architected Tool"));
            } else {
                out = text("OTHER");
            }
            return Flowable.just(LlmResponse.builder().content(out).build());
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        static Content text(String t) {
            return Content.builder().role("model").parts(Part.fromText(t)).build();
        }

        static Content call(String name, Map<String, Object> args) {
            return Content.builder().role("model").parts(Part.fromFunctionCall(name, args)).build();
        }
    }

    /** The tool the RAG agent calls: records what its ToolContext exposes. */
    public static class Search {
        @Annotations.Schema(name = "searchDocuments", description = "Search the reference documents")
        public Map<String, Object> searchDocuments(
                @Annotations.Schema(name = "query", description = "search query") String query,
                @Annotations.Schema(name = "toolContext", description = "context") ToolContext toolContext) {
            TOOL_SEEN.add("tool saw: userId=" + toolContext.userId() + " sessionId=" + toolContext.sessionId()
                    + " invocationId=" + toolContext.invocationId() + " agentName=" + toolContext.agentName()
                    + " stateKeys=" + new TreeSet<>(toolContext.state().keySet()) + " query=" + query);
            return Map.of("status", "NO_RELEVANT_CONTEXT");
        }
    }

    static void runOnce(String label, boolean skipSummarization, Map<String, Object> stateDelta) {
        LLM_CALLS.clear();
        TOOL_SEEN.clear();
        ScriptedLlm llm = new ScriptedLlm();
        LlmAgent rag = LlmAgent.builder().name("rag_agent").description("answers from the documents")
                .model(llm).instruction("use the tool")
                .tools(FunctionTool.create(new Search(), "searchDocuments")).build();
        LlmAgent coordinator = LlmAgent.builder().name("coordinator").description("router")
                .model(llm).instruction("call rag_agent")
                .tools(AgentTool.create(rag, skipSummarization)).build();

        InMemoryRunner runner = new InMemoryRunner(coordinator);
        String user = "user-1";
        String session = runner.sessionService()
                .createSession(runner.appName(), user).blockingGet().id();
        Content question = Content.fromParts(Part.fromText("What is the AWS Well-Architected Tool?"));
        RunConfig cfg = RunConfig.builder().maxLlmCalls(10).build();

        System.out.println("\n===== " + label + " (skipSummarization=" + skipSummarization + ")");
        System.out.println("top-level: user=" + user + " session=" + session + " app=" + runner.appName());
        List<Event> events = (stateDelta == null
                ? runner.runAsync(user, session, question, cfg)
                : runner.runAsync(user, session, question, cfg, stateDelta)).toList().blockingGet();
        for (Event e : events) {
            StringBuilder sb = new StringBuilder("  event author=" + e.author() + " final=" + e.finalResponse());
            e.functionCalls().forEach(fc -> sb.append(" CALL:").append(fc.name().orElse("?")).append(fc.args().orElse(Map.of())));
            for (FunctionResponse fr : e.functionResponses()) {
                sb.append(" RESPONSE:").append(fr.name().orElse("?")).append("=").append(fr.response().orElse(Map.of()));
            }
            String t = e.stringifyContent();
            if (t != null && !t.isBlank()) sb.append(" TEXT:").append(t.replace("\n", " "));
            System.out.println(sb);
        }
        TOOL_SEEN.forEach(System.out::println);
        System.out.println("  model calls: " + LLM_CALLS);
    }

    public static void main(String[] args) throws Exception {
        runOnce("A: summarization on", false, null);
        runOnce("B: summarization skipped", true, null);
        runOnce("C: state delta passed at the top level", true, Map.of("requestId", "req-42"));

        if (args.length > 0) {   // live call through the ADK Gemini wrapper
            String key = System.getenv("GOOGLE_API_KEY");
            for (Integer budget : new Integer[]{null, 0, 128}) {
                GenerateContentConfig.Builder cfg = GenerateContentConfig.builder();
                if (budget != null) cfg.thinkingConfig(ThinkingConfig.builder().thinkingBudget(budget).build());
                LlmAgent agent = LlmAgent.builder().name("echo").description("d")
                        .model(new Gemini(args[0], key)).instruction("Reply with exactly one word: OK")
                        .generateContentConfig(cfg.build()).build();
                InMemoryRunner r = new InMemoryRunner(agent);
                String s = r.sessionService().createSession(r.appName(), "u").blockingGet().id();
                long t0 = System.nanoTime();
                try {
                    List<Event> ev = r.runAsync("u", s, Content.fromParts(Part.fromText("hi")), RunConfig.builder().build())
                            .toList().blockingGet();
                    String last = ev.isEmpty() ? "" : ev.get(ev.size() - 1).stringifyContent();
                    System.out.printf("live %s thinkingBudget=%s -> %d ms, reply=%s%n", args[0], budget,
                            (System.nanoTime() - t0) / 1_000_000, last.strip());
                } catch (Exception ex) {
                    System.out.printf("live thinkingBudget=%s FAILED: %s%n", budget, String.valueOf(ex.getMessage()).split("\n")[0]);
                }
            }
        }
    }
}
