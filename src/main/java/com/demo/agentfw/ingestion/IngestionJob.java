package com.demo.agentfw.ingestion;

import com.demo.agentfw.AgentFwProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads the PDF corpus into the chunk store. Safe to run any number of times: a document is only
 * embedded when its content hash or the embedding model differs from what is stored.
 *
 * <p>Per document: hash the file; if the store already has exactly that hash and model, skip (no
 * parsing, no API call). Otherwise parse, chunk and embed everything first, and only then replace
 * the stored chunks in one transaction, so an embedding failure leaves the old version intact.
 * Documents removed from the folder are pruned, unless the folder has no PDFs at all (more likely
 * a wrong path than an intentional wipe).
 */
@Service
public class IngestionJob {

    private static final Logger log = LoggerFactory.getLogger(IngestionJob.class);

    private final AgentFwProperties app;
    private final IngestionProperties props;
    private final PdfLoader loader;
    private final Chunker chunker;
    private final EmbeddingService embeddings;
    private final ChunkStore store;

    public IngestionJob(AgentFwProperties app, IngestionProperties props, PdfLoader loader, Chunker chunker,
                        EmbeddingService embeddings, ChunkStore store) {
        this.app = app;
        this.props = props;
        this.loader = loader;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.store = store;
    }

    public IngestionResult run() {
        return run(Path.of(props.corpusDir()));
    }

    IngestionResult run(Path corpusDir) {
        if (!Files.isDirectory(corpusDir)) {
            throw new IllegalStateException("Corpus directory not found: " + corpusDir.toAbsolutePath()
                    + " (agentfw.ingestion.corpus-dir). The corpus is not in git: run scripts/fetch-corpus.sh");
        }
        List<Path> files = listPdfs(corpusDir);
        Set<String> docIdsOnDisk = docIds(files);

        String tenant = app.tenantId();
        Map<String, ChunkStore.DocState> stored = store.docStates(tenant);

        List<String> ingested = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            try {
                SourceDocument doc = SourceDocument.read(file);
                if (isUpToDate(stored.get(doc.docId()), doc)) {
                    skipped.add(doc.docId());
                    continue;
                }
                if (ingest(tenant, doc)) {
                    ingested.add(doc.docId());
                } else {
                    empty.add(fileName);
                }
            } catch (IOException | RuntimeException e) {
                failed.put(fileName, String.valueOf(e.getMessage()));
                log.error("Ingestion of {} failed, previous chunks (if any) kept: {}", fileName, e.getMessage());
            }
        }

        long pruned = 0;
        boolean pruneSkipped = files.isEmpty();
        if (pruneSkipped) {
            log.warn("No PDFs in {}; not pruning existing chunks", corpusDir.toAbsolutePath());
        } else {
            Set<String> gone = new HashSet<>(stored.keySet());
            gone.removeAll(docIdsOnDisk);
            if (!gone.isEmpty()) {
                pruned = store.deleteDocuments(tenant, gone);
                log.info("Pruned {} chunks of documents no longer in the corpus: {}", pruned, gone);
            }
        }

        IngestionResult result = new IngestionResult(ingested, skipped, empty, failed, pruned, pruneSkipped);
        log.info("Ingestion finished: {}", result.summary());
        return result;
    }

    /** @return false when the file has no extractable text (any stale chunks are removed). */
    private boolean ingest(String tenant, SourceDocument doc) {
        List<PdfPage> pages = loader.loadPages(doc);
        List<TextChunk> chunks = chunker.chunk(pages);
        if (chunks.isEmpty()) {
            log.warn("{} has no extractable text ({} pages); is it a scan? Nothing stored", doc.fileName(), pages.size());
            store.replaceDocument(tenant, doc.docId(), List.of());
            return false;
        }

        List<String> texts = chunks.stream().map(TextChunk::text).toList();
        List<float[]> vectors = embeddings.embedDocuments(titleFor(doc.fileName()), texts);

        List<StoredChunk> toStore = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk c = chunks.get(i);
            toStore.add(new StoredChunk(tenant, doc.docId(), c.index(), doc.fileName(), c.page(), c.text(),
                    doc.contentHash(), embeddings.modelName(), vectors.get(i)));
        }
        store.replaceDocument(tenant, doc.docId(), toStore);
        log.info("Ingested {}: {} pages, {} chunks", doc.fileName(), pages.size(), toStore.size());
        return true;
    }

    private boolean isUpToDate(ChunkStore.DocState state, SourceDocument doc) {
        return state != null
                && state.contentHashes().equals(Set.of(doc.contentHash()))
                && state.embeddingModels().equals(Set.of(embeddings.modelName()));
    }

    private static List<Path> listPdfs(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list corpus directory " + dir + ": " + e.getMessage(), e);
        }
    }

    /** Fails fast when two files would share an id: one would silently overwrite the other. */
    private static Set<String> docIds(List<Path> files) {
        Map<String, String> byId = new HashMap<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            String id = SourceDocument.docIdFor(name);
            String previous = byId.put(id, name);
            if (previous != null) {
                throw new IllegalStateException("Files '" + previous + "' and '" + name
                        + "' map to the same document id '" + id + "'. Rename one of them.");
            }
        }
        return byId.keySet();
    }

    private static String titleFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
