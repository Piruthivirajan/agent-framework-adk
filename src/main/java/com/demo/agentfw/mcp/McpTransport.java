package com.demo.agentfw.mcp;

/**
 * The two network transports of the MCP specification that the application supports. stdio is not
 * supported: it needs the server to run as a local child process, which a hosted application cannot do
 * for a user-supplied command (and would be a remote-code-execution risk).
 */
public enum McpTransport {
    /** The current transport: one HTTP endpoint, JSON or an event stream per request. */
    STREAMABLE_HTTP,
    /** The older HTTP+SSE transport: a GET event stream plus a POST message endpoint. */
    SSE
}
