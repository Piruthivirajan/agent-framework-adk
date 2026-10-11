package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link TextGenerator} on the application's model, with the agents' generation settings (thinking
 * budget). The call goes straight to {@code BaseLlm.generateContent}; no agent, no tools, no session.
 */
@Component
public class LlmTextGenerator implements TextGenerator {

    private final ModelFactory models;
    private final LlmProperties props;

    public LlmTextGenerator(ModelFactory models, LlmProperties props) {
        this.models = models;
        this.props = props;
    }

    @Override
    public String generate(String systemInstruction, String userText) {
        BaseLlm model = models.create();
        LlmRequest request = LlmRequest.builder()
                .model(model.model())
                .contents(List.of(Content.builder().role("user").parts(Part.fromText(userText)).build()))
                .config(config(systemInstruction))
                .build();
        List<LlmResponse> responses = model.generateContent(request, false).toList().blockingGet();
        StringBuilder out = new StringBuilder();
        for (LlmResponse response : responses) {
            if (response.errorCode().isPresent() || response.errorMessage().isPresent()) {
                throw new IllegalStateException("The model returned an error: "
                        + response.errorMessage().orElse(String.valueOf(response.errorCode().orElse(null))));
            }
            response.content().flatMap(Content::parts)
                    .ifPresent(parts -> parts.forEach(p -> p.text().ifPresent(out::append)));
        }
        return out.toString();
    }

    private GenerateContentConfig config(String systemInstruction) {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)));
        if (props.agentThinkingBudget() != null) {
            builder.thinkingConfig(ThinkingConfig.builder().thinkingBudget(props.agentThinkingBudget()).build());
        }
        return builder.build();
    }
}
