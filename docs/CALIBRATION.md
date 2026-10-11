# Grounding threshold calibration: baseline vs stripped

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

| | baseline | stripped |
| --- | --- | --- |
| What differs | running headers and footers kept in the chunk text (chunking as ingested on Day 2) | lines repeated at the top or bottom of most pages removed before chunking |
| Generated | 2026-10-11T05:40:38.583895100Z | 2026-10-11T05:52:54.603593400Z |
| Embedding / reranker | `gemini-embedding-2` / `ms-marco-MiniLM-L-6-v2/model.onnx` | `gemini-embedding-2` / `ms-marco-MiniLM-L-6-v2/model.onnx` |

## Side by side

| metric | baseline | stripped | change |
| --- | ---: | ---: | ---: |
| in-corpus questions | 32 | 32 | +0 |
| answer page in fused top 20 | 29 | 29 | +0 |
| answer page in fused top 12 | 28 | 28 | +0 |
| answer page in final top 5 (rerank 20) | 26 | 24 | -2 |
| answer page in final top 5 (rerank 12) | 26 | 24 | -2 |
| paraphrased questions: 2, in final top 5 (rerank 20) | 0 | 0 | +0 |
| mean top-1 score, in-corpus (depth 20) | 4.63 | 4.70 | +0.07 |
| highest top-1 score, OUT_FAR | -8.15 | -8.97 | -0.83 |
| highest top-1 score, OUT_NEAR | 3.17 | 3.71 | +0.54 |
| highest top-1 score, OUT_AWS | 1.14 | 0.75 | -0.39 |
| threshold chosen (depth 20) | 3.33 | 2.89 | -0.44 |
| balanced accuracy at that threshold (depth 20) | 0.90 | 0.86 | -0.04 |
| groups separable at depth 20 | no | no | |
| held-out questions classified correctly (depth 20) | 10 | 10 | +0 |
| mean rerank ms per question | 1677.00 | 1852.60 | +175.60 |

**Verdict on final top 5 (rerank 20):** stripped is worse by 2 question(s) (26 to 24).

## Per question

| id | type | baseline top-1 | stripped top-1 | baseline fused rank | stripped fused rank | baseline top 5 | stripped top 5 | question |
| --- | --- | ---: | ---: | ---: | ---: | :---: | :---: | --- |
| in-01 | IN_CORPUS | 9.04 | 8.64 | 2 | 4 | yes | yes | What are the six pillars of the AWS Well-Architected Framework? |
| in-02 | IN_CORPUS | 3.60 | 3.01 | 6 | 3 | yes | yes | Why does the framework advise to stop guessing capacity needs? |
| in-03 | IN_CORPUS | -3.35 | -3.12 | 3 | 2 | yes | yes | What is the difference between one-way door and two-way door decisions? |
| in-04 | IN_CORPUS | 10.04 | 10.05 | 2 | 1 | yes | yes | What is the AWS Well-Architected Tool? |
| in-05 | IN_CORPUS | 7.51 | 7.79 | 2 | 3 | yes | yes | What does recovery time objective (RTO) mean? |
| in-06 | IN_CORPUS | 3.48 | 4.34 | 1 | 1 | no | no | How should a client retry failed requests without overloading a service? |
| in-07 | IN_CORPUS | 7.62 | 7.87 | 1 | 1 | yes | yes | Why should a workload be aware of service quotas and constraints? |
| in-08 | IN_CORPUS | 4.99 | 3.19 | 1 | 3 | yes | no | How does the reliability pillar suggest recovering automatically from failures? |
| in-09 | IN_CORPUS | 5.86 | 5.93 | 1 | 1 | yes | yes | Who is responsible for what in the shared responsibility model for security? |
| in-10 | IN_CORPUS | 5.48 | 5.75 | 8 | 9 | no | no | How should secrets and credentials be managed and rotated? |
| in-11 | IN_CORPUS | 4.85 | 5.71 | not in fused 20 | not in fused 20 | no | no | What is the principle of least privilege? |
| in-12 | IN_CORPUS | 9.12 | 9.16 | 1 | 1 | yes | yes | What does secure key management include for protecting data at rest? |
| in-13 | IN_CORPUS | 6.86 | 5.77 | 2 | 6 | yes | no | How does the operational excellence pillar recommend making changes to workloads? |
| in-14 | IN_CORPUS | 6.65 | 7.07 | 4 | 6 | yes | yes | What are runbooks used for? |
| in-15 | IN_CORPUS | 6.66 | 6.97 | 1 | 1 | yes | yes | How can feature flags and A/B testing be used to experiment safely? |
| in-16 | IN_CORPUS | 6.75 | 6.95 | 1 | 1 | yes | yes | What does democratize advanced technologies mean in the performance efficiency pillar? |
| in-17 | IN_CORPUS | -6.23 | -4.44 | 1 | 1 | yes | yes | What is mechanical sympathy? |
| in-18 | IN_CORPUS | 5.82 | 5.93 | 1 | 1 | yes | yes | Which database is suited to caching, session management and gaming leaderboards? |
| in-19 | IN_CORPUS | 6.21 | 6.60 | 1 | 1 | yes | yes | How should failed Lambda transactions be retained and retried? |
| in-20 | IN_CORPUS | 7.83 | 7.87 | 2 | 2 | yes | yes | What does Amazon API Gateway provide for serverless microservices? |
| in-21 | IN_CORPUS | -3.52 | -2.73 | not in fused 20 | not in fused 20 | no | no | What can be considered to reduce the impact of Lambda cold starts? |
| in-22 | IN_CORPUS | 5.85 | 5.37 | 1 | 1 | yes | yes | What options does AWS offer for service discovery of microservices? |
| in-23 | IN_CORPUS | 3.59 | 3.14 | 1 | 1 | yes | yes | Why should each microservice have its own data persistence layer? |
| in-24 | IN_CORPUS | 6.02 | 6.08 | 1 | 1 | yes | yes | What do twelve-factor processes say about state? |
| out-far-01 | OUT_FAR | -10.80 | -8.97 | - | - | - | - | Who won the FIFA World Cup in 2018? |
| out-far-02 | OUT_FAR | -9.57 | -10.91 | - | - | - | - | What is a good recipe for chocolate chip cookies? |
| out-far-03 | OUT_FAR | -11.07 | -11.05 | - | - | - | - | What is the capital city of Australia? |
| out-far-04 | OUT_FAR | -8.15 | -10.95 | - | - | - | - | How many moons does Jupiter have? |
| out-far-05 | OUT_FAR | -10.94 | -10.50 | - | - | - | - | Who wrote the novel Pride and Prejudice? |
| out-far-06 | OUT_FAR | -10.76 | -10.58 | - | - | - | - | What is the boiling point of water at sea level in Fahrenheit? |
| out-near-01 | OUT_NEAR | 2.57 | 2.77 | - | - | - | - | How do I configure virtual network peering in Microsoft Azure? |
| out-near-02 | OUT_NEAR | -5.04 | -4.52 | - | - | - | - | How do I create a GKE Autopilot cluster on Google Cloud? |
| out-near-03 | OUT_NEAR | -5.23 | -5.90 | - | - | - | - | How do I write a Helm chart values.yaml for a Redis deployment? |
| out-near-04 | OUT_NEAR | -8.39 | -8.04 | - | - | - | - | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| out-near-05 | OUT_NEAR | 3.17 | 3.71 | - | - | - | - | What are the pillars of the Google Cloud Architecture Framework? |
| out-near-06 | OUT_NEAR | -6.52 | -6.60 | - | - | - | - | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| in-25 | IN_CORPUS | 0.40 | 1.06 | 2 | 3 | yes | yes | What does it mean to adopt a consumption model? |
| in-26 | IN_CORPUS | 5.08 | 3.73 | 1 | 3 | yes | yes | How does the cost optimization pillar suggest reducing cloud waste? |
| in-27 | IN_CORPUS | 6.44 | 6.25 | 1 | 1 | yes | yes | Why does the choice of AWS Region matter for the carbon footprint of a workload? |
| in-28 | IN_CORPUS | 8.83 | 9.12 | 1 | 1 | yes | yes | What is the shared responsibility model for sustainability? |
| in-29 | IN_CORPUS | 8.39 | 7.93 | 2 | 2 | yes | yes | What are the four disaster recovery strategies described for workloads on AWS? |
| in-30 | IN_CORPUS | 8.84 | 9.24 | 3 | 1 | yes | yes | What does AWS Elastic Disaster Recovery do? |
| in-31 | IN_CORPUS (paraphrase) | -6.38 | -5.81 | 14 | 16 | no | no | Why shouldn't staging servers keep running overnight and on weekends? |
| in-32 | IN_CORPUS (paraphrase) | -4.16 | -3.93 | not in fused 20 | not in fused 20 | no | no | How can a platform heal itself without anyone being paged? |
| out-aws-01 | OUT_AWS | 1.14 | 0.75 | - | - | - | - | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| out-aws-02 | OUT_AWS | -1.56 | -1.14 | - | - | - | - | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| out-aws-03 | OUT_AWS | -0.86 | -0.17 | - | - | - | - | What is the maximum size of a single object in Amazon S3? |
| out-aws-04 | OUT_AWS | -2.06 | -2.08 | - | - | - | - | What is the maximum size of an item in Amazon DynamoDB? |

