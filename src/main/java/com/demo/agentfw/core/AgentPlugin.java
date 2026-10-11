package com.demo.agentfw.core;

import com.google.adk.agents.BaseAgent;

/**
 * The contract of a pluggable agent: a Spring bean implementing this interface becomes a tool of
 * the coordinator, with no change anywhere else. {@code name} and {@code description} are what the
 * coordinator's model reads to choose a route.
 */
public interface AgentPlugin {

    /** Unique, identifier-like name, for example {@code rag_agent}. It is also the tool name. */
    String name();

    /** One or two sentences: what this agent answers and when to use it. Read by the coordinator. */
    String description();

    BaseAgent build(AgentContext context);

    /**
     * True when this agent's result is the user-visible answer, so the coordinator is not given a
     * further model call to rephrase it (ADK's "skip summarization"). It also means the turn ends
     * after this tool: the coordinator cannot chain another tool behind it.
     */
    default boolean finalAnswer() {
        return true;
    }
}
