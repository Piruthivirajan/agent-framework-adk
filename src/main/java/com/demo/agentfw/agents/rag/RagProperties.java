package com.demo.agentfw.agents.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxSearches        searches the RAG agent may run per request; a further call gets "search limit
 *                           reached" instead of a search. Enforced in code, not only in the prompt
 * @param stopAfterFloorMiss when the first search of a request is below the retrieval floor, refuse further
 *                           searches (nothing relevant exists, so rewriting only costs time)
 * @param instructionSuffix  text appended to the agent's instruction; empty in production. It exists for the
 *                           adversarial experiment: a deliberately permissive suffix shows whether the
 *                           verifier, not the prompt, is what stops an answer from memory
 */
@ConfigurationProperties("agentfw.rag")
public record RagProperties(
        @DefaultValue("2") int maxSearches,
        @DefaultValue("true") boolean stopAfterFloorMiss,
        @DefaultValue("") String instructionSuffix) {

    public RagProperties {
        if (maxSearches < 1) {
            throw new IllegalArgumentException("agentfw.rag.max-searches must be at least 1");
        }
    }

    /** The configured defaults, for code and tests that are not built by Spring. */
    public static RagProperties defaults() {
        return new RagProperties(2, true, "");
    }
}
