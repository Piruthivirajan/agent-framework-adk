package com.demo.agentfw.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts text page by page with PDFBox. Pages are kept separate because every chunk must carry
 * the page number it came from, so answers can cite [file, page].
 *
 * <p>Scanned PDFs have no text layer, so their pages come back blank; OCR is out of scope.
 */
@Component
public class PdfLoader {

    public List<PdfPage> loadPages(SourceDocument document) {
        try (PDDocument pdf = Loader.loadPDF(document.bytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = pdf.getNumberOfPages();
            List<PdfPage> pages = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(new PdfPage(page, stripper.getText(pdf)));
            }
            return pages;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read PDF " + document.fileName() + ": " + e.getMessage(), e);
        }
    }
}
