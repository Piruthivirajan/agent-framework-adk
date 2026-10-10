package com.demo.agentfw.ingestion;

/**
 * A piece of one page. {@code index} is the position within the whole document (0-based, page order),
 * {@code page} the 1-based page it came from.
 */
public record TextChunk(int index, int page, String text) {
}
