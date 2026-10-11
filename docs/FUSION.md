# Grounding threshold calibration: rank vs score

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

| | rank | score |
| --- | --- | --- |
| What differs | RANK | SCORE |
| Generated | 2026-10-11T13:55:15.619136200Z | 2026-10-11T13:57:56.291779200Z |
| Embedding / reranker | `gemini-embedding-2` / `ms-marco-MiniLM-L-6-v2/model.onnx` | `gemini-embedding-2` / `ms-marco-MiniLM-L-6-v2/model.onnx` |

## Side by side

| metric | rank | score | change |
| --- | ---: | ---: | ---: |
| in-corpus questions | 32 | 32 | +0 |
| answer page in fused top 20 | 29 | 28 | -1 |
| answer page in fused top 12 | 29 | 27 | -2 |
| answer page in final top 5 (rerank 20) | 26 | 26 | +0 |
| answer page in final top 5 (rerank 12) | 26 | 25 | -1 |
| paraphrased questions: 2, in final top 5 (rerank 20) | 0 | 0 | +0 |
| mean top-1 score, in-corpus (depth 20) | 4.64 | 4.55 | -0.09 |
| highest top-1 score, OUT_FAR | -8.15 | -10.80 | -2.65 |
| highest top-1 score, OUT_NEAR | 3.17 | 2.57 | -0.60 |
| highest top-1 score, OUT_AWS | 1.14 | 1.14 | +0.00 |
| threshold chosen (depth 20) | 3.33 | 3.03 | -0.30 |
| balanced accuracy at that threshold (depth 20) | 0.90 | 0.90 | +0.00 |
| groups separable at depth 20 | no | no | |
| held-out questions classified correctly (depth 20) | 10 | 10 | +0 |
| mean rerank ms per question | 1910.21 | 2101.19 | +190.98 |

**Verdict on final top 5 (rerank 20):** no change (26 of 32).

## Per question

