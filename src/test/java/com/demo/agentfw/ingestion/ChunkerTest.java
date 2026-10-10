package com.demo.agentfw.ingestion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkerTest {

    // Small numbers keep the arithmetic readable: target 200 chars, overlap 30, min tail 40, min fill 150.
    private final Chunker small = new Chunker(50, 15, 10);
    private static final int TARGET = 200;
    private static final int MIN_TAIL = 40;
    private static final int MIN_FILL = 150;

    private static PdfPage page(int number, String text) {
        return new PdfPage(number, text);
    }

    /** About 45 characters each, so a sentence end always falls inside the last 25% of a window. */
    private static String sentences(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> String.format("Sentence %03d covers refund and invoice rules.", i))
                .collect(Collectors.joining(" "));
    }

    private static Set<String> words(String s) {
        return new HashSet<>(List.of(s.split("\\s+")));
    }

    @Test
    void shortPageIsOneChunkWithItsPageNumber() {
        List<TextChunk> chunks = small.chunk(List.of(page(7, "Refund requests are processed within 14 days.")));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).page()).isEqualTo(7);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(0).text()).isEqualTo("Refund requests are processed within 14 days.");
    }

    @Test
    void longPageBecomesBoundedOverlappingChunksThatCoverEverything() {
        String text = sentences(30);

        List<TextChunk> chunks = small.chunk(List.of(page(1, text)));

        assertThat(chunks.size()).isGreaterThan(3);
        for (int i = 0; i < chunks.size(); i++) {
            int len = chunks.get(i).text().length();
            assertThat(len).as("chunk %d length", i).isLessThanOrEqualTo(TARGET + MIN_TAIL);
            if (i < chunks.size() - 1) {
                assertThat(len).as("chunk %d fills the window", i).isGreaterThanOrEqualTo(MIN_FILL);
            }
        }
        // overlap: the start of each chunk is already present in the previous one
        for (int i = 1; i < chunks.size(); i++) {
            String firstWords = String.join(" ", List.of(chunks.get(i).text().split(" ")).subList(0, 2));
            assertThat(chunks.get(i - 1).text()).as("overlap %d", i).contains(firstWords);
        }
        // coverage: nothing is lost
        Set<String> covered = new HashSet<>();
        chunks.forEach(c -> covered.addAll(words(c.text())));
        assertThat(covered).containsAll(words(text));
    }

    @Test
    void prefersSentenceBoundaries() {
        List<TextChunk> chunks = small.chunk(List.of(page(1, sentences(30))));

        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).text()).as("chunk %d", i).endsWith(".");
        }
    }

    @Test
    void prefersAParagraphBreakInsideTheWindow() {
        String paragraphA = "word ".repeat(34).strip();            // 169 chars, inside [150, 200]
        String paragraphB = "other ".repeat(50).strip();
        String text = paragraphA + "\n\n" + paragraphB;

        List<TextChunk> chunks = small.chunk(List.of(page(1, text)));

        assertThat(chunks.get(0).text()).isEqualTo(paragraphA);
    }

    @Test
    void joinsPdfLineBreaksButKeepsParagraphBreaks() {
        String raw = "line one of the paragraph\nline two continues here\n\nsecond paragraph starts";

        List<TextChunk> chunks = small.chunk(List.of(page(1, raw)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text())
                .isEqualTo("line one of the paragraph line two continues here\n\nsecond paragraph starts");
    }

    @Test
    void neverCrossesPagesAndNumbersChunksAcrossTheDocument() {
        List<TextChunk> chunks = small.chunk(List.of(
                page(1, "First page has a short text of its own."),
                page(2, "   \n  "),
                page(3, sentences(12))));

        assertThat(chunks).extracting(TextChunk::index)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks.get(0).page()).isEqualTo(1);
        assertThat(chunks.subList(1, chunks.size())).extracting(TextChunk::page).containsOnly(3);
        assertThat(chunks).extracting(TextChunk::page).doesNotContain(2);
        assertThat(chunks.get(0).text()).doesNotContain("Sentence");
    }

    @Test
    void skipsPagesWithAlmostNoText() {
        assertThat(small.chunk(List.of(page(1, "12"), page(2, "x".repeat(Chunker.MIN_PAGE_CHARS - 1))))).isEmpty();
        assertThat(small.chunk(List.of(page(1, "x".repeat(Chunker.MIN_PAGE_CHARS))))).hasSize(1);
    }

    @Test
    void shortRemainderIsAbsorbedInsteadOfLeavingATinyTail() {
        String fits = "alpha ".repeat((TARGET + MIN_TAIL) / 6).strip();   // 239 chars
        assertThat(small.chunk(List.of(page(1, fits)))).hasSize(1);

        String justOver = "alpha ".repeat((TARGET + MIN_TAIL) / 6 + 2).strip();   // 251 chars
        List<TextChunk> chunks = small.chunk(List.of(page(1, justOver)));
        assertThat(chunks).hasSize(2);
        int firstEnd = justOver.indexOf(chunks.get(0).text()) + chunks.get(0).text().length();
        assertThat(justOver.length() - firstEnd).as("new text in the last chunk").isGreaterThanOrEqualTo(MIN_TAIL);
    }

    @Test
    void textWithoutAnyBoundaryIsCutHardAndStillTerminates() {
        String blob = "a".repeat(1000);

        List<TextChunk> chunks = small.chunk(List.of(page(1, blob)));

        assertThat(chunks).hasSize(5);
        assertThat(chunks.stream().map(TextChunk::text).collect(Collectors.joining())).isEqualTo(blob);
    }

    @Test
    void isDeterministic() {
        List<PdfPage> pages = List.of(page(1, sentences(40)), page(2, sentences(10)));
        assertThat(small.chunk(pages)).isEqualTo(small.chunk(pages));
    }

    @Test
    void defaultSettingsGiveRoughly500To750TokenChunks() {
        Chunker defaults = new Chunker(650, 15, 100);   // the values in application.yml

        List<TextChunk> chunks = defaults.chunk(List.of(page(1, sentences(220))));   // about 10,000 chars

        assertThat(chunks.size()).isBetween(3, 6);
        List<Integer> tokens = new ArrayList<>();
        chunks.forEach(c -> tokens.add(c.text().length() / Chunker.CHARS_PER_TOKEN));
        for (int i = 0; i < tokens.size(); i++) {
            assertThat(tokens.get(i)).as("chunk %d tokens", i).isLessThanOrEqualTo(750);
            if (i < tokens.size() - 1) {
                assertThat(tokens.get(i)).as("chunk %d tokens", i).isGreaterThanOrEqualTo(487);
            }
        }
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new Chunker(5, 15, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(650, 80, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(650, 15, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
