package com.demo.agentfw.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Where the parts of a turn (the search tool, the file agent, the service itself) report which stage
 * they are in, keyed by the turn's request id. The events are always recorded, so a plain JSON answer
 * can carry a timeline too, and they are pushed to a listener when the client asked for a stream. A
 * listener that fails (the browser closed the connection) is dropped; it never fails the turn.
 */
@Component
public class TurnProgress {

    private static final Logger log = LoggerFactory.getLogger(TurnProgress.class);

    private static final class Sink {
        final long startNanos = System.nanoTime();
        final List<StageEvent> events = new ArrayList<>();
        volatile Consumer<StageEvent> listener;

        Sink(Consumer<StageEvent> listener) {
            this.listener = listener;
        }
    }

    private final Map<String, Sink> sinks = new ConcurrentHashMap<>();

    public void register(String requestId, Consumer<StageEvent> listener) {
        sinks.put(requestId, new Sink(listener));
    }

    /** Records a stage start for the turn; a no-op for an unknown turn (a tool run outside a chat turn). */
    public void emit(String requestId, String stage, String detail) {
        Sink sink = sinks.get(requestId);
        if (sink == null) {
            return;
        }
        StageEvent event = new StageEvent(stage, detail == null ? "" : detail,
                (System.nanoTime() - sink.startNanos) / 1_000_000);
        synchronized (sink.events) {
            sink.events.add(event);
        }
        Consumer<StageEvent> listener = sink.listener;
        if (listener != null) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                sink.listener = null;
                log.info("progress listener of request {} dropped: {}", requestId, e.toString());
            }
        }
    }

    /** The events so far, in order. */
    public List<StageEvent> snapshot(String requestId) {
        Sink sink = sinks.get(requestId);
        if (sink == null) {
            return List.of();
        }
        synchronized (sink.events) {
            return List.copyOf(sink.events);
        }
    }

    public void unregister(String requestId) {
        sinks.remove(requestId);
    }
}
