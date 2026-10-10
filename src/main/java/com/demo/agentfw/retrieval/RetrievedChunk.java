package com.demo.agentfw.retrieval;

/** A passage returned to the answering model, with what it needs to cite: file and page. */
public record RetrievedChunk(String file, int page, int chunkIndex, String text, double rerankScore) {
}
