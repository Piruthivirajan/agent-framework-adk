package com.demo.agentfw.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * The cross-encoder files, pinned to one Hugging Face commit and one sha256 each, and the code
 * that fetches them. Nothing is trusted by name: a file is only used when its hash matches.
 *
 * <p>Source: {@code cross-encoder/ms-marco-MiniLM-L6-v2} (Apache-2.0).
 */
public final class ModelFiles {

    private static final Logger log = LoggerFactory.getLogger(ModelFiles.class);

    static final String COMMIT = "233902d25c440f23af6f7d6e94d2946bac0bee0a";
    private static final String BASE =
            "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/" + COMMIT + "/";

    public record RemoteFile(String name, String url, String sha256, long size) {
    }

    public static final RemoteFile MODEL_FP32 = new RemoteFile("model.onnx", BASE + "onnx/model.onnx",
            "5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a", 91_011_230L);
    public static final RemoteFile MODEL_INT8 = new RemoteFile("model_quint8_avx2.onnx",
            BASE + "onnx/model_quint8_avx2.onnx",
            "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9", 23_200_716L);
    public static final RemoteFile TOKENIZER = new RemoteFile("tokenizer.json", BASE + "tokenizer.json",
            "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66", 711_396L);

    /** Opens a stream for a URL; replaced by a stub in tests so they never touch the network. */
    @FunctionalInterface
    public interface Downloader {
        InputStream open(String url) throws IOException;
    }

    private ModelFiles() {
    }

    /** The pinned model file with this name, or an error listing the allowed names. */
    public static RemoteFile modelFile(String name) {
        for (RemoteFile f : List.of(MODEL_FP32, MODEL_INT8)) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown rerank model file '" + name + "'. Allowed: "
                + MODEL_FP32.name() + ", " + MODEL_INT8.name());
    }

    public static void ensure(Path dir, List<RemoteFile> files) {
        ensure(dir, files, ModelFiles::httpDownload);
    }

    /** Downloads each file that is missing or has the wrong hash, and verifies the hash before use. */
    public static void ensure(Path dir, List<RemoteFile> files, Downloader downloader) {
        try {
            Files.createDirectories(dir);
            for (RemoteFile file : files) {
                Path target = dir.resolve(file.name());
                if (Files.isRegularFile(target) && file.sha256().equals(sha256(target))) {
                    continue;
                }
                download(file, target, downloader);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare model files in " + dir + ": " + e.getMessage(), e);
        }
    }

    private static void download(RemoteFile file, Path target, Downloader downloader) throws IOException {
        log.info("Downloading {} ({} MB) from {}", file.name(), file.size() / 1_000_000, host(file.url()));
        Path part = target.resolveSibling(file.name() + ".part");
        MessageDigest digest = newDigest();
        try (InputStream in = new DigestInputStream(downloader.open(file.url()), digest)) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(file.sha256())) {
            Files.deleteIfExists(part);
            throw new IllegalStateException("Checksum mismatch for " + file.name() + ": expected "
                    + file.sha256() + " but got " + actual + ". The file was discarded.");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Verified and stored {}", target);
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    private static InputStream httpDownload(String url) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
    }

    private static String host(String url) {
        return URI.create(url).getHost();
    }
}
