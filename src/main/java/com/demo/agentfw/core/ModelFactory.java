package com.demo.agentfw.core;

import com.google.adk.models.BaseLlm;

/** Creates the language model used by the coordinator and the agents. A seam so tests can script it. */
public interface ModelFactory {

    BaseLlm create();

    /** The model of the coordinator, which only routes. Defaults to the agents' model. */
    default BaseLlm createForRouting() {
        return create();
    }

    String modelName();
}
