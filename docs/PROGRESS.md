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

- [ ] Container healthy and `db.version()` noted
- [ ] Both search indexes show `queryable: true`
- [ ] `$vectorSearch` alone and `$search` alone each return the refund chunk first
- [ ] `$rankFusion` and `$scoreFusion` run from Java and print scores, or the Java RRF fallback is chosen
- [ ] The cloud Atlas tier planned for the demo URL is checked for the same MongoDB version
- [ ] Pin the `mongodb-atlas-local` image tag in docker-compose.yml

## Watch list

- The skeleton code in PLAN.md (`StreamableHttpServerParameters`, `AgentTool`, `FunctionTool.create`, `McpToolset`) was written against an older ADK. Before using any of them (Day 4 and Day 6), check the class and method names against the ADK 1.11.0 javadoc or sources.

## Log

<!-- Newest first. One entry per task: date, what was built, what was verified. -->

### 2026-10-10: Day 1 spike files written (not yet run)
- `docker-compose.yml` (mongodb-atlas-local:latest), `spike/spike.js` (mongosh: recreate data, create both indexes, poll until queryable, run `$vectorSearch`, `$search`, `$rankFusion`, `$scoreFusion`), `src/test/java/com/demo/agentfw/spike/FusionSpike.java` (the two fusion stages from Java, runbook step 5).
- Verified only that the Java compiles and the JS parses. Nothing was executed against MongoDB; the spike is run by hand.
- FusionSpike is not picked up by `./mvnw verify` (has `main`, no JUnit, name outside Surefire patterns).

### 2026-10-10: Stack and pom pinned
- Moved to Spring Boot 4.1.1 (3.5 is out of OSS support). Added google-adk 1.11.0, pdfbox 3.0.8, tika-parsers-standard-package 4.1.0 (`type=pom`), DJL 0.38.0. No Spring AI.
- Verified: `./mvnw dependency:tree` and `./mvnw verify` pass on JDK 21.0.12 (Temurin). The context-load test logs a Mongo "connection refused" because no DB is running yet; it still passes.
- Tree notes: Jackson 2.21.5 (ADK/Google libs) and Jackson 3.1.5 (Boot, MCP SDK) coexist; MCP SDK 2.0.0; reactor-core 3.8.7 from Boot's BOM. Test-only warning for duplicate `org.json.JSONObject` (bigquery via ADK vs jsonassert).
- `.gitattributes` forces LF so the CRLF warnings stop.
