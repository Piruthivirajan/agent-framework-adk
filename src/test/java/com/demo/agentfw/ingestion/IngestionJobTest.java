package com.demo.agentfw.ingestion;

import com.demo.agentfw.AgentFwProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No network, no MongoDB: fake embeddings and an in-memory store. */
class IngestionJobTest {

    private static final String TENANT = "demo";
    private static final int DIMS = 16;

    @TempDir
    Path corpus;

    private InMemoryChunkStore store;
    private FakeEmbeddingService embeddings;
    private IngestionJob job;

    @BeforeEach
    void setUp() {
        store = new InMemoryChunkStore();
        embeddings = new FakeEmbeddingService(DIMS, "model-a");
        job = newJob(embeddings);
    }

    private IngestionJob newJob(EmbeddingService embeddingService) {
        return newJob(embeddingService, 50);
    }

    private IngestionJob newJob(EmbeddingService embeddingService, int chunkTargetTokens) {
        IngestionProperties props = new IngestionProperties(corpus.toString(), false, chunkTargetTokens, 15, 10);
        return new IngestionJob(new AgentFwProperties(TENANT), props, new PdfLoader(), new Chunker(props),
                embeddingService, store);
    }

    private void writePdf(String fileName, String... pages) throws IOException {
        Files.write(corpus.resolve(fileName), TestPdfs.create(pages));
    }

    private static String longText(String topic) {
        return (topic + " rules apply to every request that is handled by the finance team. ").repeat(8);
    }

