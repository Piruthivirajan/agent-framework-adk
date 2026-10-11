package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.FinishReason;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmTextGeneratorTest {

    private static ModelFactory factory(BaseLlm llm) {
        return new ModelFactory() {
            @Override
            public BaseLlm create() {
                return llm;
            }

            @Override
            public String modelName() {
                return "scripted";
            }
        };
    }

    @Test
    void sendsSystemInstructionUserTextAndThinkingBudgetAndReturnsTheText() {
        ScriptedLlm llm = new ScriptedLlm().script(req -> ScriptedLlm.text("the summary"));
        LlmTextGenerator generator = new LlmTextGenerator(factory(llm),
                new LlmProperties("fake", "scripted", "", 128, 0, 12));

        String out = generator.generate("SYSTEM TEXT", "USER TEXT");

        assertThat(out).isEqualTo("the summary");
        LlmRequest request = llm.requests().get(0);
        assertThat(request.getSystemInstructions()).containsExactly("SYSTEM TEXT");
        assertThat(request.contents().get(0).parts().orElseThrow().get(0).text()).contains("USER TEXT");
        assertThat(request.config().orElseThrow().thinkingConfig().orElseThrow().thinkingBudget()).contains(0);
        assertThat(request.tools()).isEmpty();             // a plain call: no agent, no tools
    }

    @Test
    void anErrorResponseBecomesAnException() {
        BaseLlm failing = new BaseLlm("failing") {
            @Override
            public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
                return Flowable.just(LlmResponse.builder().errorCode(new FinishReason(FinishReason.Known.SAFETY))
                        .errorMessage("blocked").build());
            }

            @Override
            public BaseLlmConnection connect(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        LlmTextGenerator generator = new LlmTextGenerator(factory(failing),
                new LlmProperties("fake", "scripted", "", null, null, 12));

        assertThatThrownBy(() -> generator.generate("s", "u"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("blocked");
    }

    @Test
    void withoutABudgetTheModelDefaultIsUsed() {
        ScriptedLlm llm = new ScriptedLlm().script(req -> ScriptedLlm.text("x"));
        new LlmTextGenerator(factory(llm), new LlmProperties("fake", "scripted", "", null, null, 12))
                .generate("s", "u");

        assertThat(llm.requests().get(0).config().orElseThrow().thinkingConfig()).isEmpty();
    }
}
