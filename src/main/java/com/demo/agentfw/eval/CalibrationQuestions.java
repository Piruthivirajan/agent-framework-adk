package com.demo.agentfw.eval;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Loads {@code eval/calibration-questions.json}; extra fields (evidence, absentTerms) are ignored. */
public final class CalibrationQuestions {

    public static final String RESOURCE = "eval/calibration-questions.json";

    /**
     * Questions that tempt the model to answer from memory. Kept apart from the calibration set so they
     * never influence the retrieval floor: they exist to exercise the verifier, not to set a threshold.
     */
    public static final String ADVERSARIAL_RESOURCE = "eval/adversarial-questions.json";

    private CalibrationQuestions() {
    }

    public static List<CalibrationQuestion> load() {
        return load(RESOURCE);
    }

    public static List<CalibrationQuestion> loadAdversarial() {
        return load(ADVERSARIAL_RESOURCE);
    }

    private static List<CalibrationQuestion> load(String resource) {
        try (InputStream in = CalibrationQuestions.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found on the classpath: " + resource);
            }
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource + ": " + e.getMessage(), e);
        }
    }

    static List<CalibrationQuestion> parse(InputStream in) {
        JsonMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        JsonNode root = mapper.readTree(in);
        CalibrationQuestion[] questions = mapper.treeToValue(root.get("questions"), CalibrationQuestion[].class);
        return List.of(questions);
    }
}