| id | type | rank top-1 | score top-1 | rank fused rank | score fused rank | rank top 5 | score top 5 | question |
| --- | --- | ---: | ---: | ---: | ---: | :---: | :---: | --- |
| in-01 | IN_CORPUS | 9.04 | 9.04 | 2 | 2 | yes | yes | What are the six pillars of the AWS Well-Architected Framework? |
| in-02 | IN_CORPUS | 3.60 | 3.60 | 6 | 6 | yes | yes | Why does the framework advise to stop guessing capacity needs? |
| in-03 | IN_CORPUS | -3.35 | -3.35 | 3 | 5 | yes | yes | What is the difference between one-way door and two-way door decisions? |
| in-04 | IN_CORPUS | 10.04 | 7.87 | 3 | not in fused 20 | yes | no | What is the AWS Well-Architected Tool? |
| in-05 | IN_CORPUS | 7.51 | 7.51 | 2 | 2 | yes | yes | What does recovery time objective (RTO) mean? |
| in-06 | IN_CORPUS | 3.48 | 3.48 | 1 | 1 | no | no | How should a client retry failed requests without overloading a service? |
| in-07 | IN_CORPUS | 7.62 | 7.62 | 1 | 2 | yes | yes | Why should a workload be aware of service quotas and constraints? |
| in-08 | IN_CORPUS | 4.99 | 4.99 | 1 | 1 | yes | yes | How does the reliability pillar suggest recovering automatically from failures? |
| in-09 | IN_CORPUS | 5.86 | 5.86 | 3 | 1 | yes | yes | Who is responsible for what in the shared responsibility model for security? |
| in-10 | IN_CORPUS | 5.48 | 5.48 | 8 | 7 | no | no | How should secrets and credentials be managed and rotated? |
| in-11 | IN_CORPUS | 4.85 | 4.85 | not in fused 20 | 17 | no | yes | What is the principle of least privilege? |
| in-12 | IN_CORPUS | 9.12 | 9.12 | 1 | 1 | yes | yes | What does secure key management include for protecting data at rest? |
| in-13 | IN_CORPUS | 7.07 | 6.37 | 2 | 2 | yes | yes | How does the operational excellence pillar recommend making changes to workloads? |
| in-14 | IN_CORPUS | 6.65 | 6.65 | 4 | 7 | yes | yes | What are runbooks used for? |
| in-15 | IN_CORPUS | 6.66 | 6.66 | 1 | 1 | yes | yes | How can feature flags and A/B testing be used to experiment safely? |
| in-16 | IN_CORPUS | 6.75 | 6.75 | 1 | 1 | yes | yes | What does democratize advanced technologies mean in the performance efficiency pillar? |
| in-17 | IN_CORPUS | -6.23 | -6.23 | 1 | 1 | yes | yes | What is mechanical sympathy? |
| in-18 | IN_CORPUS | 5.82 | 5.82 | 1 | 1 | yes | yes | Which database is suited to caching, session management and gaming leaderboards? |
| in-19 | IN_CORPUS | 6.21 | 6.21 | 1 | 1 | yes | yes | How should failed Lambda transactions be retained and retried? |
| in-20 | IN_CORPUS | 7.83 | 7.83 | 2 | 3 | yes | yes | What does Amazon API Gateway provide for serverless microservices? |
| in-21 | IN_CORPUS | -3.52 | -3.52 | not in fused 20 | not in fused 20 | no | no | What can be considered to reduce the impact of Lambda cold starts? |
| in-22 | IN_CORPUS | 5.85 | 5.85 | 1 | 1 | yes | yes | What options does AWS offer for service discovery of microservices? |
| in-23 | IN_CORPUS | 3.59 | 3.59 | 1 | 1 | yes | yes | Why should each microservice have its own data persistence layer? |
| in-24 | IN_CORPUS | 6.02 | 6.02 | 1 | 1 | yes | yes | What do twelve-factor processes say about state? |
| out-far-01 | OUT_FAR | -10.80 | -10.80 | - | - | - | - | Who won the FIFA World Cup in 2018? |
| out-far-02 | OUT_FAR | -9.57 | -10.99 | - | - | - | - | What is a good recipe for chocolate chip cookies? |
| out-far-03 | OUT_FAR | -11.07 | -11.07 | - | - | - | - | What is the capital city of Australia? |
| out-far-04 | OUT_FAR | -8.15 | -11.27 | - | - | - | - | How many moons does Jupiter have? |
| out-far-05 | OUT_FAR | -10.94 | -11.09 | - | - | - | - | Who wrote the novel Pride and Prejudice? |
| out-far-06 | OUT_FAR | -10.76 | -11.12 | - | - | - | - | What is the boiling point of water at sea level in Fahrenheit? |
| out-near-01 | OUT_NEAR | 2.57 | 2.57 | - | - | - | - | How do I configure virtual network peering in Microsoft Azure? |
| out-near-02 | OUT_NEAR | -5.04 | -7.14 | - | - | - | - | How do I create a GKE Autopilot cluster on Google Cloud? |
| out-near-03 | OUT_NEAR | -5.23 | -5.23 | - | - | - | - | How do I write a Helm chart values.yaml for a Redis deployment? |
| out-near-04 | OUT_NEAR | -8.39 | -8.39 | - | - | - | - | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| out-near-05 | OUT_NEAR | 3.17 | 0.55 | - | - | - | - | What are the pillars of the Google Cloud Architecture Framework? |
| out-near-06 | OUT_NEAR | -6.52 | -6.52 | - | - | - | - | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| in-25 | IN_CORPUS | 0.40 | 0.40 | 2 | 3 | yes | yes | What does it mean to adopt a consumption model? |
| in-26 | IN_CORPUS | 5.08 | 5.08 | 1 | 1 | yes | yes | How does the cost optimization pillar suggest reducing cloud waste? |
| in-27 | IN_CORPUS | 6.44 | 6.44 | 1 | 2 | yes | yes | Why does the choice of AWS Region matter for the carbon footprint of a workload? |
| in-28 | IN_CORPUS | 8.83 | 8.83 | 1 | 1 | yes | yes | What is the shared responsibility model for sustainability? |
| in-29 | IN_CORPUS | 8.39 | 8.39 | 1 | 2 | yes | yes | What are the four disaster recovery strategies described for workloads on AWS? |
| in-30 | IN_CORPUS | 8.84 | 8.84 | 3 | 3 | yes | yes | What does AWS Elastic Disaster Recovery do? |
| in-31 | IN_CORPUS (paraphrase) | -6.38 | -6.38 | 12 | not in fused 20 | no | no | Why shouldn't staging servers keep running overnight and on weekends? |
| in-32 | IN_CORPUS (paraphrase) | -4.16 | -4.16 | not in fused 20 | not in fused 20 | no | no | How can a platform heal itself without anyone being paged? |
| out-aws-01 | OUT_AWS | 1.14 | 1.14 | - | - | - | - | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| out-aws-02 | OUT_AWS | -1.56 | -1.56 | - | - | - | - | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| out-aws-03 | OUT_AWS | -0.86 | -0.86 | - | - | - | - | What is the maximum size of a single object in Amazon S3? |
| out-aws-04 | OUT_AWS | -2.06 | -3.18 | - | - | - | - | What is the maximum size of an item in Amazon DynamoDB? |

