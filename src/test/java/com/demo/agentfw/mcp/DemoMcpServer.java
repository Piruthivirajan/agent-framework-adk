package com.demo.agentfw.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A tiny MCP server for demos and tests: JDK only, one file, no MCP library.
 *
 * <pre>
 *   java src/test/java/com/demo/agentfw/mcp/DemoMcpServer.java [--port 8099] [--token demo-secret]
 * </pre>
 *
 * It serves the same three tools over both transports of the MCP spec that the application supports:
 * <ul>
 *   <li>Streamable HTTP: {@code POST http://localhost:8099/mcp}
 *   <li>legacy HTTP+SSE: {@code GET http://localhost:8099/sse} (the message endpoint is announced in the
 *       first event)
 * </ul>
 * Every request must carry {@code Authorization: Bearer <token>}; anything else is a 401. The data is
 * invented (orders, an on-call roster), so a question about it can only be answered through this server.
 */
public final class DemoMcpServer {

    private static final String PROTOCOL = "2025-03-26";

    private final String token;
    private final HttpServer http;
    private final Map<String, LinkedBlockingQueue<String>> sseSessions = new ConcurrentHashMap<>();

    public DemoMcpServer(int port, String token) throws IOException {
        this("127.0.0.1", port, token);
    }

    /** {@code bind} is the interface to listen on: 127.0.0.1 for a laptop, 0.0.0.0 inside a container. */
    public DemoMcpServer(String bind, int port, String token) throws IOException {
        this.token = token;
        this.http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.setExecutor(Executors.newCachedThreadPool());
        http.createContext("/mcp", this::streamable);
        http.createContext("/sse", this::sseOpen);
        http.createContext("/message", this::sseMessage);
    }

    public void start() {
        http.start();
    }

    public void stop() {
        http.stop(0);
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public static void main(String[] args) throws Exception {
        int port = 8099;
        String token = "demo-secret";
        String bind = "127.0.0.1";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) {
                port = Integer.parseInt(args[i + 1]);
            } else if (args[i].equals("--token")) {
                token = args[i + 1];
            } else if (args[i].equals("--bind")) {
                bind = args[i + 1];
            }
        }
        DemoMcpServer server = new DemoMcpServer(bind, port, token);
        server.start();
        System.out.println("Demo MCP server listening on " + bind + ":" + port);
        System.out.println("  Streamable HTTP : http://localhost:" + port + "/mcp");
        System.out.println("  legacy SSE      : http://localhost:" + port + "/sse");
        System.out.println("  header          : Authorization: Bearer " + token);
        System.out.println("  tools           : lookup_order, oncall_engineer, billing_report (always fails)");
    }

    // ---- transports -------------------------------------------------------------------------------

    private void streamable(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        switch (ex.getRequestMethod()) {
            case "POST" -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String response = handle(body);
                if (response == null) {
                    ex.sendResponseHeaders(202, -1);       // a notification: accepted, no body
                } else {
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
            case "DELETE" -> ex.sendResponseHeaders(200, -1);
            default -> ex.sendResponseHeaders(405, -1);   // no server-initiated stream
        }
        ex.close();
    }

    private void sseOpen(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        String session = UUID.randomUUID().toString();
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        sseSessions.put(session, queue);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            write(out, "event: endpoint\ndata: /message?sessionId=" + session + "\n\n");
            while (true) {
                String message = queue.poll(15, TimeUnit.SECONDS);
                write(out, message == null ? ": keep-alive\n\n" : "event: message\ndata: " + message + "\n\n");
            }
        } catch (IOException | InterruptedException e) {
            // the client went away
        } finally {
            sseSessions.remove(session);
            ex.close();
        }
    }

    private void sseMessage(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String query = ex.getRequestURI().getQuery();
        String session = query != null && query.startsWith("sessionId=") ? query.substring(10) : "";
        LinkedBlockingQueue<String> queue = sseSessions.get(session);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (queue == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        String response = handle(body);
        if (response != null) {
            queue.add(response);
        }
        ex.sendResponseHeaders(202, -1);
        ex.close();
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private boolean authorized(HttpExchange ex) throws IOException {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.equals("Bearer " + token)) {
            return true;
        }
        byte[] bytes = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        ex.sendResponseHeaders(401, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        ex.close();
        return false;
    }

    // ---- JSON-RPC ---------------------------------------------------------------------------------

    /** Handles one JSON-RPC message; returns the response, or null for a notification. */
    @SuppressWarnings("unchecked")
    String handle(String body) {
        Map<String, Object> request;
        try {
            request = (Map<String, Object>) new Json(body).value();
        } catch (RuntimeException e) {
            return error(null, -32700, "Parse error");
        }
        Object id = request.get("id");
        String method = String.valueOf(request.get("method"));
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (id == null) {
            return null;                                          // notifications/initialized and friends
        }
        return switch (method) {
            case "initialize" -> result(id, "{\"protocolVersion\":" + quote(String.valueOf(
                            params.getOrDefault("protocolVersion", PROTOCOL))) + ",\"capabilities\":{\"tools\":{"
                    + "\"listChanged\":false}},\"serverInfo\":{\"name\":\"demo-orders\",\"version\":\"1.0.0\"}}");
            case "ping" -> result(id, "{}");
            case "tools/list" -> result(id, "{\"tools\":" + tools() + "}");
            case "tools/call" -> result(id, call(String.valueOf(params.get("name")),
                    params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of()));
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private static String tools() {
        return "[" + tool("lookup_order", "Looks up the status, carrier and delivery estimate of a customer order "
                + "by its order id, for example ORD-1001.", "order_id", "The order id, like ORD-1001") + ","
                + tool("oncall_engineer", "Returns who is the on-call engineer of a team today, with a phone "
                + "number. Teams: platform, payments, search.", "team", "The team name, like platform") + ","
                + tool("billing_report", "Returns last month's billing report. (This demo tool always fails, to "
                + "show how tool errors are reported.)", "month", "The month, like 2026-09") + "]";
    }

    private static String tool(String name, String description, String param, String paramDescription) {
        return "{\"name\":" + quote(name) + ",\"description\":" + quote(description) + ",\"inputSchema\":{\"type\":"
                + "\"object\",\"properties\":{" + quote(param) + ":{\"type\":\"string\",\"description\":"
                + quote(paramDescription) + "}},\"required\":[" + quote(param) + "]}}";
    }

    private static String call(String name, Map<String, Object> args) {
        return switch (name) {
            case "lookup_order" -> text(order(String.valueOf(args.get("order_id"))), false);
            case "oncall_engineer" -> text(oncall(String.valueOf(args.get("team"))), false);
            case "billing_report" -> text("The billing service returned 503 Service Unavailable.", true);
            default -> text("Unknown tool: " + name, true);
        };
    }

    private static String order(String id) {
        Map<String, String> orders = new LinkedHashMap<>();
        orders.put("ORD-1001", "Order ORD-1001: status SHIPPED, carrier BlueFreight, 3 items, delivery estimate "
                + "2026-10-14, tracking BF-77120.");
        orders.put("ORD-1002", "Order ORD-1002: status PROCESSING, 1 item, waiting for stock, no delivery "
                + "estimate yet.");
        orders.put("ORD-1003", "Order ORD-1003: status DELIVERED on 2026-10-02, carrier BlueFreight, signed by "
                + "M. Okafor.");
        return orders.getOrDefault(id.toUpperCase(), "No order with the id " + id + " exists.");
    }

    private static String oncall(String team) {
        return switch (team.toLowerCase()) {
            case "platform" -> "Platform on-call today: Priya Raman, +1-555-0142.";
            case "payments" -> "Payments on-call today: Tomas Idowu, +1-555-0177.";
            case "search" -> "Search on-call today: Hana Kobayashi, +1-555-0109.";
            default -> "There is no on-call roster for the team '" + team + "'.";
        };
    }

    private static String text(String text, boolean isError) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + quote(text) + "}],\"isError\":" + isError + "}";
    }

    private static String result(Object id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(id) + ",\"result\":" + result + "}";
    }

    private static String error(Object id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(id) + ",\"error\":{\"code\":" + code + ",\"message\":"
                + quote(message) + "}}";
    }

    private static String idJson(Object id) {
        if (id == null) {
            return "null";
        }
        return id instanceof Number n ? String.valueOf(n.longValue() == n.doubleValue() ? (Object) n.longValue() : n)
                : quote(String.valueOf(id));
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** A minimal JSON reader: objects, arrays, strings, numbers, booleans, null. */
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                    } else {
                        expect('}');
                        return m;
                    }
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                    } else {
                        expect(']');
                        return l;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return Double.valueOf(s.substring(start, i));
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private void expect(char c) {
            if (s.charAt(i++) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
            }
        }
    }
}
