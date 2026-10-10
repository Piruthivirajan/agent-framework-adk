package com.demo.agentfw.retrieval;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real model, so it needs the files in models/ (they are downloaded on the first real
 * application start). Without them the test is skipped: {@code ./mvnw verify} must work offline and
 * must never download anything.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossEncoderRerankerTest {

    private static final Path DIR = Path.of("models/ms-marco-MiniLM-L-6-v2");

    private CrossEncoderReranker reranker;

    @BeforeAll
    void load() {
        boolean present = Files.isRegularFile(DIR.resolve("model.onnx"))
                && Files.isRegularFile(DIR.resolve("tokenizer.json"));
        if (!present) {
            System.out.println("SKIPPED CrossEncoderRerankerTest: model files not in " + DIR
                    + " (they are downloaded and sha256-verified when the application starts with agentfw.rerank.provider=djl)");
        }
        assumeTrue(present, "model files not present");
        reranker = new CrossEncoderReranker(
                new RerankProperties("djl", DIR.toString(), "model.onnx", 512),
                (dir, files) -> { /* never download inside a test */ });
    }

    @AfterAll
    void close() {
        if (reranker != null) {
            reranker.close();
        }
    }

    @Test
    void relevantPassageOutscoresIrrelevantOnes() {
        String query = "How long does a refund take?";
        List<String> passages = List.of(
                "Invoices are emailed on the first business day of each month.",
                "Refund requests are processed within 14 days of approval.",
                "The ocean covers more than seventy percent of the planet surface.");

        Reranker.Result result = reranker.rerank(query, passages);

        assertThat(result.scores()).hasSize(3);
        assertThat(result.scores()[1]).isGreaterThan(result.scores()[0]).isGreaterThan(result.scores()[2]);
        assertThat(result.truncatedPairs()).isZero();
    }

    @Test
    void countsPairsThatExceedTheTokenLimit() {
        String huge = "encryption key management policy ".repeat(600);   // far more than 512 tokens

        Reranker.Result result = reranker.rerank("How are keys managed?", List.of("short passage", huge));

        assertThat(result.truncatedPairs()).isEqualTo(1);
        assertThat(result.scores()).hasSize(2);
    }

    @Test
    void emptyInputGivesEmptyResult() {
        Reranker.Result result = reranker.rerank("anything", List.of());
        assertThat(result.scores()).isEmpty();
        assertThat(result.truncatedPairs()).isZero();
    }

    @Test
    void namesTheModelAndVariantForTraces() {
        assertThat(reranker.modelName()).isEqualTo("ms-marco-MiniLM-L-6-v2/model.onnx");
    }
}