---

# Detail: rank

Generated 2026-10-11T13:55:15.619136200Z by `CalibrationRunner` on the real corpus.

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


---

# Detail: score

Generated 2026-10-11T13:57:56.291779200Z by `CalibrationRunner` on the real corpus.

- Embedding model: `gemini-embedding-2`
- Reranker: `ms-marco-MiniLM-L-6-v2/model.onnx`
- Fusion: `SCORE`, fused top 20, final top 5
- The score is the cross-encoder's raw logit (not 0 to 1). The threshold applies to the best score.
- Each question is retrieved once with all 20 fused candidates scored; the "depth 12" columns use only the fused top 12 of those same scores.

## Every question

| id | split | type | top-1 d20 | top-1 d12 | best passage | expected at fused rank | in top 5 d20 | in top 5 d12 | top 5 d12 widened | truncated | question |
| --- | --- | --- | ---: | ---: | --- | ---: | :---: | :---: | :---: | ---: | --- |
| in-01 | calibration | IN_CORPUS | 9.04 | 9.04 | wa-framework p13 | 2 | yes | yes | yes | 1 | What are the six pillars of the AWS Well-Architected Framework? |
| in-02 | calibration | IN_CORPUS | 3.60 | 3.60 | wa-reliability-pillar p13 | 6 | yes | yes | yes | 3 | Why does the framework advise to stop guessing capacity needs? |
| in-03 | calibration | IN_CORPUS | -3.35 | -3.35 | wa-framework p69 | 5 | yes | yes | yes | 2 | What is the difference between one-way door and two-way door decisions? |
| in-04 | holdout | IN_CORPUS | 7.87 | 7.87 | wa-framework p790 | not in fused 20 | no | no | no | 1 | What is the AWS Well-Architected Tool? |
| in-05 | calibration | IN_CORPUS | 7.51 | 7.51 | wa-reliability-pillar p19 | 2 | yes | yes | yes | 2 | What does recovery time objective (RTO) mean? |
| in-06 | calibration | IN_CORPUS | 3.48 | 3.48 | wa-framework p545 | 1 | no | no | yes | 3 | How should a client retry failed requests without overloading a service? |
| in-07 | calibration | IN_CORPUS | 7.62 | 7.62 | wa-framework p470 | 2 | yes | yes | yes | 2 | Why should a workload be aware of service quotas and constraints? |
| in-08 | holdout | IN_CORPUS | 4.99 | 4.99 | wa-reliability-pillar p197 | 1 | yes | yes | yes | 0 | How does the reliability pillar suggest recovering automatically from failures? |
| in-09 | calibration | IN_CORPUS | 5.86 | 5.86 | wa-security-pillar p9 | 1 | yes | yes | yes | 6 | Who is responsible for what in the shared responsibility model for security? |
| in-10 | calibration | IN_CORPUS | 5.48 | 5.48 | wa-framework p300 | 7 | no | no | yes | 0 | How should secrets and credentials be managed and rotated? |
| in-11 | calibration | IN_CORPUS | 4.85 | 4.85 | wa-security-pillar p70 | 17 | yes | no | yes | 5 | What is the principle of least privilege? |
| in-12 | holdout | IN_CORPUS | 9.12 | 9.12 | wa-framework p399 | 1 | yes | yes | yes | 5 | What does secure key management include for protecting data at rest? |
| in-13 | calibration | IN_CORPUS | 6.37 | 6.37 | wa-operational-excellence-pillar p9 | 2 | yes | yes | yes | 1 | How does the operational excellence pillar recommend making changes to workloads? |
| in-14 | calibration | IN_CORPUS | 6.65 | 6.65 | wa-framework p592 | 7 | yes | yes | yes | 2 | What are runbooks used for? |
| in-15 | calibration | IN_CORPUS | 6.66 | 6.66 | wa-framework p128 | 1 | yes | yes | yes | 2 | How can feature flags and A/B testing be used to experiment safely? |
| in-16 | holdout | IN_CORPUS | 6.75 | 6.75 | wa-performance-efficiency-pillar p9 | 1 | yes | yes | yes | 0 | What does democratize advanced technologies mean in the performance efficiency pillar? |
| in-17 | calibration | IN_CORPUS | -6.23 | -6.23 | wa-performance-efficiency-pillar p9 | 1 | yes | yes | yes | 1 | What is mechanical sympathy? |
| in-18 | calibration | IN_CORPUS | 5.82 | 5.82 | wa-framework p744 | 1 | yes | yes | yes | 4 | Which database is suited to caching, session management and gaming leaderboards? |
| in-19 | calibration | IN_CORPUS | 6.21 | 6.21 | wa-serverless-applications-lens p60 | 1 | yes | yes | yes | 5 | How should failed Lambda transactions be retained and retried? |
| in-20 | holdout | IN_CORPUS | 7.83 | 7.83 | microservices-on-aws p12 | 3 | yes | yes | yes | 2 | What does Amazon API Gateway provide for serverless microservices? |
| in-21 | calibration | IN_CORPUS | -3.52 | -3.52 | wa-serverless-applications-lens p80 | not in fused 20 | no | no | no | 1 | What can be considered to reduce the impact of Lambda cold starts? |
| in-22 | calibration | IN_CORPUS | 5.85 | 5.85 | microservices-on-aws p17 | 1 | yes | yes | yes | 4 | What options does AWS offer for service discovery of microservices? |
| in-23 | calibration | IN_CORPUS | 3.59 | 3.59 | microservices-on-aws p18 | 1 | yes | yes | yes | 5 | Why should each microservice have its own data persistence layer? |
| in-24 | holdout | IN_CORPUS | 6.02 | 6.02 | microservices-on-aws p14 | 1 | yes | yes | yes | 2 | What do twelve-factor processes say about state? |
| out-far-01 | calibration | OUT_FAR | -10.80 | -10.80 | wa-framework p531 | - | - | - | - | 1 | Who won the FIFA World Cup in 2018? |
| out-far-02 | calibration | OUT_FAR | -10.99 | -10.99 | wa-security-pillar p133 | - | - | - | - | 2 | What is a good recipe for chocolate chip cookies? |
| out-far-03 | calibration | OUT_FAR | -11.07 | -11.07 | wa-framework p812 | - | - | - | - | 1 | What is the capital city of Australia? |
| out-far-04 | holdout | OUT_FAR | -11.27 | -11.27 | wa-reliability-pillar p17 | - | - | - | - | 1 | How many moons does Jupiter have? |
| out-far-05 | calibration | OUT_FAR | -11.09 | -11.09 | wa-serverless-applications-lens p16 | - | - | - | - | 0 | Who wrote the novel Pride and Prejudice? |
| out-far-06 | calibration | OUT_FAR | -11.12 | -11.12 | wa-sustainability-pillar p7 | - | - | - | - | 3 | What is the boiling point of water at sea level in Fahrenheit? |
| out-near-01 | calibration | OUT_NEAR | 2.57 | 2.57 | wa-reliability-pillar p57 | - | - | - | - | 1 | How do I configure virtual network peering in Microsoft Azure? |
| out-near-02 | calibration | OUT_NEAR | -7.14 | -7.14 | wa-reliability-pillar p184 | - | - | - | - | 2 | How do I create a GKE Autopilot cluster on Google Cloud? |
| out-near-03 | calibration | OUT_NEAR | -5.23 | -5.23 | wa-framework p762 | - | - | - | - | 0 | How do I write a Helm chart values.yaml for a Redis deployment? |
| out-near-04 | holdout | OUT_NEAR | -8.39 | -8.39 | wa-performance-efficiency-pillar p54 | - | - | - | - | 3 | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| out-near-05 | calibration | OUT_NEAR | 0.55 | 0.55 | wa-security-pillar p48 | - | - | - | - | 0 | What are the pillars of the Google Cloud Architecture Framework? |
| out-near-06 | calibration | OUT_NEAR | -6.52 | -6.52 | wa-framework p781 | - | - | - | - | 4 | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| in-25 | calibration | IN_CORPUS | 0.40 | 0.40 | wa-cost-optimization-pillar p17 | 3 | yes | yes | yes | 3 | What does it mean to adopt a consumption model? |
| in-26 | calibration | IN_CORPUS | 5.08 | 5.08 | wa-cost-optimization-pillar p28 | 1 | yes | yes | yes | 2 | How does the cost optimization pillar suggest reducing cloud waste? |
| in-27 | calibration | IN_CORPUS | 6.44 | 6.44 | wa-framework p62 | 2 | yes | yes | yes | 2 | Why does the choice of AWS Region matter for the carbon footprint of a workload? |
| in-28 | holdout | IN_CORPUS | 8.83 | 8.83 | wa-sustainability-pillar p7 | 1 | yes | yes | yes | 7 | What is the shared responsibility model for sustainability? |
| in-29 | calibration | IN_CORPUS | 8.39 | 8.39 | disaster-recovery-workloads-on-aws p18 | 2 | yes | yes | yes | 2 | What are the four disaster recovery strategies described for workloads on AWS? |
| in-30 | calibration | IN_CORPUS | 8.84 | 8.84 | wa-framework p692 | 3 | yes | yes | yes | 1 | What does AWS Elastic Disaster Recovery do? |
| in-31 | calibration | IN_CORPUS (paraphrase) | -6.38 | -6.38 | wa-cost-optimization-pillar p109 | not in fused 20 | no | no | no | 5 | Why shouldn't staging servers keep running overnight and on weekends? |
| in-32 | holdout | IN_CORPUS (paraphrase) | -4.16 | -4.16 | wa-reliability-pillar p198 | not in fused 20 | no | no | yes | 2 | How can a platform heal itself without anyone being paged? |
| out-aws-01 | calibration | OUT_AWS | 1.14 | 1.14 | wa-reliability-pillar p96 | - | - | - | - | 6 | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| out-aws-02 | calibration | OUT_AWS | -1.56 | -1.56 | wa-cost-optimization-pillar p82 | - | - | - | - | 4 | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| out-aws-03 | calibration | OUT_AWS | -0.86 | -0.86 | wa-cost-optimization-pillar p98 | - | - | - | - | 3 | What is the maximum size of a single object in Amazon S3? |
| out-aws-04 | holdout | OUT_AWS | -3.18 | -3.30 | wa-serverless-applications-lens p22 | - | - | - | - | 3 | What is the maximum size of an item in Amazon DynamoDB? |

