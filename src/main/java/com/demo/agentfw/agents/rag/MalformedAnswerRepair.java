package com.demo.agentfw.agents.rag;

import com.demo.agentfw.core.TextGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * One retry for one failure: the RAG agent's output was not a parseable JSON object (typically a
 * dropped quote mark, with correct content). The model is shown its own output and asked to return the
 * same object as valid JSON, nothing else changed. The repaired text goes through the
 * {@link GroundingVerifier} like any other answer, so a repair can never add an unverified claim: if it
 * changes a quote, the quote check fails.
 *
 * <p>Only malformed JSON is retried. A quote that is not in the chunk, a number that is not in its
 * quote or an unknown ref is a verification failure and is not retried (the decision of the user).
 */
@Component
public class MalformedAnswerRepair {

    private static final Logger log = LoggerFactory.getLogger(MalformedAnswerRepair.class);

    static final String SYSTEM = """
            You repair a malformed JSON object. Reply with ONLY the corrected JSON object: no markdown \
            fences, no other text.
            Fix only the JSON syntax (missing or misplaced quote marks, commas, brackets). Keep every value \
            exactly as written: do not add, remove, shorten, reword or correct any text, ref or quote.
            The object has this shape: {"status":"ANSWERED","claims":[{"text":"...","ref":"...","quote":"..."}]} \
            or {"status":"NOT_FOUND"}.
            """;

    private static final int MAX_INPUT_CHARS = 20_000;

    private final TextGenerator generator;

    public MalformedAnswerRepair(TextGenerator generator) {
        this.generator = generator;
    }

    /** The repaired text, or empty when the model call failed (the caller keeps the original output). */
    public Optional<String> repair(String malformedOutput) {
        if (malformedOutput == null || malformedOutput.isBlank()) {
            return Optional.empty();
        }
        String input = malformedOutput.length() > MAX_INPUT_CHARS
                ? malformedOutput.substring(0, MAX_INPUT_CHARS) : malformedOutput;
        try {
            String repaired = generator.generate(SYSTEM, "Malformed output to repair:\n\n" + input);
            return repaired == null || repaired.isBlank() ? Optional.empty() : Optional.of(repaired);
        } catch (RuntimeException e) {
            log.warn("repair of a malformed answer failed: {}", e.toString());
            return Optional.empty();
        }
    }
}
