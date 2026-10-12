package com.demo.agentfw.mcp;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved MCP servers in MongoDB (the only store). The URL and every header value are encrypted with
 * {@link McpSecretCipher}; the id, name, transport, enabled flag and the header <em>names</em> are
 * stored in the clear, because they are what the settings page shows anyway. A value is bound to its
 * server and field by the authenticated context, so it cannot be moved around.
 *
 * <p>Nothing here logs a value, and exceptions are reported by class name only.
 */
public final class MongoMcpConfigStore implements McpConfigStore {

    public static final String COLLECTION = "mcp_servers";

    private static final Logger log = LoggerFactory.getLogger(MongoMcpConfigStore.class);

    private final MongoCollection<Document> collection;
    private final McpSecretCipher cipher;

    public MongoMcpConfigStore(MongoDatabase database, McpSecretCipher cipher) {
        this(database, COLLECTION, cipher);
    }

    public MongoMcpConfigStore(MongoDatabase database, String collectionName, McpSecretCipher cipher) {
        this.collection = database.getCollection(collectionName);
        this.cipher = cipher;
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public String note() {
        return "Saved servers are stored in the database with the address and every header value encrypted "
                + "(AES-GCM). They are loaded again when the application starts. Values are never shown or logged "
                + "and never sent to the language model.";
    }

    @Override
    public LoadResult loadAll() {
        List<McpServerConfig> configs = new ArrayList<>();
        int unreadable = 0;
        for (Document d : collection.find().sort(Sorts.ascending("createdAt"))) {
            try {
                configs.add(read(d));
            } catch (RuntimeException e) {
                unreadable++;
                log.warn("a saved MCP server could not be read ({}); it is left untouched in the database",
                        e.getClass().getSimpleName());
            }
        }
        return new LoadResult(configs, unreadable);
    }

    private McpServerConfig read(Document d) {
        String id = d.getString("_id");
        Map<String, String> headers = new LinkedHashMap<>();
        for (Document h : d.getList("headers", Document.class, List.of())) {
            String name = h.getString("name");
            headers.put(name, cipher.decrypt(h.getString("value"), headerContext(id, name)));
        }
        return new McpServerConfig(id, d.getString("name"), cipher.decrypt(d.getString("url"), urlContext(id)),
                McpTransport.valueOf(d.getString("transport")), headers, Boolean.TRUE.equals(d.getBoolean("enabled")));
    }

    @Override
    public void save(McpServerConfig c) {
        List<Document> headers = new ArrayList<>();
        for (Map.Entry<String, String> h : c.headers().entrySet()) {
            headers.add(new Document("name", h.getKey())
                    .append("value", cipher.encrypt(h.getValue(), headerContext(c.id(), h.getKey()))));
        }
        Date now = new Date();
        collection.updateOne(Filters.eq("_id", c.id()),
                Updates.combine(
                        Updates.set("name", c.name()),
                        Updates.set("url", cipher.encrypt(c.url(), urlContext(c.id()))),
                        Updates.set("transport", c.transport().name()),
                        Updates.set("enabled", c.enabled()),
                        Updates.set("headers", headers),
                        Updates.set("updatedAt", now),
                        Updates.setOnInsert("createdAt", now)),
                new UpdateOptions().upsert(true));
    }

    @Override
    public void delete(String id) {
        collection.deleteOne(Filters.eq("_id", id));
    }

    private static String urlContext(String id) {
        return "mcp-server:" + id + ":url";
    }

    private static String headerContext(String id, String headerName) {
        return "mcp-server:" + id + ":header:" + headerName;
    }
}