## Recall of the answer-bearing page (in-corpus questions), strict / widened

Strict: the page contains the question's evidence phrase (fixed before the first run). Widened: also the adjacent, near-duplicate or equivalent pages added afterwards (rules and reasons: Findings, section 2). Each cell reads strict / widened.

| set | n | in fused top 20 | in fused top 12 | in final top 5, depth 20 | in final top 5, depth 12 |
| --- | ---: | ---: | ---: | ---: | ---: |
| calibration | 24 | 22 / 22 | 21 / 22 | 20 / 22 | 19 / 22 |
| holdout | 8 | 6 / 7 | 6 / 7 | 6 / 7 | 6 / 7 |
| paraphrased (semantic path) | 2 | 0 / 1 | 0 / 1 | 0 / 1 | 0 / 1 |
| all | 32 | 28 / 29 | 27 / 29 | 26 / 29 | 25 / 29 |

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

Questions that lose the answer page from the final top 5 when only 12 candidates are reranked: in-11.

**Recall does not hold at depth 12** on this set; keep 20.

## Threshold at depth 20

Calibration split: lowest in-corpus top-1 = **-6.38**, highest out-of-corpus top-1 = **2.57** -> the groups **overlap**, no threshold is error-free.

Chosen threshold: **3.03** (balanced accuracy 0.896; in-corpus accepted 19/24, out-of-corpus rejected 13/13).

| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |
| ---: | ---: | ---: | ---: |
| -11.11 | 24/24 | 1/13 | 0.538 |
| -11.08 | 24/24 | 2/13 | 0.577 |
| -11.03 | 24/24 | 3/13 | 0.615 |
| -10.89 | 24/24 | 4/13 | 0.654 |
| -8.97 | 24/24 | 5/13 | 0.692 |
| -6.83 | 24/24 | 6/13 | 0.731 |
| -6.45 | 24/24 | 7/13 | 0.769 |
| -6.30 | 23/24 | 7/13 | 0.748 |
| -5.73 | 22/24 | 7/13 | 0.728 |
| -4.38 | 22/24 | 8/13 | 0.766 |
| -3.43 | 21/24 | 8/13 | 0.745 |
| -2.45 | 20/24 | 8/13 | 0.724 |
| -1.21 | 20/24 | 9/13 | 0.763 |
| -0.23 | 20/24 | 10/13 | 0.801 |
| 0.47 | 19/24 | 10/13 | 0.780 |
| 0.84 | 19/24 | 11/13 | 0.819 |
| 1.86 | 19/24 | 12/13 | 0.857 |
| 3.03 **(chosen)** | 19/24 | 13/13 | 0.896 |
| 3.54 | 18/24 | 13/13 | 0.875 |
| 3.60 | 17/24 | 13/13 | 0.854 |
| 4.23 | 16/24 | 13/13 | 0.833 |
| 4.96 | 15/24 | 13/13 | 0.813 |
| 5.28 | 14/24 | 13/13 | 0.792 |
| 5.65 | 13/24 | 13/13 | 0.771 |
| 5.84 | 12/24 | 13/13 | 0.750 |
| 5.86 | 11/24 | 13/13 | 0.729 |
| 6.04 | 10/24 | 13/13 | 0.708 |
| 6.29 | 9/24 | 13/13 | 0.688 |
| 6.41 | 8/24 | 13/13 | 0.667 |
| 6.55 | 7/24 | 13/13 | 0.646 |
| 6.65 | 6/24 | 13/13 | 0.625 |
| 7.08 | 5/24 | 13/13 | 0.604 |
| 7.56 | 4/24 | 13/13 | 0.583 |
| 8.00 | 3/24 | 13/13 | 0.563 |
| 8.61 | 2/24 | 13/13 | 0.542 |
| 8.94 | 1/24 | 13/13 | 0.521 |

