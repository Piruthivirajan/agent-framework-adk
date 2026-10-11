## Findings (written by hand; the tables below are generated)

### What was compared

`$rankFusion` (reciprocal rank fusion, the configured default, `RANK`) against `$scoreFusion` (`SCORE`:
sigmoid-normalised scores, equal weights, average) on the same 48 questions, the same corpus, the same
embedding model and the same cross-encoder. Only `agentfw.retrieval.fusion` differs. This is
retrieval only: 48 query embeddings per run and no chat-model call. Both runs were made back to back
(`docs/fusion/rank.json`, `docs/fusion/score.json`). `JAVA_RRF` was not run: it is the same algorithm as
`RANK` computed in Java and reproduced the native scores exactly on the Day 1 data.

### Result (rerank depth 12 is the configured depth)

| answer page of the in-corpus questions (of 32) | RANK strict / widened | SCORE strict / widened |
| --- | ---: | ---: |
| in the fused top 20 | 29 / 31 | 28 / 29 |
| in the final top 5 (rerank depth 12, what the model sees) | 26 / 31 | 25 / 29 |
| in the final top 5 (rerank depth 20) | 26 / 31 | 26 / 29 |

**RANK is equal or better on every measure; the largest gap is one question strictly and two when
widened.** 22 of the 32 in-corpus questions have some difference in which pages reach the top 5 or at
which fused rank the answer page sits; most are the same page moving a place or the fifth passage being a
different one. The ones that matter:

- **in-04 "What is the AWS Well-Architected Tool?" is lost by SCORE**: its answer page is not in the
  fused top 20 at all (RANK: fused rank 3), so the final top 5 is five unrelated framework pages. This
  is the scenario A headline question.
- **in-31 (the paraphrase about staging servers) is lost by SCORE when widened** (RANK: the equivalent
  page at fused rank 3). It is a question that is not answered by either strategy under the strict pages.
- **in-11 "What is the principle of least privilege?" is gained by SCORE** (fused rank 17; RANK misses it
  in the fused top 20), but only at depth 20: at depth 12 it still does not reach the top 5.
- The answer page moves down a rank or two for in-03, in-07, in-14, in-20, in-25, in-27, in-29 and up for
  in-09 and in-10, none of which changes whether it reaches the top 5.

### What it changes for the grounding floor

Nothing material. The highest top-1 score of the out-of-corpus questions is about the same (AWS limits and
prices 1.14 in both; near-domain 3.17 vs 2.57; unrelated -8.15 vs -10.80) and the two score groups still
overlap in both runs ("groups separable: no"), so the design in `docs/CALIBRATION.md` (a low floor, the
answering step decides) holds for either strategy.

### Decision

**Keep `RANK`.** It is not worse anywhere at the configured depth, and reciprocal rank fusion needs no
score scale or weights to be right. The README states this as a measured choice with its limits.

### Limits of this comparison

- **32 in-corpus questions and one run of each.** One or two questions decide the difference; this is
  evidence that `$scoreFusion` is not better here, not proof that `$rankFusion` is better. Retrieval is
  deterministic (47 of 48 questions had identical scores between two earlier runs of the same pipeline).
- **`SCORE` was run with its defaults** (sigmoid normalisation, equal weights 1.0 and 1.0). BM25 and
  cosine scores are on different scales and the weights were not tuned; a tuned `$scoreFusion` could do
  better. Tuning it on 48 questions would risk fitting the set.
- **The widened pages favour RANK.** They were added after reading the pages the `RANK` pipeline
  returned (see `docs/CALIBRATION.md` §2), so the widened columns are optimistic for RANK by construction.
  The strict columns were fixed in advance and are the fair comparison: 29 vs 28 in the fused top 20 and
  26 vs 25 in the final top 5.
- **Latency:** mean rerank time per question was 1.9 s (RANK) and 2.1 s (SCORE) at depth 20 on the
  development laptop; both measure the same cross-encoder on the same number of pairs, so that
  difference is noise, not a property of the strategy.