---

# Detail: baseline

Generated 2026-10-11T05:40:38.583895100Z by `CalibrationRunner` on the real corpus.

- Embedding model: `gemini-embedding-2`
- Reranker: `ms-marco-MiniLM-L-6-v2/model.onnx`
- Fusion: `RANK`, fused top 20, final top 5
- The score is the cross-encoder's raw logit (not 0 to 1). The threshold applies to the best score.
- Each question is retrieved once with all 20 fused candidates scored; the "depth 12" columns use only the fused top 12 of those same scores.

## Every question

| id | split | type | top-1 d20 | top-1 d12 | best passage | expected at fused rank | in top 5 d20 | in top 5 d12 | top 5 d12 widened | truncated | question |
| --- | --- | --- | ---: | ---: | --- | ---: | :---: | :---: | :---: | ---: | --- |
| in-01 | calibration | IN_CORPUS | 9.04 | 9.04 | wa-framework p13 | 2 | yes | yes | no | 1 | What are the six pillars of the AWS Well-Architected Framework? |
| in-02 | calibration | IN_CORPUS | 3.60 | 3.60 | wa-reliability-pillar p13 | 6 | yes | yes | no | 1 | Why does the framework advise to stop guessing capacity needs? |
| in-03 | calibration | IN_CORPUS | -3.35 | -3.35 | wa-framework p69 | 3 | yes | yes | no | 3 | What is the difference between one-way door and two-way door decisions? |
| in-04 | holdout | IN_CORPUS | 10.04 | 10.04 | wa-framework p7 | 2 | yes | yes | no | 2 | What is the AWS Well-Architected Tool? |
| in-05 | calibration | IN_CORPUS | 7.51 | 7.51 | wa-reliability-pillar p19 | 2 | yes | yes | no | 3 | What does recovery time objective (RTO) mean? |
| in-06 | calibration | IN_CORPUS | 3.48 | 3.48 | wa-framework p545 | 1 | no | no | no | 3 | How should a client retry failed requests without overloading a service? |
| in-07 | calibration | IN_CORPUS | 7.62 | 7.62 | wa-framework p470 | 1 | yes | yes | no | 3 | Why should a workload be aware of service quotas and constraints? |
| in-08 | holdout | IN_CORPUS | 4.99 | 4.99 | wa-reliability-pillar p197 | 1 | yes | yes | no | 1 | How does the reliability pillar suggest recovering automatically from failures? |
| in-09 | calibration | IN_CORPUS | 5.86 | 5.86 | wa-security-pillar p9 | 1 | yes | yes | no | 8 | Who is responsible for what in the shared responsibility model for security? |
| in-10 | calibration | IN_CORPUS | 5.48 | 5.48 | wa-framework p300 | 8 | no | no | no | 1 | How should secrets and credentials be managed and rotated? |
| in-11 | calibration | IN_CORPUS | 4.85 | 4.85 | wa-security-pillar p70 | not in fused 20 | no | no | no | 7 | What is the principle of least privilege? |
| in-12 | holdout | IN_CORPUS | 9.12 | 9.12 | wa-framework p399 | 1 | yes | yes | no | 5 | What does secure key management include for protecting data at rest? |
| in-13 | calibration | IN_CORPUS | 6.86 | 6.86 | wa-operational-excellence-pillar p82 | 2 | yes | yes | no | 3 | How does the operational excellence pillar recommend making changes to workloads? |
| in-14 | calibration | IN_CORPUS | 6.65 | 6.65 | wa-framework p592 | 4 | yes | yes | no | 2 | What are runbooks used for? |
| in-15 | calibration | IN_CORPUS | 6.66 | 6.66 | wa-framework p128 | 1 | yes | yes | no | 2 | How can feature flags and A/B testing be used to experiment safely? |
| in-16 | holdout | IN_CORPUS | 6.75 | 6.75 | wa-performance-efficiency-pillar p9 | 1 | yes | yes | no | 2 | What does democratize advanced technologies mean in the performance efficiency pillar? |
| in-17 | calibration | IN_CORPUS | -6.23 | -6.23 | wa-performance-efficiency-pillar p9 | 1 | yes | yes | no | 1 | What is mechanical sympathy? |
| in-18 | calibration | IN_CORPUS | 5.82 | 5.82 | wa-framework p744 | 1 | yes | yes | no | 2 | Which database is suited to caching, session management and gaming leaderboards? |
| in-19 | calibration | IN_CORPUS | 6.21 | 6.21 | wa-serverless-applications-lens p60 | 1 | yes | yes | no | 6 | How should failed Lambda transactions be retained and retried? |
| in-20 | holdout | IN_CORPUS | 7.83 | 7.83 | microservices-on-aws p12 | 2 | yes | yes | no | 2 | What does Amazon API Gateway provide for serverless microservices? |
| in-21 | calibration | IN_CORPUS | -3.52 | -3.52 | wa-serverless-applications-lens p80 | not in fused 20 | no | no | no | 2 | What can be considered to reduce the impact of Lambda cold starts? |
| in-22 | calibration | IN_CORPUS | 5.85 | 5.85 | microservices-on-aws p17 | 1 | yes | yes | no | 1 | What options does AWS offer for service discovery of microservices? |
| in-23 | calibration | IN_CORPUS | 3.59 | 3.59 | microservices-on-aws p18 | 1 | yes | yes | no | 2 | Why should each microservice have its own data persistence layer? |
| in-24 | holdout | IN_CORPUS | 6.02 | 6.02 | microservices-on-aws p14 | 1 | yes | yes | no | 2 | What do twelve-factor processes say about state? |
| out-far-01 | calibration | OUT_FAR | -10.80 | -10.80 | wa-framework p531 | - | - | - | - | 1 | Who won the FIFA World Cup in 2018? |
| out-far-02 | calibration | OUT_FAR | -9.57 | -10.99 | wa-performance-efficiency-pillar p4 | - | - | - | - | 3 | What is a good recipe for chocolate chip cookies? |
| out-far-03 | calibration | OUT_FAR | -11.07 | -11.07 | wa-framework p812 | - | - | - | - | 0 | What is the capital city of Australia? |
| out-far-04 | holdout | OUT_FAR | -8.15 | -8.15 | wa-framework p3 | - | - | - | - | 4 | How many moons does Jupiter have? |
| out-far-05 | calibration | OUT_FAR | -10.94 | -11.00 | wa-reliability-pillar p1 | - | - | - | - | 0 | Who wrote the novel Pride and Prejudice? |
| out-far-06 | calibration | OUT_FAR | -10.76 | -10.76 | wa-reliability-pillar p5 | - | - | - | - | 4 | What is the boiling point of water at sea level in Fahrenheit? |
| out-near-01 | calibration | OUT_NEAR | 2.57 | 2.57 | wa-reliability-pillar p57 | - | - | - | - | 1 | How do I configure virtual network peering in Microsoft Azure? |
| out-near-02 | calibration | OUT_NEAR | -5.04 | -7.79 | wa-framework p589 | - | - | - | - | 1 | How do I create a GKE Autopilot cluster on Google Cloud? |
| out-near-03 | calibration | OUT_NEAR | -5.23 | -5.23 | wa-framework p762 | - | - | - | - | 2 | How do I write a Helm chart values.yaml for a Redis deployment? |
| out-near-04 | holdout | OUT_NEAR | -8.39 | -8.39 | wa-performance-efficiency-pillar p54 | - | - | - | - | 3 | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| out-near-05 | calibration | OUT_NEAR | 3.17 | 3.17 | wa-framework p71 | - | - | - | - | 1 | What are the pillars of the Google Cloud Architecture Framework? |
| out-near-06 | calibration | OUT_NEAR | -6.52 | -6.52 | wa-framework p781 | - | - | - | - | 6 | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| in-25 | calibration | IN_CORPUS | 0.40 | 0.40 | wa-cost-optimization-pillar p17 | 2 | yes | yes | no | 2 | What does it mean to adopt a consumption model? |
| in-26 | calibration | IN_CORPUS | 5.08 | 5.08 | wa-cost-optimization-pillar p28 | 1 | yes | yes | no | 2 | How does the cost optimization pillar suggest reducing cloud waste? |
| in-27 | calibration | IN_CORPUS | 6.44 | 6.44 | wa-framework p62 | 1 | yes | yes | no | 2 | Why does the choice of AWS Region matter for the carbon footprint of a workload? |
| in-28 | holdout | IN_CORPUS | 8.83 | 8.83 | wa-sustainability-pillar p7 | 1 | yes | yes | no | 5 | What is the shared responsibility model for sustainability? |
| in-29 | calibration | IN_CORPUS | 8.39 | 8.39 | disaster-recovery-workloads-on-aws p18 | 2 | yes | yes | no | 4 | What are the four disaster recovery strategies described for workloads on AWS? |
| in-30 | calibration | IN_CORPUS | 8.84 | 8.84 | wa-framework p692 | 3 | yes | yes | no | 3 | What does AWS Elastic Disaster Recovery do? |
| in-31 | calibration | IN_CORPUS (paraphrase) | -6.38 | -6.38 | wa-cost-optimization-pillar p109 | 14 | no | no | no | 2 | Why shouldn't staging servers keep running overnight and on weekends? |
| in-32 | holdout | IN_CORPUS (paraphrase) | -4.16 | -4.16 | wa-reliability-pillar p198 | not in fused 20 | no | no | no | 1 | How can a platform heal itself without anyone being paged? |
| out-aws-01 | calibration | OUT_AWS | 1.14 | 1.14 | wa-reliability-pillar p96 | - | - | - | - | 4 | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| out-aws-02 | calibration | OUT_AWS | -1.56 | -1.56 | wa-cost-optimization-pillar p82 | - | - | - | - | 4 | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| out-aws-03 | calibration | OUT_AWS | -0.86 | -0.86 | wa-cost-optimization-pillar p98 | - | - | - | - | 3 | What is the maximum size of a single object in Amazon S3? |
| out-aws-04 | holdout | OUT_AWS | -2.06 | -2.06 | wa-serverless-applications-lens p86 | - | - | - | - | 2 | What is the maximum size of an item in Amazon DynamoDB? |

