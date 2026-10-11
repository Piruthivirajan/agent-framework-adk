package com.demo.agentfw.eval;

import com.demo.agentfw.agents.rag.GroundingVerifier;
import com.demo.agentfw.agents.rag.TestRetrieval;
import com.demo.agentfw.core.ChatResult;
import com.demo.agentfw.retrieval.RetrievalTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The verdict rules of the end-to-end report, on hand-made results (no model, no network). */
class EndToEndRunnerTest {

    private static final String FILE = "wellarchitected-framework.pdf";

    private static CalibrationQuestion inCorpus() {
        return new CalibrationQuestion("in-x", "IN_CORPUS", "What is the tool?", "calibration",
                List.of(new CalibrationQuestion.Expected(FILE, List.of(7))), null,
                List.of(new CalibrationQuestion.Expected(FILE, List.of(8))), List.of("adjacent"));
    }

    private static CalibrationQuestion outOfCorpus(String type) {
        return new CalibrationQuestion("out-x", type, "Who won?", "calibration", null, null, null, null);
    }

    private static ChatResult answered(String file, int page) {
        return new ChatResult("An answer. [" + file + ", p. " + page + "]", "rag_agent", true,
                List.of(new GroundingVerifier.Source("r1c1", file, page, "a verbatim quote of enough length")),
                List.of(), new ChatResult.Verification(List.of(), false), List.of("rag_agent"), "{}", "req", 5);
    }

    private static ChatResult notAnswered(String route, List<String> problems, boolean modelSaidNotFound,
                                          List<RetrievalTrace> traces) {
        return new ChatResult("Not found in the reference documents.", route, false, List.of(), traces,
                new ChatResult.Verification(problems, modelSaidNotFound), List.of(), null, "req", 5);
    }

    @Test
    void inCorpusAnsweredFromAStrictPageIsCorrectAndStrictCorrect() {
        E2eOutcome o = EndToEndRunner.classify(inCorpus(), answered(FILE, 7));

        assertThat(o.verdict()).isEqualTo("CORRECT");
        assertThat(o.strictCorrect()).isTrue();
        assertThat(o.answered()).isTrue();
        assertThat(o.citedPages()).containsExactly(FILE + " p. 7");
    }

    @Test
    void inCorpusAnsweredFromAWidenedPageIsCorrectButNotStrict() {
        E2eOutcome o = EndToEndRunner.classify(inCorpus(), answered(FILE, 8));

        assertThat(o.verdict()).isEqualTo("CORRECT");
        assertThat(o.strictCorrect()).isFalse();
    }

    @Test
    void inCorpusAnsweredFromAnUnrelatedPageIsAFalseAnswer() {
        E2eOutcome o = EndToEndRunner.classify(inCorpus(), answered(FILE, 500));

        assertThat(o.verdict()).isEqualTo("FALSE_ANSWER");
        assertThat(o.strictCorrect()).isFalse();
    }

    @Test
    void inCorpusNotAnsweredIsAFalseNotFoundWithItsReason() {
        RetrievalTrace belowFloor = TestRetrieval.service(TestRetrieval.sampleCandidates(), i -> -20.0, null)
                .retrieve("q").trace();

        E2eOutcome floor = EndToEndRunner.classify(inCorpus(), notAnswered("rag_agent", List.of(), true, List.of(belowFloor)));
        E2eOutcome model = EndToEndRunner.classify(inCorpus(), notAnswered("rag_agent", List.of(), true, List.of()));
        E2eOutcome verifier = EndToEndRunner.classify(inCorpus(),
                notAnswered("rag_agent", List.of("claim 1: quote not found in chunk r1c1"), false, List.of()));
        E2eOutcome noTool = EndToEndRunner.classify(inCorpus(),
                notAnswered("none", List.of("no tool was called"), false, List.of()));

        assertThat(floor.verdict()).isEqualTo("FALSE_NOT_FOUND");
        assertThat(floor.notFoundReason()).isEqualTo("retrieval-floor");
        assertThat(model.notFoundReason()).isEqualTo("model");
        assertThat(verifier.notFoundReason()).isEqualTo("verifier: claim 1: quote not found in chunk r1c1");
        assertThat(noTool.notFoundReason()).isEqualTo("no-tool");
    }

    @Test
    void outOfCorpusIsCorrectOnlyWhenNothingIsAnswered() {
        for (String type : List.of("OUT_FAR", "OUT_NEAR", "OUT_AWS")) {
            E2eOutcome notFound = EndToEndRunner.classify(outOfCorpus(type),
                    notAnswered("rag_agent", List.of(), true, List.of()));
            E2eOutcome answered = EndToEndRunner.classify(outOfCorpus(type), answered(FILE, 7));

            assertThat(notFound.verdict()).as(type).isEqualTo("CORRECT");
            assertThat(answered.verdict()).as(type).isEqualTo("FALSE_ANSWER");
            assertThat(answered.strictCorrect()).as(type).isFalse();
        }
    }
}
