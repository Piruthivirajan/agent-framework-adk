# End-to-end results: lat-r3-final

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

Generated 2026-10-11T10:23:29.574138700Z. Model `gemini-3.8-flash`, coordinator thinking budget 128, agent thinking budget 0. Every question went through the real coordinator, RAG agent, retrieval and verifier.

## Per question type

In-corpus: **correct** = answered and cited an acceptable page (strict and widened rules both shown); **false answer** = answered but cited no acceptable page; **false "not found"** = not answered. Out-of-corpus: **correct** = not found; **false answer** = any answer.

| type | n | correct | of which strict pages | false answers | false "not found" | errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IN_CORPUS | 32 | 28 | 25 | 2 | 2 | 0 |
| OUT_FAR | 6 | 6 | - | 0 | 0 | 0 |
| OUT_NEAR | 6 | 6 | - | 0 | 0 | 0 |
| OUT_AWS | 4 | 4 | - | 0 | 0 | 0 |

## Why questions were not answered

| reason | questions |
| --- | ---: |
| retrieval floor (nothing relevant retrieved) | 11 |
| the model itself answered NOT_FOUND | 7 |
| **the verifier replaced the answer** | 0 |
| no tool was called | 0 |

**False "not found" caused by the verifier: 0 of 32 in-corpus questions (0.0%).** This is the number that decides whether the one-retry option is worth testing.

## False "not found" (answerable questions that got no answer)

| id | reason | best rerank score | searches | question |
| --- | --- | ---: | ---: | --- |
| in-31 | retrieval-floor | -8.16 | 1 | Why shouldn't staging servers keep running overnight and on weekends? |
| in-32 | model | -5.32 | 2 | How can a platform heal itself without anyone being paged? |

## False answers (an answer that should not exist, or cites no acceptable page)

