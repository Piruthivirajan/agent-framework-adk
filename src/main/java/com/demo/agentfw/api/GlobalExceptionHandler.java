package com.demo.agentfw.api;

import com.demo.agentfw.files.FileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Locale;

/** Every failure of the API as {@code {code, message}}: never a stack trace, never the framework's page. */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final FileProperties files;

    GlobalExceptionHandler(FileProperties files) {
        this.files = files;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(new ApiError("TOO_LARGE",
                "The file is larger than the limit of " + String.format(Locale.ROOT, "%.0f", files.maxBytes() / 1e6)
                        + " MB."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).orElse("The request is not valid.");
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", ErrorMapping.clean(message)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST",
                "The request is incomplete or not valid JSON."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> anyOther(Exception e) {
        ErrorMapping.Mapped mapped = ErrorMapping.of(e);
        if (mapped.status() == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("request failed: {}", e.toString(), e);   // the trace goes to the log, not to the client
        } else if (mapped.status().is5xxServerError()) {
            log.warn("request failed ({}): {}", mapped.error().code(), e.toString());
        }
        return ResponseEntity.status(mapped.status()).body(mapped.error());
    }
}