## Recall of the answer-bearing page (in-corpus questions), strict / widened

Strict: the page contains the question's evidence phrase (fixed before the first run). Widened: also the adjacent, near-duplicate or equivalent pages added afterwards (rules and reasons: Findings, section 2). Each cell reads strict / widened.

| set | n | in fused top 20 | in fused top 12 | in final top 5, depth 20 | in final top 5, depth 12 |
| --- | ---: | ---: | ---: | ---: | ---: |
| calibration | 24 | 22 / 0 | 21 / 0 | 19 / 0 | 19 / 0 |
| holdout | 8 | 7 / 0 | 7 / 0 | 7 / 0 | 7 / 0 |
| paraphrased (semantic path) | 2 | 1 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| all | 32 | 29 / 0 | 28 / 0 | 26 / 0 | 26 / 0 |

## Retrieval floor and high-confidence marker (depth 12, the configured depth)

No floor was configured for this run.

## Rerank depth: 12 or 20

Questions that lose the answer page from the final top 5 when only 12 candidates are reranked: none.

**Recall holds at depth 12** on this set, so 12 is supported (it scores 40% fewer pairs).

## Threshold at depth 20

Calibration split: lowest in-corpus top-1 = **-6.38**, highest out-of-corpus top-1 = **3.17** -> the groups **overlap**, no threshold is error-free.

Chosen threshold: **3.33** (balanced accuracy 0.896; in-corpus accepted 19/24, out-of-corpus rejected 13/13).

| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |
| ---: | ---: | ---: | ---: |
| -11.01 | 24/24 | 1/13 | 0.538 |
| -10.87 | 24/24 | 2/13 | 0.577 |
| -10.78 | 24/24 | 3/13 | 0.615 |
| -10.17 | 24/24 | 4/13 | 0.654 |
| -8.05 | 24/24 | 5/13 | 0.692 |
| -6.45 | 24/24 | 6/13 | 0.731 |
| -6.30 | 23/24 | 6/13 | 0.710 |
| -5.73 | 22/24 | 6/13 | 0.689 |
| -5.13 | 22/24 | 7/13 | 0.728 |
| -4.28 | 22/24 | 8/13 | 0.766 |
| -3.43 | 21/24 | 8/13 | 0.745 |
| -2.45 | 20/24 | 8/13 | 0.724 |
| -1.21 | 20/24 | 9/13 | 0.763 |
| -0.23 | 20/24 | 10/13 | 0.801 |
| 0.77 | 19/24 | 10/13 | 0.780 |
| 1.86 | 19/24 | 11/13 | 0.819 |
| 2.87 | 19/24 | 12/13 | 0.857 |
| 3.33 **(chosen)** | 19/24 | 13/13 | 0.896 |
| 3.54 | 18/24 | 13/13 | 0.875 |
| 3.60 | 17/24 | 13/13 | 0.854 |
| 4.23 | 16/24 | 13/13 | 0.833 |
| 4.96 | 15/24 | 13/13 | 0.813 |
| 5.28 | 14/24 | 13/13 | 0.792 |
| 5.65 | 13/24 | 13/13 | 0.771 |
| 5.84 | 12/24 | 13/13 | 0.750 |
| 5.86 | 11/24 | 13/13 | 0.729 |
| 6.04 | 10/24 | 13/13 | 0.708 |
| 6.32 | 9/24 | 13/13 | 0.688 |
| 6.55 | 8/24 | 13/13 | 0.667 |
| 6.65 | 7/24 | 13/13 | 0.646 |
| 6.76 | 6/24 | 13/13 | 0.625 |
| 7.19 | 5/24 | 13/13 | 0.604 |
| 7.56 | 4/24 | 13/13 | 0.583 |
| 8.00 | 3/24 | 13/13 | 0.563 |
| 8.61 | 2/24 | 13/13 | 0.542 |
| 8.94 | 1/24 | 13/13 | 0.521 |

Held-out check with threshold 3.33:

- 10 of 11 held-out questions classified correctly.
- **in-32** (IN_CORPUS) scored -4.16 and was wrongly rejected (false "not found"): "How can a platform heal itself without anyone being paged?"

## Threshold at depth 12

Calibration split: lowest in-corpus top-1 = **-6.38**, highest out-of-corpus top-1 = **3.17** -> the groups **overlap**, no threshold is error-free.

Chosen threshold: **3.33** (balanced accuracy 0.896; in-corpus accepted 19/24, out-of-corpus rejected 13/13).

| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |
| ---: | ---: | ---: | ---: |
| -11.03 | 24/24 | 1/13 | 0.538 |
| -10.99 | 24/24 | 2/13 | 0.577 |
| -10.89 | 24/24 | 3/13 | 0.615 |
| -10.78 | 24/24 | 4/13 | 0.654 |
| -9.28 | 24/24 | 5/13 | 0.692 |
| -7.16 | 24/24 | 6/13 | 0.731 |
| -6.45 | 24/24 | 7/13 | 0.769 |
| -6.30 | 23/24 | 7/13 | 0.748 |
| -5.73 | 22/24 | 7/13 | 0.728 |
| -4.38 | 22/24 | 8/13 | 0.766 |
| -3.43 | 21/24 | 8/13 | 0.745 |
| -2.45 | 20/24 | 8/13 | 0.724 |
| -1.21 | 20/24 | 9/13 | 0.763 |
| -0.23 | 20/24 | 10/13 | 0.801 |
| 0.77 | 19/24 | 10/13 | 0.780 |
| 1.86 | 19/24 | 11/13 | 0.819 |
| 2.87 | 19/24 | 12/13 | 0.857 |
| 3.33 **(chosen)** | 19/24 | 13/13 | 0.896 |
| 3.54 | 18/24 | 13/13 | 0.875 |
| 3.60 | 17/24 | 13/13 | 0.854 |
| 4.23 | 16/24 | 13/13 | 0.833 |
| 4.96 | 15/24 | 13/13 | 0.813 |
| 5.28 | 14/24 | 13/13 | 0.792 |
| 5.65 | 13/24 | 13/13 | 0.771 |
| 5.84 | 12/24 | 13/13 | 0.750 |
| 5.86 | 11/24 | 13/13 | 0.729 |
| 6.04 | 10/24 | 13/13 | 0.708 |
| 6.32 | 9/24 | 13/13 | 0.688 |
| 6.55 | 8/24 | 13/13 | 0.667 |
| 6.65 | 7/24 | 13/13 | 0.646 |
| 6.76 | 6/24 | 13/13 | 0.625 |
| 7.19 | 5/24 | 13/13 | 0.604 |
| 7.56 | 4/24 | 13/13 | 0.583 |
| 8.00 | 3/24 | 13/13 | 0.563 |
| 8.61 | 2/24 | 13/13 | 0.542 |
| 8.94 | 1/24 | 13/13 | 0.521 |

