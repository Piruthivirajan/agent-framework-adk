package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;
import com.google.genai.types.GenerateContentConfig;

/**
 * What a plugin needs to build its agent. The model is created once by the {@link ModelFactory};
 * the config carries the agent-level generation settings (for example the thinking budget).
 */
public record AgentContext(BaseLlm model, GenerateContentConfig generateContentConfig) {
}
