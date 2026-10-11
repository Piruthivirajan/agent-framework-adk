package com.demo.agentfw.files;

import com.demo.agentfw.files.UploadRejectedException.Reason;
import com.demo.agentfw.ingestion.TestPdfs;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Tika, files built in memory: no fixtures, no network. */
class TextExtractorTest {

    private static final TextExtractor extractor = new TextExtractor();

    /** A minimal DOCX built by hand (a ZIP of three XML parts): no POI schema jars needed. */
    private static byte[] docx(String... paragraphs) {
        StringBuilder body = new StringBuilder();
        for (String p : paragraphs) {
            body.append("<w:p><w:r><w:t>").append(p).append("</w:t></w:r></w:p>");
        }
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats"
                + ".org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd"
                + ".openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                + ".wordprocessingml.document.main+xml\"/></Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats"
                + ".org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats"
                + ".org/wordprocessingml/2006/main\"><w:body>" + body + "</w:body></w:document>";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "[Content_Types].xml", contentTypes);
            put(z, "_rels/.rels", rels);
            put(z, "word/document.xml", document);
            z.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static byte[] zip() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write("hello".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsAPdfWithItsPageCount() {
        byte[] pdf = TestPdfs.create("First page about serverless functions.", "Second page about queues.");

        TextExtractor.Extracted e = extractor.extract(pdf, 100_000);

        assertThat(e.contentType()).isEqualTo("application/pdf");
        assertThat(e.text()).contains("serverless functions").contains("about queues");
        assertThat(e.pages()).isEqualTo(2);
    }

    @Test
    void readsADocx() {
        TextExtractor.Extracted e = extractor.extract(docx("Quarterly report", "Revenue grew by twelve percent."),
                100_000);

        assertThat(e.contentType()).isEqualTo(TextExtractor.DOCX);
        assertThat(e.text()).contains("Quarterly report").contains("Revenue grew");
    }

    @Test
    void readsPlainTextIncludingNonAscii() {
        TextExtractor.Extracted e = extractor.extract("Résumé of the café opening: größer, 日本語."
                .getBytes(StandardCharsets.UTF_8), 100_000);

        assertThat(e.contentType()).isEqualTo("text/plain");
        assertThat(e.text()).contains("Résumé").contains("café").contains("日本語");
    }

    @Test
    void anUnsupportedTypeIsRejectedByContentNotByName() {
        assertThatThrownBy(() -> extractor.extract(zip(), 100_000))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.UNSUPPORTED_TYPE);
                    assertThat(e.getMessage()).contains("PDF").contains("DOCX").contains("plain-text");
                });
    }

    @Test
    void aPdfWithoutATextLayerYieldsEmptyText() {
        TextExtractor.Extracted e = extractor.extract(TestPdfs.create("", ""), 100_000);

        assertThat(e.text()).isBlank();            // UploadService turns this into NO_TEXT
    }

    @Test
    void textBeyondTheLimitIsRejectedWithTheLimitInTheMessage() {
        byte[] big = "word ".repeat(5_000).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(big, 1_000))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.TOO_MUCH_TEXT);
                    assertThat(e.getMessage()).contains("1,000 characters");
                });
    }

    @Test
    void aCorruptPdfIsUnreadableNotAStackTrace() {
        byte[] corrupt = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nthis is not a real pdf body\n")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(corrupt, 100_000))
                .isInstanceOfSatisfying(UploadRejectedException.class, e ->
                        assertThat(e.reason()).isIn(Reason.UNREADABLE, Reason.UNSUPPORTED_TYPE));
    }
}
