package com.demo.agentfw.api;

import com.demo.agentfw.core.ChatService;
import com.demo.agentfw.files.UploadRejectedException;
import com.demo.agentfw.ingestion.KnowledgeBaseService;
import com.demo.agentfw.mcp.McpFailure;
import com.demo.agentfw.mcp.McpRegistry;
import com.mongodb.MongoException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

/**
 * The one place that decides what a failure looks like to the user: an HTTP status, a stable code and
 * a plain sentence. Used by the exception handler and by the streaming endpoint, so a plain request and
 * a stream fail in the same words. A message never carries a stack trace or an API key.
 */
final class ErrorMapping {

    record Mapped(HttpStatus status, ApiError error) {
    }

    private static final int MAX_MESSAGE_CHARS = 300;

    private ErrorMapping() {
    }

    static Mapped of(Throwable t) {
        if (t instanceof UploadRejectedException e) {
            return new Mapped(status(e.reason()), new ApiError(e.reason().name(), clean(e.getMessage())));
        }
        if (t instanceof KnowledgeBaseService.KbRejectedException e) {
            return new Mapped(status(e.reason()), new ApiError(e.reason().name(), clean(e.getMessage())));
        }
        if (t instanceof McpRegistry.McpBlockedException e) {
            return new Mapped(HttpStatus.BAD_REQUEST, new ApiError(McpFailure.BLOCKED_ADDRESS, clean(e.getMessage())));
        }
        if (t instanceof McpRegistry.NoSuchElementException e) {
            return new Mapped(HttpStatus.NOT_FOUND, new ApiError("NOT_FOUND", clean(e.getMessage())));
        }
        if (t instanceof ChatService.ChatFailedException e) {
            return e.isUserMessage()
                    ? new Mapped(HttpStatus.BAD_GATEWAY, new ApiError("CHAT_FAILED", clean(e.getMessage())))
                    : modelFailure(e);
        }
        if (t instanceof MongoException || t instanceof DataAccessException) {
            return new Mapped(HttpStatus.SERVICE_UNAVAILABLE, new ApiError("DATABASE_UNAVAILABLE",
                    "The document store is not reachable right now. Please try again in a moment."));
        }
        if (t instanceof IllegalArgumentException e) {
            return new Mapped(HttpStatus.BAD_REQUEST, new ApiError("BAD_REQUEST", clean(e.getMessage())));
        }
        return new Mapped(HttpStatus.INTERNAL_SERVER_ERROR,
                new ApiError("INTERNAL_ERROR", "Something went wrong on the server. Please try again."));
    }

    /**
     * What a failed model call means to the user. The provider's text is only used to choose the
     * sentence; it is never copied into it (it can carry request details), and it stays in the log.
     */
    static Mapped modelFailure(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null && all.length() < 3000; c = c.getCause() == c ? null : c.getCause()) {
            all.append(c.getClass().getSimpleName()).append(' ').append(c.getMessage()).append(' ');
        }
        String text = all.toString().toLowerCase(java.util.Locale.ROOT);
        if (text.contains("no gemini api key")) {
            return model(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_CONFIGURED", "No API key for the language model "
                    + "is configured (GOOGLE_API_KEY). Ask the administrator to set it.");
        }
        if (text.contains("429") || text.contains("resource_exhausted") || text.contains("rate limit")
                || text.contains("quota")) {
            return model(HttpStatus.TOO_MANY_REQUESTS, "MODEL_RATE_LIMITED", "The language model is rate limited "
                    + "right now (too many requests, or the quota is used up). Please wait a minute and try again.");
        }
        if (text.contains("deadline_exceeded") || text.contains("timed out") || text.contains("timeout")
                || text.contains("504")) {
            return model(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT", "The language model took too long to answer. "
                    + "Please try again.");
        }
        if (text.contains("api key") || text.contains("api_key") || text.contains("unauthenticated")
                || text.contains("permission_denied") || text.contains("401") || text.contains("403")) {
            return model(HttpStatus.BAD_GATEWAY, "MODEL_AUTH", "The language model refused the API key configured "
                    + "for this application. Ask the administrator to check GOOGLE_API_KEY.");
        }
        if (text.contains("503") || text.contains("unavailable") || text.contains("overloaded")) {
            return model(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE", "The language model is overloaded or "
                    + "unavailable right now. Please try again shortly.");
        }
        if (text.contains("llmcallslimit") || text.contains("limit exceeded")) {
            return model(HttpStatus.BAD_GATEWAY, "MODEL_LOOP", "The request needed too many model calls and was "
                    + "stopped. Try asking in a simpler way.");
        }
        return model(HttpStatus.BAD_GATEWAY, "CHAT_FAILED", "The language model could not complete the request. "
                + "Please try again.");
    }

    private static Mapped model(HttpStatus status, String code, String message) {
        return new Mapped(status, new ApiError(code, message));
    }

    static HttpStatus status(KnowledgeBaseService.Reason reason) {
        return switch (reason) {
            case EMPTY, BAD_NAME -> HttpStatus.BAD_REQUEST;
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case NOT_PDF -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case NO_TEXT_LAYER, UNREADABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case EXISTS, BUSY -> HttpStatus.CONFLICT;
            case INGESTION_FAILED -> HttpStatus.BAD_GATEWAY;
        };
    }

    static HttpStatus status(UploadRejectedException.Reason reason) {
        return switch (reason) {
            case EMPTY -> HttpStatus.BAD_REQUEST;
            case TOO_LARGE, TOO_MUCH_TEXT -> HttpStatus.CONTENT_TOO_LARGE;
            case UNSUPPORTED_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case NO_TEXT, UNREADABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TOO_MANY_FILES -> HttpStatus.CONFLICT;
        };
    }

    /** First line only, bounded, with anything that looks like an API key masked. */
    static String clean(String message) {
        if (message == null || message.isBlank()) {
            return "The request could not be completed.";
        }
        String first = message.strip().lines().findFirst().orElse("");
        String masked = com.demo.agentfw.core.Secrets.mask(first);
        return masked.length() > MAX_MESSAGE_CHARS ? masked.substring(0, MAX_MESSAGE_CHARS) + "..." : masked;
    }
}
