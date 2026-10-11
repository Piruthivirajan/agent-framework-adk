## Findings (written by hand; the tables below are generated)

### Decision: header/footer stripping is not kept

The rule was to keep it only if recall improved. It did not, so the stripper was removed from the
pipeline (commit history keeps it). The pipeline-version fingerprint it motivated stays.

**What it did.** It removed exactly the repeated header lines: one pattern per document ("Security
Pillar AWS Well-Architected Framework" and its siblings, on 98 to 100% of pages) and three in the
archived microservices PDF ("Archived", the title line, the page number). Chunks went from 2,353 to
2,350. Footers that carry the section name ("COST01-BP03 Establish cloud budgets and forecasts 16")
are not repeated on most pages, so the "repeated on most pages" rule leaves them alone.

**What changed**, over the same 32 in-corpus questions and the same expected pages in both runs:

- Answer page in the fused top 20 and top 12: 29 and 28 in both runs. At fused rank 1: 16 in both.
- Answer page in the final top 5: **26 to 24**. Lost: in-08 and in-13. Gained: none.
- Both losses are swaps between near-duplicate pages. The framework document holds a copy of every
  pillar's best practices, and the design-principle text of in-13 spans two adjacent pages, so a strict
  single-page match is noisy at this size. Four questions improved in fused rank (in-02 6 to 3,
  in-03 3 to 2, in-04 2 to 1, in-30 3 to 1) and others got worse (in-01 2 to 4, in-13 2 to 6,
  in-26 1 to 3); no pattern favours stripping.
- The two paraphrased questions are missed in both runs (0 of 2).

**Reading.** A running header is in every chunk of its document, so a word-based index gives it almost
no weight (its inverse document frequency inside that document is near zero) and the cross-encoder
largely ignores it. That is a hypothesis; it was not tested on its own.

**Limits.** 32 in-corpus questions, one run each, strict page-level matching, deterministic models but
no repeated runs. A difference of two questions is inside that noise; the finding is "no evidence of
improvement", not "stripping hurts".

### What the baseline says about the grounding gate

- **Depth 12 holds.** The answer page is in the final top 5 for 26 of 32 questions at depth 12 and at
  depth 20, and no question loses it. `rerank-depth` is set to 12 (40% fewer pairs scored).
- **The score groups overlap, so no threshold is error-free.** Best balanced accuracy on the
  calibration split is at about 3.3: it rejects every out-of-corpus question on that split but also
  rejects five in-corpus ones (in-03, in-17, in-21, in-25, in-31). On the held-out questions 10 of 11
  are classified correctly (in-32, a paraphrase, is wrongly rejected).
- **Why they overlap.** Two of those five (in-03 "one-way door", in-17 "mechanical sympathy") were
  retrieved correctly, with the answer page in the final top 5, yet score very low: the MS MARCO
  cross-encoder gives short definitional "what is X" questions low logits. An absolute score
  threshold is a weak signal for that question type.
- **The hardest out-of-corpus questions** are the confusable ones: "pillars of the Google Cloud
  Architecture Framework" (3.17 in the baseline) and Azure network peering (2.57). Among the
  AWS-specific unanswerable questions only Lambda default concurrency scores clearly above the rest (1.14);
  the S3 and DynamoDB limit and price questions score between -0.86 and -2.06.
- **Strict matching undercounts.** Several "misses" return an adjacent or equivalent page that does
  answer the question (in-06 the next page of the same retry best practice, in-10 the framework's
  Secrets Manager rotation page, in-11 a security-pillar page that defines least privilege better
  than the expected pages). The expected pages were fixed before the first run and are unchanged, so
  both runs are scored identically; widening them after seeing results would be fitting to the test.
- **No threshold is set yet.** `agentfw.retrieval.min-rerank-score` stays unset (the gate reports
  `NOT_CALIBRATED`) until the trade-off between false "not found" and unsupported answers is chosen.
