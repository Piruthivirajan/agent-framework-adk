package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.TreeSet;

/**
 * Puts the request limits of {@link GeminiLimits} on every call of the wrapped model, and logs how long
 * each call took. One decorator at the one place the model is created, so the coordinator, every agent,
 * the summarizer and the repair step all have the same bounds and none can forget them, and one log line
 * per model call answers "where did the seconds go" without a profiler.
 *
 * <p>The line says which tools the request offered (the coordinator offers the agents, the RAG agent
 * offers {@code searchDocuments}, a plain text call offers none), whether it already carried a tool
 * result (the second call of a tool loop writes the answer), the thinking budget, and the milliseconds
 * from the request to the complete response. Prompt and answer text are never logged.
 */
public final class LimitedLlm extends BaseLlm {

    private static final Logger log = LoggerFactory.getLogger(LimitedLlm.class);

    private final BaseLlm delegate;
    private final HttpOptions options;

    public LimitedLlm(BaseLlm delegate, int timeoutSeconds, int retries) {
        super(delegate.model());
        this.delegate = delegate;
        this.options = GeminiLimits.httpOptions(timeoutSeconds, retries);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        GenerateContentConfig base = request.config().orElseGet(() -> GenerateContentConfig.builder().build());
        GenerateContentConfig limited = base.toBuilder().httpOptions(options).build();
        LlmRequest limitedRequest = request.toBuilder().config(limited).build();
        String tools = new TreeSet<>(request.tools().keySet()).toString();
        String thinking = base.thinkingConfig().flatMap(t -> t.thinkingBudget()).map(String::valueOf).orElse("default");
        boolean carriesToolResult = request.contents().stream()
                .flatMap(c -> c.parts().orElse(java.util.List.of()).stream())
                .anyMatch(p -> p.functionResponse().isPresent());
        return Flowable.defer(() -> {
            long start = System.nanoTime();
            java.util.concurrent.atomic.AtomicLong firstResponse = new java.util.concurrent.atomic.AtomicLong(-1);
            return delegate.generateContent(limitedRequest, stream)
                    .doOnNext(r -> firstResponse.compareAndSet(-1, System.nanoTime()))
                    .doFinally(() -> {
                        long first = firstResponse.get();
                        long end = System.nanoTime();
                        // model = until the answer arrived; the stream can stay open while ADK runs the tools
                        // the answer asked for, so the end of the stream is a different number
                        log.info("llm call tools={} toolResult={} thinking={} model_ms={} stream_ms={}", tools,
                                carriesToolResult, thinking, (first < 0 ? end - start : first - start) / 1_000_000,
                                (end - start) / 1_000_000);
                    });
        });
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        return delegate.connect(request);
    }
}
