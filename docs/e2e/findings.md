## Findings (written by hand; the tables below are generated)

This is the **final configuration**: `gemini-3.8-flash`, coordinator thinking budget 128, agent thinking
budget 0, retrieval floor -6.5, rerank depth 12. A second full run with the agent's thinking budget at
its default is saved as `docs/e2e/full-b128.json` and compared below.

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
  runs. So the share of in-corpus questions that got a false "not found" because of the verifier is
  **0 of 32** in both runs, and the "one retry that sends the verifier's error back" option was **not built**:
  there is nothing for it to recover.
- It also means the verifier's protection against a model that answers an OUT_AWS question from memory is
  **tested (scripted adversarial cases) but not observed live**: in all 16 out-of-corpus questions the model
  itself answered NOT_FOUND. A live check would need a prompt that tempts the model, for example a user
  message that instructs it to answer from its own knowledge; that has not been run.
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
- Candidate levers, not yet built: cap searches at 2 and stop rewriting when the first search is below the
  floor; cache the query embedding between rewrites; stream the answer to the UI (Day 5) so the user sees
  progress.

### 6. Limits of this evidence

48 questions, one run per configuration, a single model. Model latency varies from run to run (the same
question took 44 s in one run and 14 s in another). The "correct" judgement for in-corpus questions is
page-based plus my reading of the answers; there is no independent grader.
