package com.demo.agentfw.files;

/**
 * An upload that cannot be accepted. The message is written for the user and is shown as is; the
 * code lets the API map it to a status and lets tests assert the reason.
 */
public class UploadRejectedException extends RuntimeException {

    public enum Reason {
        EMPTY, TOO_LARGE, UNSUPPORTED_TYPE, NO_TEXT, TOO_MUCH_TEXT, UNREADABLE, TOO_MANY_FILES
    }

    private final Reason reason;

    public UploadRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
