package com.demo.agentfw.agents.sample;

import com.demo.agentfw.api.ChatResponse;
import com.demo.agentfw.core.ChatResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClockAgentPluginTest {

    private static final Clock NOON_UTC = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC);

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(ClockAgentPlugin.class);

    @Test
    void theSampleAgentIsOffByDefault() {
        contexts.run(ctx -> assertThat(ctx).doesNotHaveBean(ClockAgentPlugin.class));
        contexts.withPropertyValues("agentfw.sample-agent.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ClockAgentPlugin.class));
    }

    @Test
    void theConfigFlagAloneEnablesIt() {
        contexts.withPropertyValues("agentfw.sample-agent.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ClockAgentPlugin.class));
    }

    @Test
    void theToolReadsTheClockInTheRequestedZone() {
        ClockAgentPlugin plugin = new ClockAgentPlugin(NOON_UTC);

        Map<String, Object> kolkata = plugin.currentTime("Asia/Kolkata");

        assertThat(kolkata.get("zone")).isEqualTo("Asia/Kolkata");
        assertThat(kolkata.get("dateTime").toString()).isEqualTo("Monday 12 October 2026, 17:30:00");
    }

    @Test
    void anUnknownOrMissingZoneFallsBackToUtc() {
        ClockAgentPlugin plugin = new ClockAgentPlugin(NOON_UTC);

        assertThat(plugin.currentTime("Not/AZone").get("zone")).isEqualTo("UTC");
        assertThat(plugin.currentTime(null).get("zone")).isEqualTo("UTC");
    }

    @Test
    void itsAnswerIsShownAsAnAgentAnswerNotAsACitedOne() {
        ChatResult r = new ChatResult("It is 12:00.", ClockAgentPlugin.NAME, false, List.of(), List.of(),
                new ChatResult.Verification(List.of(), false), List.of(ClockAgentPlugin.NAME), null, "id", 5);

        assertThat(ChatResponse.from(r).kind()).isEqualTo("agent_answer");
    }

    @Test
    void aFailedOtherAgentIsANoticeNotAnAgentAnswer() {
        ChatResult r = new ChatResult("Not found in the reference documents.", ClockAgentPlugin.NAME, false,
                List.of(), List.of(),
                new ChatResult.Verification(List.of("the agent returned no answer text"), false),
                List.of(ClockAgentPlugin.NAME), null, "id", 5);

        assertThat(ChatResponse.from(r).kind()).isEqualTo("not_found");
    }
}
