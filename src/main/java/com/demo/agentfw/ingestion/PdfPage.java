package com.demo.agentfw.ingestion;

/** The extracted text of one PDF page. {@code number} is 1-based, as a reader would cite it. */
public record PdfPage(int number, String text) {
}
