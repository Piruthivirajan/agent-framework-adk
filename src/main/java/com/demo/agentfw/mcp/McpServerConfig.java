package com.demo.agentfw.mcp;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One configured MCP server. {@code headers} carry the credentials (for example
 * {@code Authorization: Bearer ...}); they exist only in this object, in memory. {@link #toString()}
 * never prints them, and no API response or log line includes their values.
 */
public record McpServerConfig(String id, String name, String url, McpTransport transport,
                              Map<String, String> headers, boolean enabled) {

    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    static final int MAX_HEADERS = 20;
    static final int MAX_VALUE_CHARS = 4000;

    public McpServerConfig {
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
    }

    /** Throws {@link IllegalArgumentException} with a sentence for the user. */
    public void validate() {
        if (name == null || name.isBlank() || name.length() > 60) {
            throw new IllegalArgumentException("Give the server a name of 1 to 60 characters.");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Enter the server URL.");
        }
        if (!url.contains("://")) {
            throw new IllegalArgumentException("That is not a URL. Servers started by a local command (stdio) are "
                    + "not supported; enter the http:// or https:// address of a network MCP server.");
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The URL is not valid.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("The URL must start with http:// or https://. Servers that run as "
                    + "a local command (stdio) are not supported.");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("The URL has no host name.");
        }
        if (transport == null) {
            throw new IllegalArgumentException("Choose a transport: Streamable HTTP or SSE.");
        }
        if (headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("At most " + MAX_HEADERS + " headers are allowed.");
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (!HEADER_NAME.matcher(h.getKey()).matches()) {
                throw new IllegalArgumentException("'" + h.getKey() + "' is not a valid header name.");
            }
            String v = h.getValue();
            if (v == null || v.length() > MAX_VALUE_CHARS || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("The value of header '" + h.getKey() + "' is not valid.");
            }
        }
    }

    /** The URL without credentials in it: no user info and no query string (tokens often live there). */
    public String safeUrl() {
        try {
            URI u = URI.create(url.strip());
            String base = u.getScheme() + "://" + u.getHost() + (u.getPort() >= 0 ? ":" + u.getPort() : "")
                    + (u.getRawPath() == null ? "" : u.getRawPath());
            return u.getRawQuery() == null ? base : base + "?***";
        } catch (RuntimeException e) {
            return "(invalid url)";
        }
    }

    @Override
    public String toString() {
        return "McpServerConfig[id=" + id + ", name=" + name + ", url=" + safeUrl() + ", transport=" + transport
                + ", headers=" + headers.keySet() + " (values hidden), enabled=" + enabled + "]";
    }
}
