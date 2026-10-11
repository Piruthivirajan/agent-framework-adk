package com.demo.agentfw.mcp;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * A problem with an MCP server, in words for the user. The message is composed here from a fixed
 * vocabulary and the cut-down URL: nothing from the exception text is copied into it, so neither
 * credentials nor internals can leak through it.
 */
public record McpFailure(String code, String message) {

    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String UNABLE_TO_CONNECT = "UNABLE_TO_CONNECT";
    public static final String WRONG_URL = "WRONG_URL";
    public static final String NOT_MCP = "NOT_MCP_SERVER";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String BLOCKED_ADDRESS = "BLOCKED_ADDRESS";
    public static final String TOOL_ERROR = "TOOL_ERROR";
    public static final String OTHER = "MCP_ERROR";

    /** Classifies a connection or call failure by walking the cause chain. */
    public static McpFailure classify(Throwable error, String safeUrl) {
        StringBuilder all = new StringBuilder();
        boolean connect = false;
        boolean timeout = false;
        Throwable t = error;
        for (int depth = 0; t != null && depth < 12; depth++) {
            all.append(t.getClass().getSimpleName()).append(' ').append(String.valueOf(t.getMessage())).append(' ');
            connect |= t instanceof ConnectException || t instanceof UnknownHostException;
            timeout |= t instanceof TimeoutException || t instanceof SocketTimeoutException
                    || t instanceof HttpConnectTimeoutException;
            t = t.getCause() == t ? null : t.getCause();
        }
        String text = all.toString().toLowerCase(Locale.ROOT);
        String where = safeUrl == null ? "the server" : safeUrl;
        if (text.contains("401") || text.contains("unauthorized") || text.contains("invalid_token")) {
            return new McpFailure(INVALID_CREDENTIALS, "Invalid credentials: the MCP server rejected the token or "
                    + "header (HTTP 401). Check the value and try again.");
        }
        if (text.contains("403") || text.contains("forbidden")) {
            return new McpFailure(INVALID_CREDENTIALS, "Invalid credentials: the MCP server refused access "
                    + "(HTTP 403). The token may be valid but not allowed to use this server.");
        }
        if (connect || text.contains("connection refused") || text.contains("no route to host")
                || text.contains("connection reset") || text.contains("failed to connect")
                || text.contains("nodename nor servname") || text.contains("unknownhost")) {
            return new McpFailure(UNABLE_TO_CONNECT, "Unable to connect to MCP server: nothing answered at "
                    + where + ". Check that the server is running and that the address is right.");
        }
        if (text.contains("404") || text.contains("not found")) {
            return new McpFailure(WRONG_URL, "The server answered \"not found\" at " + where
                    + ". Check the path of the URL (for example /mcp for Streamable HTTP, /sse for SSE).");
        }
        if (timeout || text.contains("timed out") || text.contains("timeout")) {
            return new McpFailure(TIMEOUT, "The MCP server did not answer in time. It may be down or too slow.");
        }
        if (text.contains("content-type") || text.contains("content type") || text.contains("json")
                || text.contains("protocol") || text.contains("405") || text.contains("unexpected")) {
            return new McpFailure(NOT_MCP, "The address answered, but not like an MCP server of the selected "
                    + "transport. Check the transport (Streamable HTTP or SSE) and the URL.");
        }
        return new McpFailure(OTHER, "The MCP server could not be used. Check the URL, the transport and the "
                + "credentials.");
    }
}