Held-out check with threshold 3.33:

- 10 of 11 held-out questions classified correctly.
- **in-32** (IN_CORPUS) scored -4.16 and was wrongly rejected (false "not found"): "How can a platform heal itself without anyone being paged?"

## Out-of-corpus questions by score (depth 20)

| type | id | split | top-1 | best passage | question |
| --- | --- | --- | ---: | --- | --- |
| OUT_NEAR | out-near-05 | calibration | 3.17 | wa-framework p71 | What are the pillars of the Google Cloud Architecture Framework? |
| OUT_NEAR | out-near-01 | calibration | 2.57 | wa-reliability-pillar p57 | How do I configure virtual network peering in Microsoft Azure? |
| OUT_AWS | out-aws-01 | calibration | 1.14 | wa-reliability-pillar p96 | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| OUT_AWS | out-aws-03 | calibration | -0.86 | wa-cost-optimization-pillar p98 | What is the maximum size of a single object in Amazon S3? |
| OUT_AWS | out-aws-02 | calibration | -1.56 | wa-cost-optimization-pillar p82 | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| OUT_AWS | out-aws-04 | holdout | -2.06 | wa-serverless-applications-lens p86 | What is the maximum size of an item in Amazon DynamoDB? |
| OUT_NEAR | out-near-02 | calibration | -5.04 | wa-framework p589 | How do I create a GKE Autopilot cluster on Google Cloud? |
| OUT_NEAR | out-near-03 | calibration | -5.23 | wa-framework p762 | How do I write a Helm chart values.yaml for a Redis deployment? |
| OUT_NEAR | out-near-06 | calibration | -6.52 | wa-framework p781 | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| OUT_FAR | out-far-04 | holdout | -8.15 | wa-framework p3 | How many moons does Jupiter have? |
| OUT_NEAR | out-near-04 | holdout | -8.39 | wa-performance-efficiency-pillar p54 | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| OUT_FAR | out-far-02 | calibration | -9.57 | wa-performance-efficiency-pillar p4 | What is a good recipe for chocolate chip cookies? |
| OUT_FAR | out-far-06 | calibration | -10.76 | wa-reliability-pillar p5 | What is the boiling point of water at sea level in Fahrenheit? |
| OUT_FAR | out-far-01 | calibration | -10.80 | wa-framework p531 | Who won the FIFA World Cup in 2018? |
| OUT_FAR | out-far-05 | calibration | -10.94 | wa-reliability-pillar p1 | Who wrote the novel Pride and Prejudice? |
| OUT_FAR | out-far-03 | calibration | -11.07 | wa-framework p812 | What is the capital city of Australia? |


---

# Detail: stripped

Generated 2026-10-11T05:52:54.603593400Z by `CalibrationRunner` on the real corpus.

- Embedding model: `gemini-embedding-2`
- Reranker: `ms-marco-MiniLM-L-6-v2/model.onnx`
- Fusion: `RANK`, fused top 20, final top 5
- The score is the cross-encoder's raw logit (not 0 to 1). The threshold applies to the best score.
- Each question is retrieved once with all 20 fused candidates scored; the "depth 12" columns use only the fused top 12 of those same scores.

## Every question

| id | split | type | top-1 d20 | top-1 d12 | best passage | expected at fused rank | in top 5 d20 | in top 5 d12 | top 5 d12 widened | truncated | question |
| --- | --- | --- | ---: | ---: | --- | ---: | :---: | :---: | :---: | ---: | --- |
| in-01 | calibration | IN_CORPUS | 8.64 | 8.64 | wa-framework p71 | 4 | yes | yes | no | 0 | What are the six pillars of the AWS Well-Architected Framework? |
| in-02 | calibration | IN_CORPUS | 3.01 | 3.01 | wa-framework p11 | 3 | yes | yes | no | 1 | Why does the framework advise to stop guessing capacity needs? |
| in-03 | calibration | IN_CORPUS | -3.12 | -3.12 | wa-framework p69 | 2 | yes | yes | no | 2 | What is the difference between one-way door and two-way door decisions? |
| in-04 | holdout | IN_CORPUS | 10.05 | 10.05 | wa-framework p7 | 1 | yes | yes | no | 1 | What is the AWS Well-Architected Tool? |
| in-05 | calibration | IN_CORPUS | 7.79 | 7.79 | wa-reliability-pillar p19 | 3 | yes | yes | no | 2 | What does recovery time objective (RTO) mean? |
| in-06 | calibration | IN_CORPUS | 4.34 | 4.34 | wa-framework p552 | 1 | no | no | no | 0 | How should a client retry failed requests without overloading a service? |
| in-07 | calibration | IN_CORPUS | 7.87 | 7.87 | wa-framework p470 | 1 | yes | yes | no | 3 | Why should a workload be aware of service quotas and constraints? |
| in-08 | holdout | IN_CORPUS | 3.19 | 3.19 | wa-framework p38 | 3 | no | no | no | 0 | How does the reliability pillar suggest recovering automatically from failures? |
| in-09 | calibration | IN_CORPUS | 5.93 | 5.93 | wa-security-pillar p9 | 1 | yes | yes | no | 6 | Who is responsible for what in the shared responsibility model for security? |
| in-10 | calibration | IN_CORPUS | 5.75 | 5.75 | wa-framework p300 | 9 | no | no | no | 1 | How should secrets and credentials be managed and rotated? |
| in-11 | calibration | IN_CORPUS | 5.71 | 5.71 | wa-framework p319 | not in fused 20 | no | no | no | 6 | What is the principle of least privilege? |
| in-12 | holdout | IN_CORPUS | 9.16 | 9.16 | wa-framework p399 | 1 | yes | yes | no | 5 | What does secure key management include for protecting data at rest? |
| in-13 | calibration | IN_CORPUS | 5.77 | 5.77 | wa-operational-excellence-pillar p8 | 6 | no | no | no | 2 | How does the operational excellence pillar recommend making changes to workloads? |
| in-14 | calibration | IN_CORPUS | 7.07 | 7.07 | wa-framework p592 | 6 | yes | yes | no | 0 | What are runbooks used for? |
| in-15 | calibration | IN_CORPUS | 6.97 | 6.97 | wa-framework p128 | 1 | yes | yes | no | 1 | How can feature flags and A/B testing be used to experiment safely? |
| in-16 | holdout | IN_CORPUS | 6.95 | 6.95 | wa-performance-efficiency-pillar p9 | 1 | yes | yes | no | 2 | What does democratize advanced technologies mean in the performance efficiency pillar? |
| in-17 | calibration | IN_CORPUS | -4.44 | -4.44 | wa-performance-efficiency-pillar p9 | 1 | yes | yes | no | 0 | What is mechanical sympathy? |
| in-18 | calibration | IN_CORPUS | 5.93 | 5.93 | wa-framework p744 | 1 | yes | yes | no | 2 | Which database is suited to caching, session management and gaming leaderboards? |
| in-19 | calibration | IN_CORPUS | 6.60 | 6.60 | wa-serverless-applications-lens p60 | 1 | yes | yes | no | 4 | How should failed Lambda transactions be retained and retried? |
| in-20 | holdout | IN_CORPUS | 7.87 | 7.87 | microservices-on-aws p12 | 2 | yes | yes | no | 2 | What does Amazon API Gateway provide for serverless microservices? |
| in-21 | calibration | IN_CORPUS | -2.73 | -2.73 | wa-serverless-applications-lens p80 | not in fused 20 | no | no | no | 1 | What can be considered to reduce the impact of Lambda cold starts? |
| in-22 | calibration | IN_CORPUS | 5.37 | 5.37 | microservices-on-aws p38 | 1 | yes | yes | no | 2 | What options does AWS offer for service discovery of microservices? |
| in-23 | calibration | IN_CORPUS | 3.14 | 3.14 | microservices-on-aws p18 | 1 | yes | yes | no | 1 | Why should each microservice have its own data persistence layer? |
| in-24 | holdout | IN_CORPUS | 6.08 | 6.08 | microservices-on-aws p14 | 1 | yes | yes | no | 2 | What do twelve-factor processes say about state? |
| out-far-01 | calibration | OUT_FAR | -8.97 | -10.67 | wa-reliability-pillar p6 | - | - | - | - | 1 | Who won the FIFA World Cup in 2018? |
| out-far-02 | calibration | OUT_FAR | -10.91 | -10.91 | wa-security-pillar p133 | - | - | - | - | 3 | What is a good recipe for chocolate chip cookies? |
| out-far-03 | calibration | OUT_FAR | -11.05 | -11.05 | wa-framework p812 | - | - | - | - | 0 | What is the capital city of Australia? |
| out-far-04 | holdout | OUT_FAR | -10.95 | -11.16 | wa-reliability-pillar p4 | - | - | - | - | 4 | How many moons does Jupiter have? |
| out-far-05 | calibration | OUT_FAR | -10.50 | -10.50 | disaster-recovery-workloads-on-aws p36 | - | - | - | - | 0 | Who wrote the novel Pride and Prejudice? |
| out-far-06 | calibration | OUT_FAR | -10.58 | -10.58 | wa-reliability-pillar p6 | - | - | - | - | 5 | What is the boiling point of water at sea level in Fahrenheit? |
| out-near-01 | calibration | OUT_NEAR | 2.77 | 2.77 | wa-reliability-pillar p57 | - | - | - | - | 0 | How do I configure virtual network peering in Microsoft Azure? |
| out-near-02 | calibration | OUT_NEAR | -4.52 | -4.52 | wa-framework p589 | - | - | - | - | 1 | How do I create a GKE Autopilot cluster on Google Cloud? |
| out-near-03 | calibration | OUT_NEAR | -5.90 | -5.90 | wa-framework p762 | - | - | - | - | 1 | How do I write a Helm chart values.yaml for a Redis deployment? |
| out-near-04 | holdout | OUT_NEAR | -8.04 | -8.04 | wa-performance-efficiency-pillar p54 | - | - | - | - | 2 | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| out-near-05 | calibration | OUT_NEAR | 3.71 | 3.71 | wa-serverless-applications-lens p30 | - | - | - | - | 2 | What are the pillars of the Google Cloud Architecture Framework? |
| out-near-06 | calibration | OUT_NEAR | -6.60 | -6.60 | wa-framework p781 | - | - | - | - | 7 | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| in-25 | calibration | IN_CORPUS | 1.06 | 1.06 | wa-cost-optimization-pillar p17 | 3 | yes | yes | no | 1 | What does it mean to adopt a consumption model? |
| in-26 | calibration | IN_CORPUS | 3.73 | 3.73 | wa-framework p52 | 3 | yes | yes | no | 0 | How does the cost optimization pillar suggest reducing cloud waste? |
| in-27 | calibration | IN_CORPUS | 6.25 | 6.25 | wa-framework p936 | 1 | yes | yes | no | 1 | Why does the choice of AWS Region matter for the carbon footprint of a workload? |
| in-28 | holdout | IN_CORPUS | 9.12 | 9.12 | wa-sustainability-pillar p7 | 1 | yes | yes | no | 4 | What is the shared responsibility model for sustainability? |
| in-29 | calibration | IN_CORPUS | 7.93 | 7.93 | disaster-recovery-workloads-on-aws p18 | 2 | yes | yes | no | 3 | What are the four disaster recovery strategies described for workloads on AWS? |
| in-30 | calibration | IN_CORPUS | 9.24 | 9.24 | disaster-recovery-workloads-on-aws p26 | 1 | yes | yes | no | 1 | What does AWS Elastic Disaster Recovery do? |
| in-31 | calibration | IN_CORPUS (paraphrase) | -5.81 | -5.81 | wa-cost-optimization-pillar p109 | 16 | no | no | no | 2 | Why shouldn't staging servers keep running overnight and on weekends? |
| in-32 | holdout | IN_CORPUS (paraphrase) | -3.93 | -3.93 | wa-reliability-pillar p198 | not in fused 20 | no | no | no | 1 | How can a platform heal itself without anyone being paged? |
| out-aws-01 | calibration | OUT_AWS | 0.75 | 0.75 | wa-reliability-pillar p96 | - | - | - | - | 4 | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| out-aws-02 | calibration | OUT_AWS | -1.14 | -1.14 | wa-cost-optimization-pillar p82 | - | - | - | - | 3 | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| out-aws-03 | calibration | OUT_AWS | -0.17 | -0.17 | wa-framework p891 | - | - | - | - | 3 | What is the maximum size of a single object in Amazon S3? |
| out-aws-04 | holdout | OUT_AWS | -2.08 | -2.08 | wa-serverless-applications-lens p86 | - | - | - | - | 1 | What is the maximum size of an item in Amazon DynamoDB? |

