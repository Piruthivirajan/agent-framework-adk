package com.demo.agentfw.retrieval;

import java.util.List;

/**
 * @param decision the grounding decision
 * @param chunks   context for the answering model, best first; empty when the decision is
 *                 {@code NO_RELEVANT_CONTEXT} (the trace still lists what was scored)
 * @param trace    full record of the run, for the UI and for debugging; never sent to the model
 */
public record RetrievalResult(GroundingDecision decision, List<RetrievedChunk> chunks, RetrievalTrace trace) {
}
