package com.demo.agentfw.api;

import com.demo.agentfw.core.ChatService;
import com.demo.agentfw.files.UploadRejectedException;
import com.mongodb.MongoException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

/**
 * The one place that decides what a failure looks like to the user: an HTTP status, a stable code and
 * a plain sentence. Used by the exception handler and by the streaming endpoint, so a plain request and
 * a stream fail in the same words. A message never carries a stack trace or an API key.
 */
final class ErrorMapping {

    record Mapped(HttpStatus status, ApiError error) {
    }

    private static final Pattern API_KEY = Pattern.compile("AIza[0-9A-Za-z_-]{20,}");
    private static final int MAX_MESSAGE_CHARS = 300;

    private ErrorMapping() {
    }

    static Mapped of(Throwable t) {
        if (t instanceof UploadRejectedException e) {
            return new Mapped(status(e.reason()), new ApiError(e.reason().name(), clean(e.getMessage())));
        }
        if (t instanceof ChatService.ChatFailedException e) {
            return new Mapped(HttpStatus.BAD_GATEWAY, new ApiError("CHAT_FAILED", clean(e.getMessage())));
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
        String masked = API_KEY.matcher(first).replaceAll("***");
        return masked.length() > MAX_MESSAGE_CHARS ? masked.substring(0, MAX_MESSAGE_CHARS) + "..." : masked;
    }
}
