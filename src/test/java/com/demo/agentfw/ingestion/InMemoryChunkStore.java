package com.demo.agentfw.ingestion;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Test double for {@link ChunkStore}; keeps chunks in memory and counts writes. */
public class InMemoryChunkStore implements ChunkStore {

    private final Map<String, Map<String, List<StoredChunk>>> byTenant = new HashMap<>();
    private int replaceCalls;

    @Override
    public Map<String, DocState> docStates(String tenantId) {
        Map<String, DocState> out = new TreeMap<>();
        byTenant.getOrDefault(tenantId, Map.of()).forEach((docId, chunks) -> {
            if (!chunks.isEmpty()) {
                out.put(docId, new DocState(
                        new HashSet<>(chunks.stream().map(StoredChunk::contentHash).toList()),
                        new HashSet<>(chunks.stream().map(StoredChunk::embeddingModel).toList()),
                        chunks.size(),
                        new HashSet<>(chunks.stream().map(StoredChunk::pipelineVersion).toList())));
            }
        });
        return out;
    }

    @Override
    public void replaceDocument(String tenantId, String docId, List<StoredChunk> chunks) {
        replaceCalls++;
        byTenant.computeIfAbsent(tenantId, t -> new HashMap<>()).put(docId, List.copyOf(chunks));
    }

    @Override
    public long deleteDocuments(String tenantId, Collection<String> docIds) {
        Map<String, List<StoredChunk>> docs = byTenant.getOrDefault(tenantId, new HashMap<>());
        long removed = 0;
        for (String id : docIds) {
            List<StoredChunk> gone = docs.remove(id);
            removed += gone == null ? 0 : gone.size();
        }
        return removed;
    }

    List<StoredChunk> chunks(String tenantId, String docId) {
        return byTenant.getOrDefault(tenantId, Map.of()).getOrDefault(docId, List.of());
    }

    int replaceCalls() {
        return replaceCalls;
    }
}
