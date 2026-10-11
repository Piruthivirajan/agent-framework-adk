package com.demo.agentfw.eval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndReportTest {

    private static E2eOutcome o(String id, String type, String verdict, boolean strict, boolean answered,
                                String reason, List<String> problems, long ms, int searches) {
        return new E2eOutcome(id, type, "calibration", "question " + id, verdict, strict, answered,
                answered ? "answer " + id : "Not found in the reference documents.",
                answered ? List.of("doc.pdf p. 1") : List.of(), reason, "rag_agent", problems, searches, 1.5, ms,
                "{}", null);
    }

    private static E2eRun sample() {
        List<E2eOutcome> l = new ArrayList<>();
        l.add(o("in-1", "IN_CORPUS", "CORRECT", true, true, "", List.of(), 10_000, 1));
        l.add(o("in-2", "IN_CORPUS", "CORRECT", false, true, "", List.of(), 12_000, 2));       // widened only
        l.add(o("in-3", "IN_CORPUS", "FALSE_NOT_FOUND", false, false, "model", List.of(), 14_000, 3));
        l.add(o("in-4", "IN_CORPUS", "FALSE_NOT_FOUND", false, false,
                "verifier: claim 1: quote not found in chunk r1c1", List.of("claim 1: quote not found in chunk r1c1"),
                16_000, 1));
        l.add(o("in-5", "IN_CORPUS", "FALSE_ANSWER", false, true, "", List.of(), 9_000, 1));
        l.add(o("far-1", "OUT_FAR", "CORRECT", false, false, "retrieval-floor", List.of(), 8_000, 1));
        l.add(o("aws-1", "OUT_AWS", "CORRECT", false, false, "model", List.of(), 11_000, 2));
        l.add(o("aws-2", "OUT_AWS", "FALSE_ANSWER", false, true, "", List.of(), 13_000, 2));
        return new E2eRun("test", "gemini-3.8-flash", 128, null, "2026-10-11T00:00:00Z", l);
    }

    @Test
    void countsCorrectFalseAnswersAndFalseNotFoundPerType() {
        String md = EndToEndReport.render(sample(), null);

        // type | n | correct | of which strict | false answers | false not found | errors
        assertThat(md).contains("| IN_CORPUS | 5 | 2 | 1 | 1 | 2 | 0 |");
        assertThat(md).contains("| OUT_FAR | 1 | 1 | - | 0 | 0 | 0 |");
        assertThat(md).contains("| OUT_AWS | 2 | 1 | - | 1 | 0 | 0 |");
        assertThat(md).doesNotContain("| OUT_NEAR |");
    }

    @Test
    void separatesVerifierRejectionsFromModelAndFloorNotFound() {
        String md = EndToEndReport.render(sample(), null);

        assertThat(md).contains("| retrieval floor (nothing relevant retrieved) | 1 |");
        assertThat(md).contains("| the model itself answered NOT_FOUND | 2 |");
        assertThat(md).contains("| **the verifier replaced the answer** | 1 |");
        assertThat(md).contains("False \"not found\" caused by the verifier: 1 of 5 in-corpus questions (20.0%)");
        assertThat(md).contains("| in-4 | IN_CORPUS | claim 1: quote not found in chunk r1c1 | question in-4 |");
    }

    @Test
    void listsFalseNotFoundAndFalseAnswersForReview() {
        String md = EndToEndReport.render(sample(), null);

        assertThat(md).contains("| in-3 | model | 1.50 | 3 | question in-3 |");
        assertThat(md).contains("**aws-2** (OUT_AWS) question aws-2");
        assertThat(md).contains("**in-5** (IN_CORPUS)");
    }

    @Test
    void reportsLatencyAndPutsFindingsFirst() {
        String md = EndToEndReport.render(sample(), "## Findings\n\nhand-written");

        assertThat(md.indexOf("## Findings")).isLessThan(md.indexOf("## Per question type"));
        assertThat(md).contains("| all | 8 |");
        assertThat(md).contains("| answered | 4 |");
        assertThat(md).contains("coordinator thinking budget 128");
        assertThat(md).contains("## Every question and answer");
    }

    @Test
    void aSavedRunSurvivesAJsonRoundTrip() {
        JsonMapper mapper = JsonMapper.builder().build();
        E2eRun run = sample();

        assertThat(mapper.readValue(mapper.writeValueAsString(run), E2eRun.class)).isEqualTo(run);
    }
}