## Recall of the answer-bearing page (in-corpus questions), strict / widened

Strict: the page contains the question's evidence phrase (fixed before the first run). Widened: also the adjacent, near-duplicate or equivalent pages added afterwards (rules and reasons: Findings, section 2). Each cell reads strict / widened.

| set | n | in fused top 20 | in fused top 12 | in final top 5, depth 20 | in final top 5, depth 12 |
| --- | ---: | ---: | ---: | ---: | ---: |
| calibration | 24 | 22 / 0 | 21 / 0 | 18 / 0 | 18 / 0 |
| holdout | 8 | 7 / 0 | 7 / 0 | 6 / 0 | 6 / 0 |
| paraphrased (semantic path) | 2 | 1 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| all | 32 | 29 / 0 | 28 / 0 | 24 / 0 | 24 / 0 |

## Retrieval floor and high-confidence marker (depth 12, the configured depth)

No floor was configured for this run.

## Rerank depth: 12 or 20

Questions that lose the answer page from the final top 5 when only 12 candidates are reranked: none.

**Recall holds at depth 12** on this set, so 12 is supported (it scores 40% fewer pairs).

## Threshold at depth 20

Calibration split: lowest in-corpus top-1 = **-5.81**, highest out-of-corpus top-1 = **3.71** -> the groups **overlap**, no threshold is error-free.

Chosen threshold: **2.89** (balanced accuracy 0.857; in-corpus accepted 19/24, out-of-corpus rejected 12/13).

| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |
| ---: | ---: | ---: | ---: |
| -10.98 | 24/24 | 1/13 | 0.538 |
| -10.74 | 24/24 | 2/13 | 0.577 |
| -10.54 | 24/24 | 3/13 | 0.615 |
| -9.74 | 24/24 | 4/13 | 0.654 |
| -7.79 | 24/24 | 5/13 | 0.692 |
| -6.25 | 24/24 | 6/13 | 0.731 |
| -5.85 | 24/24 | 7/13 | 0.769 |
| -5.16 | 23/24 | 7/13 | 0.748 |
| -4.48 | 23/24 | 8/13 | 0.787 |
| -3.78 | 22/24 | 8/13 | 0.766 |
| -2.92 | 21/24 | 8/13 | 0.745 |
| -1.93 | 20/24 | 8/13 | 0.724 |
| -0.65 | 20/24 | 9/13 | 0.763 |
| 0.29 | 20/24 | 10/13 | 0.801 |
| 0.90 | 20/24 | 11/13 | 0.840 |
| 1.92 | 19/24 | 11/13 | 0.819 |
| 2.89 **(chosen)** | 19/24 | 12/13 | 0.857 |
| 3.07 | 18/24 | 12/13 | 0.837 |
| 3.43 | 17/24 | 12/13 | 0.816 |
| 3.72 | 17/24 | 13/13 | 0.854 |
| 4.03 | 16/24 | 13/13 | 0.833 |
| 4.85 | 15/24 | 13/13 | 0.813 |
| 5.54 | 14/24 | 13/13 | 0.792 |
| 5.73 | 13/24 | 13/13 | 0.771 |
| 5.76 | 12/24 | 13/13 | 0.750 |
| 5.85 | 11/24 | 13/13 | 0.729 |
| 5.93 | 10/24 | 13/13 | 0.708 |
| 6.09 | 9/24 | 13/13 | 0.688 |
| 6.43 | 8/24 | 13/13 | 0.667 |
| 6.79 | 7/24 | 13/13 | 0.646 |
| 7.02 | 6/24 | 13/13 | 0.625 |
| 7.43 | 5/24 | 13/13 | 0.604 |
| 7.83 | 4/24 | 13/13 | 0.583 |
| 7.90 | 3/24 | 13/13 | 0.563 |
| 8.28 | 2/24 | 13/13 | 0.542 |
| 8.94 | 1/24 | 13/13 | 0.521 |

Held-out check with threshold 2.89:

- 10 of 11 held-out questions classified correctly.
- **in-32** (IN_CORPUS) scored -3.93 and was wrongly rejected (false "not found"): "How can a platform heal itself without anyone being paged?"

