package com.demo.agentfw.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the rule "tests never call the embedding API". */
@SpringBootTest
class EmbeddingWiringTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    EmbeddingService embeddingService;

    @Test
    void testsGetTheFakeAndNeverTheGeminiBean() {
        assertThat(embeddingService).isInstanceOf(FakeEmbeddingService.class);
        assertThat(context.getBeansOfType(GeminiEmbeddingService.class)).isEmpty();
    }

    @Test
    void dimensionComesFromConfiguration() {
        assertThat(embeddingService.dimensions()).isEqualTo(768);
        assertThat(embeddingService.modelName()).isEqualTo("gemini-embedding-2");
    }
}
