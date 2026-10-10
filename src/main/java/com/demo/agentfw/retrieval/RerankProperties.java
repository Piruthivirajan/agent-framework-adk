package com.demo.agentfw.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider  {@code djl} (the real cross-encoder) or {@code fake} (tests, defined in src/test)
 * @param modelDir  folder holding the model and tokenizer files; created and filled on first start
 * @param modelFile {@code model.onnx} (fp32) or {@code model_quint8_avx2.onnx} (int8); must be one of the pinned files
 * @param maxLength maximum tokens of query + passage; longer pairs are truncated and counted
 */
@ConfigurationProperties("agentfw.rerank")
public record RerankProperties(
        @DefaultValue("djl") String provider,
        @DefaultValue("models/ms-marco-MiniLM-L-6-v2") String modelDir,
        @DefaultValue("model.onnx") String modelFile,
        @DefaultValue("512") int maxLength) {
}
