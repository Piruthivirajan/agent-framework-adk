# Technical review guide: what was asked, what was built, why

Written for the technical review of this project. Everything here is traceable to code or to a measured
number in `docs/`; where something is not done or not measured, it says so. Update the **[FINAL RUN]**
numbers after the single end-to-end run (README has the same placeholders).

Contents: 1. What was asked and what we did · 2. Architecture · 3. How one question flows ·
4. Mechanisms in depth · 5. Decisions and trade-offs · 6. Measured numbers · 7. Failure behaviour ·
8. Security · 9. Known gaps (say them first) · 10. Questions to expect, with answers · 11. Demo script.

---

## 1. What was asked, and what we did

Source: the round 2 interview transcript, kept in `docs/PLAN.md`.

| Asked | What we built | Where |
|---|---|---|
| Java, Google ADK, pluggable agents | Java 21, Spring Boot 4.1.1, ADK 1.11.0. An agent is a Spring bean implementing `AgentPlugin`; the registry wraps each as an ADK `AgentTool`. A third agent is one class (`ClockAgentPlugin` is the proof). | `core/AgentPlugin`, `AgentRegistry`, `CoordinatorFactory`, `agents/sample/ClockAgentPlugin` |
| Agent 1: agentic RAG over 10-20 PDFs, embedded once, semantic + lexical, re-rank, answer with LLM | 10 AWS PDFs, chunked, embedded once (idempotent by content hash), MongoDB `$vectorSearch` + BM25 `$search` fused by `$rankFusion`, local cross-encoder rerank, Gemini writes cited claims | `ingestion/`, `retrieval/`, `agents/rag/` |
| Grounding: an out-of-document question must not be answered | A retrieval floor on the cross-encoder score, plus a **verifier in code**: every claim needs a reference to a retrieved chunk and a quote that appears verbatim in it | `retrieval/GroundingGate`, `agents/rag/GroundingVerifier` |
| Agent 2: File Agent (not "summary agent"), file or raw content, summarizer backend | Upload (PDF/DOCX/text) or pasted text, one endpoint; single pass up to 30k characters, map-reduce above | `agents/file/`, `files/MapReduceSummarizer` |
| One chat, not threaded; an orchestration layer calls the right agent | One chat page, one session per browser tab; coordinator model routes text, an attachment goes to the File Agent by a code rule | `core/ChatService`, `CoordinatorFactory` |
| MCP: the evaluator configures his own server and credentials in the UI, the app pulls data through ADK | Settings page: URL, transport (Streamable HTTP / SSE), headers; test connection; tools available at the next message without a restart; saved encrypted in MongoDB | `mcp/`, `api/McpController`, `static/mcp.html` |
| Launch it where he can open it | Docker Compose stack: app, Atlas local, Caddy (auto HTTPS, SSE unbuffered), optional demo MCP server; `deploy.sh` for a fresh Ubuntu VM | `Dockerfile`, `docker-compose.prod.yml`, `scripts/deploy.sh`, `docs/DEPLOY.md` |
| Any tool incl. Claude Code, but deep understanding | This guide; every step explained in `docs/PROGRESS.md` | |

The four live checks he said he would run:

| # | Check | Result path in the app |
|---|---|---|
| A | A question the PDFs answer | coordinator -> `rag_agent`; cited answer, "From the reference documents · every quote checked" |
| B | A question they do not answer | floor or verifier -> fixed "Not found in the reference documents."; MCP tools tried only if some are connected and the model itself found nothing |
| C | Upload a file, ask for a summary | attachment -> File Agent by code (no routing call); map-reduce for large files |
| D | Add his MCP server + credentials, ask about its data | `McpRegistry.add` -> event -> coordinator rebuilt -> `mcp_agent` tool called; raw result in the trace |

---

## 2. Architecture

