package com.demo.agentfw.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from the same {@code agentfw.llm} prefix as {@link LlmProperties}.
 *
 * @param coordinatorModel the model for the coordinator only (it chooses one of a few agents, which a small
 *                         fast model does as well as a large one); empty means the same model as the agents
 */
@ConfigurationProperties("agentfw.llm")
public record LlmRoutingProperties(@DefaultValue("") String coordinatorModel) {

    public LlmRoutingProperties {
        coordinatorModel = coordinatorModel == null ? "" : coordinatorModel.strip();
    }

    public static LlmRoutingProperties none() {
        return new LlmRoutingProperties("");
    }
}
