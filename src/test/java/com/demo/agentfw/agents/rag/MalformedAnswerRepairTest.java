package com.demo.agentfw.agents.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MalformedAnswerRepairTest {

    private static final String SLIPPED = "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"t\",\"ref\":\"r1c3,quote\":\"q\"}]}";

    @Test
    void sendsTheMalformedOutputWithAnInstructionToChangeOnlySyntax() {
        List<String> systems = new ArrayList<>();
        List<String> users = new ArrayList<>();
        MalformedAnswerRepair repair = new MalformedAnswerRepair((system, user) -> {
            systems.add(system);
            users.add(user);
            return "{\"status\":\"NOT_FOUND\"}";
        });

        assertThat(repair.repair(SLIPPED)).contains("{\"status\":\"NOT_FOUND\"}");

        assertThat(systems.get(0)).contains("Fix only the JSON syntax").contains("do not add, remove, shorten");
        assertThat(users.get(0)).contains(SLIPPED);
    }

    @Test
    void aFailingModelCallMeansNoRepairAndNoException() {
        MalformedAnswerRepair repair = new MalformedAnswerRepair((system, user) -> {
            throw new IllegalStateException("quota exhausted");
        });

        assertThat(repair.repair(SLIPPED)).isEmpty();
    }

    @Test
    void blankInputOrBlankReplyIsNotARepair() {
        List<String> calls = new ArrayList<>();
        MalformedAnswerRepair blankReply = new MalformedAnswerRepair((s, u) -> {
            calls.add(u);
            return "  ";
        });

        assertThat(blankReply.repair("")).isEmpty();
        assertThat(blankReply.repair(null)).isEmpty();
        assertThat(calls).isEmpty();                       // no model call for nothing to repair
        assertThat(blankReply.repair(SLIPPED)).isEmpty();
    }

    @Test
    void veryLongOutputIsCutBeforeItIsSent() {
        List<String> users = new ArrayList<>();
        MalformedAnswerRepair repair = new MalformedAnswerRepair((s, u) -> {
            users.add(u);
            return "{}";
        });

        repair.repair("{" + "x".repeat(100_000));

        assertThat(users.get(0).length()).isLessThan(21_000);
    }
}
