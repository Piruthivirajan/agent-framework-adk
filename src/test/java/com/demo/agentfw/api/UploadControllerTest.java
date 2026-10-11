package com.demo.agentfw.api;

import com.demo.agentfw.files.FileProperties;
import com.demo.agentfw.files.InMemoryUploadStore;
import com.demo.agentfw.files.TextExtractor;
import com.demo.agentfw.files.UploadService;
import com.demo.agentfw.ingestion.TestPdfs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadControllerTest {

    private static final TextExtractor EXTRACTOR = new TextExtractor();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        FileProperties props = new FileProperties(50_000, 10_000, 24, 3);
        UploadService service = new UploadService(EXTRACTOR, new InMemoryUploadStore(), props);
        mvc = MockMvcBuilders.standaloneSetup(new UploadController(service))
                .setControllerAdvice(new GlobalExceptionHandler(props)).build();
    }

    @Test
    void aPdfIsAcceptedAndOnlyItsMetadataIsReturned() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("file", "notes.pdf", "application/pdf",
                TestPdfs.create("Serverless notes about concurrency and queues."));

        mvc.perform(multipart("/api/files").file(pdf).param("sessionId", "tab-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("notes.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.pages").value(1))
                .andExpect(jsonPath("$.text").doesNotExist());
    }

    @Test
    void pastedTextUsesTheSameEndpoint() throws Exception {
        mvc.perform(multipart("/api/files").param("sessionId", "tab-1").param("text", "A pasted paragraph."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("pasted-text.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"));
    }

    @Test
    void theSessionsFilesCanBeListed() throws Exception {
        mvc.perform(multipart("/api/files").param("sessionId", "tab-1").param("text", "first"));
        mvc.perform(multipart("/api/files").param("sessionId", "tab-1").param("text", "second"));

        mvc.perform(get("/api/files").param("sessionId", "tab-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/files").param("sessionId", "tab-2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void eachRejectionHasItsOwnStatusAndAPlainMessage() throws Exception {
        MockMultipartFile zip = new MockMultipartFile("file", "archive.txt", "text/plain",
                new byte[] {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 0, 0, 0, 0});
        MockMultipartFile scanned = new MockMultipartFile("file", "scan.pdf", "application/pdf",
                TestPdfs.create("", ""));
        MockMultipartFile huge = new MockMultipartFile("file", "big.txt", "text/plain",
                "word ".repeat(20_000).getBytes(StandardCharsets.UTF_8));
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mvc.perform(multipart("/api/files").file(zip).param("sessionId", "tab-1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_TYPE"))
                .andExpect(jsonPath("$.message").value(containsString("PDF")));
        mvc.perform(multipart("/api/files").file(scanned).param("sessionId", "tab-1"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("NO_TEXT"))
                .andExpect(jsonPath("$.message").value(containsString("no OCR")));
        mvc.perform(multipart("/api/files").file(huge).param("sessionId", "tab-1"))
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("TOO_LARGE"));
        mvc.perform(multipart("/api/files").file(empty).param("sessionId", "tab-1").param("text", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theFourthFileOfASessionIsRefusedWithAConflict() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(multipart("/api/files").param("sessionId", "tab-1").param("text", "file number " + i))
                    .andExpect(status().isOk());
        }

        mvc.perform(multipart("/api/files").param("sessionId", "tab-1").param("text", "one too many"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_MANY_FILES"));
    }

    @Test
    void aRequestWithoutFileOrTextOrWithABadSessionIsRejected() throws Exception {
        mvc.perform(multipart("/api/files").param("sessionId", "tab-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/files").param("sessionId", "../etc").param("text", "x"))
                .andExpect(status().isBadRequest());
    }
}
