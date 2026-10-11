## Findings (written by hand; the tables below are generated)

### 1. Grounding design: a low retrieval floor, and the answering step decides

The scores of the MS MARCO cross-encoder cannot separate answerable from unanswerable questions with
one number (the groups overlap: in-corpus lowest -6.38, out-of-corpus highest 3.17), because short
definitional questions ("what is mechanical sympathy") score low even when the right page is retrieved.
So retrieval does not try to be the grounding judge. Two things are set from the saved numbers:

- **Floor `min-rerank-score` = -6.5.** The lowest top-1 score of any of the 32 in-corpus questions is
  -6.38 (in-31, a paraphrase; next: in-17 -6.23, in-32 -4.16, in-21 -3.52). The floor is that value
  rounded down to the next half point, so it cuts **no in-corpus question** of the set (0 of 32) and
  removes only clearly unrelated queries. Effect on the 16 out-of-corpus questions: **9 are rejected at
  retrieval** (all 6 unrelated ones, plus PostgreSQL tuning -8.39, GKE -7.79 and sysctl -6.52, the last
  by only 0.02, so it should not be relied on) and **7 pass on** to the answering step: Helm -5.23,
  Azure peering 2.57, Google Cloud Architecture Framework 3.17, and all 4 AWS limit/price questions
  (-2.06, -1.56, -0.86, 1.14). The floor is therefore not a defence against the AWS-specific
  unanswerable questions; the citation-and-quote check in the answering step is.
- **`high-confidence-score` = 3.3, trace only.** The balanced-accuracy optimum of the baseline. 26 of
  the 32 in-corpus questions reach it and none of the 16 out-of-corpus ones do (the closest is 3.17).
  The trace marks such answers; no behaviour depends on it.

Limit of this derivation: the floor is taken from the same 32 questions it is checked on, and the
margin to the lowest one is 0.12 logits. A new in-corpus question that scores below -6.5 would be
cut as "not found". Scores were reproducible between two runs of the same pipeline (47 of 48
questions identical), so the margin is not fighting noise, but the sample is small.

### 2. Strict and widened expected pages

`expected` in the question file is the **strict** set: pages containing the question's evidence phrase,
fixed before the first run. After the first runs, `expectedWidened` was added with the reason beside each
page (`widenedNotes` in the question file):

1. **Adjacent**, for all 32 in-corpus questions by the same rule: the page before and after a strictly
   expected page (an answer can continue across a page break; for example in-06, where the retry best
   practice continues on the next page).
2. **Alternate evidence**, for 4 questions: a quoted sentence that states the answer in other words,
   accepted on every page where it appears (framework and pillar copies). in-10 (secrets rotation):
   "centrally stored, managed, and rotated on a regular basis" and "Store credentials in AWS Secrets
   Manager and establish automated rotation". in-11 (least privilege): "The principle of least
   privilege states that identities should only be permitted to perform the smallest set of actions
   necessary". in-31 (staging servers overnight): "When environments are not in use, turn them off to
   avoid costs associated with idle resources". in-32 (self-healing): "Upon detection of a failure, use
   automated capabilities to perform actions to remediate". No page was added for in-21 (cold starts):
   none of the returned pages states a remedy.

| final run, rerank depth 12 | strict | widened |
| --- | ---: | ---: |
| answer page in the fused top 20 (of 32) | 29 | 31 |
| answer page in the final top 5 (of 32) | 26 | 31 |
| paraphrased questions in the final top 5 (of 2) | 0 | 2 |

The only question still missed when widened is in-21. **Caveat:** the alternate-evidence quotes were
chosen after reading the pages that retrieval returned, so the widened numbers are optimistic by
construction; the strict numbers are the ones fixed in advance. Both are reported everywhere.

### 3. Header/footer stripping was tried and is not kept

The rule was to keep it only if recall improved. It did not, so the stripper was removed (commit history
keeps it). The pipeline-version fingerprint it motivated stays.

**What it did.** It removed exactly the repeated header lines: one pattern per document ("Security
Pillar AWS Well-Architected Framework" and its siblings, on 98 to 100% of pages) and three in the
archived microservices PDF ("Archived", the title line, the page number). Chunks went from 2,353 to
2,350. Footers that carry the section name ("COST01-BP03 Establish cloud budgets and forecasts 16")
are not repeated on most pages, so the "repeated on most pages" rule leaves them alone.

**What changed**, over the same 32 in-corpus questions and the same strict expected pages in both runs:

- Answer page in the fused top 20 and top 12: 29 and 28 in both runs. At fused rank 1: 16 in both.
- Answer page in the final top 5: **26 to 24**. Lost: in-08 and in-13. Gained: none.
- Both losses are swaps between near-duplicate pages. The framework document holds a copy of every
  pillar's best practices, and the design-principle text of in-13 spans two adjacent pages, so a strict
  single-page match is noisy at this size. Four questions improved in fused rank (in-02 6 to 3,
  in-03 3 to 2, in-04 2 to 1, in-30 3 to 1) and others got worse (in-01 2 to 4, in-13 2 to 6,
  in-26 1 to 3); no pattern favours stripping.
- The two paraphrased questions are missed in both runs under the strict rule.

**Reading.** A running header is in every chunk of its document, so a word-based index gives it almost
no weight (its inverse document frequency inside that document is near zero) and the cross-encoder
largely ignores it. That is a hypothesis; it was not tested on its own.

**Limits.** 32 in-corpus questions, one run each, strict page-level matching. A difference of two
questions is inside that noise; the finding is "no evidence of improvement", not "stripping hurts".

### 4. Other observations

- **Depth 12 holds.** The answer page is in the final top 5 for the same questions at depth 12 and at
  depth 20, strict and widened. `rerank-depth` is 12 (40% fewer pairs scored).
- **Strict matching undercounts**, as section 2 shows (26 to 31 of 32 once equivalent pages count).
- **Reproducibility.** The final run (same pipeline as the baseline, index rebuilt by a re-ingest) matches
  the baseline on 47 of 48 questions to three decimals; in-13 differs only in ranks 13 to 20 of the
  candidate pool (approximate nearest-neighbour search), with the same final top 5.
- **What retrieval cannot do alone.** The AWS limit and price questions are not stopped at retrieval, and
  the paraphrases are only found under the widened rule. Both are handled in the answering step
  (query rewriting, citation and quote verification); see Day 4 in PROGRESS.md.