```
Browser: index.html (chat, trace)   mcp.html (settings)   login.html
   | /api/chat  /api/chat/stream (SSE)  /api/files  /api/kb/documents  /api/mcp/servers
   v
api/       ChatController  UploadController  KnowledgeBaseController  McpController  ErrorMapping  AuthFilter
   v
core/      ChatService  ->  CoordinatorFactory -> ADK coordinator (LlmAgent, tools = one AgentTool per plugin)
                 |                                      rag_agent    file_agent    mcp_agent(only when tools exist)
                 |
agents/    rag/ (RagAgentPlugin, SearchDocumentsTool, SpeculativeSearch, GroundingVerifier, RetrievalLedger)
           file/ (FileAgentPlugin, FileSummaryLedger)      mcp/ (McpAgentPlugin)      sample/ (ClockAgentPlugin)
retrieval/ RetrievalService, AtlasChunkSearch, CrossEncoderReranker, GroundingGate, JavaRrfFusion
files/     UploadService, TextExtractor (Tika), MongoUploadStore, MapReduceSummarizer
mcp/       McpRegistry, McpProbe, McpUrlGuard, RecordingMcpTool, McpCallLedger, MongoMcpConfigStore, McpSecretCipher
ingestion/ PdfLoader, Chunker, GeminiEmbeddingService, IngestionJob, KnowledgeBaseService
   v
MongoDB Atlas (the only store): chunks (text + embedding + metadata), uploads (TTL), mcp_servers (encrypted)
External: Gemini API (chat, embeddings)   Local: ONNX cross-encoder ms-marco-MiniLM-L-6-v2
```

**One sentence:** the model chooses *where* a question goes and *writes* the claims; **code decides what the
user is shown** (floor, cap, verifier, call records). That split is the answer to "where does the LLM decide
and where does code decide".

| Model decides | Code decides |
|---|---|
| Which agent gets a text question (coordinator) | An attachment goes to the File Agent (rule) |
| Restating a follow-up as a standalone request | Search cap (2), early stop after a floor miss |
| The query wording of a second search | The retrieval floor (-6.5) and what chunks the model sees |
| The claims and quotes | Whether the claims are shown: `GroundingVerifier`, fail closed |
| Which MCP tool and arguments | Whether a tool answer is shown: from call records, not model prose |
| | RAG not found -> try MCP tools (`ChatService.tryConnectedTools`) |

---

## 3. How one question flows (scenario A/B)

`ChatService.run(sessionId, text)` under a per-session lock:

1. `requestId` generated. For a text turn `SpeculativeSearch.start` begins embedding + search + rerank on a
   thread **while the coordinator is still deciding** (they do not depend on each other).
2. Coordinator (`gemini-3.5-flash-lite`, ~1 s) calls `rag_agent` with the request, restating a follow-up if needed.
3. RAG agent: its first model call would only be "call `searchDocuments(query)`". A before-model callback
   answers it **in code** with `searchDocuments(query = the request text)` (with the placeholder thought
   signature Gemini 3 requires). ADK then runs the tool normally.
4. `SearchDocumentsTool.search`: refuses a third search (`SEARCH_LIMIT_REACHED`), stops after a floor miss,
   uses the early result if the text matches, registers the retrieval in `RetrievalLedger` (refs `r1c1`...).
   A failure is recorded in the ledger and returned as `SEARCH_FAILED` (never looks like "not found").
5. `RetrievalService.retrieve`: embed query (`gemini-embedding-2`, 768 dim) -> one aggregation with
   `$vectorSearch` (numCandidates 400, limit 20, tenant filter) and `$search` (BM25 on text, tenant filter,
   limit 20) fused by `$rankFusion` (weights 1:1) -> top 20 -> rerank the first 12 with the local
   cross-encoder -> top 5 -> `GroundingGate`: top score below **-6.5** -> `NO_RELEVANT_CONTEXT`, no chunks.
6. Answer model call (`gemini-3.8-flash`): strict JSON `{status, claims[{text, ref, quote}]}`.
7. `ChatService.decide` -> `GroundingVerifier.verify`: ref belongs to this request's retrieval; quote appears
   verbatim in that chunk (Unicode-normalised, whitespace-collapsed); every number in the claim appears in
   its quote; at most 6 claims; quote 25-400 chars and >= 4 words. Anything else -> fixed
   "Not found in the reference documents." One repair attempt only for malformed JSON.
