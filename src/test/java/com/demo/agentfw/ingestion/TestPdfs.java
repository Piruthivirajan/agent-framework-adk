package com.demo.agentfw.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Builds small PDFs in memory so tests need no binary fixtures. ASCII text only (Helvetica). */
public final class TestPdfs {

    private static final int CHARS_PER_LINE = 95;
    private static final int MAX_LINES_PER_PAGE = 58;

    private TestPdfs() {
    }

    /** One string becomes one page; an empty string becomes a page with no text. */
    public static byte[] create(String... pageTexts) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (text.isEmpty()) {
                    continue;
                }
                List<String> lines = wrap(text);
                if (lines.size() > MAX_LINES_PER_PAGE) {
                    throw new IllegalArgumentException("Test page text too long for one page: " + lines.size() + " lines");
                }
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 10);
                    cs.setLeading(12);
                    cs.newLineAtOffset(40, 760);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() + word.length() + 1 > CHARS_PER_LINE && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
