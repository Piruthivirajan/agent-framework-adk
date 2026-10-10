package com.demo.agentfw.retrieval;

/**
 * A chunk that survived fusion, with the evidence of how it got there. Ranks are 1-based and
 * null when the chunk was not returned by that pipeline.
 */
public record Candidate(
        String chunkId,
        String docId,
        String file,
        int page,
        int chunkIndex,
        String text,
        Integer vectorRank,
        Double vectorScore,
        Integer textRank,
        Double textScore,
        double fusedScore,
        int fusedRank) {
}