## Threshold at depth 12

Calibration split: lowest in-corpus top-1 = **-5.81**, highest out-of-corpus top-1 = **3.71** -> the groups **overlap**, no threshold is error-free.

Chosen threshold: **2.89** (balanced accuracy 0.857; in-corpus accepted 19/24, out-of-corpus rejected 12/13).

| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |
| ---: | ---: | ---: | ---: |
| -10.98 | 24/24 | 1/13 | 0.538 |
| -10.79 | 24/24 | 2/13 | 0.577 |
| -10.62 | 24/24 | 3/13 | 0.615 |
| -10.54 | 24/24 | 4/13 | 0.654 |
| -8.55 | 24/24 | 5/13 | 0.692 |
| -6.25 | 24/24 | 6/13 | 0.731 |
| -5.85 | 24/24 | 7/13 | 0.769 |
| -5.16 | 23/24 | 7/13 | 0.748 |
| -4.48 | 23/24 | 8/13 | 0.787 |
| -3.78 | 22/24 | 8/13 | 0.766 |
| -2.92 | 21/24 | 8/13 | 0.745 |
| -1.93 | 20/24 | 8/13 | 0.724 |
| -0.65 | 20/24 | 9/13 | 0.763 |
| 0.29 | 20/24 | 10/13 | 0.801 |
| 0.90 | 20/24 | 11/13 | 0.840 |
| 1.92 | 19/24 | 11/13 | 0.819 |
| 2.89 **(chosen)** | 19/24 | 12/13 | 0.857 |
| 3.07 | 18/24 | 12/13 | 0.837 |
| 3.43 | 17/24 | 12/13 | 0.816 |
| 3.72 | 17/24 | 13/13 | 0.854 |
| 4.03 | 16/24 | 13/13 | 0.833 |
| 4.85 | 15/24 | 13/13 | 0.813 |
| 5.54 | 14/24 | 13/13 | 0.792 |
| 5.73 | 13/24 | 13/13 | 0.771 |
| 5.76 | 12/24 | 13/13 | 0.750 |
| 5.85 | 11/24 | 13/13 | 0.729 |
| 5.93 | 10/24 | 13/13 | 0.708 |
| 6.09 | 9/24 | 13/13 | 0.688 |
| 6.43 | 8/24 | 13/13 | 0.667 |
| 6.79 | 7/24 | 13/13 | 0.646 |
| 7.02 | 6/24 | 13/13 | 0.625 |
| 7.43 | 5/24 | 13/13 | 0.604 |
| 7.83 | 4/24 | 13/13 | 0.583 |
| 7.90 | 3/24 | 13/13 | 0.563 |
| 8.28 | 2/24 | 13/13 | 0.542 |
| 8.94 | 1/24 | 13/13 | 0.521 |

Held-out check with threshold 2.89:

- 10 of 11 held-out questions classified correctly.
- **in-32** (IN_CORPUS) scored -3.93 and was wrongly rejected (false "not found"): "How can a platform heal itself without anyone being paged?"

## Out-of-corpus questions by score (depth 20)

| type | id | split | top-1 | best passage | question |
| --- | --- | --- | ---: | --- | --- |
| OUT_NEAR | out-near-05 | calibration | 3.71 | wa-serverless-applications-lens p30 | What are the pillars of the Google Cloud Architecture Framework? |
| OUT_NEAR | out-near-01 | calibration | 2.77 | wa-reliability-pillar p57 | How do I configure virtual network peering in Microsoft Azure? |
| OUT_AWS | out-aws-01 | calibration | 0.75 | wa-reliability-pillar p96 | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| OUT_AWS | out-aws-03 | calibration | -0.17 | wa-framework p891 | What is the maximum size of a single object in Amazon S3? |
| OUT_AWS | out-aws-02 | calibration | -1.14 | wa-cost-optimization-pillar p82 | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| OUT_AWS | out-aws-04 | holdout | -2.08 | wa-serverless-applications-lens p86 | What is the maximum size of an item in Amazon DynamoDB? |
| OUT_NEAR | out-near-02 | calibration | -4.52 | wa-framework p589 | How do I create a GKE Autopilot cluster on Google Cloud? |
| OUT_NEAR | out-near-03 | calibration | -5.90 | wa-framework p762 | How do I write a Helm chart values.yaml for a Redis deployment? |
| OUT_NEAR | out-near-06 | calibration | -6.60 | wa-framework p781 | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| OUT_NEAR | out-near-04 | holdout | -8.04 | wa-performance-efficiency-pillar p54 | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| OUT_FAR | out-far-01 | calibration | -8.97 | wa-reliability-pillar p6 | Who won the FIFA World Cup in 2018? |
| OUT_FAR | out-far-05 | calibration | -10.50 | disaster-recovery-workloads-on-aws p36 | Who wrote the novel Pride and Prejudice? |
| OUT_FAR | out-far-06 | calibration | -10.58 | wa-reliability-pillar p6 | What is the boiling point of water at sea level in Fahrenheit? |
| OUT_FAR | out-far-02 | calibration | -10.91 | wa-security-pillar p133 | What is a good recipe for chocolate chip cookies? |
| OUT_FAR | out-far-04 | holdout | -10.95 | wa-reliability-pillar p4 | How many moons does Jupiter have? |
| OUT_FAR | out-far-03 | calibration | -11.05 | wa-framework p812 | What is the capital city of Australia? |


---

# Detail: final

Final pipeline: headers kept (chunking v1), rerank depth 12, retrieval floor -6.5, high-confidence marker 3.3, expected pages reported both strict and widened.

Generated 2026-10-11T08:08:57.975253Z by `CalibrationRunner` on the real corpus.

- Embedding model: `gemini-embedding-2`
- Reranker: `ms-marco-MiniLM-L-6-v2/model.onnx`
- Fusion: `RANK`, fused top 20, final top 5
- The score is the cross-encoder's raw logit (not 0 to 1). The threshold applies to the best score.
- Each question is retrieved once with all 20 fused candidates scored; the "depth 12" columns use only the fused top 12 of those same scores.

## Every question

