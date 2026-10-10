package com.demo.agentfw.retrieval;

import java.util.List;
import java.util.Map;

/**
 * Result of the search-and-fuse stage.
 *
 * @param candidates  top {@code fusedLimit} chunks, best first
 * @param vectorHits  chunks returned by the vector pipeline (always the limit when the corpus is large:
 *                    nearest-neighbour search has no cut-off)
 * @param textHits    chunks returned by the full-text pipeline (fewer when few chunks match)
 * @param unionSize   distinct chunks across both pipelines before cutting to {@code fusedLimit}
 * @param stageMillis wall time per stage; native fusion runs search and fusion in one command
 */
public record SearchOutcome(
        FusionStrategy strategy,
        List<Candidate> candidates,
        int vectorHits,
        int textHits,
        int unionSize,
        Map<String, Long> stageMillis) {
}
