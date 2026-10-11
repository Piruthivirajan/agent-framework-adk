package com.demo.agentfw.core;

/**
 * One plain model call: a system instruction and a user text in, the model's text out. For code that
 * uses the model as a function (repairing a JSON object, summarizing a segment) instead of as an
 * agent that chooses tools. A seam so tests can replace the model with a function.
 */
@FunctionalInterface
public interface TextGenerator {

    /** @throws IllegalStateException when the model cannot be reached or returns an error */
    String generate(String systemInstruction, String userText);
}
