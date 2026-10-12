package com.demo.agentfw.core;

import com.google.adk.models.Gemini;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real Gemini client of ADK against a local HTTP server that plays the API: it proves that the
 * limits reach the HTTP call (a retry on 429/503 only, at most the configured number, and a timeout),
 * not just that a config object was built.
 */
class LimitedLlmTest {

    private static final String OK = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":"
            + "[{\"text\":\"hello\"}]},\"finishReason\":\"STOP\"}]}";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile IntUnaryOperator statusForAttempt = n -> 200;     // attempt number (1-based) -> HTTP status
    private volatile long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            int attempt = requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = statusForAttempt.applyAsInt(attempt);
            String body = status == 200 ? OK
                    : "{\"error\":{\"code\":" + status + ",\"message\":\"simulated\",\"status\":\"ERROR\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException clientGaveUp) {
                // the caller timed out and closed the connection
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LimitedLlm model(int timeoutSeconds, int retries) {
        Client client = Client.builder().apiKey("test-key")
                .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                .build();
        return new LimitedLlm(new Gemini("gemini-test", client), timeoutSeconds, retries);
    }

    private static LlmResponse ask(LimitedLlm llm) {
        LlmRequest request = LlmRequest.builder()
                .contents(List.of(Content.fromParts(Part.fromText("hi")))).build();
        return llm.generateContent(request, false).blockingFirst();
    }

    @Test
    void aRateLimitedCallIsRetriedOnceAndThenSucceeds() {
        statusForAttempt = n -> n == 1 ? 429 : 200;

        LlmResponse response = ask(model(30, 1));

        assertThat(response.content().orElseThrow().parts().orElseThrow().get(0).text()).contains("hello");
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void anOverloadedModelIsRetriedOnceAndThenSucceeds() {
        statusForAttempt = n -> n == 1 ? 503 : 200;

        ask(model(30, 1));

        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void aRateLimitThatPersistsStopsAfterTheOneRetry() {
        statusForAttempt = n -> 429;

        assertThatThrownBy(() -> ask(model(30, 1))).hasMessageContaining("429");

        assertThat(requests.get()).isEqualTo(2);        // the first call and exactly one retry
    }

    @Test
    void withZeroRetriesNothingIsRetried() {
        statusForAttempt = n -> 429;

        assertThatThrownBy(() -> ask(model(30, 0))).hasMessageContaining("429");

        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void otherErrorsAreNotRetried() {
        statusForAttempt = n -> 400;
        assertThatThrownBy(() -> ask(model(30, 1))).hasMessageContaining("400");
        assertThat(requests.get()).isEqualTo(1);

        requests.set(0);
        statusForAttempt = n -> 500;
        assertThatThrownBy(() -> ask(model(30, 1))).hasMessageContaining("500");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void aCallThatTakesLongerThanTheTimeoutFailsInsteadOfWaiting() {
        delayMillis = 6_000;
        long start = System.nanoTime();

        assertThatThrownBy(() -> ask(model(1, 0)));

        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertThat(seconds).as("seconds until the call gave up").isLessThan(5);
    }

    @Test
    void theLimitsAreChecked() {
        assertThatThrownBy(() -> GeminiLimits.httpOptions(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeminiLimits.httpOptions(60, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmLimitsProperties(60, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LlmLimitsProperties.defaults().requestTimeoutSeconds()).isEqualTo(60);
        assertThat(LlmLimitsProperties.defaults().retries()).isEqualTo(1);
    }
}
