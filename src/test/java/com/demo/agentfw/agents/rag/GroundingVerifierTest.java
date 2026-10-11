package com.demo.agentfw.agents.rag;

import com.demo.agentfw.retrieval.Candidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure code: no model, no network. The ledger holds three retrieved chunks, refs r1c1 to r1c3. */
class GroundingVerifierTest {

    private static final String REQ = "req-1";
    private static final String TOOL_QUOTE = "provides a consistent process for you to review and measure your workloads";

    private RetrievalLedger ledger;
    private GroundingVerifier verifier;

    @BeforeEach
    void setUp() {
        ledger = new RetrievalLedger();
        verifier = new GroundingVerifier(ledger);
        List<Candidate> candidates = List.of(
                TestRetrieval.candidate(1, "wellarchitected-framework.pdf", 7,
                        "The AWS Well-Architected Tool (AWS WA Tool) is a service in the cloud that "
                                + TOOL_QUOTE.replace("process for", "process\nfor")      // a line break inside the quote
                                + ". It is free."),
                TestRetrieval.candidate(2, "wellarchitected-reliability-pillar.pdf", 18,
                        "Refund requests are processed within 14 days.   Customers can wait up to 1,000 ms for "
                                + "a response and 0.5 percent of requests may fail. Retries use jitter."),
                TestRetrieval.candidate(3, "wellarchitected-security-pillar.pdf", 9,
                        "Security and Compliance is a shared responsibility between AWS and the customer. "
                                + "The ﬁrst step is to understand the model."));
        ledger.register(REQ, "q", TestRetrieval.service(candidates, i -> 5.0 - i, null).retrieve("q"));
    }

    private static String answer(String... claimJson) {
        return "{\"status\":\"ANSWERED\",\"claims\":[" + String.join(",", claimJson) + "]}";
    }

    private static String claim(String text, String ref, String quote) {
        return "{\"text\":\"" + text + "\",\"ref\":\"" + ref + "\",\"quote\":\"" + quote + "\"}";
    }

    // ---- accepted -------------------------------------------------------------------------------

    @Test
    void acceptsAClaimWhoseQuoteIsInTheChunkAndRendersFileAndPageFromTheLedger() {
        GroundingVerifier.Verified v = verifier.verify(
                answer(claim("The tool provides a consistent review process.", "r1c1", TOOL_QUOTE)), REQ);

        assertThat(v.grounded()).isTrue();
        assertThat(v.answer()).isEqualTo(
                "The tool provides a consistent review process. [wellarchitected-framework.pdf, p. 7]");
        assertThat(v.sources()).containsExactly(
                new GroundingVerifier.Source("r1c1", "wellarchitected-framework.pdf", 7, TOOL_QUOTE));
        assertThat(v.problems()).isEmpty();
        assertThat(v.answer()).doesNotContain(TOOL_QUOTE);   // quotes travel in the sources list, not in the answer text
    }

    @Test
    void whitespaceIsNormalisedOnBothSides() {
        // the chunk has a line break, runs of spaces and a no-break space; the quote has plain single spaces
        GroundingVerifier.Verified v = verifier.verify(answer(
                claim("Retries use jitter.", "r1c2", "may fail. Retries use jitter."),
                claim("Refunds are fast.", "r1c2", "Refund requests are processed within 14 days.")), REQ);

        assertThat(v.grounded()).as(v.problems().toString()).isTrue();
    }

    @Test
    void unicodeFormsAreNormalised() {
        // the chunk contains the ligature U+FB01 in "first"; the model wrote "fi"
        GroundingVerifier.Verified v = verifier.verify(answer(
                claim("The first step is to understand the model.", "r1c3",
                        "The first step is to understand the model.")), REQ);

        assertThat(v.grounded()).isTrue();
    }

    @Test
    void numberFormatsAreNormalisedBeforeComparing() {
        GroundingVerifier.Verified v = verifier.verify(answer(
                claim("Refunds are processed within 14 days.", "r1c2", "Refund requests are processed within 14 days."),
                claim("A response may take up to 1000 ms.", "r1c2", "Customers can wait up to 1,000 ms for a response"),
                claim("Half a percent may fail: 0.50 percent.", "r1c2", "and 0.5 percent of requests may fail.")), REQ);

        assertThat(v.grounded()).as(v.problems().toString()).isTrue();
    }

