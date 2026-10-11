package com.demo.agentfw.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns a message that refers back to the conversation ("and what about its price?", "make it
 * shorter") into one that stands alone, using the last few turns. The RAG agent runs in an isolated
 * session and searches with what it is given, so without this a follow-up would be searched as the
 * literal words "its price".
 *
 * <p>One short model call per turn that has history. A message that is already standalone comes back
 * unchanged. The rewrite is only used when it is plausible (not empty, not much longer than the
 * original); otherwise the original message is used, so a bad rewrite can only cost a worse search,
 * never an error.
 */
@Component
public class FollowUpRewriter {

    private static final Logger log = LoggerFactory.getLogger(FollowUpRewriter.class);

    static final String SYSTEM = """
            You prepare the latest message of a chat for a system that cannot see the conversation. \
            If the latest message refers back to the conversation (pronouns such as it, they or that; \
            phrases such as "and the other one", "more about it", "shorter", "why"), rewrite it as ONE \
            complete, self-contained message that names the things it refers to, using only the \
            conversation shown. If it is already self-contained, return it exactly unchanged. Never \
            answer the message, never add facts, keep the user's own wording where possible, and reply \
            with the message text only: no quotes, no explanation.""";

    private static final int MAX_EXTRA_CHARS = 300;

    private final TextGenerator generator;

    public FollowUpRewriter(TextGenerator generator) {
        this.generator = generator;
    }

    /**
     * @return the standalone message, or {@code message} itself when there is no history, the model
     *         call fails, or its answer is not plausible
     */
    public String rewrite(List<ChatHistory.Turn> history, String message) {
        if (history.isEmpty() || message == null || message.isBlank()) {
            return message;
        }
        StringBuilder user = new StringBuilder("Conversation so far:\n");
        for (ChatHistory.Turn t : history) {
            user.append("User: ").append(t.user()).append("\nAssistant: ").append(t.assistant()).append("\n\n");
        }
        user.append("Latest message: ").append(message.strip());
        try {
            String out = generator.generate(SYSTEM, user.toString());
            String cleaned = clean(out);
            if (cleaned.isEmpty() || cleaned.length() > message.length() * 3 + MAX_EXTRA_CHARS) {
                log.warn("follow-up rewrite discarded (implausible output of {} chars)", cleaned.length());
                return message;
            }
            return cleaned;
        } catch (RuntimeException e) {
            log.warn("follow-up rewrite failed, using the message as written: {}", e.toString());
            return message;
        }
    }

    private static String clean(String out) {
        if (out == null) {
            return "";
        }
        String s = out.strip();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s.replaceAll("\\s*\\R\\s*", " ");
    }
}
