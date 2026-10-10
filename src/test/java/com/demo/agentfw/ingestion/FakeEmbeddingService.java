package com.demo.agentfw.ingestion;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stand-in for the Gemini embeddings, used by every test. It hashes words into
 * buckets and L2-normalizes, so texts that share words get a higher cosine similarity, which keeps
 * retrieval tests meaningful without any network call.
 *
 * <p>Lives in src/test, so it can never be present in the production jar.
 */
@Service
@ConditionalOnProperty(prefix = "agentfw.embedding", name = "provider", havingValue = "fake")
public class FakeEmbeddingService implements EmbeddingService {

    private final int dimensions;
    private final String model;
    private final AtomicInteger documentCalls = new AtomicInteger();
    private final AtomicInteger textsEmbedded = new AtomicInteger();

    @Autowired
    public FakeEmbeddingService(EmbeddingProperties props) {
        this(props.dimensions(), props.model());
    }

    public FakeEmbeddingService(int dimensions, String model) {
        this.dimensions = dimensions;
        this.model = model;
    }

    @Override
    public List<float[]> embedDocuments(String title, List<String> texts) {
        documentCalls.incrementAndGet();
        textsEmbedded.addAndGet(texts.size());
        return texts.stream().map(this::vectorFor).toList();
    }

    @Override
    public float[] embedQuery(String query) {
        return vectorFor(query);
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /** Number of {@link #embedDocuments} calls so far; lets tests assert "no API call was made". */
    public int documentCalls() {
        return documentCalls.get();
    }

    public int textsEmbedded() {
        return textsEmbedded.get();
    }

    public void resetCounters() {
        documentCalls.set(0);
        textsEmbedded.set(0);
    }

    private float[] vectorFor(String text) {
        float[] v = new float[dimensions];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                v[Math.floorMod(token.hashCode(), dimensions)] += 1f;
            }
        }
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        if (norm == 0) {
            v[0] = 1f;   // empty text still needs a valid unit vector
            return v;
        }
        float inv = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
        return v;
    }
}
