package com.demo.agentfw.api;

import com.demo.agentfw.files.UploadService;
import com.demo.agentfw.files.UploadedFile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Chat attachments. One endpoint takes a file part, or pasted text in a {@code text} field: both go
 * through the same extraction and limits. The response is the file's metadata; its text stays on the
 * server and is read by the file agent.
 */
@RestController
@RequestMapping("/api/files")
class UploadController {

    private final UploadService uploads;

    UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    UploadedFile.Summary upload(@RequestParam("sessionId") String sessionId,
                                @RequestParam(value = "file", required = false) MultipartFile file,
                                @RequestParam(value = "text", required = false) String text,
                                @RequestParam(value = "name", required = false) String name) throws IOException {
        requireSession(sessionId);
        if (file != null && !file.isEmpty()) {
            return uploads.upload(sessionId, file.getOriginalFilename(), file.getBytes()).summary();
        }
        if (text != null) {
            return uploads.uploadText(sessionId, name, text).summary();
        }
        throw new IllegalArgumentException("Send a file, or the text to upload in the 'text' field.");
    }

    @GetMapping
    List<UploadedFile.Summary> list(@RequestParam("sessionId") String sessionId) {
        requireSession(sessionId);
        return uploads.list(sessionId);
    }

    private static void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 100
                || !sessionId.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("sessionId is missing or not valid.");
        }
    }
}