| id | split | type | top-1 d20 | top-1 d12 | best passage | expected at fused rank | in top 5 d20 | in top 5 d12 | top 5 d12 widened | truncated | question |
| --- | --- | --- | ---: | ---: | --- | ---: | :---: | :---: | :---: | ---: | --- |
| in-01 | calibration | IN_CORPUS | 9.04 | 9.04 | wa-framework p13 | 2 | yes | yes | yes | 0 | What are the six pillars of the AWS Well-Architected Framework? |
| in-02 | calibration | IN_CORPUS | 3.60 | 3.60 | wa-reliability-pillar p13 | 6 | yes | yes | yes | 1 | Why does the framework advise to stop guessing capacity needs? |
| in-03 | calibration | IN_CORPUS | -3.35 | -3.35 | wa-framework p69 | 3 | yes | yes | yes | 3 | What is the difference between one-way door and two-way door decisions? |
| in-04 | holdout | IN_CORPUS | 10.04 | 10.04 | wa-framework p7 | 3 | yes | yes | yes | 2 | What is the AWS Well-Architected Tool? |
| in-05 | calibration | IN_CORPUS | 7.51 | 7.51 | wa-reliability-pillar p19 | 2 | yes | yes | yes | 3 | What does recovery time objective (RTO) mean? |
| in-06 | calibration | IN_CORPUS | 3.48 | 3.48 | wa-framework p545 | 1 | no | no | yes | 3 | How should a client retry failed requests without overloading a service? |
| in-07 | calibration | IN_CORPUS | 7.62 | 7.62 | wa-framework p470 | 1 | yes | yes | yes | 3 | Why should a workload be aware of service quotas and constraints? |
| in-08 | holdout | IN_CORPUS | 4.99 | 4.99 | wa-reliability-pillar p197 | 1 | yes | yes | yes | 1 | How does the reliability pillar suggest recovering automatically from failures? |
| in-09 | calibration | IN_CORPUS | 5.86 | 5.86 | wa-security-pillar p9 | 3 | yes | yes | yes | 8 | Who is responsible for what in the shared responsibility model for security? |
| in-10 | calibration | IN_CORPUS | 5.48 | 5.48 | wa-framework p300 | 8 | no | no | yes | 1 | How should secrets and credentials be managed and rotated? |
| in-11 | calibration | IN_CORPUS | 4.85 | 4.85 | wa-security-pillar p70 | not in fused 20 | no | no | yes | 7 | What is the principle of least privilege? |
| in-12 | holdout | IN_CORPUS | 9.12 | 9.12 | wa-framework p399 | 1 | yes | yes | yes | 5 | What does secure key management include for protecting data at rest? |
| in-13 | calibration | IN_CORPUS | 7.07 | 6.86 | wa-operational-excellence-pillar p135 | 2 | yes | yes | yes | 2 | How does the operational excellence pillar recommend making changes to workloads? |
| in-14 | calibration | IN_CORPUS | 6.65 | 6.65 | wa-framework p592 | 4 | yes | yes | yes | 2 | What are runbooks used for? |
| in-15 | calibration | IN_CORPUS | 6.66 | 6.66 | wa-framework p128 | 1 | yes | yes | yes | 2 | How can feature flags and A/B testing be used to experiment safely? |
| in-16 | holdout | IN_CORPUS | 6.75 | 6.75 | wa-performance-efficiency-pillar p9 | 1 | yes | yes | yes | 2 | What does democratize advanced technologies mean in the performance efficiency pillar? |
| in-17 | calibration | IN_CORPUS | -6.23 | -6.23 | wa-performance-efficiency-pillar p9 | 1 | yes | yes | yes | 1 | What is mechanical sympathy? |
| in-18 | calibration | IN_CORPUS | 5.82 | 5.82 | wa-framework p744 | 1 | yes | yes | yes | 2 | Which database is suited to caching, session management and gaming leaderboards? |
| in-19 | calibration | IN_CORPUS | 6.21 | 6.21 | wa-serverless-applications-lens p60 | 1 | yes | yes | yes | 6 | How should failed Lambda transactions be retained and retried? |
| in-20 | holdout | IN_CORPUS | 7.83 | 7.83 | microservices-on-aws p12 | 2 | yes | yes | yes | 2 | What does Amazon API Gateway provide for serverless microservices? |
| in-21 | calibration | IN_CORPUS | -3.52 | -3.52 | wa-serverless-applications-lens p80 | not in fused 20 | no | no | no | 2 | What can be considered to reduce the impact of Lambda cold starts? |
| in-22 | calibration | IN_CORPUS | 5.85 | 5.85 | microservices-on-aws p17 | 1 | yes | yes | yes | 1 | What options does AWS offer for service discovery of microservices? |
| in-23 | calibration | IN_CORPUS | 3.59 | 3.59 | microservices-on-aws p18 | 1 | yes | yes | yes | 2 | Why should each microservice have its own data persistence layer? |
| in-24 | holdout | IN_CORPUS | 6.02 | 6.02 | microservices-on-aws p14 | 1 | yes | yes | yes | 2 | What do twelve-factor processes say about state? |
| out-far-01 | calibration | OUT_FAR | -10.80 | -10.80 | wa-framework p531 | - | - | - | - | 1 | Who won the FIFA World Cup in 2018? |
| out-far-02 | calibration | OUT_FAR | -9.57 | -10.99 | wa-performance-efficiency-pillar p4 | - | - | - | - | 3 | What is a good recipe for chocolate chip cookies? |
| out-far-03 | calibration | OUT_FAR | -11.07 | -11.07 | wa-framework p812 | - | - | - | - | 0 | What is the capital city of Australia? |
| out-far-04 | holdout | OUT_FAR | -8.15 | -8.15 | wa-framework p3 | - | - | - | - | 4 | How many moons does Jupiter have? |
| out-far-05 | calibration | OUT_FAR | -10.94 | -11.00 | wa-reliability-pillar p1 | - | - | - | - | 0 | Who wrote the novel Pride and Prejudice? |
| out-far-06 | calibration | OUT_FAR | -10.76 | -10.76 | wa-reliability-pillar p5 | - | - | - | - | 5 | What is the boiling point of water at sea level in Fahrenheit? |
| out-near-01 | calibration | OUT_NEAR | 2.57 | 2.57 | wa-reliability-pillar p57 | - | - | - | - | 1 | How do I configure virtual network peering in Microsoft Azure? |
| out-near-02 | calibration | OUT_NEAR | -5.04 | -7.14 | wa-framework p589 | - | - | - | - | 1 | How do I create a GKE Autopilot cluster on Google Cloud? |
| out-near-03 | calibration | OUT_NEAR | -5.23 | -5.23 | wa-framework p762 | - | - | - | - | 2 | How do I write a Helm chart values.yaml for a Redis deployment? |
| out-near-04 | holdout | OUT_NEAR | -8.39 | -8.39 | wa-performance-efficiency-pillar p54 | - | - | - | - | 2 | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| out-near-05 | calibration | OUT_NEAR | 3.17 | 3.17 | wa-framework p71 | - | - | - | - | 1 | What are the pillars of the Google Cloud Architecture Framework? |
| out-near-06 | calibration | OUT_NEAR | -6.52 | -6.52 | wa-framework p781 | - | - | - | - | 6 | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| in-25 | calibration | IN_CORPUS | 0.40 | 0.40 | wa-cost-optimization-pillar p17 | 2 | yes | yes | yes | 2 | What does it mean to adopt a consumption model? |
| in-26 | calibration | IN_CORPUS | 5.08 | 5.08 | wa-cost-optimization-pillar p28 | 1 | yes | yes | yes | 2 | How does the cost optimization pillar suggest reducing cloud waste? |
| in-27 | calibration | IN_CORPUS | 6.44 | 6.44 | wa-framework p62 | 1 | yes | yes | yes | 2 | Why does the choice of AWS Region matter for the carbon footprint of a workload? |
| in-28 | holdout | IN_CORPUS | 8.83 | 8.83 | wa-sustainability-pillar p7 | 1 | yes | yes | yes | 5 | What is the shared responsibility model for sustainability? |
| in-29 | calibration | IN_CORPUS | 8.39 | 8.39 | disaster-recovery-workloads-on-aws p18 | 1 | yes | yes | yes | 4 | What are the four disaster recovery strategies described for workloads on AWS? |
| in-30 | calibration | IN_CORPUS | 8.84 | 8.84 | wa-framework p692 | 3 | yes | yes | yes | 3 | What does AWS Elastic Disaster Recovery do? |
| in-31 | calibration | IN_CORPUS (paraphrase) | -6.38 | -6.38 | wa-cost-optimization-pillar p109 | 12 | no | no | yes | 2 | Why shouldn't staging servers keep running overnight and on weekends? |
| in-32 | holdout | IN_CORPUS (paraphrase) | -4.16 | -4.16 | wa-reliability-pillar p198 | not in fused 20 | no | no | yes | 1 | How can a platform heal itself without anyone being paged? |
| out-aws-01 | calibration | OUT_AWS | 1.14 | 1.14 | wa-reliability-pillar p96 | - | - | - | - | 4 | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| out-aws-02 | calibration | OUT_AWS | -1.56 | -1.56 | wa-cost-optimization-pillar p82 | - | - | - | - | 3 | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| out-aws-03 | calibration | OUT_AWS | -0.86 | -0.86 | wa-cost-optimization-pillar p98 | - | - | - | - | 3 | What is the maximum size of a single object in Amazon S3? |
| out-aws-04 | holdout | OUT_AWS | -2.06 | -2.06 | wa-serverless-applications-lens p86 | - | - | - | - | 2 | What is the maximum size of an item in Amazon DynamoDB? |

## Recall of the answer-bearing page (in-corpus questions), strict / widened

Strict: the page contains the question's evidence phrase (fixed before the first run). Widened: also the adjacent, near-duplicate or equivalent pages added afterwards (rules and reasons: Findings, section 2). Each cell reads strict / widened.

| set | n | in fused top 20 | in fused top 12 | in final top 5, depth 20 | in final top 5, depth 12 |
| --- | ---: | ---: | ---: | ---: | ---: |
| calibration | 24 | 22 / 23 | 22 / 23 | 19 / 23 | 19 / 23 |
| holdout | 8 | 7 / 8 | 7 / 8 | 7 / 8 | 7 / 8 |
| paraphrased (semantic path) | 2 | 1 / 2 | 1 / 2 | 0 / 2 | 0 / 2 |
| all | 32 | 29 / 31 | 29 / 31 | 26 / 31 | 26 / 31 |

## Retrieval floor and high-confidence marker (depth 12, the configured depth)

Floor **-6.50**: a top score below it gives NO_RELEVANT_CONTEXT, at or above it the chunks go to the answering step. High-confidence marker **3.30**: trace only, no behaviour change.

| type | n | below floor (rejected here) | at or above floor (passed on) | of those, high confidence |
| --- | ---: | ---: | ---: | ---: |
| IN_CORPUS | 32 | 0 | 32 | 26 |
| OUT_FAR | 6 | 6 | 0 | 0 |
| OUT_NEAR | 6 | 3 | 3 | 0 |
| OUT_AWS | 4 | 0 | 4 | 0 |