    @Test
    void firstRunStoresChunksWithCitationFields() throws IOException {
        writePdf("Refund Policy.pdf", "Refund requests are processed within 14 days.", longText("refund"));
        writePdf("errors.pdf", "Error code E-401 means the token has expired.");

        IngestionResult result = job.run(corpus);

        assertThat(result.ingested()).containsExactlyInAnyOrder("refund-policy", "errors");
        assertThat(result.failed()).isEmpty();

        List<StoredChunk> refund = store.chunks(TENANT, "refund-policy");
        assertThat(refund.size()).isGreaterThan(2);
        assertThat(refund).extracting(StoredChunk::chunkIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, refund.size()).boxed().toList());
        assertThat(refund).allSatisfy(c -> {
            assertThat(c.tenantId()).isEqualTo(TENANT);
            assertThat(c.file()).isEqualTo("Refund Policy.pdf");
            assertThat(c.embeddingModel()).isEqualTo("model-a");
            assertThat(c.embedding()).hasSize(DIMS);
            assertThat(c.contentHash()).hasSize(64);
        });
        assertThat(refund.get(0).page()).isEqualTo(1);
        assertThat(refund.get(0).text()).contains("Refund requests are processed within 14 days.");
        assertThat(refund.get(refund.size() - 1).page()).isEqualTo(2);
    }

    @Test
    void secondRunDoesNothing() throws IOException {
        writePdf("a.pdf", longText("alpha"));
        writePdf("b.pdf", longText("beta"));
        job.run(corpus);
        int writes = store.replaceCalls();
        embeddings.resetCounters();

        IngestionResult again = job.run(corpus);

        assertThat(again.skipped()).containsExactlyInAnyOrder("a", "b");
        assertThat(again.ingested()).isEmpty();
        assertThat(embeddings.documentCalls()).as("embedding calls on re-run").isZero();
        assertThat(store.replaceCalls()).as("store writes on re-run").isEqualTo(writes);
    }

    @Test
    void changedFileIsReEmbeddedAndOthersAreUntouched() throws IOException {
        writePdf("a.pdf", longText("alpha"));
        writePdf("b.pdf", longText("beta"));
        job.run(corpus);
        String oldHash = store.chunks(TENANT, "a").get(0).contentHash();
        String untouchedHash = store.chunks(TENANT, "b").get(0).contentHash();
        embeddings.resetCounters();

        writePdf("a.pdf", longText("alpha changed"), "A new second page with different content in it.");
        IngestionResult result = job.run(corpus);

        assertThat(result.ingested()).containsExactly("a");
        assertThat(result.skipped()).containsExactly("b");
        assertThat(embeddings.documentCalls()).isEqualTo(1);
        List<StoredChunk> a = store.chunks(TENANT, "a");
        assertThat(a).extracting(StoredChunk::contentHash).doesNotContain(oldHash).containsOnly(a.get(0).contentHash());
        assertThat(a).extracting(StoredChunk::page).contains(2);
        assertThat(store.chunks(TENANT, "b").get(0).contentHash()).isEqualTo(untouchedHash);
    }

    @Test
    void embeddingModelChangeReEmbedsEvenWhenFilesAreUnchanged() throws IOException {
        writePdf("a.pdf", longText("alpha"));
        job.run(corpus);

        FakeEmbeddingService newModel = new FakeEmbeddingService(DIMS, "model-b");
        IngestionResult result = newJob(newModel).run(corpus);

        assertThat(result.ingested()).containsExactly("a");
        assertThat(newModel.documentCalls()).isEqualTo(1);
        assertThat(store.chunks(TENANT, "a")).extracting(StoredChunk::embeddingModel).containsOnly("model-b");
    }

    @Test
    void documentRemovedFromTheFolderIsPruned() throws IOException {
        writePdf("keep.pdf", longText("keep"));
        writePdf("drop.pdf", longText("drop"));
        job.run(corpus);
        int dropChunks = store.chunks(TENANT, "drop").size();

        Files.delete(corpus.resolve("drop.pdf"));
        IngestionResult result = job.run(corpus);

        assertThat(result.prunedChunks()).isEqualTo(dropChunks);
        assertThat(store.chunks(TENANT, "drop")).isEmpty();
        assertThat(store.chunks(TENANT, "keep")).isNotEmpty();
    }

    @Test
    void emptyFolderDoesNotWipeTheStore() throws IOException {
        writePdf("a.pdf", longText("alpha"));
        job.run(corpus);
        Files.delete(corpus.resolve("a.pdf"));

        IngestionResult result = job.run(corpus);

        assertThat(result.pruneSkipped()).isTrue();
        assertThat(result.prunedChunks()).isZero();
        assertThat(store.chunks(TENANT, "a")).isNotEmpty();
    }

    @Test
    void embeddingFailureKeepsOldChunksAndDoesNotStopOtherFiles() throws IOException {
        writePdf("good.pdf", longText("good"));
        writePdf("flaky.pdf", longText("flaky v1"));
        job.run(corpus);
        List<StoredChunk> flakyBefore = store.chunks(TENANT, "flaky");

        writePdf("flaky.pdf", longText("flaky v2"));
        writePdf("good.pdf", longText("good v2"));
        EmbeddingService failsForFlaky = new EmbeddingService() {
            @Override
            public List<float[]> embedDocuments(String title, List<String> texts) {
                if (title.equals("flaky")) {
                    throw new IllegalStateException("quota exhausted");
                }
                return embeddings.embedDocuments(title, texts);
            }

            @Override
            public float[] embedQuery(String query) {
                return embeddings.embedQuery(query);
            }

            @Override
            public String modelName() {
                return embeddings.modelName();
            }

            @Override
            public int dimensions() {
                return embeddings.dimensions();
            }
        };

        IngestionResult result = newJob(failsForFlaky).run(corpus);

        assertThat(result.hasFailures()).isTrue();
        assertThat(result.failed()).containsOnlyKeys("flaky.pdf");
        assertThat(result.failed().get("flaky.pdf")).contains("quota exhausted");
        assertThat(result.ingested()).containsExactly("good");
        assertThat(store.chunks(TENANT, "flaky")).isEqualTo(flakyBefore);   // old version intact
    }

    @Test
    void corruptPdfIsReportedAndTheRestContinues() throws IOException {
        Files.writeString(corpus.resolve("broken.pdf"), "not a pdf");
        writePdf("fine.pdf", longText("fine"));

        IngestionResult result = job.run(corpus);

        assertThat(result.failed()).containsOnlyKeys("broken.pdf");
        assertThat(result.ingested()).containsExactly("fine");
    }

    @Test
    void pdfWithoutTextIsReportedEmptyAndStaleChunksAreRemoved() throws IOException {
        writePdf("scan.pdf", longText("text layer"));
        job.run(corpus);
        assertThat(store.chunks(TENANT, "scan")).isNotEmpty();

        writePdf("scan.pdf", "", "");
        IngestionResult result = job.run(corpus);

        assertThat(result.empty()).containsExactly("scan.pdf");
        assertThat(store.chunks(TENANT, "scan")).isEmpty();
    }

    @Test
    void duplicateDocumentIdsFailBeforeAnyWork() throws IOException {
        writePdf("Refund Policy.pdf", longText("one"));
        writePdf("refund-policy.pdf", longText("two"));

        assertThatThrownBy(() -> job.run(corpus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same document id 'refund-policy'");
        assertThat(store.replaceCalls()).isZero();
        assertThat(embeddings.documentCalls()).isZero();
    }

    @Test
    void missingFolderIsAnErrorNotAnEmptyRun() {
        assertThatThrownBy(() -> job.run(corpus.resolve("does-not-exist")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corpus directory not found");
    }

    @Test
    void ignoresNonPdfFilesAndAcceptsUppercaseExtension() throws IOException {
        Files.writeString(corpus.resolve("notes.txt"), "not part of the corpus");
        writePdf("UPPER.PDF", longText("upper"));

        IngestionResult result = job.run(corpus);

        assertThat(result.ingested()).containsExactly("upper");
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void changingThePipelineReIngestsFilesThatDidNotChange() throws IOException {
        writePdf("a.pdf", longText("alpha"));
        job.run(corpus);                                   // chunking: 50 tokens
        embeddings.resetCounters();
        assertThat(job.run(corpus).skipped()).containsExactly("a");   // same pipeline: nothing to do
        assertThat(embeddings.documentCalls()).isZero();

        IngestionResult result = newJob(embeddings, 60).run(corpus);   // chunking changed, file bytes did not

        assertThat(result.ingested()).containsExactly("a");
        assertThat(embeddings.documentCalls()).isEqualTo(1);
        assertThat(store.chunks(TENANT, "a")).extracting(StoredChunk::pipelineVersion)
                .allMatch(v -> v.equals("v1|chunk=60/15/10"));
        assertThat(newJob(embeddings, 60).run(corpus).skipped()).containsExactly("a");   // and it sticks
    }

    @Test
    void legacyChunksWithoutAVersionAreRebuilt() throws IOException {
        writePdf("a.pdf", longText("alpha"));
        job.run(corpus);
        // simulate chunks written before versions existed: the legacy marker "1"
        List<StoredChunk> old = store.chunks(TENANT, "a").stream()
                .map(c -> new StoredChunk(c.tenantId(), c.docId(), c.chunkIndex(), c.file(), c.page(), c.text(),
                        c.contentHash(), c.embeddingModel(), c.embedding())).toList();
        store.replaceDocument(TENANT, "a", old);
        embeddings.resetCounters();

        IngestionResult result = job.run(corpus);

        assertThat(result.ingested()).containsExactly("a");
        assertThat(embeddings.documentCalls()).isEqualTo(1);
    }
}
