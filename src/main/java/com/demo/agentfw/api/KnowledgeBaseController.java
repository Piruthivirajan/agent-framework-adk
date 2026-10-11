package com.demo.agentfw.api;

import com.demo.agentfw.ingestion.IngestionJob;
import com.demo.agentfw.ingestion.KnowledgeBaseService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The knowledge base. {@code POST /api/kb/documents} validates the PDF synchronously (so a bad file is
 * an ordinary 4xx with a message), then streams the ingestion as server-sent events: {@code stage}
 * events ({@code parsing}, {@code chunking}, {@code embedding} with done/total chunks, {@code storing})
 * and finally {@code result} or {@code error}.
 */
@RestController
@RequestMapping("/api/kb/documents")
class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    private static final long STREAM_TIMEOUT_MS = 15 * 60 * 1000L;

    /** The final event of a successful addition. */
    record AddResult(String fileName, String docId, String status, int pages, int chunks, boolean replaced) {
    }

    record Stage(String stage, int done, int total) {
    }

    private final KnowledgeBaseService kb;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "kb-ingest");
        t.setDaemon(true);
        return t;
    });

    KnowledgeBaseController(KnowledgeBaseService kb) {
        this.kb = kb;
    }

    @GetMapping
    List<KnowledgeBaseService.Document> list() {
        return kb.list();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter add(@RequestParam("file") MultipartFile file,
                   @RequestParam(value = "replace", defaultValue = "false") boolean replace) throws IOException {
        if (kb.isBusy()) {
            throw new KnowledgeBaseService.KbRejectedException(KnowledgeBaseService.Reason.BUSY,
                    "Another document is being added. Please wait a moment and try again.");
        }
        KnowledgeBaseService.Candidate candidate = kb.check(file.getOriginalFilename(), file.getBytes(), replace);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        executor.submit(() -> {
            try {
                IngestionJob.FileOutcome outcome = kb.add(candidate, p -> send(emitter, "stage",
                        new Stage(p.stage(), p.done(), p.total())));
                send(emitter, "result", new AddResult(candidate.fileName(), outcome.docId(), outcome.status().name(),
                        outcome.pages(), outcome.chunks(), candidate.replacing()));
            } catch (RuntimeException e) {
                ErrorMapping.Mapped mapped = ErrorMapping.of(e);
                if (mapped.status().value() == 500) {
                    log.error("knowledge base stream failed: {}", e.toString(), e);
                }
                send(emitter, "error", mapped.error());
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // the browser went away; the ingestion finishes anyway
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();       // let a running ingestion finish
    }
}
