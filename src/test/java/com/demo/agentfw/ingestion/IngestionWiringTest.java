package com.demo.agentfw.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IngestionWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void ingestionBeansExistButNothingRunsAtStartupByDefault() {
        assertThat(context.getBeansOfType(IngestionJob.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChunkIndexInitializer.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChunkStore.class)).hasSize(1);
        assertThat(context.getBeansOfType(IngestionStartupRunner.class)).isEmpty();
    }
}
