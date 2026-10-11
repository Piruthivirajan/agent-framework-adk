package com.demo.agentfw.api;

import com.demo.agentfw.AgentFwProperties;
import com.demo.agentfw.files.FileProperties;
import com.demo.agentfw.ingestion.Chunker;
import com.demo.agentfw.ingestion.FakeEmbeddingService;
import com.demo.agentfw.ingestion.InMemoryChunkStore;
import com.demo.agentfw.ingestion.IngestionJob;
import com.demo.agentfw.ingestion.IngestionProperties;
import com.demo.agentfw.ingestion.KnowledgeBaseService;
import com.demo.agentfw.ingestion.PdfLoader;
import com.demo.agentfw.ingestion.TestPdfs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The knowledge base endpoint over the real ingestion job with fake embeddings and an in-memory store. */
class KnowledgeBaseControllerTest {

    @TempDir
    Path corpus;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        IngestionProperties props = new IngestionProperties(corpus.toString(), false, 50, 15, 10);
        AgentFwProperties app = new AgentFwProperties("demo");
        var store = new InMemoryChunkStore();
        IngestionJob job = new IngestionJob(app, props, new PdfLoader(), new Chunker(props),
                new FakeEmbeddingService(16, "model-a"), store);
        FileProperties files = new FileProperties(2_000_000, 100_000, 24, 10);
        KnowledgeBaseService kb = new KnowledgeBaseService(job, props, files, store, app);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(kb))
                .setControllerAdvice(new GlobalExceptionHandler(files)).build();
    }

    private static byte[] pdf() {
        return TestPdfs.create("A page about refunds.",
                ("Refund rules apply to every request handled by the finance team. ").repeat(10));
    }

    @Test
    void aPdfIsAddedAndStreamedAsStagesThenAResult() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "Refund Policy.pdf", "application/pdf", pdf());

        MvcResult started = mvc.perform(multipart("/api/kb/documents").file(file).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();

        String stream = awaitContaining(started, "\"replaced\":");
        assertThat(stream).contains("event:stage").contains("\"stage\":\"parsing\"").contains("\"stage\":\"embedding\"")
                .contains("\"stage\":\"storing\"").contains("\"status\":\"INGESTED\"")
                .contains("\"fileName\":\"Refund-Policy.pdf\"").contains("\"replaced\":false");
        assertThat(Files.exists(corpus.resolve("Refund-Policy.pdf"))).isTrue();

        mvc.perform(get("/api/kb/documents")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("Refund-Policy.pdf"))
                .andExpect(jsonPath("$[0].indexed").value(true));
    }

    @Test
    void aScanIsRejectedBeforeAnythingIsSaved() throws Exception {
        MockMultipartFile scan = new MockMultipartFile("file", "scan.pdf", "application/pdf", TestPdfs.create("", ""));

        mvc.perform(multipart("/api/kb/documents").file(scan))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_TEXT_LAYER"))
                .andExpect(jsonPath("$.message").value(containsString("no text layer")));

        assertThat(Files.exists(corpus.resolve("scan.pdf"))).isFalse();
    }

    @Test
    void aRejectionIsJsonEvenWhenTheClientAsksForAnEventStream() throws Exception {
        MockMultipartFile scan = new MockMultipartFile("file", "scan.pdf", "application/pdf", TestPdfs.create("", ""));

        mvc.perform(multipart("/api/kb/documents").file(scan).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnprocessableContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NO_TEXT_LAYER"));
    }

    @Test
    void anotherFileTypeIs415() throws Exception {
        MockMultipartFile txt = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/api/kb/documents").file(txt))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("NOT_PDF"));
    }

    @Test
    void aNameCollisionWithDifferentContentIs409UntilReplaceIsConfirmed() throws Exception {
        MockMultipartFile first = new MockMultipartFile("file", "policy.pdf", "application/pdf", pdf());
        MvcResult started = mvc.perform(multipart("/api/kb/documents").file(first)).andReturn();
        awaitContaining(started, "\"replaced\":");
        MockMultipartFile other = new MockMultipartFile("file", "policy.pdf", "application/pdf",
                TestPdfs.create("A page about shipping.", ("Shipping rules apply to parcels. ").repeat(10)));

        mvc.perform(multipart("/api/kb/documents").file(other))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXISTS"));
        MvcResult replaced = mvc.perform(multipart("/api/kb/documents").file(other).param("replace", "true"))
                .andExpect(request().asyncStarted()).andReturn();

        assertThat(awaitContaining(replaced, "\"replaced\":")).contains("\"replaced\":true");
    }

    private static String awaitContaining(MvcResult result, String marker) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = result.getResponse().getContentAsString();
            if (content.contains(marker)) {
                return content;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no '" + marker + "' in the stream: " + content);
    }
}
