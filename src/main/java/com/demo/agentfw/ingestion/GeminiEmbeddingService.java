package com.demo.agentfw.ingestion;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini embeddings through the Google GenAI Java SDK.
 *
 * <p>{@code gemini-embedding-2} has no {@code taskType}; the task is expressed as a text prefix,
 * and documents and queries must use their own format. Output below 3072 dimensions is already
 * normalized by the model (checked against the live API: norm 1.0 at 768 and 1536).
 */
@Service
@ConditionalOnProperty(prefix = "agentfw.embedding", name = "provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingService.class);
    private static final long MAX_BACKOFF_MS = 30_000;

    /** One raw call to the model: prefixed texts in, vectors out. Replaced by a stub in unit tests. */
    @FunctionalInterface
    interface BatchCaller {
        List<float[]> embed(List<String> inputs);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final EmbeddingProperties props;
    private final BatchCaller caller;
    private final Sleeper sleeper;

    @Autowired
    public GeminiEmbeddingService(EmbeddingProperties props) {
        this(props, geminiCaller(props), Thread::sleep);
    }

    GeminiEmbeddingService(EmbeddingProperties props, BatchCaller caller, Sleeper sleeper) {
        this.props = props;
        this.caller = caller;
        this.sleeper = sleeper;
    }

    @Override
    public List<float[]> embedDocuments(String title, List<String> texts) {
        String safeTitle = (title == null || title.isBlank()) ? "none" : title.strip();
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += props.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(from + props.batchSize(), texts.size()));
            List<String> inputs = batch.stream().map(t -> "title: " + safeTitle + " | text: " + t).toList();
            out.addAll(embedChecked(inputs));
        }
        return out;
    }

    @Override
    public float[] embedQuery(String query) {
        return embedChecked(List.of("task: search result | query: " + query)).get(0);
    }

    @Override
    public String modelName() {
        return props.model();
    }

    @Override
    public int dimensions() {
        return props.dimensions();
    }

    private List<float[]> embedChecked(List<String> inputs) {
        List<float[]> vectors = callWithRetry(inputs);
        if (vectors.size() != inputs.size()) {
            throw new IllegalStateException("Embedding model returned " + vectors.size()
                    + " vectors for " + inputs.size() + " inputs");
        }
        for (float[] v : vectors) {
            if (v.length != props.dimensions()) {
                throw new IllegalStateException("Embedding model returned " + v.length + " dimensions, expected "
                        + props.dimensions() + ". The vector index numDimensions would not match.");
            }
        }
        return vectors;
    }

    private List<float[]> callWithRetry(List<String> inputs) {
        for (int attempt = 0; ; attempt++) {
            try {
                return caller.embed(inputs);
            } catch (RuntimeException e) {
                if (!isRetryable(e) || attempt >= props.maxRetries()) {
                    throw e;
                }
                long waitMs = Math.min(MAX_BACKOFF_MS, 1000L << attempt);
                log.warn("Embedding call failed ({}), retry {}/{} in {} ms",
                        describe(e), attempt + 1, props.maxRetries(), waitMs);
                try {
                    sleeper.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to retry embedding call", ie);
                }
            }
        }
    }

    /** Rate limits (429), server errors (5xx) and network failures are worth retrying; 4xx is not. */
    private static boolean isRetryable(RuntimeException e) {
        if (e instanceof ApiException api) {
            return api.code() == 429 || api.code() >= 500;
        }
        return e instanceof GenAiIOException;
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException api ? "HTTP " + api.code() : e.getClass().getSimpleName();
    }

    private static BatchCaller geminiCaller(EmbeddingProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "No Gemini API key: set GOOGLE_API_KEY in .env (see .env.example) or the environment.");
        }
        Client client = Client.builder().apiKey(props.apiKey()).build();
        EmbedContentConfig config = EmbedContentConfig.builder().outputDimensionality(props.dimensions()).build();
        String model = props.model();
        return inputs -> {
            EmbedContentResponse response = client.models.embedContent(model, inputs, config);
            List<ContentEmbedding> embeddings = response.embeddings()
                    .orElseThrow(() -> new IllegalStateException("Embedding response had no embeddings"));
            List<float[]> vectors = new ArrayList<>(embeddings.size());
            for (ContentEmbedding e : embeddings) {
                List<Float> values = e.values()
                        .orElseThrow(() -> new IllegalStateException("Embedding had no values"));
                float[] v = new float[values.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = values.get(i);
                }
                vectors.add(v);
            }
            return vectors;
        };
    }
}
