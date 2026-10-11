package com.demo.agentfw.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnProgressTest {

    @Test
    void recordsEventsInOrderAndPushesThemToTheListener() {
        TurnProgress progress = new TurnProgress();
        List<StageEvent> pushed = new ArrayList<>();
        progress.register("req-1", pushed::add);

        progress.emit("req-1", "routing", "");
        progress.emit("req-1", "searching", "");

        assertThat(pushed).extracting(StageEvent::stage).containsExactly("routing", "searching");
        assertThat(progress.snapshot("req-1")).extracting(StageEvent::stage).containsExactly("routing", "searching");
        assertThat(progress.snapshot("req-1").get(1).elapsedMillis())
                .isGreaterThanOrEqualTo(progress.snapshot("req-1").get(0).elapsedMillis());
    }

    @Test
    void aListenerThatFailsIsDroppedButTheEventsAreStillRecorded() {
        TurnProgress progress = new TurnProgress();
        int[] calls = {0};
        progress.register("req-1", e -> {
            calls[0]++;
            throw new IllegalStateException("browser went away");
        });

        progress.emit("req-1", "routing", "");
        progress.emit("req-1", "searching", "");

        assertThat(calls[0]).isEqualTo(1);                       // not called again after the failure
        assertThat(progress.snapshot("req-1")).hasSize(2);
    }

    @Test
    void anUnknownTurnIsIgnoredAndUnregisterForgetsTheTurn() {
        TurnProgress progress = new TurnProgress();

        progress.emit("nobody", "routing", "");              // a tool run outside a chat turn
        progress.register("req-1", null);
        progress.emit("req-1", "routing", "");
        progress.unregister("req-1");

        assertThat(progress.snapshot("nobody")).isEmpty();
        assertThat(progress.snapshot("req-1")).isEmpty();
    }
}