- **in-08** (IN_CORPUS) How does the reliability pillar suggest recovering automatically from failures?
  - answer: When a failure is detected, automated capabilities should be used to take actions to remediate. [wellarchitected-reliability-pillar.pdf, p. 197] / Degradations can heal automatically through internal service mechanisms or by restarting or removing resources using remediation actions. [wellarchitected-reliability-pillar.pdf, p. 197] / In cloud environments, an entire resource such as a compute instance or serverless function can and generally should be replaced when restarting. [wellarchitected-r…
  - cited: [wellarchitected-reliability-pillar.pdf p. 197]
- **in-21** (IN_CORPUS) What can be considered to reduce the impact of Lambda cold starts?
  - answer: Provisioned concurrency initializes a requested number of execution environments so that they can respond immediately to function invocations. [wellarchitected-serverless-applications-lens.pdf, p. 67] / To enable a Lambda function to scale without fluctuations in latency, provisioned concurrency can be used. [wellarchitected-serverless-applications-lens.pdf, p. 67] / Allocating provisioned concurrency before invocations increase ensures requests are handled by initialized instances with very low…
  - cited: [wellarchitected-serverless-applications-lens.pdf p. 67]

## Latency per question (ms, whole turn: routing, searches, model calls, verification)

| set | n | median | mean | p90 | max | mean searches |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| all | 48 | 9098 | 9763 | 13377 | 21811 | 1.2 |
| IN_CORPUS | 32 | 9098 | 9893 | 13214 | 16611 | 1.1 |
| OUT_FAR | 6 | 7593 | 7427 | 7892 | 9208 | 1.0 |
| OUT_NEAR | 6 | 9339 | 9303 | 9930 | 13377 | 1.3 |
| OUT_AWS | 4 | 10558 | 12917 | 10558 | 21811 | 2.0 |
| answered | 30 | 9098 | 9880 | 13703 | 16611 | 1.1 |
| not answered | 18 | 9208 | 9569 | 11325 | 21811 | 1.4 |

## Every question and answer

| id | type | verdict | strict | cited | ms | answer or reason |
| --- | --- | --- | :---: | --- | ---: | --- |
| in-01 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 13 | 9901 | The AWS Well-Architected Framework is based on six pillars: operational excellence, security, reliability, performance efficiency, cost optimization, and sustainability. [wellarchitected-framework.pdf, p. 13] |
| in-02 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 11; wellarchitected-reliability-pillar.pdf p. 13 | 11678 | A poor capacity decision can result in paying for expensive idle resources or suffering the performance consequences of limited capacity. [wellarchitected-framework.pdf, p. 11] / In cloud computing, users can use exactly as much capacity as needed and scale automatically in and out. [wellarchitected-framework.pdf, p. 11] / Resource saturation, which occurs when demands placed on a workload exceed its capacity, is a common reason for workload failure on premises. [wellarchitected-reliability-pillar.pdf, p. 13] / Cloud environments allow monitoring of demand and workload utilization, as well as … |
| in-03 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 69; wellarchitected-operational-excellence-pillar.pdf p. 26 | 10476 | Two-way door decisions are reversible and can utilize a lightweight process. [wellarchitected-framework.pdf, p. 69] / One-way door decisions are difficult or impossible to reverse and require greater inspection prior to being made. [wellarchitected-framework.pdf, p. 69] / Organizations should centralize one-way door decisions that could be irreversible. [wellarchitected-operational-excellence-pillar.pdf, p. 26] / Two-way door decisions can be permitted to be made by lower level organizational leaders. [wellarchitected-operational-excellence-pillar.pdf, p. 26] |
| in-04 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 7; wellarchitected-operational-excellence-pillar.pdf p. 12 | 11701 | The AWS Well-Architected Tool is a service in the cloud that provides a consistent process for reviewing and measuring architectures using the AWS Well-Architected Framework. [wellarchitected-framework.pdf, p. 7] / The service is provided by AWS for reviewing workloads at no charge. [wellarchitected-framework.pdf, p. 7] / The AWS Well-Architected Tool delivers recommendations to make workloads more reliable, secure, efficient, and cost-effective. [wellarchitected-framework.pdf, p. 7] / The tool helps users review their approach before development, the state of workloads before production, and … |
| in-05 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 680; disaster-recovery-workloads-on-aws.pdf p. 13; wellarchitected-reliability-pillar.pdf p. 19 | 12334 | Recovery Time Objective (RTO) represents the maximum acceptable delay between a service interruption and the restoration of that service. [wellarchitected-framework.pdf, p. 680] / RTO determines what an organization defines as an acceptable time window during which a service remains unavailable. [disaster-recovery-workloads-on-aws.pdf, p. 13] / Unlike Mean Time to Recovery (MTTR), which represents a mean value across multiple events over time, RTO is a target or maximum allowed value for a single event that impacts availability. [wellarchitected-reliability-pillar.pdf, p. 19] |
| in-06 | IN_CORPUS | CORRECT | no | wellarchitected-framework.pdf p. 545 | 13214 | Exponential backoff should be used to retry requests after progressively longer intervals. [wellarchitected-framework.pdf, p. 545] / Jitter should be introduced to randomize retry intervals. [wellarchitected-framework.pdf, p. 545] / Jitter prevents retries from aligning into request spikes, while exponential backoff reduces load escalation. [wellarchitected-framework.pdf, p. 545] / A maximum number of retries or elapsed time should be configured to prevent metastable failures caused by backlogs. [wellarchitected-framework.pdf, p. 545] / Retries should only be implemented at one level of the ap… |
| in-07 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 470; wellarchitected-reliability-pillar.pdf p. 24 | 9098 | Being aware of service quotas and resource constraints allows customers to prevent service degradation or disruption in their AWS accounts. [wellarchitected-framework.pdf, p. 470] / Proactively monitoring and automatically managing service quotas and resource constraints can help reduce failures. [wellarchitected-framework.pdf, p. 470] / If a constraint is reached unexpectedly, an account's applications or services can suffer degradation or disruption. [wellarchitected-reliability-pillar.pdf, p. 24] |
| in-08 | IN_CORPUS | FALSE_ANSWER | no | wellarchitected-reliability-pillar.pdf p. 197 | 16611 | When a failure is detected, automated capabilities should be used to take actions to remediate. [wellarchitected-reliability-pillar.pdf, p. 197] / Degradations can heal automatically through internal service mechanisms or by restarting or removing resources using remediation actions. [wellarchitected-reliability-pillar.pdf, p. 197] / In cloud environments, an entire resource such as a compute instance or serverless function can and generally should be replaced when restarting. [wellarchitected-reliability-pillar.pdf, p. 197] / A retry strategy using limited retries with exponential backoff and… |
| in-09 | IN_CORPUS | CORRECT | yes | wellarchitected-security-pillar.pdf p. 9; wellarchitected-security-pillar.pdf p. 130 | 14794 | Security and compliance is a shared responsibility between AWS and the customer. [wellarchitected-security-pillar.pdf, p. 9] / AWS is responsible for protecting the infrastructure—including hardware, software, networking, and facilities—that runs all services offered in the AWS Cloud. [wellarchitected-security-pillar.pdf, p. 9] / AWS manages, operates, and controls components from the host operating system and virtualization layer down to the physical security of the service facilities. [wellarchitected-security-pillar.pdf, p. 9] / Customer responsibility is determined by the specific AWS Clou… |
| in-10 | IN_CORPUS | CORRECT | no | wellarchitected-security-pillar.pdf p. 49; wellarchitected-framework.pdf p. 300; wellarchitected-framework.pdf p. 301 | 14093 | The recommended approach to secrets management is to adhere to the guidance of remove, replace, and rotate. [wellarchitected-security-pillar.pdf, p. 49] / Credentials that are no longer necessary for workload functioning should be safely removed. [wellarchitected-security-pillar.pdf, p. 49] / Whenever possible, long-term credentials should be replaced with temporary or short-term credentials, such as using IAM roles instead of hard-coding an AWS secret access key. [wellarchitected-security-pillar.pdf, p. 49] / Long-lived secrets that cannot be removed or replaced can be centrally stored, manag… |
| in-11 | IN_CORPUS | CORRECT | no | wellarchitected-framework.pdf p. 319 | 13703 | The principle of least privilege states that identities should only be allowed to perform the smallest set of actions required to carry out a specific task. [wellarchitected-framework.pdf, p. 319] / This principle balances usability, efficiency, and security. [wellarchitected-framework.pdf, p. 319] / Operating under the principle of least privilege helps limit unintended access and assists in tracking who has access to specific resources. [wellarchitected-framework.pdf, p. 319] |
| in-12 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 399 | 10748 | Secure key management encompasses the storage, rotation, access control, and monitoring of key material needed to protect data at rest for a workload. [wellarchitected-framework.pdf, p. 399] |
| in-13 | IN_CORPUS | CORRECT | yes | wellarchitected-operational-excellence-pillar.pdf p. 9; wellarchitected-operational-excellence-pillar.pdf p. 82 | 8722 | The operational excellence pillar recommends making frequent, small, and reversible changes to workloads. [wellarchitected-operational-excellence-pillar.pdf, p. 9] / Using automated deployment techniques alongside smaller, incremental changes lowers the blast radius and enables faster reversal if failures occur. [wellarchitected-operational-excellence-pillar.pdf, p. 9] / Emphasizing frequent, small, and reversible changes limits the scope of change, enabling quicker troubleshooting, remediation, and the ability to roll back. [wellarchitected-operational-excellence-pillar.pdf, p. 82] |
| in-14 | IN_CORPUS | CORRECT | yes | wellarchitected-operational-excellence-pillar.pdf p. 141; wellarchitected-framework.pdf p. 592 | 8394 | Runbooks are documented processes used to achieve a specific outcome. [wellarchitected-operational-excellence-pillar.pdf, p. 141] / In cloud operations, runbooks are used to reduce risk and achieve desired outcomes. [wellarchitected-operational-excellence-pillar.pdf, p. 141] / Runbooks are often used for routine activities. [wellarchitected-framework.pdf, p. 592] / Runbooks are also useful for running game days or fulfilling rigorous reporting and auditing requirements. [wellarchitected-framework.pdf, p. 592] / Documenting procedures in runbooks is used to provide consistent and prompt respons… |
| in-15 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 128; wellarchitected-operational-excellence-pillar.pdf p. 133 | 8302 | Feature flags and A/B testing can be used to safely conduct experiments and gather user feedback. [wellarchitected-framework.pdf, p. 128] / AWS AppConfig Feature Flags provides the capability to create feature flags. [wellarchitected-framework.pdf, p. 128] / A/B testing is utilized for new features to validate them with real user feedback. [wellarchitected-framework.pdf, p. 128] / Feature-flag testing allows an individual feature of a new version to be flagged on and off from outside the application so each new feature can be validated one at a time. [wellarchitected-operational-excellence-pil… |
| in-16 | IN_CORPUS | CORRECT | yes | wellarchitected-performance-efficiency-pillar.pdf p. 9 | 7913 | Democratizing advanced technologies means making advanced technology implementation easier for your team by delegating complex tasks to your cloud vendor. [wellarchitected-performance-efficiency-pillar.pdf, p. 9] / Instead of having an IT team learn to host and run a new technology, organizations can consume the technology as a service. [wellarchitected-performance-efficiency-pillar.pdf, p. 9] / Technologies requiring specialized expertise, like NoSQL databases, media transcoding, and machine learning, become cloud services that teams can consume. [wellarchitected-performance-efficiency-pillar… |
| in-17 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 45 | 7793 | Mechanical sympathy involves understanding how cloud services are consumed and consistently using the technology approach that aligns best with your workload goals. [wellarchitected-framework.pdf, p. 45] / For instance, mechanical sympathy includes considering data access patterns when selecting database or storage approaches for a workload. [wellarchitected-framework.pdf, p. 45] |
| in-18 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 744 | 8876 | In-memory databases can be used for application caching, session management, and gaming leaderboards. [wellarchitected-framework.pdf, p. 744] / AWS in-memory database services include Amazon ElastiCache and Amazon MemoryDB for Redis. [wellarchitected-framework.pdf, p. 744] |
| in-19 | IN_CORPUS | CORRECT | yes | wellarchitected-serverless-applications-lens.pdf p. 60 | 9667 | A dead-letter queue mechanism should be used to retain, investigate, and retry failed transactions. [wellarchitected-serverless-applications-lens.pdf, p. 60] / AWS Lambda enables sending failed transactions to an Amazon SQS dead-letter queue designated on a per-function basis. [wellarchitected-serverless-applications-lens.pdf, p. 60] / Within AWS Lambda, retry during data record processing can be managed by configuring Maximum Retry Attempts, Maximum Record Age, and Destination on Failure. [wellarchitected-serverless-applications-lens.pdf, p. 60] / Metadata for poison-pill messages can be remo… |
| in-20 | IN_CORPUS | CORRECT | yes | wellarchitected-serverless-applications-lens.pdf p. 15; microservices-on-aws.pdf p. 22; microservices-on-aws.pdf p. 12 | 7988 | Amazon API Gateway hosts RESTful HTTP requests and responses to customers for RESTful microservices. [wellarchitected-serverless-applications-lens.pdf, p. 15] / In a RESTful microservices scenario, Amazon API Gateway provides built-in authorization, throttling, security, fault tolerance, request and response mapping, and performance optimizations. [wellarchitected-serverless-applications-lens.pdf, p. 15] / API Gateway handles tasks including traffic management, authorization and access control, monitoring, and API version management when accepting and processing up to hundreds of thousands of … |
| in-21 | IN_CORPUS | FALSE_ANSWER | no | wellarchitected-serverless-applications-lens.pdf p. 67 | 9992 | Provisioned concurrency initializes a requested number of execution environments so that they can respond immediately to function invocations. [wellarchitected-serverless-applications-lens.pdf, p. 67] / To enable a Lambda function to scale without fluctuations in latency, provisioned concurrency can be used. [wellarchitected-serverless-applications-lens.pdf, p. 67] / Allocating provisioned concurrency before invocations increase ensures requests are handled by initialized instances with very low latency. [wellarchitected-serverless-applications-lens.pdf, p. 67] / Application Auto Scaling can b… |
| in-22 | IN_CORPUS | CORRECT | yes | microservices-on-aws.pdf p. 17; wellarchitected-reliability-pillar.pdf p. 65 | 10345 | Amazon ECS provides integrated service discovery by managing a registry of service names using the Route 53 Auto Naming API. [microservices-on-aws.pdf, p. 17] / Services managed by Kubernetes can use unified service discovery through AWS's contributions to the External DNS project. [microservices-on-aws.pdf, p. 17] / AWS Cloud Map extends Auto Naming APIs by providing a service registry and an API-based discovery mechanism. [microservices-on-aws.pdf, p. 17] / Third-party software such as HashiCorp Consul, etcd, or Netflix Eureka can also be used to implement service discovery. [microservices-o… |
| in-23 | IN_CORPUS | CORRECT | yes | microservices-on-aws.pdf p. 18 | 6667 | In a microservices approach, relying on a central database would prevent the goal of building decentralized and independent components. [microservices-on-aws.pdf, p. 18] |
| in-24 | IN_CORPUS | CORRECT | yes | microservices-on-aws.pdf p. 14 | 7859 | Twelve-factor processes are stateless and share-nothing. [microservices-on-aws.pdf, p. 14] / Any data that must persist has to be stored in a stateful backing service, which is typically a database. [microservices-on-aws.pdf, p. 14] |
| out-far-01 | OUT_FAR | CORRECT | - | - | 7177 | (not answered: retrieval-floor) |
| out-far-02 | OUT_FAR | CORRECT | - | - | 5984 | (not answered: retrieval-floor) |
| out-far-03 | OUT_FAR | CORRECT | - | - | 7593 | (not answered: retrieval-floor) |
| out-far-04 | OUT_FAR | CORRECT | - | - | 7892 | (not answered: retrieval-floor) |
| out-far-05 | OUT_FAR | CORRECT | - | - | 9208 | (not answered: retrieval-floor) |
| out-far-06 | OUT_FAR | CORRECT | - | - | 6707 | (not answered: retrieval-floor) |
| out-near-01 | OUT_NEAR | CORRECT | - | - | 13377 | (not answered: model) |
| out-near-02 | OUT_NEAR | CORRECT | - | - | 9339 | (not answered: retrieval-floor) |
| out-near-03 | OUT_NEAR | CORRECT | - | - | 6654 | (not answered: retrieval-floor) |
| out-near-04 | OUT_NEAR | CORRECT | - | - | 7848 | (not answered: retrieval-floor) |
| out-near-05 | OUT_NEAR | CORRECT | - | - | 8671 | (not answered: model) |
| out-near-06 | OUT_NEAR | CORRECT | - | - | 9930 | (not answered: retrieval-floor) |
| in-25 | IN_CORPUS | CORRECT | yes | wellarchitected-cost-optimization-pillar.pdf p. 8 | 7664 | Adopting a consumption model means paying only for the computing resources consumed, and increasing or decreasing usage based on business requirements. [wellarchitected-cost-optimization-pillar.pdf, p. 8] / For instance, development and test environments can be stopped when not in use to achieve potential cost savings of 75%. [wellarchitected-cost-optimization-pillar.pdf, p. 8] |
| in-26 | IN_CORPUS | CORRECT | yes | wellarchitected-cost-optimization-pillar.pdf p. 28; wellarchitected-cost-optimization-pillar.pdf p. 37 | 7484 | Organizations should identify and recoup expenditure tied to cloud waste for resources that are deployed. [wellarchitected-cost-optimization-pillar.pdf, p. 28] / Cloud waste happens when incorrectly-sized resources are provisioned or when observed usage patterns deviate from expectations. [wellarchitected-cost-optimization-pillar.pdf, p. 28] / To reduce waste, organizations can follow AWS best practices or ask their account team and partner for assistance in optimizing and saving cloud costs. [wellarchitected-cost-optimization-pillar.pdf, p. 28] / Attributing resource costs to workloads, indiv… |
| in-27 | IN_CORPUS | CORRECT | yes | wellarchitected-sustainability-pillar.pdf p. 23 | 7000 | The selection of an AWS Region for a workload significantly influences its carbon footprint as well as other key performance indicators. [wellarchitected-sustainability-pillar.pdf, p. 23] / Deploying workloads in Regions near Amazon renewable energy projects or where the grid carbon intensity is low can reduce a workload's carbon footprint. [wellarchitected-sustainability-pillar.pdf, p. 23] |
| in-28 | IN_CORPUS | CORRECT | yes | wellarchitected-sustainability-pillar.pdf p. 7 | 7727 | Environmental sustainability is a shared responsibility between AWS and its customers. [wellarchitected-sustainability-pillar.pdf, p. 7] / AWS is responsible for sustainability of the cloud, which includes delivering efficient shared infrastructure, managing water stewardship, and sourcing renewable power. [wellarchitected-sustainability-pillar.pdf, p. 7] / Customers bear responsibility for sustainability in the cloud by optimizing workloads and resource utilization, as well as minimizing required workload resources. [wellarchitected-sustainability-pillar.pdf, p. 7] |
| in-29 | IN_CORPUS | CORRECT | yes | disaster-recovery-workloads-on-aws.pdf p. 13 | 7280 | The four disaster recovery strategies discussed for workloads on AWS are backup and restore, pilot light, warm standby, and multi-site active/active. [disaster-recovery-workloads-on-aws.pdf, p. 13] |
| in-30 | IN_CORPUS | CORRECT | yes | disaster-recovery-workloads-on-aws.pdf p. 26; wellarchitected-framework.pdf p. 692 | 8364 | AWS Elastic Disaster Recovery continuously replicates server-hosted applications and server-hosted databases from any source into AWS via block-level replication of the underlying server. [disaster-recovery-workloads-on-aws.pdf, p. 26] / It allows an AWS Cloud Region to be used as a disaster recovery target for workloads hosted on-premises or on another cloud provider. [disaster-recovery-workloads-on-aws.pdf, p. 26] / It can also support disaster recovery of AWS-hosted workloads if they consist solely of EC2-hosted applications and databases instead of RDS. [disaster-recovery-workloads-on-aws.… |
| in-31 | IN_CORPUS | FALSE_NOT_FOUND | no | - | 8859 | (not answered: retrieval-floor) |
| in-32 | IN_CORPUS | FALSE_NOT_FOUND | no | - | 11325 | (not answered: model) |
| out-aws-01 | OUT_AWS | CORRECT | - | - | 9996 | (not answered: model) |
| out-aws-02 | OUT_AWS | CORRECT | - | - | 10558 | (not answered: model) |
| out-aws-03 | OUT_AWS | CORRECT | - | - | 9304 | (not answered: model) |
| out-aws-04 | OUT_AWS | CORRECT | - | - | 21811 | (not answered: model) |

