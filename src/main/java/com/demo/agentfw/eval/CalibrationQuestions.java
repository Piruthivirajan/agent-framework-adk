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

    private CalibrationQuestions() {
    }

    public static List<CalibrationQuestion> load() {
        try (InputStream in = CalibrationQuestions.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found on the classpath: " + RESOURCE);
            }
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE + ": " + e.getMessage(), e);
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
