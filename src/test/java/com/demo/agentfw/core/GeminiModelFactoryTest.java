package com.demo.agentfw.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which model the coordinator gets. No network: building a Gemini model does not call the API. */
class GeminiModelFactoryTest {

    private static LlmProperties props(String key) {
        return new LlmProperties("gemini", "gemini-big", key, null, null, 12);
    }

    @Test
    void withoutACoordinatorModelTheCoordinatorSharesTheAgentsModel() {
        GeminiModelFactory f = new GeminiModelFactory(props("k"), LlmLimitsProperties.defaults(),
                LlmRoutingProperties.none());

        assertThat(f.createForRouting()).isSameAs(f.create());
    }

    @Test
    void theSameNameAsTheAgentsModelIsNotBuiltTwice() {
        GeminiModelFactory f = new GeminiModelFactory(props("k"), LlmLimitsProperties.defaults(),
                new LlmRoutingProperties("gemini-big"));

        assertThat(f.createForRouting()).isSameAs(f.create());
    }

    @Test
    void aCoordinatorModelIsItsOwnLimitedModelWithItsOwnName() {
        GeminiModelFactory f = new GeminiModelFactory(props("k"), LlmLimitsProperties.defaults(),
                new LlmRoutingProperties("gemini-small"));

        assertThat(f.createForRouting()).isNotSameAs(f.create()).isInstanceOf(LimitedLlm.class);
        assertThat(f.createForRouting().model()).isEqualTo("gemini-small");
        assertThat(f.create().model()).isEqualTo("gemini-big");
        assertThat(f.createForRouting()).isSameAs(f.createForRouting());        // built once
    }

    @Test
    void withoutAKeyBothSayNoKey() {
        GeminiModelFactory f = new GeminiModelFactory(props(""), LlmLimitsProperties.defaults(),
                new LlmRoutingProperties("gemini-small"));

        assertThatThrownBy(f::createForRouting).hasMessageContaining("No Gemini API key");
        assertThatThrownBy(f::create).hasMessageContaining("No Gemini API key");
    }
}
