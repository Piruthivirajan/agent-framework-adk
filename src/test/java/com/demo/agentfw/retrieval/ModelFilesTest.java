package com.demo.agentfw.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No network: downloads are served from memory. */
class ModelFilesTest {

    private static final byte[] CONTENT = "pretend model bytes".getBytes(StandardCharsets.UTF_8);

    private static ModelFiles.RemoteFile fileFor(byte[] content, String name) throws Exception {
        String sha = HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        return new ModelFiles.RemoteFile(name, "https://example.invalid/" + name, sha, content.length);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void downloadsMissingFileAndVerifiesIt(@TempDir Path dir) throws Exception {
        ModelFiles.RemoteFile file = fileFor(CONTENT, "model.onnx");

        ModelFiles.ensure(dir, List.of(file), url -> new ByteArrayInputStream(CONTENT));

        assertThat(Files.readAllBytes(dir.resolve("model.onnx"))).isEqualTo(CONTENT);
        assertThat(names(dir)).containsExactly("model.onnx");   // no .part left behind
    }

    @Test
    void skipsAFileThatAlreadyHasTheRightHash(@TempDir Path dir) throws Exception {
        ModelFiles.RemoteFile file = fileFor(CONTENT, "model.onnx");
        Files.write(dir.resolve("model.onnx"), CONTENT);
        AtomicInteger calls = new AtomicInteger();

        ModelFiles.ensure(dir, List.of(file), url -> {
            calls.incrementAndGet();
            return new ByteArrayInputStream(CONTENT);
        });

        assertThat(calls).hasValue(0);
    }

    @Test
    void replacesACorruptedFile(@TempDir Path dir) throws Exception {
        ModelFiles.RemoteFile file = fileFor(CONTENT, "model.onnx");
        Files.writeString(dir.resolve("model.onnx"), "half-written garbage");

        ModelFiles.ensure(dir, List.of(file), url -> new ByteArrayInputStream(CONTENT));

        assertThat(Files.readAllBytes(dir.resolve("model.onnx"))).isEqualTo(CONTENT);
    }

    @Test
    void rejectsAndDiscardsADownloadWithTheWrongHash(@TempDir Path dir) throws Exception {
        ModelFiles.RemoteFile file = fileFor(CONTENT, "model.onnx");
        byte[] tampered = "tampered model bytes".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ModelFiles.ensure(dir, List.of(file), url -> new ByteArrayInputStream(tampered)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Checksum mismatch")
                .hasMessageContaining("model.onnx");

        assertThat(names(dir)).isEmpty();   // neither the file nor a .part
    }

    @Test
    void networkFailureLeavesNoPartialFile(@TempDir Path dir) throws Exception {
        ModelFiles.RemoteFile file = fileFor(CONTENT, "model.onnx");

        assertThatThrownBy(() -> ModelFiles.ensure(dir, List.of(file), url -> {
            throw new IOException("connection reset");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("connection reset");

        assertThat(names(dir)).isEmpty();
    }

    @Test
    void pinnedFilesAreFullyIdentified() {
        for (ModelFiles.RemoteFile f : List.of(ModelFiles.MODEL_FP32, ModelFiles.MODEL_INT8, ModelFiles.TOKENIZER)) {
            assertThat(f.sha256()).hasSize(64);
            assertThat(f.url()).startsWith("https://huggingface.co/").contains(ModelFiles.COMMIT);
        }
        assertThat(ModelFiles.modelFile("model.onnx")).isEqualTo(ModelFiles.MODEL_FP32);
        assertThat(ModelFiles.modelFile("model_quint8_avx2.onnx")).isEqualTo(ModelFiles.MODEL_INT8);
        assertThatThrownBy(() -> ModelFiles.modelFile("model_O4.onnx")).isInstanceOf(IllegalArgumentException.class);
    }
}
