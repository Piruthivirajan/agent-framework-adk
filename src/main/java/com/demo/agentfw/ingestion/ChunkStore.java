package com.demo.agentfw.ingestion;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Storage port for chunks. The job depends on this, so its logic is testable without a database. */
public interface ChunkStore {

    /**
     * What is currently stored for one document.
     *
     * @param contentHashes   distinct source hashes found on its chunks (one value when consistent)
     * @param embeddingModels distinct embedding models found on its chunks
     * @param chunkCount      number of stored chunks
     */
    record DocState(Set<String> contentHashes, Set<String> embeddingModels, int chunkCount) {
    }

    /** Stored state per docId for a tenant. */
    Map<String, DocState> docStates(String tenantId);

    /** Atomically replaces all chunks of a document: either the new set is visible, or the old one. */
    void replaceDocument(String tenantId, String docId, List<StoredChunk> chunks);

    /** Deletes all chunks of the given documents; returns the number of chunks removed. */
    long deleteDocuments(String tenantId, Collection<String> docIds);
}
