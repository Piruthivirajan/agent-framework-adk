package com.demo.agentfw.spike;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day 1 spike, runbook step 5: the same two fusion stages as spike/spike.js, run from Java.
 *
 * <p>Not a test: it has a {@code main} method and no JUnit annotations, and its name does not match
 * Surefire's default patterns, so {@code ./mvnw verify} never runs it. Run spike/spike.js first, it
 * creates the data and the two search indexes this class queries.
 */
public class FusionSpike {

    static final String URI =
            "mongodb://localhost:27017/?directConnection=true&serverSelectionTimeoutMS=5000";

    static final String PIPELINES = """
        { vector: [ { $vectorSearch: { index: "chunk_vector", path: "embedding",
                      queryVector: [0.85, 0.15, 0.0], numCandidates: 20, limit: 3,
                      filter: { tenantId: "demo" } } } ],
          text:   [ { $search: { index: "chunk_text", text: { query: "refund days", path: "text" } } },
                    { $limit: 3 } ] }""";

    static final Map<String, String> STAGES = new LinkedHashMap<>();

    static {
        STAGES.put("rankFusion",
                "{ $rankFusion: { input: { pipelines: " + PIPELINES + " },"
                + " combination: { weights: { vector: 1, text: 1 } }, scoreDetails: true } }");
        STAGES.put("scoreFusion",
                "{ $scoreFusion: { input: { pipelines: " + PIPELINES + ", normalization: 'sigmoid' },"
                + " combination: { weights: { vector: 1, text: 1 } }, scoreDetails: true } }");
    }

    static final String PROJECT =
            "{ $project: { text: 1, docId: 1, score: { $meta: 'score' }, details: { $meta: 'scoreDetails' } } }";

    public static void main(String[] args) {
        String uri = args.length > 0 ? args[0] : URI;
        try (MongoClient client = MongoClients.create(uri)) {
            Document build = client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
            System.out.println("MongoDB version: " + build.getString("version"));

            MongoCollection<Document> chunks = client.getDatabase("spike").getCollection("chunks");
            STAGES.forEach((name, stage) -> {
                long t0 = System.nanoTime();
                try {
                    List<Document> out = chunks
                            .aggregate(List.of(Document.parse(stage), Document.parse(PROJECT)))
                            .into(new ArrayList<>());
                    System.out.printf("%n%s: %d docs in %d ms%n", name, out.size(),
                            (System.nanoTime() - t0) / 1_000_000);
                    out.forEach(d -> {
                        System.out.println("  " + d.get("score") + "  [" + d.getString("docId") + "]  "
                                + d.getString("text"));
                        Document details = d.get("details", Document.class);
                        if (details != null) {
                            System.out.println("      details: " + details.toJson());
                        }
                    });
                } catch (MongoCommandException e) {
                    System.out.println(name + " FAILED: " + e.getErrorMessage());   // -> Java RRF fallback
                }
            });
        } catch (MongoException e) {
            System.out.println("Could not run the spike: " + e.getMessage());
            System.out.println("Is the container healthy (docker ps) and did spike/spike.js finish?");
        }
    }
}
