package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A language model replaced by a function from request to reply, for tests that must run agents
 * without any network call. It records every request so a test can assert what an agent was given
 * (its tools, system instruction and generation config).
 */
public class ScriptedLlm extends BaseLlm {

    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<LlmRequest, Content> script = r -> text("unscripted");

    public ScriptedLlm() {
        super("scripted");
    }

    public ScriptedLlm script(Function<LlmRequest, Content> script) {
        this.script = script;
        return this;
    }

    public List<LlmRequest> requests() {
        return requests;
    }

    public void reset() {
        requests.clear();
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        requests.add(request);
        return Flowable.just(LlmResponse.builder().content(script.apply(request)).build());
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("live connections are not used in tests");
    }

    // ---- helpers for writing scripts ------------------------------------------------------------

    public static Content text(String text) {
        return Content.builder().role("model").parts(Part.fromText(text)).build();
    }

    public static Content call(String tool, Map<String, Object> args) {
        return Content.builder().role("model").parts(Part.fromFunctionCall(tool, args)).build();
    }

    public static Set<String> toolNames(LlmRequest request) {
        return request.tools().keySet();
    }

    /**
     * True when the last message of the conversation is a tool result, i.e. this call is the model's
     * follow-up to a tool it just called. Earlier turns in the session history do not count.
     */
    public static boolean hasToolResult(LlmRequest request) {
        List<Content> contents = request.contents();
        if (contents.isEmpty()) {
            return false;
        }
        return contents.get(contents.size() - 1).parts().orElse(List.of()).stream()
                .anyMatch(p -> p.functionResponse().isPresent());
    }
}
