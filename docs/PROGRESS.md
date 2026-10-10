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
- [x] 3. `Chunker` and tests
- [x] 4. `ChunkStore`, `MongoChunkStore`, index initializer, Mongo integration test
- [x] 5. `IngestionJob`, startup trigger, tests
- [x] 6. Manual smoke run on the placeholder PDFs (`corpus-sample/`)

## Day 3: retrieval (approved plan, each step built, tested and committed)

Decisions: reranker `ms-marco-MiniLM-L6-v2` fp32 as baseline, int8 chosen only if its top-5 agrees with fp32 on the same 20 pairs; rerank depth is a config value and 12 is measured against 20 (choose 12 if the expected page stays inside the fused top 12); truncation at 512 accepted and counted in the trace; corpus is gitignored and fetched by `scripts/fetch-corpus.sh` with a sha256 per file; calibration questions are drafted, then reviewed by the user before any calibration run.

- [x] 0. Corpus fetch script, compose robustness, ingest the real corpus
- [x] 1. Reranker: `ModelFiles` (download + sha256), `CrossEncoderReranker`, fp32 vs int8 comparison
- [x] 2. Retrieval core: `RANK` / `SCORE` / `JAVA_RRF`, config, Mongo integration test
- [x] 3. `RetrievalTrace` and `RetrievalService` with the grounding gate
- [ ] 4. Calibration: question set drafted and harness built (**calibration NOT run; waiting for the user's review of `src/main/resources/eval/calibration-questions.json`**), then run it and write `docs/CALIBRATION.md`
- [ ] 5. Smoke at retrieval level

## Day 7 notes

- **Hosting decision needs a measurement:** rerank latency on a small cloud CPU will be several times the laptop number (about 1.8 s for 20 chunks, single predictor, on an i7-10850H 6C/12T). Measure rerank latency on the target host before choosing the hosting tier; if too slow, options are the int8 model, a smaller rerank depth, or a hosted reranker behind the same `Reranker` interface.

## Watch list

- The skeleton code in PLAN.md (`StreamableHttpServerParameters`, `AgentTool`, `FunctionTool.create`, `McpToolset`) was written against an older ADK. Before using any of them (Day 4 and Day 6), check the class and method names against the ADK 1.11.0 javadoc or sources.

## Log

<!-- Newest first. One entry per task: date, what was built, what was verified. -->

### 2026-10-10: Day 3 step 4 (part 1), calibration questions and harness
- `src/main/resources/eval/calibration-questions.json`: 36 questions. 24 in-corpus (3 to 4 per document, across all 7), 6 out-of-corpus far (sports, cooking, geography), 6 out-of-corpus near-domain (Azure, GKE, Helm, PostgreSQL tuning, Linux sysctl, Google Cloud Architecture Framework). Every in-corpus question carries a verbatim evidence phrase; its `expected` pages are every page in the corpus where that phrase appears (the framework and the pillars repeat passages), table-of-contents pages excluded. Every out-of-corpus question lists terms that were checked absent from the corpus text. Every 4th question per type is held out (8 questions) and not used to choose the threshold.
- Harness (not yet run on the corpus): `CalibrationRunner` (only with `agentfw.calibration.run=true`) retrieves each question once with all 20 fused candidates scored and no threshold, derives the depth-12 numbers from the same scores, and `CalibrationReport` renders `docs/CALIBRATION.md`: every score, recall at both depths, the depth decision, the threshold picked on the calibration split with its candidate table, the held-out check, and out-of-corpus scores by type. `ThresholdSelector` picks the midpoint with the best balanced accuracy and the widest empty gap.
- Verified: BUILD SUCCESS, 106 tests.

### 2026-10-10: Day 3 step 3, trace and grounding gate
- `RetrievalService.retrieve(query)` runs: embed, search and fuse (top 20), rerank the first `rerankDepth`, keep `finalTopN`, gate. `retrieve(query, settings)` takes per-call settings (fusion, depth), which the calibration and the Day 7 eval use.
- `RetrievalTrace` (plain records, serializes with Jackson 3) holds: the settings in force and model names; `embedMillis`; search: strategy, vector hits, text hits, union size, per-stage ms, and the fused top 20 each with fused rank, file, page, 160-char snippet, fused score and per-pipeline rank and score; rerank: model, pairs scored, truncated pairs, ms, and every scored pair with rerank score and its fused rank; grounding: top score, threshold, margin, decision; `totalMillis` and a `stageMillis()` timeline. Full passages are only in `RetrievedChunk`, never in the trace and never in logs.
- Gate: top-1 rerank score against the threshold; no candidates means `NO_RELEVANT_CONTEXT`; **no threshold set means `NOT_CALIBRATED`** (chunks are returned for inspection, a WARN is logged at startup). The vector similarity is deliberately not used (compressed scale, shown on Day 2).
- Each stage logs one INFO line with counts and ms (no question or passage text).
- Verified: BUILD SUCCESS, 93 tests.

### 2026-10-10: Day 3 step 2, retrieval core
- `AtlasChunkSearch` runs vector (`$vectorSearch`, numCandidates 400, limit 20, tenant filter) and BM25 (`$search` compound with a tenant `equals` filter, limit 20) and fuses them three ways: `RANK` (`$rankFusion`), `SCORE` (`$scoreFusion`, sigmoid) or `JAVA_RRF` (two queries, fused in Java). The strategy is a config default and a per-call override (the Day 7 eval uses that).
- Native fusion cuts to the largest possible union (2 x 20) before projecting, so per-pipeline hit counts parsed from `scoreDetails` are exact; only then is the list cut to the fused 20. `$scoreFusion` reports raw scores, not ranks, so ranks are derived per pipeline.
- `RetrievalProperties` holds every funnel number, with `rerankDepth` configurable (default 20; the depth experiment decides 12). `minRerankScore` is nullable on purpose: null means "not calibrated", never a guessed number.
- Verified against the live Atlas container on the Day 1 toy data: all three strategies rank the refund chunk first; `$rankFusion` gives the same 2/61 score as the Day 1 spike; Java RRF reproduces the native scores and ranking exactly; another tenant's matching chunk never comes back; a query with no lexical match still gets vector candidates.
- Verified: BUILD SUCCESS, 80 tests (6 against MongoDB, 4 with the real model).

### 2026-10-10: Day 3 steps 0 and 1, corpus, compose, reranker
- **Corpus:** gitignored; `scripts/fetch-corpus.sh` downloads the 7 PDFs from official AWS URLs and verifies a sha256 each (`scripts/corpus-manifest.tsv`; `--check` only reports). 6 of 7 match the current docs.aws.amazon.com files byte for byte. `microservices-on-aws.pdf` is the **ARCHIVED 2021 edition** from d1.awsstatic.com (title "ARCHIVED: Implementing Microservices on AWS"); the current docs edition is a different file, so the manifest pins the archived one.
- **Compose:** stable `hostname: agentfw-mongo` plus named volumes for `/data/db`, `/data/configdb` and `/data/mongot` (the last is not declared by the image but holds the search indexes). Tested: `up -d --force-recreate` keeps documents, the replica set and a READY search index. `down -v` still wipes everything on purpose.
- **Real ingestion:** 7 documents, 2,050 chunks (1:1 with pages roughly), 768-dim `gemini-embedding-2`, 0 failures, about 4 minutes, both search indexes READY, 24.7 MB of data.
- **Reranker:** `ModelFiles` pins the HF commit and a sha256 per file (fp32 91.0 MB, int8 23.2 MB, tokenizer 0.7 MB), downloads atomically and discards any file whose hash differs. `CrossEncoderReranker` scores pairs one at a time through DJL + ONNX Runtime and counts pairs truncated at 512 tokens. Default tests use `FakeReranker` and never load the model or download anything.
- **fp32 vs int8 (12 real questions x their top-20 vector candidates):** top-1 equal 11/12, top-5 set equal 7/12 (mean overlap 4.58/5), median 1.71 s (fp32) vs 1.65 s (int8) per 20 pairs. Rule was "int8 only if top-5 agrees", so **fp32 is kept**; int8 also gave almost no speed-up on this CPU.
- **Threads (20 pairs, fp32):** ONNX Runtime default 1.59 s, 1 thread 3.44 s, 4 threads 1.82 s, 6 threads 1.62 s, 12 threads 2.51 s. So per-query rerank is about 85 ms per pair on 6 cores and about 170 ms per pair on one core: relevant for Day 7 hosting (a 1 to 2 vCPU host will be 2x or more slower).
- Not investigated: why int8 gives almost no gain (candidates: DJL per-call overhead, no VNNI path on this CPU).

### 2026-10-10: Day 3 research (no code yet)
- Pinned compose tag verified: `mongodb-atlas-local:8.3.11-20260928T091948Z` has the same image ID as `latest` and `db.version()` returns 8.3.11, healthy.
- Pitfall found: `docker compose up -d` after the image/tag changed *recreates* the container, which re-attaches the old anonymous data volume whose replica-set config names the old container hostname. The new container exits (exit 2, `ReplicaSetNoPrimary`). Use `docker compose stop/start`, or `docker compose down -v` and re-ingest. Fixing it properly (stable `hostname:` plus named volumes) is a Day 3 step 0 proposal.
- Real corpus is in `corpus/` (7 AWS Well-Architected PDFs, 2,011 pages, about 35 MB). Chunker output on it: 2,050 chunks, average about 1,900 characters (one chunk is roughly one page, because chunks never cross a page), 65 embedding requests at batch 32. Not yet ingested.
- Cross-encoder `cross-encoder/ms-marco-MiniLM-L6-v2` (Apache-2.0), commit `233902d`; measured on this machine (i7-10850H, 6C/12T): model load 0.5 s, 20 real chunks warm: 2.7 to 3.1 s as one batch, 1.7 to 2.0 s as 20 single calls. Real wordpiece tokens average 377 per chunk vs 468 estimated by chars/4; 1 of 20 chunks exceeded 500.

### 2026-10-10: Day 2 step 6, smoke run with the real API and MongoDB
- Placeholder PDFs in `corpus-sample/` (2 files, 5 pages, every page stamped "SAMPLE PLACEHOLDER ... NOT part of the real corpus"), generated by `src/test/java/com/demo/agentfw/spike/SamplePdfGenerator.java`. They are kept out of `corpus/` on purpose so they cannot appear in retrieval answers. The real corpus goes in `corpus/` before Day 4.
- Run 1 (real Gemini API, local Atlas): `ingested=2 skipped=0 failed=0`, 6 chunks, both search indexes created and `READY/queryable` within seconds. Stored: 768-dim vectors, model `gemini-embedding-2`, page 2 of the refund policy split into 2 chunks (3,610 chars incl. overlap).
- Run 2 (nothing changed): `ingested=0 skipped=2`, no embedding call. That is the idempotency check on the real system, not only in unit tests.
- Query probe (throwaway, not committed): real query embedding + `$vectorSearch`. "How many days does a refund take?" returned the refund policy first (cosine 0.890); "What does error E-401 mean?" returned the API errors file first (0.896).
- Observation for Day 3: an unrelated question ("Who won the football world cup?") still scored 0.760 on the best chunk. Cosine scores are compressed (about 0.75 to 0.90), so a grounding gate on raw vector score would be fragile; this supports gating on the cross-encoder rerank score as the plan says. Caveat: with 3 documents and an identical banner on every page this is a weak sample; re-check on the real corpus.

### 2026-10-10: Day 2 step 5, ingestion job
- `IngestionJob.run()`: list `*.pdf` (case-insensitive, sorted), fail fast on two files with the same `docId`, then per file: hash it; skip if the store has exactly that hash and the current embedding model; otherwise parse, chunk, embed everything, and only then replace the stored chunks in one transaction. Per-file errors are collected in the result and do not stop the other files; the old chunks of a failed file stay.
- Pruning: documents that are no longer in the folder are deleted from the store, except when the folder contains no PDFs at all (then it is skipped with a warning; more likely a wrong path than an intended wipe). A missing folder is an error, not an empty run.
- PDFs without a text layer (scans) are reported as `empty`, nothing is stored and stale chunks are removed.
- `IngestionStartupRunner` (only when `agentfw.ingestion.on-startup=true`) creates the indexes, then runs the job; that is how a deployment seeds its corpus. Default is off, so tests and normal runs never touch MongoDB or the API at boot (`IngestionWiringTest`).
- `tenantId` comes from `agentfw.tenant-id` via `AgentFwProperties`, used for every read, write and delete.
- Tests (in-memory `ChunkStore`, fake embeddings, PDFs built on the fly): first run fields and page numbers, re-run makes 0 embedding calls and 0 store writes, changed file re-embeds only itself, model change re-embeds everything, prune, empty-folder guard, embedding failure keeps old chunks and continues, corrupt PDF, scan, duplicate ids, missing folder, non-PDF files.
- Verified: BUILD SUCCESS, 58 tests.

### 2026-10-10: Day 2 step 4, MongoDB storage and search indexes
- `ChunkStore` is the port the ingestion job will depend on; `MongoChunkStore` implements it with the plain driver. `replaceDocument` runs delete + insert in one transaction (works because Atlas local is a replica set), so a reader sees the old chunks or the new ones, never a mix; a failed insert rolls back (tested with a deliberate unique-index violation).
- Unique index `(tenantId, docId, chunkIndex)` is a safety net against duplicates. `docStates` is one `$group` aggregation returning distinct `contentHash` and `embeddingModel` per document, which is what "skip or re-embed" needs.
- `ChunkIndexInitializer` creates `chunk_vector` (cosine, `numDimensions` from the embedding service, `tenantId` as filter) and `chunk_text` (`lucene.english` on `text`, `tenantId` token), the definitions proven on Day 1. It is idempotent, and refuses to continue if an existing vector index has a different dimension, with an instruction to drop the index and re-ingest.
- Integration test (`MongoChunkStoreTest`) uses a throwaway database `agentfw_test_<random>`, dropped afterwards. Verified both ways: 7 tests run against the live container, and with the container stopped it prints `SKIPPED ... MongoDB not reachable` and the build still succeeds.
- Verified: BUILD SUCCESS, 45 tests (7 against MongoDB).

### 2026-10-10: Day 2 step 3, chunking
- `Chunker` slides a window of 650 estimated tokens (chars / 4) over each page with 15% overlap; chunks never cross a page, so each has one citable page number. Each chunk ends at the best boundary in the last 25% of the window: paragraph break, then sentence end, then space; with no boundary at all it cuts hard (and still terminates).
- Resulting sizes: 75% of the target (about 487 tokens) up to target + min tail (650 + 100 = 750 tokens). The plan said "500 to 800"; this is that range within estimation error. A remainder shorter than the min tail is absorbed into the last chunk instead of becoming a tiny chunk that is mostly overlap.
- No tokenizer on purpose: the model takes 8192 tokens, over 10x the largest chunk, so a chars/4 estimate cannot truncate. Not measured: real token counts of the real corpus.
- PDF line breaks become spaces, blank lines stay as paragraph breaks; pages under 20 characters (page numbers, blank scans) produce no chunk.
- Verified: BUILD SUCCESS, 38 tests.

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
