# corpus-sample

Two **placeholder** PDFs used only to smoke-test the ingestion pipeline (Day 2). They are
fictional, every page is stamped "SAMPLE PLACEHOLDER DOCUMENT", and they are **not** the assignment corpus.

The real PDFs go in `corpus/` (the default `agentfw.ingestion.corpus-dir`). Run the pipeline on these with:

```
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--agentfw.ingestion.on-startup=true --agentfw.ingestion.corpus-dir=corpus-sample --spring.main.web-application-type=none"
```

Regenerate with `src/test/java/com/demo/agentfw/spike/SamplePdfGenerator.java`.