    @Test
    void severalClaimsKeepOrderAndDuplicateQuotesAreListedOnce() {
        GroundingVerifier.Verified v = verifier.verify(answer(
                claim("Customers share security duties.", "r1c3", "is a shared responsibility between AWS and the customer"),
                claim("It is a joint duty.", "r1c3", "is a shared responsibility between AWS and the customer"),
                claim("Refunds take 14 days.", "r1c2", "Refund requests are processed within 14 days.")), REQ);

        assertThat(v.grounded()).isTrue();
        assertThat(v.answer().lines().toList()).hasSize(3);
        assertThat(v.sources()).hasSize(2);
        assertThat(v.answer().lines().toList().get(0)).startsWith("Customers share security duties.");
    }

    @Test
    void tolerantOfFencesAndProseAroundTheJsonButNeverRendersThem() {
        String wrapped = "Sure! Here is the answer:\n```json\n"
                + answer(claim("The tool provides a consistent review process.", "r1c1", TOOL_QUOTE))
                + "\n```\nAnd by the way, the capital of France is Paris.";

        GroundingVerifier.Verified v = verifier.verify(wrapped, REQ);

        assertThat(v.grounded()).isTrue();
        assertThat(v.answer()).doesNotContain("France").doesNotContain("Sure!");
    }

    @Test
    void extraFieldsInTheJsonAreIgnoredAndNeverShown() {
        String json = "{\"status\":\"ANSWERED\",\"answer\":\"Lambda allows 1,000 concurrent executions.\",\"claims\":["
                + claim("The tool provides a consistent review process.", "r1c1", TOOL_QUOTE) + "]}";

        GroundingVerifier.Verified v = verifier.verify(json, REQ);

        assertThat(v.grounded()).isTrue();
        assertThat(v.answer()).doesNotContain("Lambda").doesNotContain("1,000");
    }

    // ---- rejected: provenance -------------------------------------------------------------------

    @Test
    void aFabricatedQuoteReplacesTheWholeAnswer() {
        GroundingVerifier.Verified v = verifier.verify(answer(
                claim("Good claim.", "r1c1", TOOL_QUOTE),
                claim("Invented claim.", "r1c1", "is a free service that also stores every account's billing history")), REQ);

        assertThat(v.grounded()).isFalse();
        assertThat(v.answer()).isEqualTo(GroundingVerifier.NOT_FOUND);
        assertThat(v.sources()).isEmpty();   // even the good claim is withheld: one bad quote rejects the answer
        assertThat(v.problems()).hasSize(1);
        assertThat(v.problems().get(0)).contains("claim 2").contains("not found in chunk r1c1");
        assertThat(v.modelSaidNotFound()).isFalse();
    }

    @Test
    void aRealQuoteCitedAgainstTheWrongChunkIsRejected() {
        GroundingVerifier.Verified v = verifier.verify(
                answer(claim("The tool reviews workloads.", "r1c3", TOOL_QUOTE)), REQ);   // quote belongs to r1c1

        assertThat(v.grounded()).isFalse();
        assertThat(v.problems().get(0)).contains("not found in chunk r1c3");
    }

    @Test
    void anUnknownRefOrOneFromAnotherRequestIsRejected() {
        assertThat(verifier.verify(answer(claim("x y z", "r9c9", TOOL_QUOTE)), REQ).problems().get(0))
                .contains("was not returned by retrieval in this request");
        assertThat(verifier.verify(answer(claim("The tool reviews.", "r1c1", TOOL_QUOTE)), "other-request").grounded())
                .isFalse();
    }

    @Test
    void caseAndPunctuationMustMatchExactly() {
        GroundingVerifier.Verified v = verifier.verify(answer(
                claim("Refunds are fast.", "r1c2", "REFUND REQUESTS ARE PROCESSED WITHIN 14 DAYS.")), REQ);

        assertThat(v.grounded()).isFalse();
    }

    @Test
    void aQuoteThatIsTooShortOrTooLongIsRejected() {
        assertThat(verifier.verify(answer(claim("The tool.", "r1c1", "AWS WA Tool")), REQ).problems().get(0))
                .contains("too short");
        assertThat(verifier.verify(answer(claim("Words.", "r1c1", "a service in the cloud")), REQ).problems().get(0))
                .contains("too short");   // 22 characters
        String tooLong = "lorem ipsum dolor sit amet ".repeat(20);   // 540 characters of words
        assertThat(verifier.verify(answer(claim("Words words.", "r1c1", tooLong)), REQ).problems().get(0))
                .contains("longer than");
    }

