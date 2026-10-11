package com.demo.agentfw.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider                  {@code gemini}, or {@code fake} (tests; the fake lives in src/test)
 * @param model                     model name, used by the coordinator and every agent
 * @param coordinatorThinkingBudget thinking tokens for the coordinator, which only routes; null leaves the model default
 * @param agentThinkingBudget       thinking tokens for the other agents; null leaves the model default
 * @param maxLlmCalls               upper bound of model calls in one request (a runaway-loop and cost guard)
 */
@ConfigurationProperties("agentfw.llm")
public record LlmProperties(
        @DefaultValue("gemini") String provider,
        @DefaultValue("gemini-3.8-flash") String model,
        @DefaultValue("") String apiKey,
        Integer coordinatorThinkingBudget,
        Integer agentThinkingBudget,
        @DefaultValue("12") int maxLlmCalls) {

    @Override
    public String toString() {   // never print the key
        return "LlmProperties[provider=" + provider + ", model=" + model + ", apiKey=***, coordinatorThinkingBudget="
                + coordinatorThinkingBudget + ", agentThinkingBudget=" + agentThinkingBudget
                + ", maxLlmCalls=" + maxLlmCalls + "]";
    }
}
