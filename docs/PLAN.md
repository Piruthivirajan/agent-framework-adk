# Agentic Framework Assignment — Execution Plan & Project Skeleton

Oct 10, 2026 · @Alagar raja

## Requirements

Build a pluggable agent framework in Java on Google ADK with two agents, a coordinator that routes one chat, and MCP servers configurable from the UI. Requirements below are taken from the round 2 transcript (log timestamps in brackets).

| Area | Requirement | Source |
| --- | --- | --- |
| Framework | Google ADK, Java preferred for enterprise deployment; pluggable agents | 16:51:31 to 16:51:43 |
| Agent 1: Agentic RAG | 10 to 20 PDFs embedded once into a vector store; semantic + lexical search, re-ranking, answer generation with LLM calls | 16:51:46 to 16:52:24 |
| Agent 2: File Agent | Takes a file or raw content as input; backend is a summarizer. Name it File Agent, not Summary Agent | 16:52:45 to 16:53:09 |
| Orchestration | One chat interface (not threaded); an orchestration layer calls the right agent for a query or a file | 16:53:16 to 16:53:39 |
| MCP provision | Evaluator configures his own MCP server and credentials in the UI; the app connects and pulls data through ADK | 16:53:49 to 16:54:02 |
| Delivery | Launch the app somewhere he can open; no tight timeline, another weekend is fine; inform HR (Nanda) when done | 16:55:14, 16:55:39, 16:59:25 |
| Tooling | Any tool, including Claude Code, but deep understanding of everything built | 16:55:05 |

Two corrections to earlier notes:

- "Anti-generation" is a speech-to-text error for "answer generation" (16:52:24). A grounding check is still required, because an out-of-document question is one of the test cases.
- The second agent is the File Agent. It accepts a file upload or pasted content and summarizes it.

## Interviewer test scenarios

The evaluator said he will run four checks on the live app (16:55:16 to 16:55:29). Every build day should keep these four passing.

| # | Evaluator action | Expected route | Pass condition |
| --- | --- | --- | --- |
| A | Asks a question answered in the PDFs | Coordinator to RAG Agent | Correct answer with source PDF and page citations |
| B | Asks a question not in the PDFs | Coordinator to RAG Agent, then grounding check | Clear "not found in the documents" reply, no invented answer; MCP tried only if a configured tool fits |
| C | Uploads a file and asks for a summary | Deterministic route to File Agent | Summary returned, including for large files (map-reduce) |
| D | Adds his MCP server URL and credentials in the UI, then asks a question that needs its data | Coordinator calls the MCP tool | Tool called, data used in the answer, no restart needed |

## Architecture

An attachment goes to the File Agent by a code rule; every text message goes to an ADK coordinator whose LLM chooses the RAG Agent, an MCP tool, or a not-found reply.

&#91;embedded content: request routing · coordinator, two agents, MCP toolsets\]

The coordinator is the only place a routing decision is made by the model, which answers the round 2 question about where the LLM decides.

- **Plugin framework:** each agent is an `AgentPlugin` bean. The registry wraps every plugin as an ADK AgentTool that the coordinator calls, so a third agent is one new class. Agent-as-tool (not sub-agent transfer) is deliberate: the result returns to the coordinator, which is what lets it try an MCP tool after the RAG agent reports nothing found.
- **MCP provisioning:** the settings page saves URL and headers, tests the connection by listing tools, and publishes a config-changed event. The coordinator is rebuilt on the next message with one `McpToolset` per enabled server; no restart.
- **Session:** one chat session per browser tab, held in ADK's in-memory session service. Threads are out of scope, as requested.

## Retrieval design

Retrieval runs in MongoDB Atlas: vector and BM25 search in one aggregation, fused to 20 chunks, reranked to 5, and gated before Gemini answers.

&#91;embedded content: retrieval funnel · MongoDB hybrid search, rerank, grounding gate\]

Every number in the funnel is a field in `RetrievalConfig`, so the walkthrough can show the difference between candidates (400) and the final context (5).

