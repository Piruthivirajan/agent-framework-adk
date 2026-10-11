package com.demo.agentfw.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes running headers and footers: lines that sit at the top or bottom of most pages of one
 * document. They carry no meaning ("Security Pillar AWS Well-Architected Framework" on every
 * page) but are repeated in every chunk, so they add identical words to the full-text index and
 * pull the embeddings of unrelated chunks of the same document towards each other.
 *
 * <p>A line is a running line when, with digits replaced by "#" (so "Doc 12" and "Doc 13" are the
 * same), it appears in the top 3 or bottom 3 lines of at least half of the document's non-blank
 * pages. Only lines in those zones are removed; the same words inside the body are kept. Footers
 * that change with the section ("COST01-BP03 Establish cloud budgets 16") are not repeated on most
 * pages and are therefore left alone.
 */
@Component
public class RunningHeaderStripper {

    static final int ZONE_LINES = 3;
    static final double MIN_PAGE_SHARE = 0.5;
    /** Too few pages to tell a running line from a coincidence. */
    static final int MIN_PAGES = 8;

    public record Result(List<PdfPage> pages, int removedLines, List<String> patterns) {
    }

    public Result strip(List<PdfPage> pages) {
        long nonBlank = pages.stream().filter(p -> !p.text().isBlank()).count();
        if (nonBlank < MIN_PAGES) {
            return new Result(pages, 0, List.of());
        }

        Map<String, Integer> pagesWithLine = new HashMap<>();
        for (PdfPage page : pages) {
            Set<String> seen = new LinkedHashSet<>();
            for (int index : zoneIndexes(split(page.text()))) {
                seen.add(normalize(split(page.text()).get(index)));
            }
            seen.forEach(n -> pagesWithLine.merge(n, 1, Integer::sum));
        }
        double needed = Math.ceil(MIN_PAGE_SHARE * nonBlank);
        Set<String> patterns = new LinkedHashSet<>();
        pagesWithLine.forEach((line, count) -> {
            if (!line.isEmpty() && count >= needed) {
                patterns.add(line);
            }
        });
        if (patterns.isEmpty()) {
            return new Result(pages, 0, List.of());
        }

        int removed = 0;
        List<PdfPage> out = new ArrayList<>(pages.size());
        for (PdfPage page : pages) {
            List<String> lines = split(page.text());
            Set<Integer> drop = new LinkedHashSet<>();
            for (int index : zoneIndexes(lines)) {
                if (patterns.contains(normalize(lines.get(index)))) {
                    drop.add(index);
                }
            }
            if (drop.isEmpty()) {
                out.add(page);
                continue;
            }
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (!drop.contains(i)) {
                    kept.add(lines.get(i));
                }
            }
            removed += drop.size();
            out.add(new PdfPage(page.number(), String.join("\n", kept)));
        }
        return new Result(out, removed, List.copyOf(patterns));
    }

    /** Indexes (into {@code lines}) of the first and last {@value #ZONE_LINES} non-blank lines. */
    private static List<Integer> zoneIndexes(List<String> lines) {
        List<Integer> nonBlank = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                nonBlank.add(i);
            }
        }
        Set<Integer> zone = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(ZONE_LINES, nonBlank.size()); i++) {
            zone.add(nonBlank.get(i));
        }
        for (int i = Math.max(0, nonBlank.size() - ZONE_LINES); i < nonBlank.size(); i++) {
            zone.add(nonBlank.get(i));
        }
        return List.copyOf(zone);
    }

    private static List<String> split(String text) {
        return List.of(text.split("\\R", -1));
    }

    static String normalize(String line) {
        return line.strip().replaceAll("\\d+", "#").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
