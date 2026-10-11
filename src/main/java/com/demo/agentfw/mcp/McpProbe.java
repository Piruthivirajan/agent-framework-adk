package com.demo.agentfw.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * A one-request check of an MCP endpoint before the SDK is used, with the same headers. It exists
 * because the HTTP status is what tells "wrong token" from "wrong path" from "server down", and the
 * SDK's exceptions do not carry it reliably (and retry on their own, slowly).
 *
 * <p>Streamable HTTP: POSTs a JSON-RPC {@code initialize}. SSE: opens the event stream with GET and
 * looks at the status and content type. The result is either nothing (the endpoint looks right) or a
 * {@link McpFailure} composed from a fixed vocabulary.
 */
final class McpProbe {

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"agent-framework\","
            + "\"version\":\"0.1\"}}}";

    private McpProbe() {
    }

    static Optional<McpFailure> check(McpServerConfig config, Duration connectTimeout, Duration requestTimeout) {
        String safe = config.safeUrl();
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.url().strip())).timeout(requestTimeout);
            config.headers().forEach(b::header);
            HttpRequest request;
            if (config.transport() == McpTransport.SSE) {
                request = b.header("Accept", "text/event-stream").GET().build();
            } else {
                request = b.header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE)).build();
            }
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                return judge(config.transport(), response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(""), safe, body);
            }
        } catch (HttpTimeoutException e) {
            return Optional.of(new McpFailure(McpFailure.TIMEOUT,
                    "The MCP server did not answer in time. It may be down or too slow."));
        } catch (IOException e) {
            return Optional.of(McpFailure.classify(e, safe));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(new McpFailure(McpFailure.OTHER, "The connection was interrupted."));
        } catch (RuntimeException e) {
            return Optional.of(McpFailure.classify(e, safe));
        }
    }

    static Optional<McpFailure> judge(McpTransport transport, int status, String contentType, String safeUrl,
                                      InputStream body) throws IOException {
        if (status == 401) {
            return fail(McpFailure.INVALID_CREDENTIALS, "Invalid credentials: the MCP server rejected the token or "
                    + "header (HTTP 401). Check the value and try again.");
        }
        if (status == 403) {
            return fail(McpFailure.INVALID_CREDENTIALS, "Invalid credentials: the MCP server refused access "
                    + "(HTTP 403). The token may be valid but not allowed to use this server.");
        }
        if (status == 404 || status == 410) {
            return fail(McpFailure.WRONG_URL, "The server answered \"not found\" at " + safeUrl + ". Check the path "
                    + "of the URL (for example /mcp for Streamable HTTP, /sse for SSE).");
        }
        if (status >= 500) {
            return fail(McpFailure.OTHER, "The MCP server reported an internal error (HTTP " + status
                    + "). Try again later or check the server.");
        }
        if (status >= 400) {
            return fail(McpFailure.NOT_MCP, "The address answered with HTTP " + status + ", which an MCP server of "
                    + "the selected transport would not. Check the transport (Streamable HTTP or SSE) and the URL.");
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        if (transport == McpTransport.SSE) {
            return type.startsWith("text/event-stream") ? Optional.empty() : fail(McpFailure.NOT_MCP,
                    "The address did not open an event stream, so it is not an SSE MCP server. If it is a "
                            + "Streamable HTTP server, change the transport.");
        }
        if (type.startsWith("application/json") || type.startsWith("text/event-stream")) {
            String start = new String(body.readNBytes(2048), StandardCharsets.UTF_8);
            if (start.contains("jsonrpc") || start.contains("event:") || start.contains("data:")) {
                return Optional.empty();
            }
        }
        return fail(McpFailure.NOT_MCP, "The address answered, but not like a Streamable HTTP MCP server. Check "
                + "the transport (it may be an SSE server) and the URL.");
    }

    private static Optional<McpFailure> fail(String code, String message) {
        return Optional.of(new McpFailure(code, message));
    }
}
