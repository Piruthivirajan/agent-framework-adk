package com.demo.agentfw.spike;

import com.demo.agentfw.ingestion.TestPdfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes two clearly labelled placeholder PDFs for the Day 2 smoke run. They are NOT the real
 * corpus: every page carries a banner saying so, and they live in {@code corpus-sample/}, away
 * from the real {@code corpus/} folder, so they can never leak into retrieval answers.
 *
 * <p>Run: {@code ./mvnw test-compile exec:java -Dexec.mainClass=com.demo.agentfw.spike.SamplePdfGenerator
 * -Dexec.classpathScope=test -Dexec.args=corpus-sample}
 */
public class SamplePdfGenerator {

    private static final String BANNER =
            "[SAMPLE PLACEHOLDER DOCUMENT - generated for pipeline testing - NOT part of the real corpus]\n\n";

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "corpus-sample");
        Files.createDirectories(dir);

        Files.write(dir.resolve("sample-refund-policy.pdf"), TestPdfs.create(
                BANNER + "Refund Policy (fictional company)\n\n"
                        + "Refund requests are processed within 14 days of approval. "
                        + "The refund is returned to the original payment method. "
                        + "Customers receive an email confirmation when the refund is issued.",

                BANNER + "Eligibility and process\n\n"
                        + paragraph("A request is eligible when it is submitted within 30 days of purchase, "
                        + "the item is unused, and the order number is provided. Digital goods are eligible "
                        + "only if they have not been downloaded. ")
                        + "\n\n"
                        + paragraph("After a request is received, the finance team reviews it within three "
                        + "business days. Approved requests are queued for payment. Rejected requests receive "
                        + "an explanation and may be appealed once within 10 days. ")
                        + "\n\n"
                        + paragraph("Partial refunds apply when only part of an order is returned. Shipping "
                        + "costs are refunded only when the return is caused by an error on our side. "
                        + "Chargebacks opened with a bank are handled separately and may take longer. "),

                BANNER + "Exceptions\n\n"
                        + "Contact support at support@example.invalid for exceptions to this policy."));

        Files.write(dir.resolve("sample-api-errors.pdf"), TestPdfs.create(
                BANNER + "API Error Codes (fictional service)\n\n"
                        + "Error code E-401 means the token has expired. Request a new token and retry.\n"
                        + "Error code E-403 means the token is valid but the caller lacks permission.\n"
                        + "Error code E-429 means the rate limit was exceeded. Wait and retry with backoff.\n"
                        + "Error code E-503 means the service is temporarily unavailable.",

                BANNER + "Billing notes\n\n"
                        + "Invoices are emailed on the first business day of each month. "
                        + "Invoices can be downloaded as PDF from the billing page for 24 months."));

        System.out.println("Wrote placeholder PDFs to " + dir.toAbsolutePath());
    }

    private static String paragraph(String sentences) {
        return sentences.repeat(5).strip();   // page 2 is about 3,300 chars, so it splits into 2 chunks
    }
}
