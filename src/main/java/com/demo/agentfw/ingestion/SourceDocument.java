package com.demo.agentfw.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A corpus file read once into memory, with the two identifiers ingestion needs.
 *
 * <ul>
 *   <li>{@code docId}: stable identity derived from the file name, so editing a file keeps its id.
 *   <li>{@code contentHash}: SHA-256 of the bytes; changes whenever the content changes. Ingestion
 *       compares it with the stored hash to decide "skip" or "re-embed".
 * </ul>
 */
public record SourceDocument(String fileName, String docId, String contentHash, byte[] bytes) {

    public static SourceDocument read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String fileName = path.getFileName().toString();
        return new SourceDocument(fileName, docIdFor(fileName), sha256Hex(bytes), bytes);
    }

    /** "Refund Policy (v2).PDF" becomes "refund-policy-v2". */
    static String docIdFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("Cannot derive a document id from file name: " + fileName);
        }
        return slug;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    @Override
    public String toString() {   // never dump the bytes into logs
        return "SourceDocument[" + fileName + ", docId=" + docId + ", hash=" + contentHash.substring(0, 12) + "…, "
                + bytes.length + " bytes]";
    }
}
