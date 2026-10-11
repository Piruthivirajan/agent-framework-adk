package com.demo.agentfw.ingestion;

import com.demo.agentfw.AgentFwProperties;
import com.demo.agentfw.files.FileProperties;
import com.demo.agentfw.ingestion.KnowledgeBaseService.KbRejectedException;
import com.demo.agentfw.ingestion.KnowledgeBaseService.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real ingestion job, fake embeddings, in-memory store, a temporary corpus folder: no network. */
class KnowledgeBaseServiceTest {

    private static final String TENANT = "demo";

    @TempDir
    Path corpus;

    private InMemoryChunkStore store;
    private FakeEmbeddingService embeddings;
    private KnowledgeBaseService kb;

    @BeforeEach
    void setUp() {
        store = new InMemoryChunkStore();
        embeddings = new FakeEmbeddingService(16, "model-a");
        kb = serviceWith(embeddings);
    }

    private KnowledgeBaseService serviceWith(EmbeddingService embeddingService) {
        IngestionProperties props = new IngestionProperties(corpus.toString(), false, 50, 15, 10);
        AgentFwProperties app = new AgentFwProperties(TENANT);
        IngestionJob job = new IngestionJob(app, props, new PdfLoader(), new Chunker(props), embeddingService, store);
        return new KnowledgeBaseService(job, props, new FileProperties(2_000_000, 100_000, 24, 10), store, app);
    }

    private static String longText(String topic) {
        return (topic + " rules apply to every request that is handled by the finance team. ").repeat(8);
    }

    private static byte[] pdf(String topic) {
        return TestPdfs.create("Page one of the " + topic + " document.", longText(topic));
    }