8. If not grounded and MCP tools are connected -> `tryConnectedTools` runs `mcp_agent` in code.
9. `ChatResult` (answer, sources, trace of every stage, timeline) -> `ChatResponse` JSON / SSE `result`.

SSE: stage names stream live (`routing`, `searching`, ...); the full trace arrives with the final `result`.

---

## 4. Mechanisms in depth

### 4.1 Plugin framework and orchestration ("hybrid orchestration")
- `AgentPlugin`: `name()`, `description()`, `build(AgentContext)`, `finalAnswer()`, `available()`.
  `AgentRegistry` validates names and wraps each active plugin with `AgentTool.create(agent, skipSummarization)`.
- **Agent-as-tool, not sub-agent transfer:** the result comes back to the coordinator's turn, and
  `finalAnswer` ends the turn without a second model call (ADK "skip summarization").
- **Hybrid:** deterministic rules where the case is unambiguous (attachment -> File Agent; RAG not found ->
  MCP; search cap; fallback), the model where judgment is needed (route text, restate follow-ups).
- `CoordinatorFactory` builds lazily (the app starts without a key), caches the runner, `invalidate()` on MCP
  change. **One shared `InMemorySessionService`** so a rebuild does not drop conversation history.
- The routing prompt tells the coordinator to pass a self-contained message **unchanged** and to restate a
  follow-up using only the conversation: this folds the follow-up rewrite into the routing call (no extra
  model call). Verified 10/10 by `scripts/live-routing-check.py`.

### 4.2 Retrieval (what a reviewer will probe)
- **Why hybrid:** vectors miss exact terms (product names, numbers); BM25 misses paraphrase. Fused by
  **reciprocal rank** (`$rankFusion`: 1/(60+rank), weights 1:1) because RRF ignores incompatible score scales.
  `$scoreFusion` (sigmoid-normalised) and a Java RRF fallback exist, switchable; compared on 48 questions
  (`docs/FUSION.md`): RANK equal or better everywhere, so RANK is the default.
- **numCandidates 400, limit 20:** MongoDB's guidance is >= 20x the limit for ANN recall.
- **Rerank depth 12, final 5:** measured; depth 12 kept the answer page in the final 5 for the same
  questions as depth 20 (`docs/CALIBRATION.md`). Top-K 5 -> 8 gains one strict question and no widened one
  (`TopKProbeRunner`), so it stays 5.
- **Floor -6.5:** just below the lowest top-1 score of any in-corpus question in the calibration set
  (-6.38). It removes clear junk and no in-corpus question. **Caveat to state:** it was derived from the same
  32 questions; the margin is 0.12 logits.
- **Cross-encoder:** `ms-marco-MiniLM-L-6-v2` through DJL + ONNX Runtime, files pinned to one Hugging Face
  commit and sha256-verified (also baked into the image). Scores one pair at a time (`synchronized`).
- **Tenant filter:** `tenantId` on both pipelines; there is no per-document filter.

### 4.3 Grounding is provenance, not entailment
The verifier proves a claim *cites a real passage and quotes it verbatim, with its numbers*. It does not prove
the passage *entails* the claim. Say this before they do. What it closes: answers from memory (adversarial
questions: the model refused itself in 12 of 12 trials with the production prompt, the verifier is the net).

### 4.4 File agent
- Custom `BaseAgent` that calls `MapReduceSummarizer` directly (an `LlmAgent` with a tool cost two extra
  model calls, ~4 s, for the same work).
- Resolution order: attached `fileId` -> `fileId=` in the request -> name mention -> latest upload of the session.
- Single pass <= 30,000 chars; else 15,000-char segments, parallelism 4, 2 retries with backoff, grouped
  reduce (<= 30,000 chars per call), whole-summary failure names the failing part.
