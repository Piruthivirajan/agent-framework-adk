package com.demo.agentfw.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FollowUpRewriterTest {

    private static final List<ChatHistory.Turn> HISTORY =
            List.of(new ChatHistory.Turn("What is Amazon SQS?", "A managed message queue service."));

    @Test
    void withoutHistoryThereIsNoModelCall() {
        List<String> calls = new ArrayList<>();
        FollowUpRewriter rewriter = new FollowUpRewriter((s, u) -> {
            calls.add(u);
            return "x";
        });

        assertThat(rewriter.rewrite(List.of(), "What is Amazon SQS?")).isEqualTo("What is Amazon SQS?");
        assertThat(calls).isEmpty();
    }

    @Test
    void thePromptCarriesTheHistoryAndTheInstructionNotToAnswer() {
        List<String> systems = new ArrayList<>();
        List<String> users = new ArrayList<>();
        FollowUpRewriter rewriter = new FollowUpRewriter((s, u) -> {
            systems.add(s);
            users.add(u);
            return "What is the maximum message size in Amazon SQS?";
        });

        String out = rewriter.rewrite(HISTORY, "What is its maximum message size?");

        assertThat(out).isEqualTo("What is the maximum message size in Amazon SQS?");
        assertThat(systems.get(0)).contains("Never answer the message").contains("exactly unchanged");
        assertThat(users.get(0)).contains("User: What is Amazon SQS?").contains("Assistant: A managed message queue")
                .endsWith("Latest message: What is its maximum message size?");
    }

    @Test
    void quotesAndLineBreaksAreRemovedFromTheRewrite() {
        FollowUpRewriter rewriter = new FollowUpRewriter((s, u) -> "\"What is the size\nof an SQS message?\"");

        assertThat(rewriter.rewrite(HISTORY, "and its size?")).isEqualTo("What is the size of an SQS message?");
    }

    @Test
    void anImplausibleRewriteIsDiscarded() {
        FollowUpRewriter blank = new FollowUpRewriter((s, u) -> "   ");
        FollowUpRewriter essay = new FollowUpRewriter((s, u) -> "word ".repeat(500));

        assertThat(blank.rewrite(HISTORY, "and its size?")).isEqualTo("and its size?");
        assertThat(essay.rewrite(HISTORY, "and its size?")).isEqualTo("and its size?");
    }

    @Test
    void historyKeepsOnlyTheLastTurnsAndShortenedAnswers() {
        ChatHistory history = new ChatHistory(new ChatProperties(true, 2, 10));

        history.add("s", "q1", "a1");
        history.add("s", "q2", "a2");
        history.add("s", "q3", "x".repeat(2_000));

        assertThat(history.recent("s")).extracting(ChatHistory.Turn::user).containsExactly("q2", "q3");
        assertThat(history.recent("s").get(1).assistant().length()).isLessThan(600);
        assertThat(history.recent("other")).isEmpty();
    }

    @Test
    void theLeastRecentlyUsedSessionIsDroppedWhenThereAreTooMany() {
        ChatHistory history = new ChatHistory(new ChatProperties(true, 2, 2));

        history.add("a", "q", "a");
        history.add("b", "q", "a");
        history.recent("a");                  // touch a: b becomes the least recently used
        history.add("c", "q", "a");

        assertThat(history.recent("a")).hasSize(1);
        assertThat(history.recent("c")).hasSize(1);
        assertThat(history.recent("b")).isEmpty();
    }
}
