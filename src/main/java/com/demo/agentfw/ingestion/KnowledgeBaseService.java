package com.demo.agentfw.ingestion;

import com.demo.agentfw.files.FileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * "Add to knowledge base": saves an uploaded PDF into the corpus folder and runs the existing
 * idempotent {@link IngestionJob} for that one file. This is separate from a chat attachment: an
 * attachment is read by the file agent for one session and expires; a knowledge base document is
 * chunked, embedded and searchable by the RAG agent for everyone.
 *
 * <p>Two phases so the caller can answer a bad file with an ordinary error before any work starts:
 * {@link #check} validates (type, size, name, readable, has a text layer, no silent overwrite) and
 * writes only a temporary file; {@link #add} then moves it into place and ingests it. A file with no
 * text layer is rejected in {@code check}, before anything is saved or replaced, so a scan can never
 * wipe the chunks of an existing document of the same name. If ingestion fails, the previous file and
 * chunks are restored.
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    public enum Reason {
        EMPTY, TOO_LARGE, NOT_PDF, BAD_NAME, UNREADABLE, NO_TEXT_LAYER, EXISTS, BUSY, INGESTION_FAILED
    }

    /** A document that cannot be added; the message is for the user. */
    public static class KbRejectedException extends RuntimeException {
        private final Reason reason;

        public KbRejectedException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** @param replacing a document with this name is already in the knowledge base (different content) */
    public record Candidate(Path temp, String fileName, String docId, boolean replacing, int pages, int chunks) {
    }

    /** One document of the knowledge base. {@code indexed} is false when the file is present but has no chunks. */
    public record Document(String fileName, String docId, int chunks, boolean indexed) {
    }

    private static final int MAX_STEM_CHARS = 80;

    private final IngestionJob job;
    private final IngestionProperties props;
    private final FileProperties files;
    private final ChunkStore store;
    private final com.demo.agentfw.AgentFwProperties app;
    private final ReentrantLock lock = new ReentrantLock();

    public KnowledgeBaseService(IngestionJob job, IngestionProperties props, FileProperties files, ChunkStore store,
                                com.demo.agentfw.AgentFwProperties app) {
        this.job = job;
        this.props = props;
        this.files = files;
        this.store = store;
        this.app = app;
    }

    public boolean isBusy() {
        return lock.isLocked();
    }

    public List<Document> list() {
        var stored = store.docStates(app.tenantId());
        List<Document> out = new ArrayList<>();
        Path dir = Path.of(props.corpusDir());
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().forEach(p -> {
                        String name = p.getFileName().toString();
                        String id = SourceDocument.docIdFor(name);
                        ChunkStore.DocState state = stored.get(id);
                        out.add(new Document(name, id, state == null ? 0 : state.chunkCount(), state != null));
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list the knowledge base folder: " + e.getMessage(), e);
        }
        return out;
    }

    /** Validates an upload; writes only a temporary file, which {@link #add} moves or {@link #discard} removes. */
    public Candidate check(String originalName, byte[] bytes, boolean replace) {
        if (bytes == null || bytes.length == 0) {
            throw new KbRejectedException(Reason.EMPTY, "The file is empty.");
        }
        if (bytes.length > files.maxBytes()) {
            throw new KbRejectedException(Reason.TOO_LARGE, "The file is larger than the limit of "
                    + String.format(Locale.ROOT, "%.0f", files.maxBytes() / 1e6) + " MB.");
        }
        String fileName = fileName(originalName);
        if (!looksLikePdf(bytes)) {
            throw new KbRejectedException(Reason.NOT_PDF, "Only PDF files can be added to the knowledge base.");
        }
        Path temp = null;
        try {
            // a temporary directory, so the file keeps its final name and its document id is the
            // one the stored chunks will have
            temp = Files.createTempDirectory("kb-upload-").resolve(fileName);
            Files.write(temp, bytes);
            int[] counts;
            try {
                counts = job.dryRun(temp);
            } catch (IOException | RuntimeException e) {
                throw new KbRejectedException(Reason.UNREADABLE, "This PDF could not be read. It may be corrupt "
                        + "or password-protected.");
            }
            if (counts[1] == 0) {
                throw new KbRejectedException(Reason.NO_TEXT_LAYER, "This PDF has no text layer (it looks like a "
                        + "scan), so there is nothing to search. Only PDFs with selectable text can be added; there "
                        + "is no OCR.");
            }
            SourceDocument doc = SourceDocument.read(temp);
            var hashes = job.storedHashes(doc.docId());
            boolean exists = hashes.isPresent();
            boolean sameContent = exists && hashes.get().equals(Set.of(doc.contentHash()));
            if (exists && !sameContent && !replace) {
                throw new KbRejectedException(Reason.EXISTS, "A different document named '" + fileName
                        + "' is already in the knowledge base. Rename the file, or confirm that it should be "
                        + "replaced.");
            }
            Candidate candidate = new Candidate(temp, fileName, doc.docId(), exists && !sameContent, counts[0],
                    counts[1]);
            temp = null;           // handed over to the candidate
            return candidate;
        } catch (IOException e) {
            throw new KbRejectedException(Reason.UNREADABLE, "The file could not be processed: " + e.getMessage());
        } finally {
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
    }

    /** Removes a candidate's temporary file when it will not be added. */
    public void discard(Candidate candidate) {
        deleteQuietly(candidate.temp());
    }

    /** Moves the file into the corpus folder and ingests it; one at a time. */
    public IngestionJob.FileOutcome add(Candidate candidate, Consumer<IngestionJob.Progress> progress) {
        if (!lock.tryLock()) {
            discard(candidate);
            throw new KbRejectedException(Reason.BUSY, "Another document is being added. Please wait a moment "
                    + "and try again.");
        }
        Path target = Path.of(props.corpusDir()).resolve(candidate.fileName());
        Path backup = null;
        boolean existed = false;
        try {
            Files.createDirectories(target.getParent());
            existed = Files.exists(target);
            if (existed) {
                backup = Files.createTempFile("kb-backup-", ".pdf");
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(candidate.temp(), target, StandardCopyOption.REPLACE_EXISTING);
            deleteQuietly(candidate.temp());          // removes the now empty temporary directory
            try {
                IngestionJob.FileOutcome outcome = job.ingestFile(target, progress);
                log.info("knowledge base: {} -> {} ({} pages, {} chunks)", candidate.fileName(), outcome.status(),
                        outcome.pages(), outcome.chunks());
                return outcome;
            } catch (IOException | RuntimeException e) {
                restore(target, backup, existed);
                log.error("knowledge base: adding {} failed, previous state restored: {}", candidate.fileName(),
                        e.toString());
                throw new KbRejectedException(Reason.INGESTION_FAILED, "The document could not be added ("
                        + shorten(e.getMessage()) + "). Nothing was changed.");
            }
        } catch (IOException e) {
            discard(candidate);
            throw new KbRejectedException(Reason.INGESTION_FAILED, "The document could not be saved: "
                    + shorten(e.getMessage()));
        } finally {
            deleteQuietly(backup);
            lock.unlock();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static void restore(Path target, Path backup, boolean existed) {
        try {
            if (existed && backup != null) {
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            log.error("knowledge base: could not restore {}: {}", target, e.toString());
        }
    }

    /** Letters, digits, dot, dash and underscore only; always ends in {@code .pdf}. */
    static String fileName(String original) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).strip();
        if (!n.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new KbRejectedException(Reason.NOT_PDF, "Only PDF files can be added to the knowledge base.");
        }
        String stem = n.substring(0, n.length() - 4).replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^[.-]+", "").replaceAll("-+$", "");
        if (stem.isEmpty()) {
            throw new KbRejectedException(Reason.BAD_NAME, "The file name has no usable characters.");
        }
        if (stem.length() > MAX_STEM_CHARS) {
            stem = stem.substring(0, MAX_STEM_CHARS);
        }
        return stem + ".pdf";
    }

    private static boolean looksLikePdf(byte[] bytes) {
        int n = Math.min(bytes.length, 1024);
        return new String(bytes, 0, n, StandardCharsets.ISO_8859_1).contains("%PDF-");
    }

    /** Deletes a file and, for an upload's temporary directory, the directory too. */
    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
            Path parent = p.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().startsWith("kb-upload-")) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException e) {
            log.debug("could not delete {}: {}", p, e.toString());
        }
    }

    private static String shorten(String s) {
        if (s == null || s.isBlank()) {
            return "unknown error";
        }
        String first = s.strip().lines().findFirst().orElse("");
        return first.length() > 200 ? first.substring(0, 200) + "..." : first;
    }
}
