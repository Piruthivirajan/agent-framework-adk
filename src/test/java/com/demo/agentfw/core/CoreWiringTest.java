package com.demo.agentfw.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the wiring rules: tests never reach the real model, and the RAG and file agents are registered plugins. */
@SpringBootTest
class CoreWiringTest {

    @Autowired
    ModelFactory models;

    @Autowired
    AgentRegistry registry;

    @Test
    void testsGetTheScriptedModelFactory() {
        assertThat(models).isInstanceOf(FakeModelFactory.class);
        assertThat(models.create()).isInstanceOf(ScriptedLlm.class);
    }

    @Test
    void theRagAndFileAgentsAreTheRegisteredPluginsAndBothAreCoordinatorTools() {
        assertThat(registry.plugins()).extracting(AgentPlugin::name).containsExactly("file_agent", "rag_agent");
    }
}
