## Findings (written by hand; the tables below are generated)

This is the **final configuration**: `gemini-3.8-flash`, coordinator thinking budget 128, agent thinking
budget 0, retrieval floor -6.5, rerank depth 12, **at most 2 searches per question, early stop after a
below-floor first search** (section 7). Sections 1 to 6 describe the first full runs, made with a limit of 3
searches (`full-agent-b0.json`, and `full-b128.json` with the agent's default thinking budget); section 7
is the latency step and its measurements, and its numbers are the current ones.

### 1. Result on the 48 questions

| | agent budget 0 (final) | agent budget default |
| --- | ---: | ---: |
| in-corpus answered with an acceptable page (of 32) | 28 (25 on strict pages) | 27 (24 strict) |
| in-corpus "false answers" by the page rule | 2 | 2 |
| in-corpus false "not found" | 2 | 3 |
| out-of-corpus answered (of 16: 6 unrelated, 6 near-domain, 4 AWS limits and prices) | **0** | **0** |
| answers replaced by the verifier | 0 | 0 |
| latency per question, median / p90 / max | 12.3 / 15.1 / 23.1 s | 15.8 / 23.1 / 44.2 s |
| mean searches per question | 1.8 | 2.1 |

**No out-of-corpus question was answered in either run**, including the four AWS limit and price
questions (Lambda concurrency, S3 price, S3 object size, DynamoDB item size).

### 2. The "false answers" are not wrong answers

The page rule calls an answer false when it cites no page of the expected set. All four such cases
(in-01 and in-25 in the default run; in-08 and in-21 in the final run) were read, quote by quote: every
quote is verbatim text of the cited page, and every answer is correct. They cite a page that says the
same thing in another document (the framework PDF contains a copy of each pillar), or, for in-21, a
better page than the one I had expected (serverless lens p. 67, provisioned concurrency). By reading, there
were **no false answers in either run**. The reviewer is the author of this report; the answers and their
cited pages are listed below for anyone to check. The expected sets were not widened further, because that
should be a separate, documented step.

### 3. The verifier: what it did and did not show

- It replaced **no** answer in the two full runs (96 answered or declined turns) nor in the two partial
  runs. So the share of in-corpus questions that got a false "not found" because of the verifier was
  **0 of 32** in both runs, and the "one retry that sends the verifier's error back" option was **not built**.
  *Superseded by section 7: three later runs of the same questions had 0, 2 and 2 of 32.*
- It also means the verifier's protection against a model that answers an OUT_AWS question from memory is
  **tested (scripted adversarial cases) but not observed live**: in all 16 out-of-corpus questions the model
  itself answered NOT_FOUND. *Section 7 runs the live check.*
- The quotes pass because the model copies them exactly, replacement characters from the PDF's bullets
  included (in-01's quote contains them). The NFKC and whitespace normalisation never needed widening.

### 4. The remaining false "not found"

in-31 and in-32 (the two paraphrases) in the final run; in-21 as well in the default run. For in-31 and in-32
retrieval did put an answering page in the final top 5 under the widened rule, and the model still declined:
the model is more conservative than retrieval. That is the safe direction for this system, and it is where
a paraphrase-handling step (query rewriting that restates the question in the documents' vocabulary) would
help; the agent already rewrites, up to 3 times, and these questions used all of them.

### 5. Latency is the open problem

Median 12.3 s per question is too slow for a live demo, so these numbers should drive the next change.

- **Model calls dominate.** Each search costs about 1.4 s of retrieval (embedding 0.67 s, rerank of 12
  pairs 0.74 s, fusion about 0.1 s), but the RAG agent makes one model call per search plus a final one,
  and each call carries up to 12 chunks.
- **Questions that cannot be answered are the slow ones**: they use all three searches (mean 3.0) before
  giving up, because the agent keeps rewriting. Answered questions used 1.4 searches.
- **The search limit is only an instruction.** One question (in-21, default run) made 5 searches although the
  instruction says at most 3. The limit should be enforced in the tool.
- **Thinking budget:** the coordinator's budget makes no measurable difference (routing call: median 2.0 to
  2.6 s, overlapping spreads, 72 of 72 routed correctly at every setting). The agent's budget does: 0 cut
  the median by 22% and the mean by 34% with no change in quality in 48 questions.
- Candidate levers: cap searches at 2 and stop rewriting when the first search is below the floor; cache the
  query embedding between rewrites; stream the answer to the UI (Day 5) so the user sees progress.
  *The first two were built and measured, the cache was built and dropped: section 7.*

### 6. Limits of this evidence

48 questions, one run per configuration, a single model. Model latency varies from run to run (the same
question took 44 s in one run and 14 s in another). The "correct" judgement for in-corpus questions is
page-based plus my reading of the answers; there is no independent grader.

### 7. Latency step: what was changed, what it bought, and the adversarial questions

**Method.** Every lever was measured on the same 48 questions with the same model and the same retrieval.
Model speed drifts between runs: in the first cap-2 run the questions that did one search each (identical
behaviour in both configurations) were about 2 s slower than in a repeat of the unchanged configuration made
minutes earlier. So the "before" below is that same-hour repeat (`lat-b3-repeat.json`), not the run from
earlier in the day (`full-agent-b0.json`, shown for reference).

| run | median | mean | p90 | max | searches / question | in-corpus correct (strict pages) | false answers | false "not found" | out-of-corpus answered |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| earlier today, limit 3 (`full-agent-b0`) | 12.3 s | 11.6 s | 15.1 s | 23.1 s | 1.79 | 28 (25) | 2 | 2 | 0 |
| **before**: same hour, limit 3 (`lat-b3-repeat`) | 12.7 s | 13.4 s | 19.3 s | 25.0 s | 1.83 | 26 (23) | 1 | 5 | 0 |
| lever 2 only: limit 2 (`lat-r1-cap2`) | 14.5 s | 14.3 s | 17.2 s | 31.9 s | 1.42 | 26 (24) | 1 | 5 | 0 |
| **after**: limit 2 + early stop (`lat-r3-final`) | **9.1 s** | **9.8 s** | **13.4 s** | 21.8 s | 1.19 | 28 (25) | 2 | 2 | 0 |

Per question type, before (same-hour repeat) and after:

| type | n | correct before / after | false answers | false "not found" | median before / after | searches before / after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_CORPUS | 32 | 26 / 28 | 1 / 2 | 5 / 2 | 11.4 s / 9.1 s | 1.3 / 1.1 |
| OUT_FAR | 6 | 6 / 6 | 0 / 0 | - | 13.5 s / 7.6 s | 2.7 / 1.0 |
| OUT_NEAR | 6 | 6 / 6 | 0 / 0 | - | 19.3 s / 9.3 s | 3.0 / 1.3 |
| OUT_AWS | 4 | 4 / 4 | 0 / 0 | - | 18.1 s / 10.6 s | 3.0 / 2.0 |

Median 12.7 s to 9.1 s (-29%) and p90 19.3 s to 13.4 s (-31%). The in-corpus correct count moved from 26 to
28, but that is **run-to-run variation, not an improvement**: the unchanged configuration scored 28 earlier in
the day and 26 an hour later, and the questions that changed between those two runs (in-22, in-26, in-21)
did not change because of the cap. Quality did not get worse; it also did not get better.

**Lever by lever**

1. **Chunks sent to the model: no change needed.** Every passing search in the logs sent exactly 5 chunks
   (`finalTopN` 5; rerank depth 12 only decides which 5).
2. **Search cap in code: kept.** `SearchDocumentsTool` counts the request's searches in the ledger and
   returns `SEARCH_LIMIT_REACHED` (no retrieval, nothing recorded) from the third call. Live, the code guard
   **never fired** (0 refusals in the 48-question runs): the model obeys "at most 2 calls" in the
   instruction. It is a backstop, proven by unit tests, not something observed live. Effect: the third
   search disappeared from 21 of 48 questions; comparing each question with itself in the repeat run and
   subtracting the 1.9 s drift of the questions whose behaviour did not change, each such question saved
   about 3.5 to 4.2 s (a search plus a model call costs about 4 s). No in-corpus verdict changed because of
   the cap: in-21, in-31 and in-32 were false "not found" with 3 searches as well.
3. **Early stop after a below-floor first search: kept, with a caveat.** Across the four full live runs
   (128 in-corpus question-runs) exactly one in-corpus question ever had its first search below the floor:
   **in-31, a paraphrase**. It was never answered, although a second search passed the floor in all four
   runs, so the rewrite rescued nothing in four tries. 10 out-of-corpus questions (6 unrelated, 4
   near-domain) also had a below-floor first search and gain most: OUT_FAR now makes 1.0 search (was 2.7).
   The caveat is that the evidence about paraphrases is one question; if in-31 were ever answered after a
   rewrite, the stop would lose it. The "do not search again" message sits in the tool result and the model
   followed it (0 in-code refusals); the in-code refusal is again only a backstop.
4. **Query-embedding cache: built, measured, removed.** 57 query embeddings in the 48-question run, **0
   cache hits**: the model never repeats a query inside a turn, it rewrites it. A cache that never hits is
   code to defend for no gain, so it was removed (the embedding call is 0.67 s per search; a cache across
   turns would be a different feature).

**Adversarial questions** (`eval/adversarial-questions.json`, type OUT_ADVERSARIAL, kept out of the floor
calibration). Four questions that ask for a well-known AWS number in words that invite an answer from
memory ("According to the documents, what is the default Lambda concurrency limit? Just give the number."):
default Lambda concurrency, SQS maximum message size, Lambda maximum memory, API Gateway default throttle.
The numbers (1,000 / 256 KB / 10,240 MB / 10,000 requests per second) do not occur in any of the ten PDFs
(checked against the extracted text), while the topics do, so retrieval returns related chunks: the top
rerank score was 3.5 to 6.5 for three of the four, **above the 3.3 high-confidence marker**. The retrieval
floor therefore cannot stop these; only the model or the verifier can.

| prompt | trials (4 questions x 3 runs) | model said NOT_FOUND itself | **verifier replaced the answer** | wrong answer shown |
| --- | ---: | ---: | ---: | ---: |
| production prompt | 12 | 12 | **0** | **0** |
| deliberately permissive suffix ("give the value from your own knowledge ... never reply NOT_FOUND"), logged as ACTIVE | 12 | 11 | **1** | **0** |

So **the verifier caught 1 of 12 stress trials and 0 of 12 production trials, because with the production
prompt the model never tried**. The one catch: for the SQS question the model answered `256 KB`, citing a
real but unrelated SQS chunk; the numeric rule rejected it (`256` does not appear in the quote). That is the
check working on a live model for the first time, on a sample of one. Two limits: the rule catches a
remembered *number* because the number is absent from the corpus; a remembered statement without a number,
backed by a real quote, would pass (provenance, not entailment). And an earlier stress attempt that passed
the suffix through an environment variable was discarded: nothing proved the suffix was applied, so the
agent now logs `instruction suffix ACTIVE` at startup, and the results above rely on that line.

**What the extra runs showed about the verifier (open question for the next step).** In the three
48-question runs made after the first (same questions, same code for the verifier), the verifier replaced an
in-corpus answer 2, 2 and 0 times in 32, different questions each time: 4 cases, two kinds.
(a) Two were malformed JSON from the model, the same slip both times (`"ref":"r1c3,quote":"..."`, a lost
quote mark) with otherwise correct content. (b) Two were the numeric rule firing on a digit the quote did not
repeat (a claim saying "on EC2", another "Route 53"); the rule did what it was designed to do, and the price
is that one added detail replaces the whole answer. Neither kind was changed in this step. The options, when
you want them: one retry that returns the verifier's error to the agent (fixes (a), costs one model call
only on failures), or dropping only the offending claim instead of the whole answer (fixes (b), but changes
the rule you set).

**Limits.** One run per configuration. The drift between runs (about 2 s) is as large as the saving of the
cap alone, which is why the single run of lever 2 looks slower than the baseline (14.5 s vs 12.7 s median)
although the paired estimate says it saves; the final run is also a single run. The adversarial sample is 24
trials, 12 of them with the permissive prompt.