- Limits: 20 MB, 1,000,000 characters (ceiling 3,000,000), 10 files per session, TTL 24 h. Measured about
  3 s + 10 s per 100,000 characters; the 1,003-page framework PDF takes 247 s.
- No OCR: a PDF without a text layer is rejected with a clear message. The summary is labelled, not verified.

### 4.5 MCP
- ADK `McpToolset` with `StreamableHttpServerParameters` / `SseServerParameters` (URL + headers). stdio is
  unsupported (a hosted app cannot run a user's command).
- Save path: `McpUrlGuard` (SSRF) -> `McpProbe` (5 s connect / 8 s request, classifies 401/404/not-MCP) ->
  `McpToolset.getTools` (15 s) -> each tool wrapped by `RecordingMcpTool` -> `McpConfigChanged` ->
  `CoordinatorFactory.invalidate()`. `mcp_agent` is offered only while tools exist (`available()`).
- `RecordingMcpTool` records server, tool, arguments, **raw result** for the trace; errors become results the
  model can see instead of exceptions. The answer path reads the **call records**, not the agent's prose: no
  call = no tool answer; only failed calls = tool error with the tool's own message.
- **Persistence:** `MongoMcpConfigStore` with AES-GCM (random IV per value; server id + field authenticated);
  key from `AGENTFW_MCP_SECRET_KEY`; no key -> memory only and the page says so. Saved servers load and
  connect in the background at startup.
- **Credential hygiene:** header values never returned by the API (names only), `toString` hides them,
  `Secrets` masks every configured value (and the token part of `Bearer x`, URL query/user info) in results,
  trace, errors, logs, and in what the model is shown.
- Found and fixed: Spring Boot's nested-jar loader made MCP tool lists silently empty; the image uses an
  extracted layout.

### 4.6 Ingestion and the knowledge base
- `PdfLoader` (PDFBox, per page) -> `Chunker` (650-token window, 15% overlap, never crosses a page, best
  boundary in the last 25%) -> `GeminiEmbeddingService` (batches of 32, retries with backoff) -> MongoDB.
  `contentHash` makes ingestion idempotent; a changed file replaces its chunks; startup ingestion costs no
  API call when nothing changed.
- "Add to knowledge base" (`POST /api/kb/documents`): rejects PDFs without a text layer, writes to `corpus/`.
- Indexes (`chunk_vector`, `chunk_text`) created by `ChunkIndexInitializer`, vector dimension from config.

### 4.7 Performance work (a good story: measured, not guessed)
| Step | Finding | Change | Effect |
|---|---|---|---|
| Per-call timing | the coordinator and RAG first call were ~4 s before any search | log `model_ms` per call (`LimitedLlm`) | made the cost visible |
| Prefetch first search | the RAG agent's first call only said "call the tool" | before-model callback answers it in code | one model call fewer (~2 s) |
| Speculative search | routing (~1-2 s) and search (~1.5 s) are independent | start the search with the turn | ~1 s |
| Small coordinator model | routing is a small decision; `gemini-3.8-flash` 2-5 s vs lite ~1 s | `agentfw.llm.coordinator-model` | coordinator 3-5 s -> ~1 s, routing check still 10/10 |
| Warm-up | first request paid ~4 s of one-time cost | throwaway RAG turn at startup | first request ~7 s instead of ~13 s |
Typical turn now 4-6 s (was 9-13 s). The rest is one answer-writing model call (3-4 s). Rejected after
measuring: thinking budget 128 vs 0 on the coordinator (no difference), lite model for the answer
(speed 3 s median but 24/32 vs 26/32), `final-top-n` 8.

---

## 5. Decisions and trade-offs

| Decision | Why | Trade-off | Revisit when |
|---|---|---|---|
| MongoDB Atlas for vectors, BM25, metadata, MCP config | One store; native `$rankFusion`; simple to run | Atlas local image only for dev; BM25 tuning shallower than Elasticsearch | tens of millions of chunks / dedicated search needs |
| RRF as default fusion | ignores score scale | drops score gaps | corpus or embedding model changes |
| Local cross-encoder | no per-call cost, predictable | one pair at a time, CPU | eval gap, non-English corpus |
| Code verifies, model only proposes | a model cannot be trusted to refuse | provenance, not entailment | need entailment: NLI check or LLM judge |
| Answer decided in code from ledgers | model prose can be wrong or injected | extra plumbing | never |
| Attachment by rule, text by model | unambiguous vs judgment | one routing model call | single-eligible-agent shortcut (see gaps) |
| Agent-as-tool | result returns to the turn; chain-able | coordinator hop | |
| In-memory ADK sessions | assignment: one chat, no threads | lost on restart, unbounded | persistent session service |
| Quotes shown in the sources list, not inline | readable answer, still checkable | | |
| Streaming stages, not tokens | an unverified answer must not be shown | silent seconds | |
| AES key from the environment | credentials survive restart without plaintext | no rotation/KMS | secrets manager |

---

## 6. Measured numbers (and where)

- Retrieval, 48 questions (32 in-corpus): answer page in the final top 5: **26/31 strict, 30-31/32 widened**;
  one true miss (in-21: never retrieved). Paraphrases are the weak spot (`docs/CALIBRATION.md`, `docs/FUSION.md`).
- Out-of-corpus and adversarial: refused in every run of the checks (verifier + floor).
- Routing check: 10/10 (`scripts/live-routing-check.py`), MCP check (`scripts/live-mcp-check.py`).
- Latency (laptop, warm): 4-6 s per question; search ~1.5 s of which overlaps routing; answer 3-4 s.
- Files: see 4.4. Tests: 446, 0 failures (MongoDB-dependent tests skip when the container is down).
- **[FINAL RUN]** full 48-question end-to-end table and VM rerank latency: pending (README placeholders).

---

## 7. Failure behaviour

| Failure | What the user sees | How |
|---|---|---|
| Gemini 429/503 | one retry with backoff, then "rate limited" / "overloaded" | `GeminiLimits` per-request options, `ErrorMapping.modelFailure` |
| Gemini slow | after 60 s: "took too long" | OkHttp call timeout |
| MongoDB down | `DATABASE_UNAVAILABLE` (never "not found") | search failure recorded in the ledger; cause-chain check before model mapping; driver waits up to 30 s first |
| Embedding/rerank failure | error, not "not found" | same |
| Reranker model files missing | app does not start (fail fast); image has them baked in | `ModelFiles.ensure` |
| MCP wrong token / down / hang | "Invalid credentials", "Unable to connect", tool error with its message; read timeout 30 s | `McpProbe`, `RecordingMcpTool` |
| Malformed model JSON | one repair attempt, else not found | `MalformedAnswerRepair` |
| Verifier rejects | fixed not-found; trace says why; MCP tried if connected | `ChatService.decide` |

---

## 8. Security

- Demo login: one password from env, lockout, session-fixation fix, `Secure` cookies behind Caddy.
- UI renders model and document text with `textContent` only (no markup injection).
- SSRF: `McpUrlGuard` resolves the host and refuses loopback, link-local, site-local, ULA, IPv4-mapped, numeric
  forms; allow-list by host name. **Application level:** DNS rebinding and redirects are not covered; the
  deployment adds an egress rule dropping container traffic to `169.254.169.254`.
- Keys: `GOOGLE_API_KEY` only in the gitignored `.env`; masked in logs and errors.

---

## 9. Known gaps (say these first, calmly)

1. **Provenance, not entailment** (4.3).
2. **Floor tuned on the evaluation questions;** 2 of 32 paraphrases not answered; one question never retrieved.
3. **Third agent's answer is shown unverified** (labelled); only RAG, File, MCP have their own checks.
4. **One AES key, no rotation;** a lost key leaves saved servers unreadable (not deleted).
5. **SSRF is application-level** plus one egress rule; other private ranges only by the guard.
6. **ADK sessions in memory, unbounded;** one shared password, no per-user separation.
7. **No OCR;** summaries of very large files are slow (4 min for 1,003 pages) and run inside the request;
   a ~500-page PDF may exceed the 1,000,000-character default.
8. **Not validated on a real VM:** `deploy.sh`, Let's Encrypt for sslip.io, the iptables rule.
9. **Quote lengths and claim counts** make answers long (4-5 s of model time); a shorter format is untested.
10. **Global secret masking** also masks non-secret header values (for example `Accept: application/json`).
11. Coordinator model name is configuration; a preview/lite name can be retired (fails loudly).

What I would do next: entailment check (small NLI or judge on top-3 claims), query expansion for paraphrases
(one more model call), persistent sessions, async jobs for big files, per-user identity, KMS.

---

## 10. Questions to expect, with short answers

1. **MCP through ADK?** `McpToolset` with `StreamableHttpServerParameters`/`SseServerParameters`; a custom
   `McpProbe` pre-flight only to classify errors (SDK exceptions lacked HTTP status). Code path in 4.5.
2. **New tools without restart?** event -> `invalidate()` -> next turn builds the coordinator with the new
   `mcp_agent`; sessions are shared so history survives. Edit mid-turn: the in-flight call can fail as a tool error.
3. **Where does the LLM decide?** table in section 2.
4. **Agentic RAG?** the agent may rewrite the query and search once more; cap enforced in the tool by request
   id, plus `max-llm-calls` 12. In practice one search almost always (measured: a rewrite rarely rescued anything).
5. **Hybrid retrieval exact query?** section 3 step 5; `AtlasChunkSearch.nativeFusion`.
6. **"Max object size in S3?"** not in the PDFs -> floor/verifier -> fixed not-found; MCP tried after, only if
   tools are connected and the model itself said not found.
7. **File agent: scanned PDF? 500 pages?** rejected (no OCR) / may exceed the character limit, raise
   `agentfw.files.max-chars` (ceiling 3M) and expect minutes.
8. **Third agent?** one `@Component` class; coordinator prompt lists plugins automatically; its answer is shown
   labelled. Test: `CoordinatorFactoryTest.aThirdAgentIsOneNewClassAndNothingElseChanges`, plus a chat-level
   test that its answer reaches the user.
9. **Gemini 429 / Mongo down / MCP hang?** section 7.
10. **Where are MCP credentials, how could they leak?** encrypted in `mcp_servers`; leak vectors: a server that
    echoes its header (masked by exact value), logs (masked), API (names only), the model (never shown).
11. **Why not just a vector DB + one prompt?** no routing, no file path, no MCP, no verification; the
    assignment asks for an orchestration layer and grounding.
12. **Why a cross-encoder?** bi-encoder and BM25 rank by similarity, a cross-encoder reads query and passage
    together; it also gives a score the floor can use.
13. **Why `$rankFusion`?** see 4.2.
14. **What would you change for 10x traffic?** reranker batching/parallelism, persistent sessions, async file
    jobs, a hosted reranker, per-tenant filters, rate limiting.
15. **How do you know it works?** 446 tests, live scripts, the 48-question run, and the trace for every answer.

---

## 11. Demo script (5 minutes)

1. Open the app; show the knowledge base list (10 PDFs).
2. Ask "What is the AWS Well-Architected Tool?" -> cited answer; open "How this answer was produced":
   the time table, fused list, rerank scores, floor margin.
3. Ask "What is the maximum object size in Amazon S3?" -> Not found (explain floor + verifier).
4. Upload a file, "Summarize my file" -> summary, labelled, strategy in the trace.
5. MCP settings: add the demo server (URL, `Authorization: Bearer demo-secret`), test connection, ask
   "Where is order ORD-1001?" -> tool answer, raw result in the trace; try a wrong token for the clear error.
6. (Optional) `AGENTFW_SAMPLE_AGENT=true` -> "what time is it in Kolkata?" -> third agent, one class.
