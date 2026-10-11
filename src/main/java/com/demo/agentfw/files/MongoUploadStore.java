package com.demo.agentfw.files;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Uploads in MongoDB (the only store). A TTL index on {@code createdAt} deletes a file after the
 * configured number of hours, so uploaded text does not accumulate. Every read is scoped by session id.
 */
@Component
public class MongoUploadStore implements UploadStore {

    public static final String COLLECTION = "uploads";

    private final MongoCollection<Document> collection;
    private final int ttlHours;
    private volatile boolean schemaReady;

    @Autowired
    public MongoUploadStore(MongoTemplate template, FileProperties props) {
        this(template.getDb(), COLLECTION, props.ttlHours());
    }

    public MongoUploadStore(MongoDatabase database, String collectionName, int ttlHours) {
        this.collection = database.getCollection(collectionName);
        this.ttlHours = ttlHours;
    }

    /** Creates the indexes; safe to call repeatedly, and replaces a TTL index whose lifetime changed. */
    public void ensureSchema() {
        collection.createIndex(Indexes.ascending("sessionId", "createdAt"));
        IndexOptions ttl = new IndexOptions().name("uploads_ttl").expireAfter((long) ttlHours, TimeUnit.HOURS);
        try {
            collection.createIndex(Indexes.ascending("createdAt"), ttl);
        } catch (MongoCommandException e) {
            // same name, different lifetime: the configuration changed, so recreate it
            collection.dropIndex("uploads_ttl");
            collection.createIndex(Indexes.ascending("createdAt"), ttl);
        }
    }

    @Override
    public void save(UploadedFile f) {
        if (!schemaReady) {      // lazily, so the application starts without a reachable database
            ensureSchema();
            schemaReady = true;
        }
        Document doc = new Document("_id", f.id())
                .append("sessionId", f.sessionId())
                .append("name", f.name())
                .append("contentType", f.contentType())
                .append("sizeBytes", f.sizeBytes())
                .append("pages", f.pages())
                .append("chars", f.chars())
                .append("text", f.text())
                .append("createdAt", Date.from(f.createdAt()));
        collection.insertOne(doc);
    }

    @Override
    public Optional<UploadedFile> find(String sessionId, String fileId) {
        Document d = collection.find(Filters.and(Filters.eq("_id", fileId), Filters.eq("sessionId", sessionId)))
                .first();
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new UploadedFile(d.getString("_id"), d.getString("sessionId"), d.getString("name"),
                d.getString("contentType"), ((Number) d.get("sizeBytes")).longValue(), d.getInteger("pages"),
                d.getString("text"), d.getDate("createdAt").toInstant()));
    }

    @Override
    public List<UploadedFile.Summary> summaries(String sessionId) {
        List<UploadedFile.Summary> out = new ArrayList<>();
        for (Document d : collection.find(Filters.eq("sessionId", sessionId))
                .projection(Projections.exclude("text")).sort(Sorts.ascending("createdAt"))) {
            out.add(new UploadedFile.Summary(d.getString("_id"), d.getString("name"), d.getString("contentType"),
                    ((Number) d.get("sizeBytes")).longValue(), d.getInteger("pages"),
                    ((Number) d.get("chars")).intValue()));
        }
        return out;
    }
}
