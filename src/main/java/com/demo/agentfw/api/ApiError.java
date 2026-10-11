package com.demo.agentfw.api;

/** The body of every error response: a stable code for the UI and a sentence for the user. */
public record ApiError(String code, String message) {
}
