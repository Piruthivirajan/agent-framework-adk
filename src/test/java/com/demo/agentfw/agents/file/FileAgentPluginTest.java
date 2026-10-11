package com.demo.agentfw.agents.file;

import com.demo.agentfw.files.FileProperties;
import com.demo.agentfw.files.InMemoryUploadStore;
import com.demo.agentfw.files.MapReduceSummarizer;
import com.demo.agentfw.files.SummaryProperties;
import com.demo.agentfw.files.TextExtractor;
import com.demo.agentfw.files.UploadService;
import com.demo.agentfw.files.UploadedFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FileAgentPluginTest {

    private static final TextExtractor EXTRACTOR = new TextExtractor();

    private final UploadService uploads = new UploadService(EXTRACTOR, new InMemoryUploadStore(),
            FileProperties.defaults());
    private final FileSummaryLedger ledger = new FileSummaryLedger();
    private final FileAgentPlugin plugin = new FileAgentPlugin(uploads,
            new MapReduceSummarizer((system, user) -> "summary", SummaryProperties.defaults()), ledger);

    private UploadedFile upload(String session, String name, String text) {
        return uploads.upload(session, name, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resolutionOrderIsAttachedThenFileIdThenNameThenLatest() {
        UploadedFile first = upload("s", "alpha.txt", "alpha content");
        UploadedFile second = upload("s", "beta.txt", "beta content");
        UploadedFile third = upload("s", "gamma.txt", "gamma content");

        assertThat(plugin.resolve("s", first.id(), "anything").orElseThrow().id()).isEqualTo(first.id());
        assertThat(plugin.resolve("s", null, "use fileId=" + second.id() + " please").orElseThrow().id())
                .isEqualTo(second.id());
        assertThat(plugin.resolve("s", null, "what is in Alpha.TXT?").orElseThrow().id()).isEqualTo(first.id());
        assertThat(plugin.resolve("s", null, "summarize it").orElseThrow().id()).isEqualTo(third.id());
    }

    @Test
    void anAttachmentNeverFallsBackToAnotherFile() {
        upload("s", "alpha.txt", "alpha content");

        assertThat(plugin.resolve("s", "11111111-1111-1111-1111-111111111111", "summarize")).isEmpty();
    }

    @Test
    void aFileIdOfAnotherSessionIsIgnoredAndTheSessionsOwnFileIsUsed() {
        UploadedFile mine = upload("s", "mine.txt", "my content");
        UploadedFile theirs = upload("other", "theirs.txt", "their content");

        assertThat(plugin.resolve("s", null, "fileId=" + theirs.id()).orElseThrow().id()).isEqualTo(mine.id());
    }

    @Test
    void nothingUploadedMeansNoFile() {
        assertThat(plugin.resolve("s", null, "summarize")).isEmpty();
        String text = plugin.handle("s", "req-1", null, "summarize", p -> { });

        assertThat(text).isEqualTo(FileAgentPlugin.NO_FILE_MESSAGE);
        assertThat(ledger.take("req-1").orElseThrow().status()).isEqualTo(FileSummaryLedger.Status.NO_FILE);
    }

    @Test
    void theLedgerKeepsTheOutcomeUntilItIsTakenOnce() {
        upload("s", "alpha.txt", "alpha content");

        plugin.handle("s", "req-1", null, "summarize", p -> { });

        FileSummaryLedger.Outcome o = ledger.take("req-1").orElseThrow();
        assertThat(o.status()).isEqualTo(FileSummaryLedger.Status.SUMMARIZED);
        assertThat(o.summary()).isEqualTo("summary");
        assertThat(o.fileName()).isEqualTo("alpha.txt");
        assertThat(ledger.take("req-1")).isEmpty();
    }

    @Test
    void theDescriptionSeparatesUploadedFilesFromTheReferenceDocuments() {
        assertThat(plugin.description()).contains("uploaded").contains("Do not use it for questions about the "
                + "reference documents");
    }
}
