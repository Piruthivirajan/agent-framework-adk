package com.demo.agentfw.ingestion;

import com.mongodb.client.model.SearchIndexModel;
import com.mongodb.client.model.SearchIndexType;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates the two Atlas Search indexes the retrieval stage needs, if they do not exist yet:
 * {@code chunk_vector} (vector search, cosine) and {@code chunk_text} (BM25 over {@code text}).
 * The names and definitions are the ones proven in the Day 1 spike.
 *
 * <p>{@code numDimensions} is taken from the embedding service, the same value that is sent to the
 * model, so the two cannot disagree. If an index with a different size already exists the job
 * stops with an instruction: changing the dimension needs a dropped index and a re-ingest, and
 * silently dropping data is not this class's decision.
 */
@Component
public class ChunkIndexInitializer {

    public static final String VECTOR_INDEX = "chunk_vector";
    public static final String TEXT_INDEX = "chunk_text";

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexInitializer.class);

    private final MongoChunkStore store;
    private final int dimensions;

    @Autowired
    public ChunkIndexInitializer(MongoChunkStore store, EmbeddingService embeddings) {
        this(store, embeddings.dimensions());
    }

    public ChunkIndexInitializer(MongoChunkStore store, int dimensions) {
        this.store = store;
        this.dimensions = dimensions;
    }

    /** Index creation is asynchronous: the indexes become queryable a few seconds after this returns. */
    public void ensureIndexes() {
        store.ensureSchema();

        List<Document> existing = store.collection().listSearchIndexes().into(new ArrayList<>());
        Document vector = find(existing, VECTOR_INDEX);
        Document text = find(existing, TEXT_INDEX);

        List<SearchIndexModel> toCreate = new ArrayList<>();
        if (vector == null) {
            toCreate.add(new SearchIndexModel(VECTOR_INDEX, vectorDefinition(), SearchIndexType.vectorSearch()));
        } else {
            checkDimensions(vector);
        }
        if (text == null) {
            toCreate.add(new SearchIndexModel(TEXT_INDEX, textDefinition(), SearchIndexType.search()));
        }

        if (toCreate.isEmpty()) {
            log.info("Search indexes {} and {} already exist", VECTOR_INDEX, TEXT_INDEX);
            return;
        }
        store.collection().createSearchIndexes(toCreate);
        toCreate.forEach(m -> log.info("Created search index {} (queryable after a short build)", m.getName()));
    }

    private Document vectorDefinition() {
        return new Document("fields", List.of(
                new Document("type", "vector").append("path", "embedding")
                        .append("numDimensions", dimensions).append("similarity", "cosine"),
                new Document("type", "filter").append("path", "tenantId")));
    }

    private static Document textDefinition() {
        return new Document("mappings", new Document("dynamic", false).append("fields", new Document()
                .append("text", new Document("type", "string").append("analyzer", "lucene.english"))
                .append("tenantId", new Document("type", "token"))));
    }

    private void checkDimensions(Document vectorIndex) {
        Document definition = vectorIndex.get("latestDefinition", Document.class);
        if (definition == null) {
            log.warn("Index {} exists but its definition was not returned; dimension not verified", VECTOR_INDEX);
            return;
        }
        for (Object f : definition.getList("fields", Object.class, List.of())) {
            if (f instanceof Document field && "vector".equals(field.getString("type"))) {
                Number actual = (Number) field.get("numDimensions");
                if (actual != null && actual.intValue() != dimensions) {
                    throw new IllegalStateException("Search index " + VECTOR_INDEX + " has numDimensions="
                            + actual + " but agentfw.embedding.dimensions=" + dimensions
                            + ". Drop the index and re-ingest after changing the embedding size.");
                }
            }
        }
    }

    private static Document find(List<Document> indexes, String name) {
        return indexes.stream().filter(i -> name.equals(i.getString("name"))).findFirst().orElse(null);
    }
}
