package com.demo.agentfw.mcp;

import java.util.List;

/**
 * No persistence: the servers live in the registry's memory and a restart forgets them. This is what
 * happens when no encryption key is configured, because credentials are never written unencrypted.
 */
public final class MemoryOnlyMcpConfigStore implements McpConfigStore {

    public static final String NO_KEY_NOTE = "Saved servers and their credentials are kept in memory only and are "
            + "lost when the application restarts, because no encryption key is configured. The administrator "
            + "can set AGENTFW_MCP_SECRET_KEY to store them encrypted in the database.";

    public static final String BAD_KEY_NOTE = "Saved servers and their credentials are kept in memory only and are "
            + "lost when the application restarts: AGENTFW_MCP_SECRET_KEY is set but is not a valid key (it must "
            + "be base64 of 16, 24 or 32 random bytes), so nothing is written to the database.";

    private final String note;

    public MemoryOnlyMcpConfigStore() {
        this(NO_KEY_NOTE);
    }

    public MemoryOnlyMcpConfigStore(String note) {
        this.note = note;
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public String note() {
        return note;
    }

    @Override
    public LoadResult loadAll() {
        return new LoadResult(List.of(), 0);
    }

    @Override
    public void save(McpServerConfig config) {
        // memory only: the registry's own map is the copy
    }

    @Override
    public void delete(String id) {
    }
}
