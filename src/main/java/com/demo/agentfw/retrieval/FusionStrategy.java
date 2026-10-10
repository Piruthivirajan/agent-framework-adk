package com.demo.agentfw.retrieval;

/** How the vector and the full-text result lists are combined into one ranking. */
public enum FusionStrategy {

    /** MongoDB {@code $rankFusion}: reciprocal rank fusion, ignores score scale. */
    RANK,

    /** MongoDB {@code $scoreFusion}: normalizes each pipeline's scores and combines them with weights. */
    SCORE,

    /** Two separate queries fused in Java with reciprocal rank fusion; works without the Atlas fusion stages. */
    JAVA_RRF
}
