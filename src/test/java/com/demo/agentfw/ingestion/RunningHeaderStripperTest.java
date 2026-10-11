package com.demo.agentfw.ingestion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunningHeaderStripperTest {

    private final RunningHeaderStripper stripper = new RunningHeaderStripper();

    private static final String HEADER = "Security Pillar AWS Well-Architected Framework";

    // Body lines differ by words, never only by digits: digits are normalized away on purpose
    // (they are page numbers), so digit-only differences would count as the same running line.
    private static final String[] W = {"encryption", "backups", "latency", "quotas", "tagging", "lambda",
            "queues", "tracing", "budgets", "routing", "caching", "sharding"};

    private static String w(int i) {
        return W[Math.floorMod(i, W.length)];
    }

    /** n pages: header on top, six body lines that differ per page, optionally a footer with a page number. */
    private static List<PdfPage> pages(int n, boolean footerWithPageNumber) {
        List<PdfPage> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            StringBuilder t = new StringBuilder(HEADER).append('\n');
            t.append("This page is about ").append(w(i)).append(" and covers ").append(w(i + 5)).append(".\n");
            t.append("A second line mentions ").append(w(i + 2)).append(" together with ").append(w(i + 7)).append(".\n");
            t.append("A third line discusses ").append(w(i * 2)).append(" versus ").append(w(i * 3 + 1)).append(".\n");
            t.append("The fourth line compares ").append(w(i + 4)).append(" to ").append(w(i + 9)).append(".\n");
            t.append("The fifth line explains ").append(w(i * 5)).append(" using ").append(w(i + 11)).append(".\n");
            t.append("The sixth line ends with ").append(w(i + 3)).append(" and ").append(w(i * 7)).append(".");
            if (footerWithPageNumber) {
                t.append("\nDocument title ").append(i + 14);
            }
            out.add(new PdfPage(i, t.toString()));
        }
        return out;
    }

    @Test
    void removesAHeaderThatSitsOnEveryPage() {
        RunningHeaderStripper.Result r = stripper.strip(pages(12, false));

        assertThat(r.removedLines()).isEqualTo(12);
        assertThat(r.patterns()).containsExactly(RunningHeaderStripper.normalize(HEADER));
        assertThat(r.pages()).allSatisfy(p -> assertThat(p.text()).doesNotContain("Well-Architected"));
        assertThat(r.pages().get(0).text()).startsWith("This page is about backups");
        assertThat(r.pages().get(0).number()).isEqualTo(1);
    }

    @Test
    void treatsPageNumbersAsTheSameLine() {
        RunningHeaderStripper.Result r = stripper.strip(pages(12, true));

        // "Document title 15", "Document title 16", ... normalize to one pattern and are removed too
        assertThat(r.removedLines()).isEqualTo(24);
        assertThat(r.pages()).allSatisfy(p -> assertThat(p.text()).doesNotContain("Document title"));
    }

    @Test
    void aBarePageNumberLineIsRemovedToo() {
        List<PdfPage> input = new ArrayList<>();
        for (PdfPage p : pages(10, false)) {
            input.add(new PdfPage(p.number(), p.text() + "\n" + (p.number() + 40)));   // "41", "42", ...
        }

        RunningHeaderStripper.Result r = stripper.strip(input);

        assertThat(r.removedLines()).isEqualTo(20);
        assertThat(r.pages().get(0).text()).doesNotContain("\n41");
    }

    @Test
    void keepsTheSameWordsWhenTheyAppearInsideTheBody() {
        List<PdfPage> input = new ArrayList<>(pages(10, false));
        input.set(4, new PdfPage(5, HEADER + "\nFirst body line.\nAs the " + HEADER
                + " says, middle line.\nthird.\nfourth.\nfifth.\nsixth."));

        RunningHeaderStripper.Result r = stripper.strip(input);

        String page5 = r.pages().get(4).text();
        assertThat(page5).startsWith("First body line.");
        assertThat(page5).contains("As the " + HEADER + " says, middle line.");   // a different line, in the body
    }

    @Test
    void leavesLinesThatAreRepeatedOnlyOnSomePages() {
        List<PdfPage> input = new ArrayList<>(pages(12, false));
        for (int i = 0; i < 3; i++) {   // "Resources" at the bottom of 3 of 12 pages (25%)
            PdfPage p = input.get(i);
            input.set(i, new PdfPage(p.number(), p.text() + "\nResources"));
        }

        RunningHeaderStripper.Result r = stripper.strip(input);

        assertThat(r.pages().get(0).text()).endsWith("Resources");
        assertThat(r.removedLines()).isEqualTo(12);   // only the header
    }

    @Test
    void doesNotGuessOnVeryShortDocuments() {
        List<PdfPage> shortDoc = pages(RunningHeaderStripper.MIN_PAGES - 1, false);

        RunningHeaderStripper.Result r = stripper.strip(shortDoc);

        assertThat(r.removedLines()).isZero();
        assertThat(r.pages()).isEqualTo(shortDoc);
    }

    @Test
    void onlyTheTopAndBottomThreeLinesAreCandidates() {
        List<PdfPage> input = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            // the repeated line is the 5th of 9 lines on every page: outside the top 3 and bottom 3
            input.add(new PdfPage(i, "top " + w(i) + "\ntwo " + w(i + 1) + "\nthree " + w(i + 2) + "\nfour " + w(i + 3)
                    + "\nREPEATED BODY LINE\nsix " + w(i + 4) + "\nseven " + w(i + 5) + "\neight " + w(i + 6)
                    + "\nnine " + w(i + 7)));
        }

        RunningHeaderStripper.Result r = stripper.strip(input);

        assertThat(r.removedLines()).isZero();
        assertThat(r.pages().get(0).text()).contains("REPEATED BODY LINE");
    }

    @Test
    void blankPagesAndEmptyInputAreSafe() {
        assertThat(stripper.strip(List.of()).pages()).isEmpty();

        List<PdfPage> input = new ArrayList<>(pages(10, false));
        input.set(3, new PdfPage(4, "   \n  "));
        RunningHeaderStripper.Result r = stripper.strip(input);

        assertThat(r.pages().get(3).text()).isBlank();
        assertThat(r.removedLines()).isEqualTo(9);   // the header on the 9 non-blank pages
    }

    @Test
    void isIdempotent() {
        RunningHeaderStripper.Result once = stripper.strip(pages(12, true));
        RunningHeaderStripper.Result twice = stripper.strip(once.pages());

        assertThat(twice.removedLines()).isZero();
        assertThat(twice.pages()).isEqualTo(once.pages());
    }
}
