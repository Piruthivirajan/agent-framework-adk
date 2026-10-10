package com.demo.agentfw.ingestion;

import com.google.genai.errors.ApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses a stubbed {@code BatchCaller}: no network, no API key. */
class GeminiEmbeddingServiceTest {

    private static final int DIMS = 8;

    private static EmbeddingProperties props(int batchSize, int maxRetries) {
        return new EmbeddingProperties("gemini", "gemini-embedding-2", DIMS, batchSize, maxRetries, "");
    }

    private static List<float[]> vectors(int n, int dims) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new float[dims]);
        }
        return out;
    }

    @Test
    void splitsIntoBatchesAndUsesDocumentPrefix() {
        List<List<String>> calls = new ArrayList<>();
        GeminiEmbeddingService svc = new GeminiEmbeddingService(props(32, 0),
                inputs -> {
                    calls.add(inputs);
                    return vectors(inputs.size(), DIMS);
                }, ms -> { });

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            texts.add("chunk " + i);
        }
        List<float[]> out = svc.embedDocuments("handbook", texts);

        assertThat(out).hasSize(70);
        assertThat(calls).extracting(List::size).containsExactly(32, 32, 6);
        assertThat(calls.get(0).get(0)).isEqualTo("title: handbook | text: chunk 0");
        assertThat(calls.get(2).get(5)).isEqualTo("title: handbook | text: chunk 69");
    }

    @Test
    void blankTitleBecomesNone() {
        List<String> seen = new ArrayList<>();
        GeminiEmbeddingService svc = new GeminiEmbeddingService(props(32, 0),
                inputs -> {
                    seen.addAll(inputs);
                    return vectors(inputs.size(), DIMS);
                }, ms -> { });

        svc.embedDocuments("  ", List.of("hello"));

        assertThat(seen).containsExactly("title: none | text: hello");
    }

    @Test
    void queryUsesSearchResultPrefix() {
        List<String> seen = new ArrayList<>();
        GeminiEmbeddingService svc = new GeminiEmbeddingService(props(32, 0),
                inputs -> {
                    seen.addAll(inputs);
                    return vectors(inputs.size(), DIMS);
                }, ms -> { });

        float[] v = svc.embedQuery("refund days");

        assertThat(v).hasSize(DIMS);
        assertThat(seen).containsExactly("task: search result | query: refund days");
    }

    @Test
    void retriesRateLimitWithExponentialBackoffThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        GeminiEmbeddingService svc = new GeminiEmbeddingService(props(32, 5),
                inputs -> {
                    if (attempts.incrementAndGet() <= 2) {
                        throw new ApiException(429, "RESOURCE_EXHAUSTED", "quota");
                    }
                    return vectors(inputs.size(), DIMS);
                }, sleeps::add);

        List<float[]> out = svc.embedDocuments("d", List.of("a", "b"));

        assertThat(out).hasSize(2);
        assertThat(attempts).hasValue(3);
        assertThat(sleeps).containsExactly(1000L, 2000L);
    }

    @Test
    void doesNotRetryClientErrors() {
        AtomicInteger attempts = new AtomicInteger();
        GeminiEmbeddingService svc = new GeminiEmbeddingService(props(32, 5),
                inputs -> {
                    attempts.incrementAndGet();
                    throw new ApiException(400, "INVALID_ARGUMENT", "bad request");
                }, ms -> { });

        assertThatThrownBy(() -> svc.embedDocuments("d", List.of("a")))
                .isInstanceOf(ApiException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void givesUpAfterMaxRetries() {
        AtomicInteger attempts = new AtomicInteger();
        GeminiEmbeddingService svc = new GeminiEmbeddingService(props(32, 2),
                inputs -> {
                    attempts.incrementAndGet();
                    throw new ApiException(503, "UNAVAILABLE", "down");
                }, ms -> { });

        assertThatThrownBy(() -> svc.embedDocuments("d", List.of("a")))
                .isInstanceOf(ApiException.class);
        assertThat(attempts).hasValue(3);   // first try + 2 retries
    }

    @Test
    void rejectsWrongDimensionBecauseTheIndexWouldNotMatch() {
        GeminiEmbeddingService svc = new GeminiEmbeddingService(props(32, 0),
                inputs -> vectors(inputs.size(), DIMS + 1), ms -> { });

        assertThatThrownBy(() -> svc.embedDocuments("d", List.of("a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected " + DIMS);
    }

    @Test
    void rejectsWrongVectorCount() {
        GeminiEmbeddingService svc = new GeminiEmbeddingService(props(32, 0),
                inputs -> vectors(inputs.size() - 1, DIMS), ms -> { });

        assertThatThrownBy(() -> svc.embedDocuments("d", List.of("a", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 vectors for 2 inputs");
    }

    @Test
    void failsFastWithoutApiKey() {
        assertThatThrownBy(() -> new GeminiEmbeddingService(props(32, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_API_KEY");
    }

    @Test
    void propertiesToStringHidesTheKey() {
        EmbeddingProperties p = new EmbeddingProperties("gemini", "m", 768, 32, 5, "super-secret-key");
        assertThat(p.toString()).doesNotContain("super-secret-key");
    }
}
