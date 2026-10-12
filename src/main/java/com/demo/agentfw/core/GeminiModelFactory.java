package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini through ADK's wrapper, with the key from configuration. The model is built lazily, so the
 * application (and every test that never runs an agent) starts without a key.
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.llm", name = "provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiModelFactory implements ModelFactory {

    private final LlmProperties props;
    private final LlmLimitsProperties limits;
    private final LlmRoutingProperties routing;
    private BaseLlm model;
    private BaseLlm routingModel;

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiModelFactory(LlmProperties props, LlmLimitsProperties limits, LlmRoutingProperties routing) {
        this.props = props;
        this.limits = limits;
        this.routing = routing;
    }

    public GeminiModelFactory(LlmProperties props, LlmLimitsProperties limits) {
        this(props, limits, LlmRoutingProperties.none());
    }

    public GeminiModelFactory(LlmProperties props) {
        this(props, LlmLimitsProperties.defaults());
    }

    @Override
    public synchronized BaseLlm create() {
        if (model == null) {
            if (props.apiKey() == null || props.apiKey().isBlank()) {
                throw new IllegalStateException(
                        "No Gemini API key: set GOOGLE_API_KEY in .env (see .env.example) or the environment.");
            }
            // every call, whoever makes it, has a timeout and at most the configured retries on 429/503
            model = new LimitedLlm(new Gemini(props.model(), props.apiKey()), limits.requestTimeoutSeconds(),
                    limits.retries());
        }
        return model;
    }

    @Override
    public synchronized BaseLlm createForRouting() {
        if (routing.coordinatorModel().isEmpty() || routing.coordinatorModel().equals(props.model())) {
            return create();
        }
        if (routingModel == null) {
            if (props.apiKey() == null || props.apiKey().isBlank()) {
                throw new IllegalStateException(
                        "No Gemini API key: set GOOGLE_API_KEY in .env (see .env.example) or the environment.");
            }
            routingModel = new LimitedLlm(new Gemini(routing.coordinatorModel(), props.apiKey()),
                    limits.requestTimeoutSeconds(), limits.retries());
        }
        return routingModel;
    }

    @Override
    public String modelName() {
        return props.model();
    }
}