- **numCandidates:** MongoDB recommends at least 20 times the `limit`, so 400 for a limit of 20 ([docs](https://www.mongodb.com/zh-cn/docs/vector-search/query/aggregation-stages/vector-search-stage/)).
- **ANN vs ENN:** `exact: true` scans every vector and is the reference result; ANN recall is measured against it.
- **Fusion:** `$rankFusion` combines by rank with a fixed constant of 60; `$scoreFusion` combines normalized scores with per-pipeline weights. Both are GA since June 30, 2026 ([MongoDB](https://www.mongodb.com/products/updates/now-ga-hybrid-search-with-rankfusion-and-scorefusion/)).
- **Fallback:** if the local Atlas image lacks either stage, run the two searches separately and fuse with the Java RRF method in Core interfaces. Either way the app runs on one database.
- **Chunk schema:** `tenantId`, `docId`, `chunkIndex`, `file`, `page`, `text`, `contentHash`, `embedding`. `tenantId` is a filter field in the vector index.
- **Updates:** a changed `contentHash` deletes that `docId`'s chunks and re-inserts them, so re-running ingestion is idempotent.

## Design decisions

Each choice below is stated as decision, why, trade-off and when to revisit, which is the format to use when asked "why did you choose this?". Choices fixed by the assignment are marked as such.

| Decision | Why | Trade-off | Revisit when |
| --- | --- | --- | --- |
| Google ADK for Java | Required; native MCP toolsets and sub-agent transfer | Java ADK gets new features after Python | Fixed by the assignment |
| MongoDB Atlas for vectors, BM25, metadata and MCP config | One store; native `$rankFusion` and `$scoreFusion`; simple to run | Search needs the Atlas local image in dev; BM25 tuning less deep than Elasticsearch | Tens of millions of chunks, heavy filtered ANN or a strict p99 SLA: Qdrant or OpenSearch behind `HybridRetriever` |
| Both fusion stages, picked by eval | RRF ignores score scale; score fusion keeps confidence | RRF drops score gaps; score fusion depends on normalization | Re-run the eval when the corpus or embedding model changes |
| numCandidates 400, 20 per pipeline, 5 to the LLM | MongoDB's 20x-limit guidance; 5 chunks fit with citations | More candidates add latency | ANN recall against ENN falls below target |
| Local cross-encoder re-ranker (DJL + ONNX) | No external dependency or per-call cost; predictable latency | Hosted rerankers may score better, especially multilingual | Eval gap, or a non-English corpus: A/B a hosted reranker |
| Rule for attachments, LLM for text routing | Attachments are unambiguous; text needs judgment between RAG, MCP and not found | One extra model call; possible misroutes | Misroutes in the trace: add routing examples or a classifier |
| Chunks of 500 to 800 tokens, 15% overlap | Balance of precision and context | Weak on tables and forms | Eval misses on tables: layout-aware parsing or smaller chunks |
| MCP over Streamable HTTP, SSE fallback | Current MCP transport, single endpoint | Some older servers speak only SSE | Once the evaluator's server type is known |
| Credentials masked, never logged, never in the prompt | POC scope with a real secret involved | Not encrypted at rest | Production: KMS or Secret Manager encryption |
| Render or Railway or one VM, with a cloud Atlas cluster | A reachable URL in half a day | Not production grade; the Atlas local image is for development only | Production: Cloud Run or GKE and a dedicated Atlas tier |

**Consistency with round 1.** Round 1 argued for a dedicated vector store when specific performance needs exist (13:13:37). Say it the same way here: at 20 PDFs vector performance is not the bottleneck, so one store and native hybrid fusion win; at production scale the dedicated store comes back behind the same `HybridRetriever` interface.

**Option considered: Postgres + pgvector.** It is the usual Spring Boot default and was the first plan. It lost on two points: native Postgres full-text (`tsvector`, `ts_rank`) is not BM25, so true BM25 needs an extension such as ParadeDB `pg_search`, and fusion would be hand-written. Do not describe Postgres full-text as BM25 in the discussion.

Do not say MongoDB was chosen because the company uses it; that is an inference from round 1, not a fact. Use the criteria above.

## Tech stack

The stack is locked to Java 21, Spring Boot 4.x and Google ADK for Java, with MongoDB Atlas as the only data store.

| Layer | Choice | Why |
| --- | --- | --- |
| Agent framework | Google ADK for Java 1.x, pinned to 1.11.0 (latest on Maven Central, Oct 2026) | Asked for explicitly; MCP support built in |
| App | Spring Boot 4.1.1, Java 21, Maven | Evaluator prefers Java for enterprise apps; Boot 3.5 lost OSS support on June 30, 2026 and ADK Java moved to Boot 4 |
| LLM + embeddings | Gemini through ADK and the Google GenAI Java SDK | Native to ADK |
| Vector + lexical search | MongoDB Atlas Vector Search + Atlas Search (BM25), local via Atlas CLI | Round 1 questions mapped to MongoDB features |
| Hybrid fusion | `$rankFusion` and `$scoreFusion`, switchable; Java RRF as fallback | Round 1 asked about score fusion |
| Re-ranker | Cross-encoder (ms-marco-MiniLM) through DJL + ONNX Runtime | Matches what was said in round 1 |
| File parsing | Apache PDFBox for the corpus, Apache Tika for uploads | PDF, DOCX and TXT uploads |
| MCP | ADK `McpToolset` with `StreamableHttpServerParameters` (URL, headers); SSE as fallback | Evaluator's own server, runtime config |
| UI | One chat page + an MCP settings page (plain HTML/JS or React) | Single chat, no threads |
| Hosting | Render, Railway, or one VM with Docker Compose; Atlas free or flex tier | A reachable URL, set up in half a day |

[ADK Java MCP API reference](https://adk.dev/api-reference/java/com/google/adk/tools/mcp/StreamableHttpServerParameters.Builder.html) · [MongoDB hybrid search GA note](https://www.mongodb.com/products/updates/now-ga-hybrid-search-with-rankfusion-and-scorefusion/)

## Project structure and pom.xml

One Spring Boot module, split by package so each part can be explained on its own. A new agent is one new class under `agents/`; nothing in `core/` changes.

```text
agent-framework/
├── pom.xml
├── docker-compose.yml            # app + Atlas local (mongodb/mongodb-atlas-local)
├── src/main/java/com/demo/agentfw/
│   ├── AgentFrameworkApplication.java
│   ├── core/          # AgentPlugin, AgentRegistry, CoordinatorFactory, ChatService
│   ├── agents/
│   │   ├── rag/       # RagAgentPlugin, SearchDocumentsTool, GroundingCheck
│   │   └── file/      # FileAgentPlugin, SummarizeFileTool, MapReduceSummarizer
│   ├── retrieval/     # HybridRetriever, FusionStrategy, CrossEncoderReranker, RetrievalConfig
│   ├── ingestion/     # PdfLoader, Chunker, EmbeddingService, IngestionJob
│   ├── mcp/           # McpServerConfig, McpRegistry, McpController
│   ├── api/           # ChatController (SSE), UploadController, GlobalExceptionHandler
│   └── eval/          # GoldenSetRunner (RRF vs score fusion)
├── src/main/resources/
│   ├── application.yml
│   ├── static/        # chat.html, mcp.html
│   └── eval/golden-set.json
└── corpus/            # the 10 to 20 PDFs
```

Versions below are the ones pinned and verified on Oct 10, 2026 (`./mvnw dependency:tree` and `./mvnw verify` pass). Three things from the dependency tree to remember:

- **Jackson 2 and 3 coexist.** Boot 4 uses Jackson 3 (`tools.jackson`); ADK's Google libraries use Jackson 2 (`com.fasterxml.jackson`, 2.21.x via Boot's BOM). The packages differ, so there is no class clash. ADK's MCP SDK (`io.modelcontextprotocol.sdk:mcp` 2.0.0) uses Jackson 3 through `mcp-json-jackson3`.
- **Tika 4 changed packaging.** `tika-parsers-standard-package` is a pom-only aggregator since 4.0, so it needs `<type>pom</type>`; its parser modules come in transitively.
- **Do not add Spring AI or `google-adk-spring-ai`.** Known MCP SDK version conflicts.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version> <!-- literal: Maven can't use a property here; latest GA 4.x -->
  </parent>

  <groupId>com.demo</groupId>
  <artifactId>agent-framework</artifactId>
  <version>0.1.0</version>

  <properties>
    <java.version>21</java.version>
    <!-- Spring Boot version lives on the parent above -->
    <adk.version>1.11.0</adk.version>                <!-- pin latest 1.x -->
    <pdfbox.version>3.0.8</pdfbox.version>
    <tika.version>4.1.0</tika.version>
    <djl.version>0.38.0</djl.version>
  </properties>

  <dependencies>
    <!-- Agents + MCP -->
    <dependency>
      <groupId>com.google.adk</groupId>
      <artifactId>google-adk</artifactId>
      <version>${adk.version}</version>
    </dependency>

    <!-- Web, SSE, validation, health -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- MongoDB Atlas (vector + search + config store) -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-mongodb</artifactId>
    </dependency>

    <!-- Parsing -->
    <dependency>
      <groupId>org.apache.pdfbox</groupId>
      <artifactId>pdfbox</artifactId>
      <version>${pdfbox.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.tika</groupId>
      <artifactId>tika-parsers-standard-package</artifactId>
      <version>${tika.version}</version>
      <type>pom</type> <!-- pom-only aggregator since Tika 4; has no jar -->
    </dependency>

    <!-- Cross-encoder re-ranker -->
    <dependency>
      <groupId>ai.djl</groupId>
      <artifactId>api</artifactId>
      <version>${djl.version}</version>
    </dependency>
    <dependency>
      <groupId>ai.djl.onnxruntime</groupId>
      <artifactId>onnxruntime-engine</artifactId>
      <version>${djl.version}</version>
    </dependency>
    <dependency>
      <groupId>ai.djl.huggingface</groupId>
      <artifactId>tokenizers</artifactId>
      <version>${djl.version}</version>
    </dependency>

    <!-- Boot 4 has one test starter per main starter -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-mongodb-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

## Core interfaces and key classes

These are skeletons to build on, not finished code. ADK and MongoDB calls marked `// verify` should be checked against the versions you pin, because both APIs moved quickly in 2026.

### Plugin contract (core)

```java
public interface AgentPlugin {
    String name();          // "rag_agent", "file_agent"
    String description();   // the coordinator reads this to route
    BaseAgent build(AgentContext ctx);
}

public record AgentContext(String model) {}

@Component
public class AgentRegistry {
    private final List<AgentPlugin> plugins;           // Spring injects every @Component plugin
    public AgentRegistry(List<AgentPlugin> plugins) { this.plugins = plugins; }

    public List<BaseAgent> buildAll(AgentContext ctx) {
        return plugins.stream().map(p -> p.build(ctx)).toList();
    }
    public BaseAgent build(String name, AgentContext ctx) {
        return plugins.stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow().build(ctx);
    }
}
```

### Coordinator, rebuilt when MCP config changes (core)

```java
@Component
public class CoordinatorFactory {
    private final AgentRegistry registry;
    private final McpRegistry mcp;
    private final AgentContext ctx;
    private volatile InMemoryRunner runner;              // cached; null = rebuild

    public synchronized InMemoryRunner runner() {
        if (runner == null) {
            List<Object> tools = new ArrayList<>(mcp.activeToolsets());   // McpToolset per server
            registry.buildAll(ctx).forEach(a -> tools.add(AgentTool.create(a)));  // verify; results return to coordinator
            LlmAgent root = LlmAgent.builder()
                .name("coordinator")
                .model(ctx.model())
                .instruction("""
                    Route each user message:
                    - Questions about the reference documents -> call rag_agent.
                    - Questions needing live data from a connected MCP tool -> call that tool.
                    - If rag_agent reports NO_RELEVANT_CONTEXT, try a fitting MCP tool;
                      if none fits, say the answer is not in the documents.
                    Never invent facts.""")
                .description("Routes each message to an agent tool or an MCP tool")
                .tools(tools)                                     // verify: toolset overload
                .build();
            runner = new InMemoryRunner(root);
        }
        return runner;
    }

    @EventListener(McpConfigChanged.class)
    public synchronized void invalidate() { runner = null; }       // next chat rebuilds with new tools
}
```

### Deterministic file route, LLM route for text (core)

```java
@Service
public class ChatService {
    public Flowable<Event> chat(String sessionId, String text, @Nullable String fileId) {
        Content msg = Content.fromParts(Part.fromText(
            fileId == null ? text : "[fileId=" + fileId + "] " + text));
        InMemoryRunner r = (fileId != null)
            ? fileRunner()                    // rule: an attachment always goes to file_agent
            : coordinators.runner();          // LLM decides: RAG, MCP tool, or not found
        return r.runAsync(USER_ID, sessionId, msg);
    }
}
```

### RAG agent and its search tool (agents/rag)

```java
@Component
public class RagAgentPlugin implements AgentPlugin {
    public String name() { return "rag_agent"; }
    public String description() {
        return "Answers questions from the indexed reference PDFs, with citations.";
    }
    public BaseAgent build(AgentContext ctx) {
        return LlmAgent.builder()
            .name(name()).description(description()).model(ctx.model())
            .instruction("""
                Call searchDocuments. You may rewrite the query and call it up to 3 times.
                Answer only from returned chunks and cite [file, page] for every claim.
                If status is NO_RELEVANT_CONTEXT, reply: "Not found in the reference documents."
                """)
            .tools(FunctionTool.create(SearchDocumentsTool.class, "searchDocuments"))
            .build();
    }
}

public class SearchDocumentsTool {
    static HybridRetriever retriever;     // set at startup; static because FunctionTool.create
    static double minRerankScore;         // takes a static method (verify instance overload)

    public static Map<String, Object> searchDocuments(
            @Schema(name = "query", description = "Search query") String query) {
        List<ScoredChunk> top = retriever.retrieve(query);                 // fuse + rerank
        if (top.isEmpty() || top.get(0).score() < minRerankScore) {
            return Map.of("status", "NO_RELEVANT_CONTEXT");             // grounding gate
        }
        return Map.of("status", "OK", "chunks", top.stream().map(ScoredChunk::toMap).toList());
    }
}
```

### Hybrid retrieval in MongoDB (retrieval)

```java
public enum FusionStrategy { RANK, SCORE, JAVA_RRF }

// One aggregation for RANK; swap the stage name and add normalization for SCORE.  // verify syntax
List<Document> pipeline = List.of(
  new Document("$rankFusion", new Document()
    .append("input", new Document("pipelines", new Document()
        .append("vector", List.of(new Document("$vectorSearch", new Document()
            .append("index", "chunk_vector").append("path", "embedding")
            .append("queryVector", queryVector)
            .append("numCandidates", cfg.numCandidates())        // 20x limit, e.g. 400
            .append("limit", cfg.perPipelineLimit())             // e.g. 20
            .append("filter", new Document("tenantId", tenantId)))))
        .append("text", List.of(
            new Document("$search", new Document("index", "chunk_text")
                .append("text", new Document("query", query).append("path", "text"))),
            new Document("$limit", cfg.perPipelineLimit())))))
    .append("combination", new Document("weights",
        new Document("vector", cfg.vectorWeight()).append("text", cfg.textWeight())))),
  new Document("$limit", cfg.fusedLimit()),                       // e.g. 20, then rerank
  new Document("$project", new Document("text", 1).append("file", 1).append("page", 1)
      .append("score", new Document("$meta", "score"))));
```

Fallback if the local image lacks `$rankFusion`: run both pipelines separately and fuse in Java.

```java
static List<String> rrf(List<List<String>> rankedIdLists, int k) {   // k = 60
    Map<String, Double> score = new HashMap<>();
    for (List<String> ids : rankedIdLists)
        for (int r = 0; r < ids.size(); r++)
            score.merge(ids.get(r), 1.0 / (k + r + 1), Double::sum);
    return score.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .map(Map.Entry::getKey).toList();
}
```

### MCP provisioning (mcp)

```java
public record McpServerConfig(String id, String name, String url,
                              Map<String, String> headers, boolean enabled) {
    @Override public String toString() {            // never print secrets
        return "McpServerConfig[" + name + ", " + url + ", headers=***]";
    }
}

@Service
public class McpRegistry {
    private final Map<String, McpToolset> toolsets = new ConcurrentHashMap<>();

    public void save(McpServerConfig c) {
        McpToolset old = toolsets.remove(c.id());
        if (old != null) old.close();
        if (c.enabled()) {
            toolsets.put(c.id(), new McpToolset(StreamableHttpServerParameters.builder()
                .url(c.url()).headers(c.headers())
                .timeout(Duration.ofSeconds(30)).build()));
        }
        events.publishEvent(new McpConfigChanged()); // no direct call: avoids a circular bean
    }
    public List<McpToolset> activeToolsets() { return List.copyOf(toolsets.values()); }
}
```

The UI's Test connection button lists the server's tools through the toolset and shows names only. Headers are masked in every API response and never logged or sent to the model.

### Errors (api)

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    public record ApiError(String code, String message, Instant timestamp) {}

    @ExceptionHandler(McpConnectionException.class)
    ResponseEntity<ApiError> mcp(McpConnectionException e) {
        return ResponseEntity.status(502).body(new ApiError("MCP_UNREACHABLE", e.getMessage(), Instant.now()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> any(Exception e) {
        return ResponseEntity.status(500).body(new ApiError("INTERNAL", "Unexpected error", Instant.now()));
    }
}
```

## Priorities and timeline

The must-have list takes about 6 working days, plus a day for deployment, eval and rehearsal; there is no fixed deadline, so quality comes before speed.

**Must have**

- [ ] Plugin framework: `AgentPlugin`, registry, coordinator built from plugins
- [ ] RAG agent: MongoDB hybrid retrieval, cross-encoder rerank, citations
- [ ] Grounding gate: "Not found in the reference documents" below the rerank threshold
- [ ] File agent: Tika parsing, single pass for small input, map-reduce for large
- [ ] Routing: attachment goes to the file agent by rule; text goes through the coordinator LLM
- [ ] MCP settings page: URL + auth header, test connection, tools loaded at runtime
- [ ] Credential hygiene: masked in UI and API, never logged, never sent to the model
- [ ] Graceful failures: wrong MCP URL or token, MCP server down, Gemini timeout each show a clear UI message ("Invalid credentials", "Unable to connect to MCP server"), never a stack trace
- [ ] Trace panel under each answer: route chosen, tool calls, vector and BM25 hit counts, fused top 20, rerank scores, latency per stage
- [ ] Small eval: 15 to 20 golden questions, precision@5 for `$rankFusion` vs `$scoreFusion`
- [ ] Reachable deployment with a seeded corpus

**Should have (about half a day each)**

- [ ] README with the two diagrams, the design decisions table, and how to add a third agent
- [ ] A 5-minute live demo of adding a third agent as one `AgentPlugin` class

**Skip, mention verbally if asked**

- Resilience4j circuit breaker and bulkhead, AES-GCM at rest, collection-per-tenant, ANN vs ENN recall chart.

**Day plan**

1. Day 1 spike (runbook below): Atlas local, indexes, `$rankFusion` and `$scoreFusion` from Java on toy data. Then Spring Boot + ADK hello agent.
2. Ingestion: PDFBox, chunking (about 500 to 800 tokens, 15% overlap), embeddings, idempotent upsert by `docId` + `contentHash`.
3. Hybrid retrieval with fusion toggle, cross-encoder rerank, grounding gate; trace events recorded per stage.
4. RAG agent tool + plugin registry + coordinator; scenarios A and B passing.
5. File agent with map-reduce; upload endpoint; chat UI with the trace panel; scenario C passing.
6. MCP settings page, runtime toolsets, graceful failure messages; scenario D passing against a small test MCP server you write.
7. Deploy, run the eval, write the README; rehearse the walkthrough twice.

## Day 1 spike runbook

Day 1 proves the riskiest piece, MongoDB hybrid search called from Java, on three toy documents before any app code. It needs no embedding API: the toy vectors have 3 dimensions.

**1. Start Atlas local.** The `mongodb/mongodb-atlas-local` image bundles `mongod` and `mongot` (the search process) in one container; it is for development only ([MongoDB quickstart](https://www.mongodb.com/docs/search/self-managed/current/installation/quick-start/)).

```yaml
# docker-compose.yml
services:
  mongodb:
    image: mongodb/mongodb-atlas-local:latest   # pin the tag once the spike passes
    ports:
      - "27017:27017"
```

```bash
docker compose up -d
docker ps                                  # wait for (healthy)
mongosh "mongodb://localhost:27017/?directConnection=true"
```

In mongosh, run `db.version()`. The docs list `$scoreFusion` as MongoDB 8.2+, and some doc versions say 8.3+. If the version is older, try the `:preview` tag.

**2. Insert toy data.**

```javascript
use spike
db.chunks.insertMany([
  { tenantId: "demo", docId: "d1", page: 1, text: "Refund requests are processed within 14 days.",   embedding: [0.9, 0.1, 0.0] },
  { tenantId: "demo", docId: "d2", page: 3, text: "Invoices are emailed on the first business day.", embedding: [0.1, 0.9, 0.0] },
  { tenantId: "demo", docId: "d3", page: 2, text: "Error code E-401 means the token has expired.",   embedding: [0.0, 0.2, 0.9] }
])
```

**3. Create both indexes.** On real data, `numDimensions` must equal the embedding model's output size.

```javascript
db.chunks.createSearchIndex("chunk_vector", "vectorSearch", {
  fields: [
    { type: "vector", path: "embedding", numDimensions: 3, similarity: "cosine" },
    { type: "filter", path: "tenantId" }
  ]
})

db.chunks.createSearchIndex("chunk_text", {
  mappings: { dynamic: false, fields: {
    text:     { type: "string", analyzer: "lucene.english" },
    tenantId: { type: "token" }
  } }
})

db.chunks.getSearchIndexes()   // wait until both show queryable: true
```

**4. Run both fusion stages in mongosh.** For the query "refund days", the refund chunk should rank first in both.

```javascript
const pipelines = {
  vector: [ { $vectorSearch: { index: "chunk_vector", path: "embedding",
              queryVector: [0.85, 0.15, 0.0], numCandidates: 20, limit: 3,
              filter: { tenantId: "demo" } } } ],
  text:   [ { $search: { index: "chunk_text", text: { query: "refund days", path: "text" } } },
            { $limit: 3 } ]
};
const project = { $project: { text: 1, score: { $meta: "score" }, details: { $meta: "scoreDetails" } } };

// Rank fusion (RRF)
db.chunks.aggregate([
  { $rankFusion: { input: { pipelines }, combination: { weights: { vector: 1, text: 1 } }, scoreDetails: true } },
  project
])

// Score fusion (also try normalization: "minMaxScaler")
db.chunks.aggregate([
  { $scoreFusion: { input: { pipelines, normalization: "sigmoid" },
                    combination: { weights: { vector: 1, text: 1 } }, scoreDetails: true } },
  project
])
```

**5. Run the same from Java.** `Document.parse` keeps the pipeline readable and identical to the mongosh version. The driver (`mongodb-driver-sync`) comes with the Spring Boot MongoDB starter.

```java
public class FusionSpike {
    static final String PIPELINES = """
        { vector: [ { $vectorSearch: { index: "chunk_vector", path: "embedding",
                      queryVector: [0.85, 0.15, 0.0], numCandidates: 20, limit: 3,
                      filter: { tenantId: "demo" } } } ],
          text:   [ { $search: { index: "chunk_text", text: { query: "refund days", path: "text" } } },
                    { $limit: 3 } ] }""";

    static final Map<String, String> STAGES = Map.of(
        "rankFusion",  "{ $rankFusion: { input: { pipelines: " + PIPELINES + " },"
                     + " combination: { weights: { vector: 1, text: 1 } }, scoreDetails: true } }",
        "scoreFusion", "{ $scoreFusion: { input: { pipelines: " + PIPELINES + ", normalization: 'sigmoid' },"
                     + " combination: { weights: { vector: 1, text: 1 } }, scoreDetails: true } }");

    static final String PROJECT =
        "{ $project: { text: 1, score: { $meta: 'score' }, details: { $meta: 'scoreDetails' } } }";

    public static void main(String[] args) {
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017/?directConnection=true")) {
            MongoCollection<Document> chunks = client.getDatabase("spike").getCollection("chunks");
            STAGES.forEach((name, stage) -> {
                long t0 = System.nanoTime();
                try {
                    List<Document> out = chunks.aggregate(List.of(Document.parse(stage), Document.parse(PROJECT)))
                                               .into(new ArrayList<>());
                    System.out.printf("%s: %d docs in %d ms%n", name, out.size(), (System.nanoTime() - t0) / 1_000_000);
                    out.forEach(d -> System.out.println("  " + d.get("score") + "  " + d.getString("text")));
                } catch (MongoCommandException e) {
                    System.out.println(name + " FAILED: " + e.getErrorMessage());   // -> Java RRF fallback
                }
            });
        }
    }
}
```

**6. If a stage fails.** Run `$vectorSearch` and `$search` as two separate aggregations, collect `_id`s in rank order, and fuse with the `rrf` method in Core interfaces. Record the decision in Design decisions.

| Spike result | Decision |
| --- | --- |
| Everything passes | MongoDB with native fusion, plan as written |
| `$vectorSearch` and `$search` work, fusion stages do not | MongoDB with Java RRF |
| Atlas local search (`mongot`) cannot be set up | Postgres + pgvector + Java RRF; record the execution risk as the reason |

**Day 1 is done when:**

- [ ] Container healthy and `db.version()` noted
- [ ] Both search indexes show `queryable: true`
- [ ] `$vectorSearch` alone and `$search` alone each return the refund chunk first
- [ ] `$rankFusion` and `$scoreFusion` run from Java and print scores, or the Java RRF fallback is chosen
- [ ] The cloud Atlas tier planned for the demo URL is checked for the same MongoDB version

## Discussion prep

The follow-up call is 20 to 25 minutes of technical discussion on what you built (16:55:56), so every answer below should point at your own code or your own measured numbers.

| Likely question | Where it came from | Answer from your build |
| --- | --- | --- |
| Where exactly is the LLM used, and does it decide anything? | Round 2, 16:38:52 and 16:39:37 | Coordinator routing, RAG query rewriting and answer generation, file map-reduce, MCP tool choice |
| Why RRF and not score fusion? | Round 1, 12:48:12 | Show both behind the toggle, with your eval numbers |
| limit vs numCandidates, and the final count to the LLM | Round 1, 12:48:40 to 12:50:00 | 400 candidates, 20 per pipeline, 20 fused, 5 after rerank |
| ANN vs ENN accuracy | Round 1, 13:06:22 | ENN is exact and slower; ANN is tuned with numCandidates and measured against ENN |
| Why not plain MongoDB for vectors? | Round 1, 13:13:37 | You did use MongoDB Atlas; explain the choice |
| A user reports no answer: how do you debug? | Round 1, 12:55:06 | Per-stage logs: hits per pipeline, fused count, top rerank score, LLM errors |
| How do you update embeddings when a PDF changes? | Round 1, 12:52:06 | Content hash changes, so delete by `docId` and re-insert its chunks |
| How are MCP credentials protected? | Round 2 requirement | Masked, not logged, never in the prompt; at-rest encryption is the production next step |
| Adding a third agent? | Round 2, 16:51:43 | One new `AgentPlugin` class; show the diff |

Keep claims consistent with round 1. Round 1 mentioned 90% retrieval precision, a 500+ question golden set, Cohere rerank and Pinecone. In this call, quote only numbers you measured in this project, and say clearly which parts come from past work and which from this build.
