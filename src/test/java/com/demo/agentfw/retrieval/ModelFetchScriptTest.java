package com.demo.agentfw.retrieval;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The image bakes the model in with {@code scripts/fetch-model.sh}; the application verifies the same
 * files by the values in {@link ModelFiles}. Two copies of a pinned hash must never disagree.
 */
class ModelFetchScriptTest {

    @Test
    void theScriptPinsTheSameCommitAndHashesAsTheApplication() throws IOException {
        String script = Files.readString(Path.of("scripts/fetch-model.sh"), StandardCharsets.UTF_8);

        assertThat(script).contains("COMMIT=\"" + ModelFiles.COMMIT + "\"");
        assertThat(script).contains(ModelFiles.MODEL_FP32.sha256()).contains(ModelFiles.TOKENIZER.sha256());
        assertThat(script).contains("\"model.onnx|onnx/model.onnx|" + ModelFiles.MODEL_FP32.sha256() + "\"");
        assertThat(script).contains("\"tokenizer.json|tokenizer.json|" + ModelFiles.TOKENIZER.sha256() + "\"");
        assertThat(ModelFiles.MODEL_FP32.url()).endsWith(ModelFiles.COMMIT + "/onnx/model.onnx");
    }

    @Test
    void theModelFilesOnDiskMatchThePinnedHashesWhenTheyArePresent() throws IOException {
        Path dir = Path.of("models/ms-marco-MiniLM-L-6-v2");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(dir.resolve("model.onnx")),
                "model files not downloaded on this machine");

        assertThat(ModelFiles.sha256(dir.resolve("model.onnx"))).isEqualTo(ModelFiles.MODEL_FP32.sha256());
        assertThat(ModelFiles.sha256(dir.resolve("tokenizer.json"))).isEqualTo(ModelFiles.TOKENIZER.sha256());
    }
}
