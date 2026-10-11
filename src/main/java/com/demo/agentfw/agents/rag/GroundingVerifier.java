package com.demo.agentfw.agents.rag;

import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the RAG agent's answer in plain code, after the model and without any model call. The
 * answer is only shown if every claim passes; otherwise the whole answer is replaced by
 * {@link #NOT_FOUND}.
 *
 * <p>A claim passes when all of this holds:
 * <ol>
 *   <li>its {@code ref} names a chunk that retrieval really returned in this request;
 *   <li>its {@code quote} is long enough to mean something, and occurs verbatim in that chunk's text
 *       (Unicode NFKC and whitespace normalised, case and punctuation exact);
 *   <li>every number in the claim's {@code text} also occurs in its quote, with number formats
 *       normalised (1,000 equals 1000).
 * </ol>
 *
 * <p>What this guarantees is provenance: the text on screen is built from sentences whose quotes
 * exist in retrieved documents, and file and page come from the ledger, not from the model. What it
 * cannot do is prove that a quote entails a claim; the numeric rule closes the most dangerous gap (a
 * real but unrelated quote next to a figure from the model's own memory), not every gap.
 */
@Component
public class GroundingVerifier {

    public static final String NOT_FOUND = "Not found in the reference documents.";

    static final int MIN_QUOTE_CHARS = 25;
    static final int MIN_QUOTE_WORDS = 4;
    static final int MAX_QUOTE_CHARS = 400;
    static final int MAX_CLAIMS = 6;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** A supporting quote shown next to the answer. File and page come from the ledger. */
    public record Source(String ref, String file, int page, String quote) {
    }

    /**
     * @param grounded true when the answer is shown; false when it was replaced by {@link #NOT_FOUND}
     * @param answer   the text for the user: the verified claims with citations, or {@link #NOT_FOUND}
     * @param sources  the quotes behind the answer, in claim order without duplicates
     * @param problems why the answer was replaced; empty when grounded or when the model itself said NOT_FOUND
     * @param modelSaidNotFound true when the model answered NOT_FOUND on its own (not a verification failure)
     */
    public record Verified(boolean grounded, String answer, List<Source> sources, List<String> problems,
                           boolean modelSaidNotFound) {
    }

    private record RawClaim(String text, String ref, String quote) {
    }

    private record RawAnswer(String status, List<RawClaim> claims) {
    }

    private final RetrievalLedger ledger;

    public GroundingVerifier(RetrievalLedger ledger) {
        this.ledger = ledger;
    }

    public Verified verify(String modelOutput, String requestId) {
        Optional<RawAnswer> parsed = parse(modelOutput);
        if (parsed.isEmpty()) {
            return rejected("the model output is not the JSON object of the contract");
        }
        RawAnswer answer = parsed.get();
        String status = answer.status() == null ? "" : answer.status().strip();
        if ("NOT_FOUND".equals(status)) {
            return new Verified(false, NOT_FOUND, List.of(), List.of(), true);
        }
        if (!"ANSWERED".equals(status)) {
            return rejected("unknown status '" + status + "'");
        }
        List<RawClaim> claims = answer.claims();
        if (claims == null || claims.isEmpty()) {
            return rejected("status ANSWERED without any claim");
        }
        if (claims.size() > MAX_CLAIMS) {
            return rejected(claims.size() + " claims, at most " + MAX_CLAIMS + " are allowed");
        }

        List<String> problems = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Set<Source> sources = new LinkedHashSet<>();
        for (int i = 0; i < claims.size(); i++) {
            String label = "claim " + (i + 1);
            RawClaim claim = claims.get(i);
            if (claim == null || blank(claim.text()) || blank(claim.ref()) || blank(claim.quote())) {
                problems.add(label + ": text, ref and quote are all required");
                continue;
            }
            Optional<RetrievalLedger.Entry> chunk = ledger.chunk(requestId, claim.ref().strip());
            if (chunk.isEmpty()) {
                problems.add(label + ": ref '" + claim.ref().strip() + "' was not returned by retrieval in this request");
                continue;
            }
            String quote = normalize(claim.quote());
            if (quote.length() < MIN_QUOTE_CHARS || wordCount(quote) < MIN_QUOTE_WORDS) {
                problems.add(label + ": quote is too short to support a statement");
                continue;
            }
            if (quote.length() > MAX_QUOTE_CHARS) {
                problems.add(label + ": quote is longer than " + MAX_QUOTE_CHARS + " characters");
                continue;
            }
            if (!normalize(chunk.get().text()).contains(quote)) {
                problems.add(label + ": quote not found in chunk " + claim.ref().strip());
                continue;
            }
            Set<String> missing = numbersIn(claim.text());
            missing.removeAll(numbersIn(claim.quote()));
            if (!missing.isEmpty()) {
                problems.add(label + ": number(s) " + missing + " in the claim do not appear in its quote");
                continue;
            }
            RetrievalLedger.Entry e = chunk.get();
            lines.add(claim.text().strip() + " [" + e.file() + ", p. " + e.page() + "]");
            sources.add(new Source(e.ref(), e.file(), e.page(), claim.quote().strip()));
        }
        if (!problems.isEmpty()) {
            return new Verified(false, NOT_FOUND, List.of(), List.copyOf(problems), false);
        }
        return new Verified(true, String.join("\n", lines), List.copyOf(sources), List.of(), false);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static Verified rejected(String problem) {
        return new Verified(false, NOT_FOUND, List.of(), List.of(problem), false);
    }

    /** The JSON object between the first '{' and the last '}'; text around it is discarded, never shown. */
    private static Optional<RawAnswer> parse(String output) {
        if (output == null) {
            return Optional.empty();
        }
        int from = output.indexOf('{');
        int to = output.lastIndexOf('}');
        if (from < 0 || to <= from) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(MAPPER.readValue(output.substring(from, to + 1), RawAnswer.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** NFKC (ligatures, full-width forms, non-breaking spaces) and all whitespace runs collapsed to one space. */
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
    }

    /** Numbers as canonical strings: "1,000" and "1000" are the same, "14.0" and "14" are the same. */
    static Set<String> numbersIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (m.find()) {
            out.add(new BigDecimal(m.group().replace(",", "")).stripTrailingZeros().toPlainString());
        }
        return out;
    }

    private static int wordCount(String text) {
        return text.isEmpty() ? 0 : text.split(" ").length;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
