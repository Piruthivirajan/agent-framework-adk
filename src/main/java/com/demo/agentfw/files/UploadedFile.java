package com.demo.agentfw.files;

import java.time.Instant;

/**
 * An uploaded file after parsing: the extracted text and what is needed to show and resolve it.
 * The original bytes are not kept.
 *
 * @param id          random id the client and the coordinator use to refer to the file
 * @param sessionId   the chat session that uploaded it; a file can only be read from that session
 * @param name        display name, sanitised (no path, no control characters)
 * @param contentType type detected from the content, not from the file name
 * @param sizeBytes   size of the upload
 * @param pages       page count when the format has one, else null
 * @param text        extracted text
 * @param createdAt   upload time (the TTL index counts from it)
 */
public record UploadedFile(String id, String sessionId, String name, String contentType, long sizeBytes,
                           Integer pages, String text, Instant createdAt) {

    public int chars() {
        return text.length();
    }

    /** What the API returns: everything except the text. */
    public Summary summary() {
        return new Summary(id, name, contentType, sizeBytes, pages, chars());
    }

    public record Summary(String id, String name, String contentType, long sizeBytes, Integer pages, int chars) {
    }
}