Out-of-corpus questions that pass the floor are not wrongly answered by retrieval: they are handed to the answering step, whose citation and quote check must reject them.

## Rerank depth: 12 or 20

Questions that lose the answer page from the final top 5 when only 12 candidates are reranked: none.

**Recall holds at depth 12** on this set, so 12 is supported (it scores 40% fewer pairs).

## Threshold at depth 20

Calibration split: lowest in-corpus top-1 = **-6.38**, highest out-of-corpus top-1 = **3.17** -> the groups **overlap**, no threshold is error-free.

Chosen threshold: **3.33** (balanced accuracy 0.896; in-corpus accepted 19/24, out-of-corpus rejected 13/13).

| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |
| ---: | ---: | ---: | ---: |
| -11.01 | 24/24 | 1/13 | 0.538 |
| -10.87 | 24/24 | 2/13 | 0.577 |
| -10.78 | 24/24 | 3/13 | 0.615 |
| -10.17 | 24/24 | 4/13 | 0.654 |
| -8.05 | 24/24 | 5/13 | 0.692 |
| -6.45 | 24/24 | 6/13 | 0.731 |
| -6.30 | 23/24 | 6/13 | 0.710 |
| -5.73 | 22/24 | 6/13 | 0.689 |
| -5.13 | 22/24 | 7/13 | 0.728 |
| -4.28 | 22/24 | 8/13 | 0.766 |
| -3.43 | 21/24 | 8/13 | 0.745 |
| -2.45 | 20/24 | 8/13 | 0.724 |
| -1.21 | 20/24 | 9/13 | 0.763 |
| -0.23 | 20/24 | 10/13 | 0.801 |
| 0.77 | 19/24 | 10/13 | 0.780 |
| 1.86 | 19/24 | 11/13 | 0.819 |
| 2.87 | 19/24 | 12/13 | 0.857 |
| 3.33 **(chosen)** | 19/24 | 13/13 | 0.896 |
| 3.54 | 18/24 | 13/13 | 0.875 |
| 3.60 | 17/24 | 13/13 | 0.854 |
| 4.23 | 16/24 | 13/13 | 0.833 |
| 4.96 | 15/24 | 13/13 | 0.813 |
| 5.28 | 14/24 | 13/13 | 0.792 |
| 5.65 | 13/24 | 13/13 | 0.771 |
| 5.84 | 12/24 | 13/13 | 0.750 |
| 5.86 | 11/24 | 13/13 | 0.729 |
| 6.04 | 10/24 | 13/13 | 0.708 |
| 6.32 | 9/24 | 13/13 | 0.688 |
| 6.55 | 8/24 | 13/13 | 0.667 |
| 6.65 | 7/24 | 13/13 | 0.646 |
| 6.86 | 6/24 | 13/13 | 0.625 |
| 7.29 | 5/24 | 13/13 | 0.604 |
| 7.56 | 4/24 | 13/13 | 0.583 |
| 8.00 | 3/24 | 13/13 | 0.563 |
| 8.61 | 2/24 | 13/13 | 0.542 |
| 8.94 | 1/24 | 13/13 | 0.521 |

Held-out check with threshold 3.33:

- 10 of 11 held-out questions classified correctly.
- **in-32** (IN_CORPUS) scored -4.16 and was wrongly rejected (false "not found"): "How can a platform heal itself without anyone being paged?"

## Threshold at depth 12

Calibration split: lowest in-corpus top-1 = **-6.38**, highest out-of-corpus top-1 = **3.17** -> the groups **overlap**, no threshold is error-free.

Chosen threshold: **3.33** (balanced accuracy 0.896; in-corpus accepted 19/24, out-of-corpus rejected 13/13).

| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |
| ---: | ---: | ---: | ---: |
| -11.03 | 24/24 | 1/13 | 0.538 |
| -10.99 | 24/24 | 2/13 | 0.577 |
| -10.89 | 24/24 | 3/13 | 0.615 |
| -10.78 | 24/24 | 4/13 | 0.654 |
| -8.95 | 24/24 | 5/13 | 0.692 |
| -6.83 | 24/24 | 6/13 | 0.731 |
| -6.45 | 24/24 | 7/13 | 0.769 |
| -6.30 | 23/24 | 7/13 | 0.748 |
| -5.73 | 22/24 | 7/13 | 0.728 |
| -4.38 | 22/24 | 8/13 | 0.766 |
| -3.43 | 21/24 | 8/13 | 0.745 |
| -2.45 | 20/24 | 8/13 | 0.724 |
| -1.21 | 20/24 | 9/13 | 0.763 |
| -0.23 | 20/24 | 10/13 | 0.801 |
| 0.77 | 19/24 | 10/13 | 0.780 |
| 1.86 | 19/24 | 11/13 | 0.819 |
| 2.87 | 19/24 | 12/13 | 0.857 |
| 3.33 **(chosen)** | 19/24 | 13/13 | 0.896 |
| 3.54 | 18/24 | 13/13 | 0.875 |
| 3.60 | 17/24 | 13/13 | 0.854 |
| 4.23 | 16/24 | 13/13 | 0.833 |
| 4.96 | 15/24 | 13/13 | 0.813 |
| 5.28 | 14/24 | 13/13 | 0.792 |
| 5.65 | 13/24 | 13/13 | 0.771 |
| 5.84 | 12/24 | 13/13 | 0.750 |
| 5.86 | 11/24 | 13/13 | 0.729 |
| 6.04 | 10/24 | 13/13 | 0.708 |
| 6.32 | 9/24 | 13/13 | 0.688 |
| 6.55 | 8/24 | 13/13 | 0.667 |
| 6.65 | 7/24 | 13/13 | 0.646 |
| 6.76 | 6/24 | 13/13 | 0.625 |
| 7.19 | 5/24 | 13/13 | 0.604 |
| 7.56 | 4/24 | 13/13 | 0.583 |
| 8.00 | 3/24 | 13/13 | 0.563 |
| 8.61 | 2/24 | 13/13 | 0.542 |
| 8.94 | 1/24 | 13/13 | 0.521 |

Held-out check with threshold 3.33:

- 10 of 11 held-out questions classified correctly.
- **in-32** (IN_CORPUS) scored -4.16 and was wrongly rejected (false "not found"): "How can a platform heal itself without anyone being paged?"

## Out-of-corpus questions by score (depth 20)

| type | id | split | top-1 | best passage | question |
| --- | --- | --- | ---: | --- | --- |
| OUT_NEAR | out-near-05 | calibration | 3.17 | wa-framework p71 | What are the pillars of the Google Cloud Architecture Framework? |
| OUT_NEAR | out-near-01 | calibration | 2.57 | wa-reliability-pillar p57 | How do I configure virtual network peering in Microsoft Azure? |
| OUT_AWS | out-aws-01 | calibration | 1.14 | wa-reliability-pillar p96 | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| OUT_AWS | out-aws-03 | calibration | -0.86 | wa-cost-optimization-pillar p98 | What is the maximum size of a single object in Amazon S3? |
| OUT_AWS | out-aws-02 | calibration | -1.56 | wa-cost-optimization-pillar p82 | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| OUT_AWS | out-aws-04 | holdout | -2.06 | wa-serverless-applications-lens p86 | What is the maximum size of an item in Amazon DynamoDB? |
| OUT_NEAR | out-near-02 | calibration | -5.04 | wa-framework p589 | How do I create a GKE Autopilot cluster on Google Cloud? |
| OUT_NEAR | out-near-03 | calibration | -5.23 | wa-framework p762 | How do I write a Helm chart values.yaml for a Redis deployment? |
| OUT_NEAR | out-near-06 | calibration | -6.52 | wa-framework p781 | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| OUT_FAR | out-far-04 | holdout | -8.15 | wa-framework p3 | How many moons does Jupiter have? |
| OUT_NEAR | out-near-04 | holdout | -8.39 | wa-performance-efficiency-pillar p54 | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| OUT_FAR | out-far-02 | calibration | -9.57 | wa-performance-efficiency-pillar p4 | What is a good recipe for chocolate chip cookies? |
| OUT_FAR | out-far-06 | calibration | -10.76 | wa-reliability-pillar p5 | What is the boiling point of water at sea level in Fahrenheit? |
| OUT_FAR | out-far-01 | calibration | -10.80 | wa-framework p531 | Who won the FIFA World Cup in 2018? |
| OUT_FAR | out-far-05 | calibration | -10.94 | wa-reliability-pillar p1 | Who wrote the novel Pride and Prejudice? |
| OUT_FAR | out-far-03 | calibration | -11.07 | wa-framework p812 | What is the capital city of Australia? |

