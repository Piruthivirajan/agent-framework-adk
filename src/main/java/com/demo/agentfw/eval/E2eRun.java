package com.demo.agentfw.eval;

import java.util.List;

/** A saved end-to-end run with the settings that make its latency interpretable. */
public record E2eRun(String label, String model, Integer coordinatorThinkingBudget, Integer agentThinkingBudget,
                     String startedAt, List<E2eOutcome> outcomes) {
}
