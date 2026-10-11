package com.demo.agentfw.files;

import com.demo.agentfw.files.UploadRejectedException.Reason;
import org.apache.tika.Tika;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Text out of an uploaded file with Apache Tika. The type is detected from the bytes (a file named
 * {@code notes.txt} that is really a ZIP is rejected), only an allow-list of types is parsed, and the
 * extraction stops at {@code maxChars} so a huge or hostile file cannot fill memory.
 */
@Component
public class TextExtractor {

    static final String PDF = "application/pdf";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String TEXT = "text/plain";

    private static final Set<String> ALLOWED = Set.of(PDF, DOCX, TEXT);
    private static final String[] PAGE_COUNT_KEYS = {"xmpTPg:NPages", "meta:page-count", "Page-Count"};

    /** The extracted text and what was learned about the file. */
    public record Extracted(String text, String contentType, Integer pages) {
    }

    private final Tika detector = new Tika();
    private final AutoDetectParser parser = new AutoDetectParser();

    public Extracted extract(byte[] bytes, int maxChars) {
        String type = detect(bytes);
        if (!ALLOWED.contains(type)) {
            throw new UploadRejectedException(Reason.UNSUPPORTED_TYPE, "This file type (" + type
                    + ") is not supported. Upload a PDF, a DOCX or a plain-text file.");
        }
        BodyContentHandler handler = new BodyContentHandler(maxChars);
        Metadata metadata = new Metadata();
        try (TikaInputStream in = TikaInputStream.get(bytes)) {
            parser.parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                throw new UploadRejectedException(Reason.UNREADABLE, "The file could not be read. It may be "
                        + "corrupt or password-protected.");
            }
            throw new UploadRejectedException(Reason.TOO_MUCH_TEXT, "The file contains more than "
                    + String.format(Locale.ROOT, "%,d", maxChars) + " characters of text, which is more than can "
                    + "be summarized. Split it and upload the part you need.");
        }
        String text = handler.toString().strip();
        return new Extracted(text, type, pages(metadata));
    }

    private String detect(byte[] bytes) {
        return detector.detect(bytes).toLowerCase(Locale.ROOT);
    }

    private static Integer pages(Metadata metadata) {
        for (String key : PAGE_COUNT_KEYS) {
            String value = metadata.get(key);
            if (value != null) {
                try {
                    return Integer.valueOf(value.strip());
                } catch (NumberFormatException ignored) {
                    // try the next key
                }
            }
        }
        return null;
    }
}
