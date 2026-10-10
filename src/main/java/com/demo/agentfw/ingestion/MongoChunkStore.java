package com.demo.agentfw.ingestion;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunks in MongoDB through the plain driver. The driver is used directly (not repositories)
 * because we need an explicit transaction, search index commands and an aggregation.
 */
@Component
public class MongoChunkStore implements ChunkStore {

    public static final String COLLECTION = "chunks";

    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final MongoDatabase database;

    @Autowired
    public MongoChunkStore(MongoClient client, MongoTemplate template) {
        this(client, template.getDb(), COLLECTION);
    }

    public MongoChunkStore(MongoClient client, MongoDatabase database, String collectionName) {
        this.client = client;
        this.database = database;
        this.collection = database.getCollection(collectionName);
    }

    MongoCollection<Document> collection() {
        return collection;
    }

    /**
     * Creates the collection if needed (search indexes need it to exist) and the unique index
     * {@code (tenantId, docId, chunkIndex)}, which makes a duplicate chunk impossible even if the
     * job logic had a bug. Safe to call repeatedly.
     */
    public void ensureSchema() {
        boolean exists = false;
        for (String name : database.listCollectionNames()) {
            if (name.equals(collection.getNamespace().getCollectionName())) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            database.createCollection(collection.getNamespace().getCollectionName());
        }
        collection.createIndex(Indexes.ascending("tenantId", "docId", "chunkIndex"),
                new IndexOptions().unique(true).name("uniq_tenant_doc_chunk"));
    }

    @Override
    public Map<String, DocState> docStates(String tenantId) {
        Map<String, DocState> result = new HashMap<>();
        List<Document> groups = collection.aggregate(List.of(
                Aggregates.match(Filters.eq("tenantId", tenantId)),
                Aggregates.group("$docId",
                        Accumulators.addToSet("hashes", "$contentHash"),
                        Accumulators.addToSet("models", "$embeddingModel"),
                        Accumulators.sum("n", 1)))).into(new ArrayList<>());
        for (Document g : groups) {
            result.put(g.getString("_id"), new DocState(
                    toStringSet(g.getList("hashes", Object.class)),
                    toStringSet(g.getList("models", Object.class)),
                    ((Number) g.get("n")).intValue()));
        }
        return result;
    }

    @Override
    public void replaceDocument(String tenantId, String docId, List<StoredChunk> chunks) {
        List<Document> documents = chunks.stream().map(MongoChunkStore::toDocument).toList();
        try (ClientSession session = client.startSession()) {
            session.withTransaction(() -> {
                collection.deleteMany(session, Filters.and(
                        Filters.eq("tenantId", tenantId), Filters.eq("docId", docId)));
                if (!documents.isEmpty()) {
                    collection.insertMany(session, documents);
                }
                return null;
            });
        }
    }

    @Override
    public long deleteDocuments(String tenantId, Collection<String> docIds) {
        if (docIds.isEmpty()) {
            return 0;
        }
        return collection.deleteMany(Filters.and(
                Filters.eq("tenantId", tenantId), Filters.in("docId", docIds))).getDeletedCount();
    }

    static Document toDocument(StoredChunk c) {
        List<Double> vector = new ArrayList<>(c.embedding().length);
        for (float f : c.embedding()) {
            vector.add((double) f);
        }
        return new Document("tenantId", c.tenantId())
                .append("docId", c.docId())
                .append("chunkIndex", c.chunkIndex())
                .append("file", c.file())
                .append("page", c.page())
                .append("text", c.text())
                .append("contentHash", c.contentHash())
                .append("embeddingModel", c.embeddingModel())
                .append("embedding", vector);
    }

    private static Set<String> toStringSet(List<Object> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            for (Object v : values) {
                if (v != null) {
                    out.add(v.toString());
                }
            }
        }
        return out;
    }
}
