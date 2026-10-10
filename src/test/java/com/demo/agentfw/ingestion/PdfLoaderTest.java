package com.demo.agentfw.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfLoaderTest {

    private final PdfLoader loader = new PdfLoader();

    private static SourceDocument doc(String fileName, byte[] bytes) {
        return new SourceDocument(fileName, SourceDocument.docIdFor(fileName), SourceDocument.sha256Hex(bytes), bytes);
    }

    @Test
    void returnsOnePageEntryPerPdfPageWithOneBasedNumbers() {
        byte[] pdf = TestPdfs.create(
                "Refund requests are processed within 14 days.",
                "Invoices are emailed on the first business day.",
                "Error code E-401 means the token has expired.");

        List<PdfPage> pages = loader.loadPages(doc("handbook.pdf", pdf));

        assertThat(pages).extracting(PdfPage::number).containsExactly(1, 2, 3);
        assertThat(pages.get(0).text()).contains("Refund requests");
        assertThat(pages.get(1).text()).contains("Invoices are emailed");
        assertThat(pages.get(2).text()).contains("E-401");
    }

    @Test
    void textDoesNotLeakAcrossPages() {
        byte[] pdf = TestPdfs.create("alpha only here", "beta only here");

        List<PdfPage> pages = loader.loadPages(doc("two.pdf", pdf));

        assertThat(pages.get(0).text()).contains("alpha").doesNotContain("beta");
        assertThat(pages.get(1).text()).contains("beta").doesNotContain("alpha");
    }

    @Test
    void blankPageIsKeptWithBlankText() {
        byte[] pdf = TestPdfs.create("first page text", "", "third page text");

        List<PdfPage> pages = loader.loadPages(doc("gap.pdf", pdf));

        assertThat(pages).hasSize(3);
        assertThat(pages.get(1).text()).isBlank();
        assertThat(pages.get(2).number()).isEqualTo(3);
    }

    @Test
    void longLinesAreWrappedButWordsStayIntact() {
        String sentence = "Refund requests are processed within fourteen calendar days of approval by the finance team. ";
        byte[] pdf = TestPdfs.create(sentence.repeat(5));

        String text = loader.loadPages(doc("long.pdf", pdf)).get(0).text().replaceAll("\\s+", " ");

        assertThat(text).contains("processed within fourteen calendar days of approval by the finance team.");
    }

    @Test
    void corruptFileFailsWithFileNameInMessage() {
        SourceDocument broken = doc("broken.pdf", "this is not a pdf".getBytes());

        assertThatThrownBy(() -> loader.loadPages(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken.pdf");
    }

    @Test
    void readComputesStableDocIdAndContentHash(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Refund Policy (v2).PDF");
        Files.write(file, TestPdfs.create("v1 text"));

        SourceDocument first = SourceDocument.read(file);
        SourceDocument again = SourceDocument.read(file);
        Files.write(file, TestPdfs.create("v2 text"));
        SourceDocument changed = SourceDocument.read(file);

        assertThat(first.docId()).isEqualTo("refund-policy-v2");
        assertThat(first.fileName()).isEqualTo("Refund Policy (v2).PDF");
        assertThat(first.contentHash()).hasSize(64).isEqualTo(again.contentHash());
        assertThat(changed.docId()).isEqualTo(first.docId());          // identity survives edits
        assertThat(changed.contentHash()).isNotEqualTo(first.contentHash());
    }

    @Test
    void docIdCannotBeEmpty() {
        assertThatThrownBy(() -> SourceDocument.docIdFor("---.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringDoesNotDumpBytes() {
        SourceDocument d = doc("a.pdf", TestPdfs.create("hello"));
        assertThat(d.toString()).contains("a.pdf").hasSizeLessThan(200);
    }
}