    // ---- rejected: numeric support --------------------------------------------------------------

    @Test
    void aNumberFromTheModelsMemoryIsRejectedEvenWithARealQuote() {
        // the quote is genuine and the chunk even contains "1,000", but the quote says nothing about it:
        // this is the OUT_AWS failure mode, a real excerpt next to a figure from the model's memory
        GroundingVerifier.Verified v = verifier.verify(answer(
                claim("The default is 1,000 concurrent executions.", "r1c2",
                        "Refund requests are processed within 14 days.")), REQ);

        assertThat(v.grounded()).isFalse();
        assertThat(v.problems().get(0)).contains("number(s) [1000]").contains("do not appear in its quote");
    }

    @Test
    void aDifferentNumberIsNotTheSameNumber() {
        assertThat(verifier.verify(answer(
                claim("Refunds take 30 days.", "r1c2", "Refund requests are processed within 14 days.")), REQ).grounded())
                .isFalse();
        // 1,000 is not 1,000,000
        assertThat(verifier.verify(answer(
                claim("Up to 1,000,000 ms.", "r1c2", "Customers can wait up to 1,000 ms for a response")), REQ).grounded())
                .isFalse();
    }

    @Test
    void numbersAreCanonicalised() {
        assertThat(GroundingVerifier.numbersIn("1,000 and 1000 and 14.0 and 09 and 3.25"))
                .containsExactlyInAnyOrder("1000", "14", "9", "3.25");
    }

    // ---- rejected: contract ---------------------------------------------------------------------

    @Test
    void notFoundFromTheModelIsPassedOnAndFlagged() {
        GroundingVerifier.Verified v = verifier.verify("{\"status\":\"NOT_FOUND\"}", REQ);

        assertThat(v.grounded()).isFalse();
        assertThat(v.answer()).isEqualTo(GroundingVerifier.NOT_FOUND);
        assertThat(v.modelSaidNotFound()).isTrue();
        assertThat(v.problems()).isEmpty();
    }

    @Test
    void anythingThatBreaksTheContractFailsClosed() {
        List<String> bad = List.of(
                "",
                "   ",
                "The Well-Architected Tool is a service.",              // prose only
                "{not json at all}",
                "{\"status\":\"ANSWERED\"}",                            // no claims
                "{\"status\":\"ANSWERED\",\"claims\":[]}",
                "{\"status\":\"MAYBE\",\"claims\":[]}",
                "{\"claims\":[]}",                                      // no status
                "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"a\",\"ref\":\"r1c1\"}]}",     // no quote
                "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"\",\"ref\":\"r1c1\",\"quote\":\"" + TOOL_QUOTE + "\"}]}",
                "{\"status\":\"ANSWERED\",\"claims\":\"not an array\"}");

        for (String output : bad) {
            GroundingVerifier.Verified v = verifier.verify(output, REQ);
            assertThat(v.grounded()).as("output: " + output).isFalse();
            assertThat(v.answer()).as("output: " + output).isEqualTo(GroundingVerifier.NOT_FOUND);
        }
        assertThat(verifier.verify(null, REQ).grounded()).isFalse();
    }

    @Test
    void tooManyClaimsAreRejected() {
        String[] claims = new String[GroundingVerifier.MAX_CLAIMS + 1];
        java.util.Arrays.fill(claims, claim("The tool provides a consistent review process.", "r1c1", TOOL_QUOTE));

        GroundingVerifier.Verified v = verifier.verify(answer(claims), REQ);

        assertThat(v.grounded()).isFalse();
        assertThat(v.problems().get(0)).contains("at most 6");
    }

    @Test
    void allProblemsAreReportedNotJustTheFirst() {
        GroundingVerifier.Verified v = verifier.verify(answer(
                claim("One.", "r9c9", TOOL_QUOTE),
                claim("Two items here.", "r1c1", "this sentence does not exist anywhere in the chunk")), REQ);

        assertThat(v.problems()).hasSize(2);
    }
}