    private static void assertRejected(Runnable call, Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(KbRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void aNewPdfIsSavedIngestedAndReportsProgress() {
        KnowledgeBaseService.Candidate c = kb.check("Refund Policy.pdf", pdf("refund"), false);
        List<IngestionJob.Progress> progress = new ArrayList<>();

        IngestionJob.FileOutcome outcome = kb.add(c, progress::add);

        assertThat(c.fileName()).isEqualTo("Refund-Policy.pdf");
        assertThat(c.replacing()).isFalse();
        assertThat(outcome.status()).isEqualTo(IngestionJob.FileStatus.INGESTED);
        assertThat(outcome.chunks()).isGreaterThan(2).isEqualTo(c.chunks());
        assertThat(Files.exists(corpus.resolve("Refund-Policy.pdf"))).isTrue();
        assertThat(store.chunks(TENANT, "refund-policy")).hasSize(outcome.chunks());
        assertThat(progress).extracting(IngestionJob.Progress::stage)
                .startsWith("parsing", "chunking", "embedding").endsWith("storing");
        IngestionJob.Progress lastEmbedding = progress.stream().filter(p -> p.stage().equals("embedding"))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(lastEmbedding.done()).isEqualTo(lastEmbedding.total()).isEqualTo(outcome.chunks());
    }

    @Test
    void theSameFileAgainIsUnchangedAndMakesNoEmbeddingCall() {
        byte[] bytes = pdf("refund");     // the same bytes: a generated PDF embeds a timestamp, so two builds differ
        kb.add(kb.check("refund.pdf", bytes, false), p -> { });
        int calls = embeddings.documentCalls();

        KnowledgeBaseService.Candidate again = kb.check("refund.pdf", bytes, false);
        IngestionJob.FileOutcome outcome = kb.add(again, p -> { });

        assertThat(again.replacing()).isFalse();
        assertThat(outcome.status()).isEqualTo(IngestionJob.FileStatus.UNCHANGED);
        assertThat(embeddings.documentCalls()).isEqualTo(calls);
    }

    @Test
    void aScanWithoutATextLayerIsRejectedAndNeverTouchesAnExistingDocument() throws IOException {
        kb.add(kb.check("refund.pdf", pdf("refund"), false), p -> { });
        byte[] original = Files.readAllBytes(corpus.resolve("refund.pdf"));
        int chunksBefore = store.chunks(TENANT, "refund").size();

        assertRejected(() -> kb.check("refund.pdf", TestPdfs.create("", ""), true), Reason.NO_TEXT_LAYER);

        assertThat(Files.readAllBytes(corpus.resolve("refund.pdf"))).isEqualTo(original);
        assertThat(store.chunks(TENANT, "refund")).hasSize(chunksBefore);
    }

    @Test
    void aDifferentDocumentWithTheSameNameNeedsExplicitReplacement() throws IOException {
        kb.add(kb.check("policy.pdf", pdf("refund"), false), p -> { });
        String before = store.chunks(TENANT, "policy").get(0).contentHash();

        assertRejected(() -> kb.check("policy.pdf", pdf("shipping"), false), Reason.EXISTS);
        assertThat(store.chunks(TENANT, "policy").get(0).contentHash()).isEqualTo(before);   // nothing changed

        KnowledgeBaseService.Candidate replace = kb.check("policy.pdf", pdf("shipping"), true);
        IngestionJob.FileOutcome outcome = kb.add(replace, p -> { });

        assertThat(replace.replacing()).isTrue();
        assertThat(outcome.status()).isEqualTo(IngestionJob.FileStatus.INGESTED);
        assertThat(store.chunks(TENANT, "policy").get(0).contentHash()).isNotEqualTo(before);
        assertThat(store.chunks(TENANT, "policy").get(0).text()).contains("shipping");
    }

    @Test
    void badInputsAreRejectedWithDistinctReasons() {
        assertRejected(() -> kb.check("a.pdf", new byte[0], false), Reason.EMPTY);
        assertRejected(() -> kb.check("notes.txt", pdf("x"), false), Reason.NOT_PDF);
        assertRejected(() -> kb.check("fake.pdf", "PK\u0003\u0004 not a pdf at all".getBytes(StandardCharsets.UTF_8),
                false), Reason.NOT_PDF);
        assertRejected(() -> kb.check("...pdf", pdf("x"), false), Reason.BAD_NAME);
        assertRejected(() -> kb.check("big.pdf", new byte[2_000_001], false), Reason.TOO_LARGE);
        assertRejected(() -> kb.check("broken.pdf", "%PDF-1.4 this is not really a pdf body".getBytes(
                StandardCharsets.UTF_8), false), Reason.UNREADABLE);
    }

    @Test
    void fileNamesAreReducedToSafeCharactersAndKeepAPdfExtension() {
        assertThat(KnowledgeBaseService.fileName("My Report (final).PDF")).isEqualTo("My-Report-final.pdf");
        assertThat(KnowledgeBaseService.fileName("C:\\Users\\me\\a b.pdf")).isEqualTo("a-b.pdf");
        assertThat(KnowledgeBaseService.fileName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(KnowledgeBaseService.fileName("x".repeat(200) + ".pdf")).hasSize(84);
    }

    @Test
    void aFailedIngestionLeavesNoFileForANewDocumentAndRestoresAReplacedOne() throws IOException {
        kb.add(kb.check("policy.pdf", pdf("refund"), false), p -> { });
        byte[] original = Files.readAllBytes(corpus.resolve("policy.pdf"));
        String hash = store.chunks(TENANT, "policy").get(0).contentHash();
        KnowledgeBaseService failing = serviceWith(new FailingEmbeddings());

        KnowledgeBaseService.Candidate replacement = failing.check("policy.pdf", pdf("shipping"), true);
        assertRejected(() -> failing.add(replacement, p -> { }), Reason.INGESTION_FAILED);
        KnowledgeBaseService.Candidate brandNew = failing.check("new.pdf", pdf("billing"), false);
        assertRejected(() -> failing.add(brandNew, p -> { }), Reason.INGESTION_FAILED);

        assertThat(Files.readAllBytes(corpus.resolve("policy.pdf"))).isEqualTo(original);   // restored
        assertThat(store.chunks(TENANT, "policy").get(0).contentHash()).isEqualTo(hash);   // chunks untouched
        assertThat(Files.exists(corpus.resolve("new.pdf"))).isFalse();                     // not left behind
    }

    @Test
    void onlyOneDocumentIsAddedAtATimeTheOtherIsToldToWait() throws Exception {
        CountDownLatch inEmbedding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EmbeddingService blocking = new FakeEmbeddingService(16, "model-a") {
            @Override
            public List<float[]> embedDocuments(String title, List<String> texts) {
                inEmbedding.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.embedDocuments(title, texts);
            }
        };
        KnowledgeBaseService slow = serviceWith(blocking);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            KnowledgeBaseService.Candidate first = slow.check("one.pdf", pdf("refund"), false);
            Future<?> running = pool.submit(() -> slow.add(first, p -> { }));
            assertThat(inEmbedding.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(slow.isBusy()).isTrue();
            KnowledgeBaseService.Candidate second = slow.check("two.pdf", pdf("shipping"), false);
            assertRejected(() -> slow.add(second, p -> { }), Reason.BUSY);

            release.countDown();
            running.get(10, TimeUnit.SECONDS);
            assertThat(slow.isBusy()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void theListShowsEveryPdfWithItsChunkCount() throws IOException {
        kb.add(kb.check("refund.pdf", pdf("refund"), false), p -> { });
        Files.write(corpus.resolve("notyet.pdf"), pdf("shipping"));          // present but never ingested

        List<KnowledgeBaseService.Document> docs = kb.list();

        assertThat(docs).extracting(KnowledgeBaseService.Document::fileName).containsExactly("notyet.pdf", "refund.pdf");
        assertThat(docs.get(0).indexed()).isFalse();
        assertThat(docs.get(1).indexed()).isTrue();
        assertThat(docs.get(1).chunks()).isGreaterThan(0);
    }

    @Test
    void noTemporaryFilesAreLeftBehindAfterARejection() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before = countUploadDirs(tmp);

        assertRejected(() -> kb.check("scan.pdf", TestPdfs.create("", ""), false), Reason.NO_TEXT_LAYER);

        assertThat(countUploadDirs(tmp)).isEqualTo(before);
    }

    private static long countUploadDirs(Path tmp) throws IOException {
        try (var s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().startsWith("kb-upload-")).count();
        }
    }

    private static final class FailingEmbeddings extends FakeEmbeddingService {
        FailingEmbeddings() {
            super(16, "model-a");
        }

        @Override
        public List<float[]> embedDocuments(String title, List<String> texts) {
            throw new IllegalStateException("quota exhausted");
        }
    }
}
