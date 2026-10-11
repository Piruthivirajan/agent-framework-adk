package com.demo.agentfw.files;

import com.demo.agentfw.files.UploadRejectedException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepts a file or pasted text for a chat session: checks the limits, extracts the text and stores
 * it. Pasted text goes through the same path as a file (as {@code text/plain}), so there is one route.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    static final int MAX_NAME_CHARS = 200;
    static final String DEFAULT_PASTE_NAME = "pasted-text.txt";

    private final TextExtractor extractor;
    private final UploadStore store;
    private final FileProperties props;

    public UploadService(TextExtractor extractor, UploadStore store, FileProperties props) {
        this.extractor = extractor;
        this.store = store;
        this.props = props;
    }

    public UploadedFile upload(String sessionId, String fileName, byte[] bytes) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (bytes == null || bytes.length == 0) {
            throw new UploadRejectedException(Reason.EMPTY, "The file is empty.");
        }
        if (bytes.length > props.maxBytes()) {
            throw new UploadRejectedException(Reason.TOO_LARGE, "The file is "
                    + readable(bytes.length) + "; the limit is " + readable(props.maxBytes()) + ".");
        }
        if (store.summaries(sessionId).size() >= props.maxFilesPerSession()) {
            throw new UploadRejectedException(Reason.TOO_MANY_FILES, "At most " + props.maxFilesPerSession()
                    + " files can be uploaded in one session.");
        }
        TextExtractor.Extracted extracted = extractor.extract(bytes, props.maxChars());
        if (extracted.text().isBlank()) {
            throw new UploadRejectedException(Reason.NO_TEXT, "No text could be extracted from this file. "
                    + "Scanned or image-only documents are not supported (there is no OCR).");
        }
        UploadedFile file = new UploadedFile(UUID.randomUUID().toString(), sessionId, sanitize(fileName),
                extracted.contentType(), bytes.length, extracted.pages(), extracted.text(), Instant.now());
        store.save(file);
        log.info("upload session={} file={} type={} bytes={} chars={} pages={}", sessionId, file.id(),
                file.contentType(), file.sizeBytes(), file.chars(), file.pages());
        return file;
    }

    /** Pasted content: stored as a {@code text/plain} file named {@code pasted-text.txt}. */
    public UploadedFile uploadText(String sessionId, String name, String text) {
        if (text == null || text.isBlank()) {
            throw new UploadRejectedException(Reason.EMPTY, "There is no text to upload.");
        }
        String fileName = name == null || name.isBlank() ? DEFAULT_PASTE_NAME : name;
        return upload(sessionId, fileName, text.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<UploadedFile> find(String sessionId, String fileId) {
        return store.find(sessionId, fileId);
    }

    public List<UploadedFile.Summary> list(String sessionId) {
        return store.summaries(sessionId);
    }

    /** A display name only: no directories, no control characters, bounded length. */
    static String sanitize(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (n.isEmpty()) {
            n = "uploaded-file";
        }
        return n.length() > MAX_NAME_CHARS ? n.substring(0, MAX_NAME_CHARS) : n;
    }

    static String readable(long bytes) {
        if (bytes < 1_000) {
            return bytes + " bytes";
        }
        if (bytes < 1_000_000) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }
}
