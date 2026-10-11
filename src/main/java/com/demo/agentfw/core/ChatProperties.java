package com.demo.agentfw.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param rewriteFollowUps rewrite a message that refers back to the conversation into a standalone one
 *                         before it reaches the coordinator (the RAG agent's session is isolated, so it
 *                         cannot see the history). Costs one short model call on every turn after the first
 * @param historyTurns     previous turns shown to the rewriter
 * @param maxSessions      chat sessions whose history is kept in memory (oldest dropped first)
 */
@ConfigurationProperties("agentfw.chat")
public record ChatProperties(
        @DefaultValue("true") boolean rewriteFollowUps,
        @DefaultValue("4") int historyTurns,
        @DefaultValue("500") int maxSessions) {

    public ChatProperties {
        if (historyTurns < 1 || maxSessions < 1) {
            throw new IllegalArgumentException("agentfw.chat.history-turns and max-sessions must be at least 1");
        }
    }

    public static ChatProperties defaults() {
        return new ChatProperties(true, 4, 500);
    }
}
