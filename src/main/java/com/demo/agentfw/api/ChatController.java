package com.demo.agentfw.api;

import com.demo.agentfw.core.ChatResult;
import com.demo.agentfw.core.ChatService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The chat API. {@code POST /api/chat} returns the finished turn as JSON. {@code POST /api/chat/stream}
 * returns the same turn as server-sent events: one {@code stage} event each time the turn enters a
 * stage (routing, searching, reranking, answering, verifying), then one {@code result} event with the
 * same body as the JSON endpoint, or one {@code error} event. The answer itself is never streamed
 * token by token: it is not shown until it has been verified.
 */
@RestController
@RequestMapping("/api/chat")
class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;

    private final ChatService chat;
    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "chat-stream");
        t.setDaemon(true);
        return t;
    });

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping
    ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        ChatResult result = chat.chat(request.sessionId(), request.effectiveMessage(), request.fileId(), null);
        return ChatResponse.from(result);
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        String message = request.effectiveMessage();           // validated before the stream starts
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        executor.submit(() -> {
            try {
                ChatResult result = chat.chat(request.sessionId(), message, request.fileId(), event -> {
                    if (!send(emitter, "stage", event)) {
                        throw new ClientGone();          // the progress hook drops this listener
                    }
                });
                send(emitter, "result", ChatResponse.from(result));
            } catch (RuntimeException e) {
                ErrorMapping.Mapped mapped = ErrorMapping.of(e);
                if (mapped.status().is5xxServerError() && !(e instanceof ChatService.ChatFailedException)) {
                    log.error("stream failed: {}", e.toString(), e);
                }
                send(emitter, "error", mapped.error());
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** False when the browser has gone away; the turn then finishes and is simply not delivered. */
    private static boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    /** Thrown inside a progress listener so that the turn's progress hook drops the listener. */
    private static final class ClientGone extends RuntimeException {
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
