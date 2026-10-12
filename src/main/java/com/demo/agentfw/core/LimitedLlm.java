package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Puts the request limits of {@link GeminiLimits} on every call of the wrapped model. One decorator at
 * the one place the model is created, so the coordinator, every agent, the summarizer and the repair
 * step all have the same bounds and none can forget them.
 */
public final class LimitedLlm extends BaseLlm {

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
        return delegate.generateContent(request.toBuilder().config(limited).build(), stream);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        return delegate.connect(request);
    }
}
