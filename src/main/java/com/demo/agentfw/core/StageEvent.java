package com.demo.agentfw.core;

/**
 * One progress event of a turn: which stage has just started and how long after the turn began.
 *
 * <p>Stages in the order a RAG turn goes through them: {@code understanding} (a follow-up is made
 * standalone), {@code routing} (the coordinator model chooses an agent), {@code searching} (embedding,
 * vector and text search, fusion), {@code reranking}, {@code answering} (the model writes the answer
 * from the chunks), {@code repairing} (only after malformed JSON), {@code verifying}. A file turn has
 * {@code summarizing} with a detail such as {@code 3/12}, and {@code combining}.
 */
public record StageEvent(String stage, String detail, long elapsedMillis) {
}
