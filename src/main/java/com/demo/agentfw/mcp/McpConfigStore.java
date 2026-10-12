package com.demo.agentfw.mcp;

import java.util.List;

/**
 * Where the configured MCP servers are kept between runs. The registry keeps its working copy in memory
 * either way; a store only decides whether a restart forgets it.
 */
public interface McpConfigStore {

    /** What was read back, and how many saved servers could not be (wrong key, damaged value). */
    record LoadResult(List<McpServerConfig> configs, int unreadable) {
        public LoadResult {
            configs = List.copyOf(configs);
        }
    }

    /** True when saved servers survive a restart. */
    boolean persistent();

    /** One sentence for the settings page: where the credentials live and why, when not persistent. */
    String note();

    LoadResult loadAll();

    /** Inserts or replaces one server, secrets encrypted. Throws when the database is not reachable. */
    void save(McpServerConfig config);

    void delete(String id);
}
