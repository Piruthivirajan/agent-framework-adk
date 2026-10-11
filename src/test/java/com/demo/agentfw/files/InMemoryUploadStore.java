package com.demo.agentfw.files;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** An {@link UploadStore} for tests that must not need a database. */
public class InMemoryUploadStore implements UploadStore {

    private final List<UploadedFile> files = new CopyOnWriteArrayList<>();

    @Override
    public void save(UploadedFile file) {
        files.add(file);
    }

    @Override
    public Optional<UploadedFile> find(String sessionId, String fileId) {
        return files.stream().filter(f -> f.id().equals(fileId) && f.sessionId().equals(sessionId)).findFirst();
    }

    @Override
    public List<UploadedFile.Summary> summaries(String sessionId) {
        List<UploadedFile.Summary> out = new ArrayList<>();
        files.stream().filter(f -> f.sessionId().equals(sessionId)).forEach(f -> out.add(f.summary()));
        return out;
    }
}
