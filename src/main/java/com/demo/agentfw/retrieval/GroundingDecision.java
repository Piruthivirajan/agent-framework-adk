package com.demo.agentfw.retrieval;

/** Outcome of the grounding gate. */
public enum GroundingDecision {

    /** The best passage scored at or above the calibrated threshold: answer from the returned chunks. */
    OK,

    /** Nothing scored high enough: the answer is "not found in the reference documents". */
    NO_RELEVANT_CONTEXT,

    /** No threshold has been calibrated yet, so no decision is made; chunks are returned for inspection only. */
    NOT_CALIBRATED
}
