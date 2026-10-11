# End-to-end results: full-agent-b0

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

Generated 2026-10-11T09:18:47.140528700Z. Model `gemini-3.8-flash`, coordinator thinking budget 128, agent thinking budget 0. Every question went through the real coordinator, RAG agent, retrieval and verifier.

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
| retrieval floor (nothing relevant retrieved) | 4 |
| the model itself answered NOT_FOUND | 14 |
| **the verifier replaced the answer** | 0 |
| no tool was called | 0 |

**False "not found" caused by the verifier: 0 of 32 in-corpus questions (0.0%).** This is the number that decides whether the one-retry option is worth testing.

## False "not found" (answerable questions that got no answer)

| id | reason | best rerank score | searches | question |
| --- | --- | ---: | ---: | --- |
| in-31 | model | -0.35 | 3 | Why shouldn't staging servers keep running overnight and on weekends? |
| in-32 | model | -5.32 | 3 | How can a platform heal itself without anyone being paged? |

## False answers (an answer that should not exist, or cites no acceptable page)

- **in-08** (IN_CORPUS) How does the reliability pillar suggest recovering automatically from failures?
  - answer: Workloads should be monitored for key performance indicators (KPIs) to trigger automation when a threshold is breached. [wellarchitected-framework.pdf, p. 38] / KPIs used for this monitoring should measure business value rather than the technical aspects of service operation. [wellarchitected-framework.pdf, p. 38] / Monitoring KPIs enables automatic notification and tracking of failures, as well as automated recovery processes to work around or repair the failure. [wellarchitected-framework.pdf,…
  - cited: [wellarchitected-framework.pdf p. 38]
- **in-21** (IN_CORPUS) What can be considered to reduce the impact of Lambda cold starts?
  - answer: Provisioned concurrency initializes a requested number of execution environments so that they are prepared to respond immediately to function invocations. [wellarchitected-serverless-applications-lens.pdf, p. 67] / Allocating provisioned concurrency prior to an increase in invocations ensures requests are served by initialized instances with very low latency. [wellarchitected-serverless-applications-lens.pdf, p. 67] / Using scheduled scaling with Application Auto Scaling allows you to increase p…
  - cited: [wellarchitected-serverless-applications-lens.pdf p. 67]

## Latency per question (ms, whole turn: routing, searches, model calls, verification)

| set | n | median | mean | p90 | max | mean searches |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| all | 48 | 12282 | 11624 | 15082 | 23075 | 1.8 |
| IN_CORPUS | 32 | 9192 | 10458 | 14197 | 15872 | 1.3 |
| OUT_FAR | 6 | 12984 | 12218 | 13245 | 14841 | 2.7 |
| OUT_NEAR | 6 | 15464 | 16144 | 16892 | 23075 | 3.0 |
| OUT_AWS | 4 | 13297 | 13284 | 13297 | 13790 | 3.0 |
| answered | 30 | 9180 | 10203 | 14197 | 15301 | 1.1 |
| not answered | 18 | 13297 | 13993 | 15872 | 23075 | 2.9 |

## Every question and answer

| id | type | verdict | strict | cited | ms | answer or reason |
| --- | --- | --- | :---: | --- | ---: | --- |
| in-01 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 13 | 10121 | The six pillars of the AWS Well-Architected Framework are operational excellence, security, reliability, performance efficiency, cost optimization, and sustainability. [wellarchitected-framework.pdf, p. 13] |
| in-02 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 11; wellarchitected-reliability-pillar.pdf p. 13 | 9179 | Making a poor capacity decision can result in paying for expensive idle resources or facing the performance issues of limited capacity. [wellarchitected-framework.pdf, p. 11] / With cloud computing, organizations can use only the capacity they need and scale in and out automatically. [wellarchitected-framework.pdf, p. 11] / Resource saturation happens when demands exceed a workload's capacity, which is a common cause of failure in on-premises workloads. [wellarchitected-reliability-pillar.pdf, p. 13] / Cloud environments allow monitoring workload demand and automating resource addition or remo… |
| in-03 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 69; wellarchitected-operational-excellence-pillar.pdf p. 26 | 9093 | Two-way door decisions are reversible and can utilize a lightweight process. [wellarchitected-framework.pdf, p. 69] / One-way door decisions are hard or impossible to reverse, requiring more inspection before they are made. [wellarchitected-framework.pdf, p. 69] / Organizations should centralize one-way door decisions that could be irreversible. [wellarchitected-operational-excellence-pillar.pdf, p. 26] / Two-way door decisions may be made by lower level organizational leaders. [wellarchitected-operational-excellence-pillar.pdf, p. 26] |
| in-04 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 7; wellarchitected-operational-excellence-pillar.pdf p. 12 | 10275 | The AWS Well-Architected Tool is a cloud service that offers a consistent process to review and measure your architecture using the AWS Well-Architected Framework. [wellarchitected-framework.pdf, p. 7] / The AWS WA Tool provides recommendations designed to make workloads more reliable, secure, efficient, and cost-effective. [wellarchitected-framework.pdf, p. 7] / The tool assists in reviewing your approach prior to development, workload states before production, and workload states in production. [wellarchitected-operational-excellence-pillar.pdf, p. 12] |
| in-05 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 680; disaster-recovery-workloads-on-aws.pdf p. 13; wellarchitected-reliability-pillar.pdf p. 19 | 9718 | Recovery Time Objective (RTO) refers to the maximum acceptable delay between the interruption of a service and its restoration. [wellarchitected-framework.pdf, p. 680] / This objective determines what an organization defines as an acceptable time window during which the service is unavailable. [disaster-recovery-workloads-on-aws.pdf, p. 13] / RTO represents a target, or maximum value permitted, for an individual availability-impacting event. [wellarchitected-reliability-pillar.pdf, p. 19] |
| in-06 | IN_CORPUS | CORRECT | no | wellarchitected-framework.pdf p. 545 | 14328 | Exponential backoff should be used to retry requests after progressively longer intervals. [wellarchitected-framework.pdf, p. 545] / Jitter should be introduced to randomize retry intervals and avoid aligning requests into traffic spikes. [wellarchitected-framework.pdf, p. 545] / A maximum number of retries or maximum elapsed time should be configured to prevent backlog build-up. [wellarchitected-framework.pdf, p. 545] / Retries should be implemented at only one layer of the application stack to avoid compounding attempts into a retry storm. [wellarchitected-framework.pdf, p. 545] |
| in-07 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 470; wellarchitected-reliability-pillar.pdf p. 24 | 9180 | Being aware of service quotas and constraints helps customers prevent service degradation or disruption in their AWS accounts. [wellarchitected-framework.pdf, p. 470] / Unexpectedly reaching a constraint can cause an account's applications or services to be degraded or disrupted. [wellarchitected-reliability-pillar.pdf, p. 24] / Monitoring and automatically managing service quotas and resource constraints helps proactively reduce failures. [wellarchitected-framework.pdf, p. 470] |
| in-08 | IN_CORPUS | FALSE_ANSWER | no | wellarchitected-framework.pdf p. 38 | 13249 | Workloads should be monitored for key performance indicators (KPIs) to trigger automation when a threshold is breached. [wellarchitected-framework.pdf, p. 38] / KPIs used for this monitoring should measure business value rather than the technical aspects of service operation. [wellarchitected-framework.pdf, p. 38] / Monitoring KPIs enables automatic notification and tracking of failures, as well as automated recovery processes to work around or repair the failure. [wellarchitected-framework.pdf, p. 38] / More sophisticated automation allows for anticipating and remediating failures before they… |
| in-09 | IN_CORPUS | CORRECT | yes | wellarchitected-security-pillar.pdf p. 9; wellarchitected-security-pillar.pdf p. 130 | 15082 | Security and compliance is shared between AWS and the customer, commonly differentiated as Security of the Cloud versus Security in the Cloud. [wellarchitected-security-pillar.pdf, p. 9] / AWS is responsible for protecting the infrastructure—including hardware, software, networking, and facilities—that runs all AWS Cloud services. [wellarchitected-security-pillar.pdf, p. 9] / AWS operates, manages, and controls components from the host operating system and virtualization layer down to the physical security of facilities. [wellarchitected-security-pillar.pdf, p. 9] / Customers are responsible f… |
| in-10 | IN_CORPUS | CORRECT | no | wellarchitected-security-pillar.pdf p. 49; wellarchitected-framework.pdf p. 301 | 14197 | The recommended approach to secrets management is to follow the guidance of remove, replace, and rotate. [wellarchitected-security-pillar.pdf, p. 49] / Where credentials are required, long-term credentials should be replaced with temporary or short-term credentials, such as temporary credentials using IAM roles. [wellarchitected-security-pillar.pdf, p. 49] / Long-lived secrets that cannot be removed or replaced can be stored in a service such as AWS Secrets Manager to be centrally stored, managed, and rotated regularly. [wellarchitected-security-pillar.pdf, p. 49] / Application and database cr… |
| in-11 | IN_CORPUS | CORRECT | no | wellarchitected-framework.pdf p. 319 | 12300 | The principle of least privilege states that identities should only be allowed to perform the smallest set of actions necessary to complete a specific task. [wellarchitected-framework.pdf, p. 319] / This principle strikes a balance between usability, efficiency, and security. [wellarchitected-framework.pdf, p. 319] / Operating under the principle of least privilege aids in limiting unintended access and tracking who has access to specific resources. [wellarchitected-framework.pdf, p. 319] |
| in-12 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 399 | 12282 | Secure key management includes storing, rotating, controlling access to, and monitoring key material needed to secure data at rest for a workload. [wellarchitected-framework.pdf, p. 399] |
| in-13 | IN_CORPUS | CORRECT | yes | wellarchitected-operational-excellence-pillar.pdf p. 9; wellarchitected-operational-excellence-pillar.pdf p. 149 | 9040 | The operational excellence pillar recommends making frequent, small, and reversible changes to workloads. [wellarchitected-operational-excellence-pillar.pdf, p. 9] / Combining automated deployment techniques with smaller, incremental changes reduces the blast radius and enables faster reversal in case of failures. [wellarchitected-operational-excellence-pillar.pdf, p. 9] / Organizations should have processes established for both successful and unsuccessful workload changes. [wellarchitected-operational-excellence-pillar.pdf, p. 149] / Teams should evaluate the benefits and risks of deploying w… |
| in-14 | IN_CORPUS | CORRECT | yes | wellarchitected-operational-excellence-pillar.pdf p. 141; wellarchitected-framework.pdf p. 592 | 8982 | A runbook is a documented process used to achieve a specific outcome. [wellarchitected-operational-excellence-pillar.pdf, p. 141] / Runbooks are used in cloud operations to reduce risk and achieve desired outcomes. [wellarchitected-operational-excellence-pillar.pdf, p. 141] / Runbooks serve as codified processes providing consistent outcomes for tasks ranging from onboarding team members to deploying major releases. [wellarchitected-operational-excellence-pillar.pdf, p. 141] / Runbooks are commonly used for routine activities rather than responding to non-routine events. [wellarchitected-frame… |
| in-15 | IN_CORPUS | CORRECT | yes | wellarchitected-operational-excellence-pillar.pdf p. 74 | 8141 | Feature flags and A/B testing can be utilized to experiment safely and collect feedback from users. [wellarchitected-operational-excellence-pillar.pdf, p. 74] / AWS AppConfig Feature Flags offers functionality to set up feature flags. [wellarchitected-operational-excellence-pillar.pdf, p. 74] / AWS Lambda versions can be leveraged to release a new function version for beta testing. [wellarchitected-operational-excellence-pillar.pdf, p. 74] / A/B testing is applied to new features in order to validate them using real user feedback. [wellarchitected-operational-excellence-pillar.pdf, p. 74] / Im… |
| in-16 | IN_CORPUS | CORRECT | yes | wellarchitected-performance-efficiency-pillar.pdf p. 9 | 8236 | Democratizing advanced technologies means making advanced technology implementation easier for your team by delegating complex tasks to your cloud vendor. [wellarchitected-performance-efficiency-pillar.pdf, p. 9] / Rather than requiring an IT team to learn how to host and run a new technology, organizations can consume the technology as a service. [wellarchitected-performance-efficiency-pillar.pdf, p. 9] / Consuming technologies like NoSQL databases, media transcoding, and machine learning as cloud services enables teams to focus on product development instead of resource provisioning and mana… |
| in-17 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 45 | 8398 | Mechanical sympathy involves understanding how cloud services are consumed and always utilizing the technology approach that aligns with your workload goals. [wellarchitected-framework.pdf, p. 45] / For instance, it means considering data access patterns when selecting database or storage approaches for your workload. [wellarchitected-framework.pdf, p. 45] |
| in-18 | IN_CORPUS | CORRECT | yes | wellarchitected-framework.pdf p. 744 | 8956 | In-memory databases such as Amazon ElastiCache and Amazon MemoryDB for Redis can be used for application caching, session management, and gaming leaderboards. [wellarchitected-framework.pdf, p. 744] |
| in-19 | IN_CORPUS | CORRECT | yes | wellarchitected-serverless-applications-lens.pdf p. 60 | 12954 | A dead-letter queue mechanism should be used to retain, investigate, and retry failed transactions. [wellarchitected-serverless-applications-lens.pdf, p. 60] / AWS Lambda allows failed transactions to be delivered to a dedicated Amazon SQS dead-letter queue on a per-function basis. [wellarchitected-serverless-applications-lens.pdf, p. 60] / Within AWS Lambda, Maximum Retry Attempts, Maximum Record Age, and Destination on Failure can be configured to manage retries and route poison-pill metadata to an Amazon SQS DLQ. [wellarchitected-serverless-applications-lens.pdf, p. 60] / Whenever possible,… |
| in-20 | IN_CORPUS | CORRECT | yes | wellarchitected-serverless-applications-lens.pdf p. 15; microservices-on-aws.pdf p. 12 | 8746 | Amazon API Gateway provides built-in authorization, throttling, security, fault tolerance, request and response mapping, and performance optimizations. [wellarchitected-serverless-applications-lens.pdf, p. 15] / API Gateway allows synchronous calls to AWS Lambda, enabling the creation of fully serverless applications. [microservices-on-aws.pdf, p. 12] |
| in-21 | IN_CORPUS | FALSE_ANSWER | no | wellarchitected-serverless-applications-lens.pdf p. 67 | 15301 | Provisioned concurrency initializes a requested number of execution environments so that they are prepared to respond immediately to function invocations. [wellarchitected-serverless-applications-lens.pdf, p. 67] / Allocating provisioned concurrency prior to an increase in invocations ensures requests are served by initialized instances with very low latency. [wellarchitected-serverless-applications-lens.pdf, p. 67] / Using scheduled scaling with Application Auto Scaling allows you to increase provisioned concurrency in anticipation of peak traffic. [wellarchitected-serverless-applications-len… |
| in-22 | IN_CORPUS | CORRECT | yes | microservices-on-aws.pdf p. 16; microservices-on-aws.pdf p. 17; wellarchitected-framework.pdf p. 512 | 11582 | Amazon ECS offers integrated service discovery that allows containerized services to discover and connect with each other. [microservices-on-aws.pdf, p. 16] / Amazon ECS creates and manages a registry of service names using the Route 53 Auto Naming API, automatically mapping names to DNS records. [microservices-on-aws.pdf, p. 17] / Unified service discovery can be used for Kubernetes-managed services through AWS contributions to the External DNS project. [microservices-on-aws.pdf, p. 17] / AWS Cloud Map provides a service registry for resources such as IPs, URLs, and ARNs, offering an API-base… |
| in-23 | IN_CORPUS | CORRECT | yes | microservices-on-aws.pdf p. 18 | 7642 | In a microservices approach, a central database prevents the goal of building decentralized and independent components, which is why each microservice component should have its own data persistence layer. [microservices-on-aws.pdf, p. 18] |
| in-24 | IN_CORPUS | CORRECT | yes | microservices-on-aws.pdf p. 14 | 7101 | Twelve-factor processes are stateless and share-nothing. [microservices-on-aws.pdf, p. 14] / Any data that needs to persist must be stored in a stateful backing service, typically a database. [microservices-on-aws.pdf, p. 14] |
| out-far-01 | OUT_FAR | CORRECT | - | - | 14841 | (not answered: retrieval-floor) |
| out-far-02 | OUT_FAR | CORRECT | - | - | 13245 | (not answered: model) |
| out-far-03 | OUT_FAR | CORRECT | - | - | 10820 | (not answered: retrieval-floor) |
| out-far-04 | OUT_FAR | CORRECT | - | - | 12984 | (not answered: model) |
| out-far-05 | OUT_FAR | CORRECT | - | - | 9745 | (not answered: retrieval-floor) |
| out-far-06 | OUT_FAR | CORRECT | - | - | 11672 | (not answered: retrieval-floor) |
| out-near-01 | OUT_NEAR | CORRECT | - | - | 23075 | (not answered: model) |
| out-near-02 | OUT_NEAR | CORRECT | - | - | 13783 | (not answered: model) |
| out-near-03 | OUT_NEAR | CORRECT | - | - | 12948 | (not answered: model) |
| out-near-04 | OUT_NEAR | CORRECT | - | - | 14702 | (not answered: model) |
| out-near-05 | OUT_NEAR | CORRECT | - | - | 15464 | (not answered: model) |
| out-near-06 | OUT_NEAR | CORRECT | - | - | 16892 | (not answered: model) |
| in-25 | IN_CORPUS | CORRECT | yes | wellarchitected-cost-optimization-pillar.pdf p. 8 | 8397 | Adopting a consumption model means paying only for the computing resources consumed, while increasing or decreasing usage based on business requirements. [wellarchitected-cost-optimization-pillar.pdf, p. 8] / For instance, development and test environments can be stopped when not in use since they are typically used only for eight hours a day during the work week, resulting in potential cost savings of 75%. [wellarchitected-cost-optimization-pillar.pdf, p. 8] |
| in-26 | IN_CORPUS | CORRECT | yes | wellarchitected-cost-optimization-pillar.pdf p. 28; wellarchitected-cost-optimization-pillar.pdf p. 37 | 12595 | Cloud waste happens when resources are created with incorrect sizing or when observed usage patterns deviate from expectations. [wellarchitected-cost-optimization-pillar.pdf, p. 28] / Organizations should identify and recoup expenditures associated with cloud waste across deployed resources. [wellarchitected-cost-optimization-pillar.pdf, p. 28] / Organizations are advised to follow AWS best practices to decrease waste, or seek assistance from their account team and partner to optimize cloud costs. [wellarchitected-cost-optimization-pillar.pdf, p. 28] / Attributing resource costs directly to wo… |
| in-27 | IN_CORPUS | CORRECT | yes | wellarchitected-sustainability-pillar.pdf p. 23 | 7678 | The choice of Region for a workload significantly affects key performance indicators, including carbon footprint. [wellarchitected-sustainability-pillar.pdf, p. 23] / Placing a workload close to Amazon renewable energy projects or in Regions with low published carbon intensity can help lower the carbon footprint of a cloud workload. [wellarchitected-sustainability-pillar.pdf, p. 23] |
| in-28 | IN_CORPUS | CORRECT | yes | wellarchitected-sustainability-pillar.pdf p. 7 | 7773 | Environmental sustainability is a shared responsibility between AWS and its customers. [wellarchitected-sustainability-pillar.pdf, p. 7] / AWS is responsible for sustainability of the cloud, which includes delivering efficient shared infrastructure, managing water stewardship, and sourcing renewable power. [wellarchitected-sustainability-pillar.pdf, p. 7] / Customers hold responsibility for sustainability in the cloud, optimizing their workloads and resource utilization while minimizing required resources. [wellarchitected-sustainability-pillar.pdf, p. 7] |
| in-29 | IN_CORPUS | CORRECT | yes | disaster-recovery-workloads-on-aws.pdf p. 13 | 6380 | The four disaster recovery strategies discussed for workloads on AWS are backup and restore, pilot light, warm standby, and multi-site active/active. [disaster-recovery-workloads-on-aws.pdf, p. 13] |
| in-30 | IN_CORPUS | CORRECT | yes | disaster-recovery-workloads-on-aws.pdf p. 26; wellarchitected-framework.pdf p. 692; disaster-recovery-workloads-on-aws.pdf p. 27 | 9192 | AWS Elastic Disaster Recovery continuously replicates server-hosted applications and server-hosted databases from any source into AWS via block-level replication of the underlying server. [disaster-recovery-workloads-on-aws.pdf, p. 26] / It allows an AWS Cloud Region to serve as a disaster recovery target for workloads hosted on-premises or on another cloud provider. [disaster-recovery-workloads-on-aws.pdf, p. 26] / It can also serve disaster recovery needs for AWS-hosted workloads if those workloads consist solely of EC2-hosted applications and databases rather than RDS. [disaster-recovery-wo… |
| in-31 | IN_CORPUS | FALSE_NOT_FOUND | no | - | 12693 | (not answered: model) |
| in-32 | IN_CORPUS | FALSE_NOT_FOUND | no | - | 15872 | (not answered: model) |
| out-aws-01 | OUT_AWS | CORRECT | - | - | 13790 | (not answered: model) |
| out-aws-02 | OUT_AWS | CORRECT | - | - | 13139 | (not answered: model) |
| out-aws-03 | OUT_AWS | CORRECT | - | - | 13297 | (not answered: model) |
| out-aws-04 | OUT_AWS | CORRECT | - | - | 12911 | (not answered: model) |