Held-out check with threshold 3.03:

- 10 of 11 held-out questions classified correctly.
- **in-32** (IN_CORPUS) scored -4.16 and was wrongly rejected (false "not found"): "How can a platform heal itself without anyone being paged?"

## Threshold at depth 12

Calibration split: lowest in-corpus top-1 = **-6.38**, highest out-of-corpus top-1 = **2.57** -> the groups **overlap**, no threshold is error-free.

Chosen threshold: **3.03** (balanced accuracy 0.896; in-corpus accepted 19/24, out-of-corpus rejected 13/13).

| candidate threshold | accepts in-corpus | rejects out-of-corpus | balanced accuracy |
| ---: | ---: | ---: | ---: |
| -11.11 | 24/24 | 1/13 | 0.538 |
| -11.08 | 24/24 | 2/13 | 0.577 |
| -11.03 | 24/24 | 3/13 | 0.615 |
| -10.89 | 24/24 | 4/13 | 0.654 |
| -8.97 | 24/24 | 5/13 | 0.692 |
| -6.83 | 24/24 | 6/13 | 0.731 |
| -6.45 | 24/24 | 7/13 | 0.769 |
| -6.30 | 23/24 | 7/13 | 0.748 |
| -5.73 | 22/24 | 7/13 | 0.728 |
| -4.38 | 22/24 | 8/13 | 0.766 |
| -3.43 | 21/24 | 8/13 | 0.745 |
| -2.45 | 20/24 | 8/13 | 0.724 |
| -1.21 | 20/24 | 9/13 | 0.763 |
| -0.23 | 20/24 | 10/13 | 0.801 |
| 0.47 | 19/24 | 10/13 | 0.780 |
| 0.84 | 19/24 | 11/13 | 0.819 |
| 1.86 | 19/24 | 12/13 | 0.857 |
| 3.03 **(chosen)** | 19/24 | 13/13 | 0.896 |
| 3.54 | 18/24 | 13/13 | 0.875 |
| 3.60 | 17/24 | 13/13 | 0.854 |
| 4.23 | 16/24 | 13/13 | 0.833 |
| 4.96 | 15/24 | 13/13 | 0.813 |
| 5.28 | 14/24 | 13/13 | 0.792 |
| 5.65 | 13/24 | 13/13 | 0.771 |
| 5.84 | 12/24 | 13/13 | 0.750 |
| 5.86 | 11/24 | 13/13 | 0.729 |
| 6.04 | 10/24 | 13/13 | 0.708 |
| 6.29 | 9/24 | 13/13 | 0.688 |
| 6.41 | 8/24 | 13/13 | 0.667 |
| 6.55 | 7/24 | 13/13 | 0.646 |
| 6.65 | 6/24 | 13/13 | 0.625 |
| 7.08 | 5/24 | 13/13 | 0.604 |
| 7.56 | 4/24 | 13/13 | 0.583 |
| 8.00 | 3/24 | 13/13 | 0.563 |
| 8.61 | 2/24 | 13/13 | 0.542 |
| 8.94 | 1/24 | 13/13 | 0.521 |

