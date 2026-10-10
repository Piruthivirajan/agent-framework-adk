# Progress

Source of truth for scope: [PLAN.md](PLAN.md). Update this file after every task.

## Must have

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

## Day 1 spike (done when all checked)

- [x] Container healthy and `db.version()` noted (8.3.11)
- [x] Both search indexes show `queryable: true`
- [x] `$vectorSearch` alone and `$search` alone each return the refund chunk first
- [x] `$rankFusion` and `$scoreFusion` run from Java and print scores (no Java RRF fallback needed)
- [ ] The cloud Atlas tier planned for the demo URL is checked for the same MongoDB version. Docs say `$rankFusion` needs 8.0+ and `$scoreFusion` needs **8.3+**; Flex runs "at least 8.0" and is upgraded by Atlas only after several patches of a new version, so Flex/M0 may lack `$scoreFusion`. Verify by running `spike/spike.js` against the cloud cluster.
- [x] Pin the `mongodb-atlas-local` image tag in docker-compose.yml (`8.3.11-20260928T091948Z`)

## Day 2: ingestion (steps, each built, tested and committed)

- [x] 1. Config, `EmbeddingService`, Gemini implementation, fake, tests
- [x] 2. `PdfLoader` and tests
- [ ] 3. `Chunker` and tests
- [ ] 4. `ChunkStore`, `MongoChunkStore`, index initializer, Mongo integration test
- [ ] 5. `IngestionJob`, startup trigger, tests
- [ ] 6. Manual smoke run on the placeholder PDFs (`corpus-sample/`)

## Watch list

- The skeleton code in PLAN.md (`StreamableHttpServerParameters`, `AgentTool`, `FunctionTool.create`, `McpToolset`) was written against an older ADK. Before using any of them (Day 4 and Day 6), check the class and method names against the ADK 1.11.0 javadoc or sources.

## Log

<!-- Newest first. One entry per task: date, what was built, what was verified. -->

### 2026-10-10: Day 2 step 2, PDF loading
- `SourceDocument.read(path)` reads the file once and derives `docId` (slug of the file name, stable across edits) and `contentHash` (SHA-256 of the bytes). `PdfLoader.loadPages` returns one `PdfPage(number, text)` per page via PDFBox (`setStartPage/setEndPage` per page), so chunks can carry a real page number for citations.
- Blank pages are returned with blank text (the Chunker will skip them); scanned PDFs therefore yield empty pages, OCR is out of scope. A corrupt file raises `IllegalStateException` naming the file, so the ingestion job can skip one bad file and continue.
- Tests build PDFs in memory (`TestPdfs`), no binary fixtures. Verified: BUILD SUCCESS, 26 tests.

### 2026-10-10: Day 2 step 1, embeddings
- Model `gemini-embedding-2` at 768 dimensions, one model only (the name stays configurable in `application.yml`). `gemini-embedding-001` is not in the code. Its listed shutdown date is May 14, 2028 (July 14, 2025 is its release date), so retirement was not the reason; one model is simply less to defend.
- Facts measured against the live API with a throwaway probe (5 tiny calls, not part of the repo): `-2` returns unit-norm vectors at 768 and 1536; `-001` at 768 returned norm 0.59 (not normalized); a list of 3 strings returns 3 embeddings.
- `EmbeddingService` has `embedDocuments(title, texts)` and `embedQuery(q)` so the model's prefix formats (`title: … | text: …`, `task: search result | query: …`) live in one class. `GeminiEmbeddingService` batches (32), retries 429/5xx/network with exponential backoff (max 5, cap 30 s), and rejects a wrong vector count or dimension.
- Dimension 768 is one property that feeds both the API `outputDimensionality` and (step 4) the vector index `numDimensions`. Not measured: recall at 768 vs 1536 vs 3072; the Day 7 golden-set eval is where that gets compared.
- Tests never reach the API: `FakeEmbeddingService` lives in `src/test` and is selected by `agentfw.embedding.provider=fake` in test `application.properties`; the Gemini bean does not exist then. `EmbeddingWiringTest` asserts that. The key is also blanked in test config.
- `application.properties` replaced by `application.yml`; `.env` is loaded through `spring.config.import`; Mongo URI is `spring.mongodb.uri` (Boot 4 name) with `directConnection=true`.
- Verified: `./mvnw verify` BUILD SUCCESS, 18 tests.

### 2026-10-10: Day 1 spike run, passed
- Environment: MongoDB 8.3.11 (Atlas local image, build 2026-09-28); Docker Desktop 4.94.0 on WSL 2; mongosh 2.13.0.
- mongosh (`spike/spike.js`): both indexes queryable on the 2nd poll. For "refund days", `d1` is first in `$vectorSearch` (0.999), `$search` (0.674), `$rankFusion` (0.0328) and `$scoreFusion` sigmoid (0.697). `$search` returned only 2 of 3 docs (d3 has no lexical match), the fusions returned all 3.
- Java (`FusionSpike`): same two fusion stages, same ranking and scores, so the Java driver path works with `Document.parse`. `$rankFusion` took ~38 ms and `$scoreFusion` ~14 ms on 3 docs (not a benchmark).
- Observations for the walkthrough: RRF score is `1/(60+rank)` summed over pipelines, so d1 = 2/61; `scoreDetails` shows each pipeline's rank and raw score; `$scoreFusion` default combination is `average` and a doc missing from a pipeline gets 0 for it (d3 text = 0).
- Decision: native fusion stages work, so MongoDB with `$rankFusion`/`$scoreFusion`, no Java RRF fallback needed (kept in the plan as a safety net).
- Still open: check that the cloud Atlas tier for the demo URL runs the same MongoDB version.

### 2026-10-10: Day 1 spike files written
- `docker-compose.yml` (mongodb-atlas-local:latest), `spike/spike.js` (mongosh: recreate data, create both indexes, poll until queryable, run `$vectorSearch`, `$search`, `$rankFusion`, `$scoreFusion`), `src/test/java/com/demo/agentfw/spike/FusionSpike.java` (the two fusion stages from Java, runbook step 5).
- Verified only that the Java compiles and the JS parses. Nothing was executed against MongoDB; the spike is run by hand.
- FusionSpike is not picked up by `./mvnw verify` (has `main`, no JUnit, name outside Surefire patterns).

### 2026-10-10: Stack and pom pinned
- Moved to Spring Boot 4.1.1 (3.5 is out of OSS support). Added google-adk 1.11.0, pdfbox 3.0.8, tika-parsers-standard-package 4.1.0 (`type=pom`), DJL 0.38.0. No Spring AI.
- Verified: `./mvnw dependency:tree` and `./mvnw verify` pass on JDK 21.0.12 (Temurin). The context-load test logs a Mongo "connection refused" because no DB is running yet; it still passes.
- Tree notes: Jackson 2.21.5 (ADK/Google libs) and Jackson 3.1.5 (Boot, MCP SDK) coexist; MCP SDK 2.0.0; reactor-core 3.8.7 from Boot's BOM. Test-only warning for duplicate `org.json.JSONObject` (bigquery via ADK vs jsonassert).
- `.gitattributes` forces LF so the CRLF warnings stop.
