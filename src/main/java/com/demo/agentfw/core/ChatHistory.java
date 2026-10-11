package com.demo.agentfw.core;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last few turns of each chat session, in memory, for resolving follow-up questions. Bounded in
 * both directions: a few turns per session and a few hundred sessions (the least recently used one
 * is dropped). Answers are cut short: the rewriter only needs enough to know what "it" was.
 */
@Component
public class ChatHistory {

    public record Turn(String user, String assistant) {
    }

    static final int MAX_ANSWER_CHARS = 500;
    static final int MAX_USER_CHARS = 500;

    private final int turnsPerSession;
    private final Map<String, List<Turn>> sessions;

    public ChatHistory(ChatProperties props) {
        this.turnsPerSession = props.historyTurns();
        int max = props.maxSessions();
        this.sessions = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Turn>> eldest) {
                return size() > max;
            }
        });
    }

    /** The most recent turns, oldest first. */
    public List<Turn> recent(String sessionId) {
        synchronized (sessions) {
            List<Turn> turns = sessions.get(sessionId);
            return turns == null ? List.of() : List.copyOf(turns);
        }
    }

    public void add(String sessionId, String user, String assistant) {
        synchronized (sessions) {
            List<Turn> turns = sessions.computeIfAbsent(sessionId, id -> new ArrayList<>());
            turns.add(new Turn(cut(user, MAX_USER_CHARS), cut(assistant, MAX_ANSWER_CHARS)));
            while (turns.size() > turnsPerSession) {
                turns.remove(0);
            }
        }
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
