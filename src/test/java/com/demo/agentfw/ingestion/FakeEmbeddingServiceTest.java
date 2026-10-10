package com.demo.agentfw.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FakeEmbeddingServiceTest {

    private final FakeEmbeddingService fake = new FakeEmbeddingService(768, "fake-model");

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    @Test
    void vectorsHaveConfiguredSizeAndUnitNorm() {
        float[] v = fake.embedQuery("Refund requests are processed within 14 days.");

        assertThat(v).hasSize(768);
        assertThat(dot(v, v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void isDeterministic() {
        assertThat(fake.embedQuery("same text")).isEqualTo(fake.embedQuery("same text"));
    }

    @Test
    void sharedWordsMeanHigherSimilarity() {
        float[] query = fake.embedQuery("refund days");
        float[] refund = fake.embedQuery("Refund requests are processed within 14 days.");
        float[] invoice = fake.embedQuery("Invoices are emailed on the first business day.");

        assertThat(dot(query, refund)).isGreaterThan(dot(query, invoice));
    }

    @Test
    void emptyTextStillGivesAUnitVector() {
        float[] v = fake.embedQuery("   ");
        assertThat(dot(v, v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void countsCallsSoTestsCanProveNothingWasEmbedded() {
        fake.embedDocuments("t", List.of("a", "b", "c"));
        fake.embedDocuments("t", List.of("d"));

        assertThat(fake.documentCalls()).isEqualTo(2);
        assertThat(fake.textsEmbedded()).isEqualTo(4);

        fake.resetCounters();
        assertThat(fake.documentCalls()).isZero();
    }
}
