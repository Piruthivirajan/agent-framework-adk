package com.demo.agentfw.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the rule "default tests never load the model and never download anything". */
@SpringBootTest
class RerankWiringTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    Reranker reranker;

    @Test
    void testsGetTheFakeAndNeverTheRealModel() {
        assertThat(reranker).isInstanceOf(FakeReranker.class);
        assertThat(context.getBeansOfType(CrossEncoderReranker.class)).isEmpty();
    }
}
