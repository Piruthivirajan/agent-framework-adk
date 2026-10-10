package com.demo.agentfw.ingestion;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits page text into overlapping chunks that never cross a page boundary, so every chunk has one
 * true page number to cite.
 *
 * <p>Sizes are estimated in tokens as {@code characters / 4}. A tokenizer is not needed: the
 * embedding model accepts 8192 tokens, more than ten times the largest chunk, so the estimate
 * error cannot cause truncation.
 *
 * <p>Per page, a window of {@code target} characters slides forward. Each chunk ends at the best
 * boundary found in the last 25% of the window (paragraph break, then sentence end, then space),
 * and the next chunk starts {@code overlap} characters earlier, at a word start, so a sentence cut
 * at a boundary appears whole in one of the two chunks.
 *
 * <p>The last chunk of a page absorbs a short remainder (under {@code minTail}), instead of leaving
 * a tiny chunk that is mostly a copy of the overlap. Chunks therefore range from 75% of the target
 * up to target + minTail (650 + 100 = 750 tokens with the defaults).
 */
@Component
public class Chunker {

    static final int CHARS_PER_TOKEN = 4;
    /** A page with less text than this (a stray page number, a blank scan) produces no chunk. */
    static final int MIN_PAGE_CHARS = 20;
    private static final int MIN_FILL_PERCENT = 75;

    private final int targetChars;
    private final int overlapChars;
    private final int minTailChars;

    @Autowired
    public Chunker(IngestionProperties props) {
        this(props.chunkTargetTokens(), props.chunkOverlapPercent(), props.chunkMinTailTokens());
    }

    public Chunker(int targetTokens, int overlapPercent, int minTailTokens) {
        if (targetTokens < 20) {
            throw new IllegalArgumentException("chunk target must be at least 20 tokens");
        }
        if (overlapPercent < 0 || overlapPercent >= MIN_FILL_PERCENT) {
            throw new IllegalArgumentException("overlap percent must be in [0, " + MIN_FILL_PERCENT + ")");
        }
        if (minTailTokens < 0) {
            throw new IllegalArgumentException("min tail must not be negative");
        }
        this.targetChars = targetTokens * CHARS_PER_TOKEN;
        this.overlapChars = targetChars * overlapPercent / 100;
        this.minTailChars = minTailTokens * CHARS_PER_TOKEN;
    }

    public List<TextChunk> chunk(List<PdfPage> pages) {
        List<TextChunk> out = new ArrayList<>();
        for (PdfPage page : pages) {
            String text = normalize(page.text());
            if (text.length() < MIN_PAGE_CHARS) {
                continue;
            }
            for (String piece : split(text)) {
                out.add(new TextChunk(out.size(), page.number(), piece));
            }
        }
        return out;
    }

    /**
     * PDF extraction puts a line break at every visual line. Single line breaks are joined into
     * spaces; blank lines are kept as paragraph breaks.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{2,}", " ")
                .replace('\n', ' ')
                .replace(" ", "\n\n")
                .replaceAll(" {2,}", " ");
        return s.strip();
    }

    private List<String> split(String text) {
        int len = text.length();
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (true) {
            if (len - start <= targetChars + minTailChars) {
                pieces.add(text.substring(start).strip());
                return pieces;
            }
            int end = breakPoint(text, start, start + targetChars);
            pieces.add(text.substring(start, end).strip());
            start = wordStart(text, end - overlapChars, end);
        }
    }

    /** Best place to end a chunk inside [start + 75% of target, hardEnd]; hardEnd if there is none. */
    private int breakPoint(String text, int start, int hardEnd) {
        int minEnd = start + targetChars * MIN_FILL_PERCENT / 100;

        int paragraph = text.lastIndexOf("\n\n", hardEnd);
        if (paragraph >= minEnd) {
            return paragraph;
        }
        for (int i = hardEnd; i > minEnd; i--) {
            char c = text.charAt(i - 1);
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        int space = text.lastIndexOf(' ', hardEnd);
        if (space >= minEnd) {
            return space;
        }
        return hardEnd;   // no boundary at all (for example a very long unbroken string)
    }

    /** First word start at or after {@code from}, capped at {@code limit} (which means "no overlap"). */
    private static int wordStart(String text, int from, int limit) {
        int i = Math.max(from, 0);
        if (i == 0 || Character.isWhitespace(text.charAt(i - 1))) {
            return skipWhitespace(text, i, limit);
        }
        while (i < limit && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return skipWhitespace(text, i, limit);
    }

    private static int skipWhitespace(String text, int from, int limit) {
        int i = from;
        while (i < limit && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
