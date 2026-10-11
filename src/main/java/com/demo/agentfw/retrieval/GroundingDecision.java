package com.demo.agentfw.retrieval;

/**
 * Outcome of the retrieval floor. It is deliberately not called "grounded": passing the floor only
 * means retrieval found something plausible. Whether an answer is supported by it is decided later,
 * by the citation and quote check (the grounding verifier).
 */
public enum GroundingDecision {

    /** The best passage scored at or above the floor: the chunks are handed to the answering step. */
    PASSED_FLOOR,

    /** Nothing scored high enough: the answer is "not found in the reference documents". */
    NO_RELEVANT_CONTEXT,

    /** No floor has been calibrated yet, so no decision is made; chunks are returned for inspection only. */
    NOT_CALIBRATED
}
