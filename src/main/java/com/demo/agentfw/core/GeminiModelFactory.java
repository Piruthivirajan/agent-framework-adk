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
    private BaseLlm model;

    public GeminiModelFactory(LlmProperties props) {
        this.props = props;
    }

    @Override
    public synchronized BaseLlm create() {
        if (model == null) {
            if (props.apiKey() == null || props.apiKey().isBlank()) {
                throw new IllegalStateException(
                        "No Gemini API key: set GOOGLE_API_KEY in .env (see .env.example) or the environment.");
            }
            model = new Gemini(props.model(), props.apiKey());
        }
        return model;
    }

    @Override
    public String modelName() {
        return props.model();
    }
}
