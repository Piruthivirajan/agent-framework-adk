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

## Log

<!-- Newest first. One entry per task: date, what was built, what was verified. -->

### 2026-10-10: Stack and pom pinned
- Moved to Spring Boot 4.1.1 (3.5 is out of OSS support). Added google-adk 1.11.0, pdfbox 3.0.8, tika-parsers-standard-package 4.1.0 (`type=pom`), DJL 0.38.0. No Spring AI.
- Verified: `./mvnw dependency:tree` and `./mvnw verify` pass on JDK 21.0.12 (Temurin). The context-load test logs a Mongo "connection refused" because no DB is running yet; it still passes.
- Tree notes: Jackson 2.21.5 (ADK/Google libs) and Jackson 3.1.5 (Boot, MCP SDK) coexist; MCP SDK 2.0.0; reactor-core 3.8.7 from Boot's BOM. Test-only warning for duplicate `org.json.JSONObject` (bigquery via ADK vs jsonassert).
- `.gitattributes` forces LF so the CRLF warnings stop.
