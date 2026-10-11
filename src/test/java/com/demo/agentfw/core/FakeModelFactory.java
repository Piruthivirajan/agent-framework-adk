package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The model factory of every Spring test: it hands out one {@link ScriptedLlm} that a test can script.
 * It exists only with {@code agentfw.llm.provider=fake} and lives in src/test, so no test can reach
 * the real model by accident (the real factory is not created then).
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.llm", name = "provider", havingValue = "fake")
public class FakeModelFactory implements ModelFactory {

    private final ScriptedLlm llm = new ScriptedLlm();

    @Override
    public BaseLlm create() {
        return llm;
    }

    @Override
    public String modelName() {
        return "scripted";
    }

    public ScriptedLlm llm() {
        return llm;
    }
}
