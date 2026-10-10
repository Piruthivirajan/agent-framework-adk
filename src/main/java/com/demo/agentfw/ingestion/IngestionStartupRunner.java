package com.demo.agentfw.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds the corpus at startup when {@code agentfw.ingestion.on-startup=true}. This is how a
 * deployment gets its documents. Off by default, so tests and ordinary runs never touch MongoDB or
 * the embedding API at boot.
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.ingestion", name = "on-startup", havingValue = "true")
public class IngestionStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionStartupRunner.class);

    private final ChunkIndexInitializer indexes;
    private final IngestionJob job;

    public IngestionStartupRunner(ChunkIndexInitializer indexes, IngestionJob job) {
        this.indexes = indexes;
        this.job = job;
    }

    @Override
    public void run(ApplicationArguments args) {
        indexes.ensureIndexes();
        IngestionResult result = job.run();
        if (result.hasFailures()) {
            log.warn("Some documents failed to ingest: {}", result.failed().keySet());
        }
    }
}