Held-out check with threshold 3.03:

- 10 of 11 held-out questions classified correctly.
- **in-32** (IN_CORPUS) scored -4.16 and was wrongly rejected (false "not found"): "How can a platform heal itself without anyone being paged?"

## Out-of-corpus questions by score (depth 20)

| type | id | split | top-1 | best passage | question |
| --- | --- | --- | ---: | --- | --- |
| OUT_NEAR | out-near-01 | calibration | 2.57 | wa-reliability-pillar p57 | How do I configure virtual network peering in Microsoft Azure? |
| OUT_AWS | out-aws-01 | calibration | 1.14 | wa-reliability-pillar p96 | What is the default maximum number of concurrent executions for AWS Lambda per Region in a new account? |
| OUT_NEAR | out-near-05 | calibration | 0.55 | wa-security-pillar p48 | What are the pillars of the Google Cloud Architecture Framework? |
| OUT_AWS | out-aws-03 | calibration | -0.86 | wa-cost-optimization-pillar p98 | What is the maximum size of a single object in Amazon S3? |
| OUT_AWS | out-aws-02 | calibration | -1.56 | wa-cost-optimization-pillar p82 | How much does Amazon S3 Standard storage cost per GB per month in us-east-1? |
| OUT_AWS | out-aws-04 | holdout | -3.18 | wa-serverless-applications-lens p22 | What is the maximum size of an item in Amazon DynamoDB? |
| OUT_NEAR | out-near-03 | calibration | -5.23 | wa-framework p762 | How do I write a Helm chart values.yaml for a Redis deployment? |
| OUT_NEAR | out-near-06 | calibration | -6.52 | wa-framework p781 | How do I tune Linux sysctl TCP buffer sizes for a 10 Gbps network card? |
| OUT_NEAR | out-near-02 | calibration | -7.14 | wa-reliability-pillar p184 | How do I create a GKE Autopilot cluster on Google Cloud? |
| OUT_NEAR | out-near-04 | holdout | -8.39 | wa-performance-efficiency-pillar p54 | How do I tune PostgreSQL shared_buffers for a 64 GB server? |
| OUT_FAR | out-far-01 | calibration | -10.80 | wa-framework p531 | Who won the FIFA World Cup in 2018? |
| OUT_FAR | out-far-02 | calibration | -10.99 | wa-security-pillar p133 | What is a good recipe for chocolate chip cookies? |
| OUT_FAR | out-far-03 | calibration | -11.07 | wa-framework p812 | What is the capital city of Australia? |
| OUT_FAR | out-far-05 | calibration | -11.09 | wa-serverless-applications-lens p16 | Who wrote the novel Pride and Prejudice? |
| OUT_FAR | out-far-06 | calibration | -11.12 | wa-sustainability-pillar p7 | What is the boiling point of water at sea level in Fahrenheit? |
| OUT_FAR | out-far-04 | holdout | -11.27 | wa-reliability-pillar p17 | How many moons does Jupiter have? |

