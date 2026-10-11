package com.demo.agentfw.files;

import com.demo.agentfw.files.UploadRejectedException.Reason;
import com.demo.agentfw.ingestion.TestPdfs;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadServiceTest {

    private static final TextExtractor EXTRACTOR = new TextExtractor();   // building Tika's parser takes seconds

    private final InMemoryUploadStore store = new InMemoryUploadStore();

    private UploadService service(FileProperties props) {
        return new UploadService(EXTRACTOR, store, props);
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRejected(Runnable call, Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(UploadRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void storesTheExtractedTextAndReturnsASummaryWithoutIt() {
        UploadService service = service(FileProperties.defaults());

        UploadedFile f = service.upload("tab-1", "notes.pdf",
                TestPdfs.create("Lambda concurrency notes for the quarter."));

        assertThat(f.text()).contains("Lambda concurrency notes");
        assertThat(f.contentType()).isEqualTo("application/pdf");
        assertThat(f.pages()).isEqualTo(1);
        assertThat(f.summary().toString()).doesNotContain("Lambda concurrency");
        assertThat(service.find("tab-1", f.id())).isPresent();
        assertThat(service.list("tab-1")).extracting(UploadedFile.Summary::name).containsExactly("notes.pdf");
    }

    @Test
    void aFileCanOnlyBeReadFromTheSessionThatUploadedIt() {
        UploadService service = service(FileProperties.defaults());
        UploadedFile f = service.upload("tab-1", "a.txt", text("some private notes"));

        assertThat(service.find("tab-2", f.id())).isEmpty();
        assertThat(service.list("tab-2")).isEmpty();
    }

    @Test
    void pastedTextGoesThroughTheSamePathAsAFile() {
        UploadService service = service(FileProperties.defaults());

        UploadedFile f = service.uploadText("tab-1", null, "A pasted paragraph about queues and topics.");

        assertThat(f.name()).isEqualTo("pasted-text.txt");
        assertThat(f.contentType()).isEqualTo("text/plain");
        assertThat(f.text()).contains("pasted paragraph");
    }

    @Test
    void rejectsEmptyOversizedAndTextlessFilesWithDistinctReasons() {
        UploadService small = service(new FileProperties(1_000, 100_000, 24, 10));

        assertRejected(() -> small.upload("s", "e.txt", new byte[0]), Reason.EMPTY);
        assertRejected(() -> small.upload("s", "big.txt", text("x ".repeat(2_000))), Reason.TOO_LARGE);
        assertRejected(() -> service(FileProperties.defaults()).upload("s", "scan.pdf", TestPdfs.create("", "")),
                Reason.NO_TEXT);
        assertRejected(() -> service(FileProperties.defaults()).uploadText("s", null, "   "), Reason.EMPTY);
    }

    @Test
    void theSizeMessageStatesBothTheFileAndTheLimit() {
        UploadService small = service(new FileProperties(1_000, 100_000, 24, 10));

        assertThatThrownBy(() -> small.upload("s", "big.txt", text("x ".repeat(2_000))))
                .hasMessageContaining("4.0 KB").hasMessageContaining("1.0 KB");
    }

    @Test
    void thePerSessionFileLimitIsEnforced() {
        UploadService service = service(new FileProperties(1_000_000, 100_000, 24, 2));
        service.upload("tab-1", "a.txt", text("first file content here"));
        service.upload("tab-1", "b.txt", text("second file content here"));

        assertRejected(() -> service.upload("tab-1", "c.txt", text("third")), Reason.TOO_MANY_FILES);
        assertThat(service.upload("tab-2", "c.txt", text("another session is unaffected"))).isNotNull();
    }

    @Test
    void displayNamesAreSanitised() {
        assertThat(UploadService.sanitize("C:\\Users\\me\\report.pdf")).isEqualTo("report.pdf");
        assertThat(UploadService.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(UploadService.sanitize("bad\u0000na\nme.txt")).isEqualTo("badname.txt");
        assertThat(UploadService.sanitize("   ")).isEqualTo("uploaded-file");
        assertThat(UploadService.sanitize(null)).isEqualTo("uploaded-file");
        assertThat(UploadService.sanitize("x".repeat(500))).hasSize(200);
    }

    @Test
    void aMissingSessionIdIsAProgrammingErrorNotAnUpload() {
        assertThatThrownBy(() -> service(FileProperties.defaults()).upload(" ", "a.txt", text("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
