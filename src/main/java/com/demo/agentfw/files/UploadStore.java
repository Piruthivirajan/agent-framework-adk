package com.demo.agentfw.files;

import java.util.List;
import java.util.Optional;

/** Where parsed uploads live. The port keeps the service testable without a database. */
public interface UploadStore {

    void save(UploadedFile file);

    /** The file, only when it belongs to this session. */
    Optional<UploadedFile> find(String sessionId, String fileId);

    /** Metadata of the session's files, oldest first; no text is loaded. */
    List<UploadedFile.Summary> summaries(String sessionId);
}
