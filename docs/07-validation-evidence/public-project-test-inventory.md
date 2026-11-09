# Public-project test inventory

Version: `planning-v1` · metadata revision: `p04-sg02-reconciliation-2026-11-09-r1` · framework reference: [`b95f6b76`](https://github.com/hermanwangd/Auto_Regression_Test_Framework/commit/b95f6b76f5e54fe9b93f99aad78d17a8568e52f7)

**20 repositories / 116 proposed scenario groups**: 11 selected client/test-source repositories (62 groups) and 9 application candidates (54 groups). These are planning denominators. They are not approved executable-case counts or product requirement coverage.

Full product requirements/AC denominator and global approved executable-case/layer denominators remain **UNKNOWN**. P04-SG02 now has a bounded, frozen four-case Framework-provider/Module result as detailed below. This does not define a portfolio-wide execution rate. The 116 groups remain a planning denominator.

Authoritative machine-readable snapshot: [public-project-test-inventory.json](public-project-test-inventory.json). Original proposal project IDs, ordering, complete commits, titles and notes are preserved; its 13,829 bytes were verified against SHA256 `d98ea460034ab3b3b10a15c075378d61e264324e4672f5dd4f3194e83f0e14f3`.

`Pxx-SGnn` / `Axx-SGnn` identify scenario groups assigned during this formalization, not upstream executable case IDs. Original IDs such as RG01–RG05, GL01–GL06 and KP01–KP14 remain in source observations.

## Portfolio

`ADAPT_NOW_BOUNDED_SUBSET` is a prioritization lane. Each concrete implementation/adaptation work package still requires its own applicable review; the lane does not establish authorization, runtime readiness or tests passed. Environment/adapter and license lanes require those prerequisites. A microservice product, a monolith, a template and an SDK/test source remain different categories.

| ID | Repository / source | Type | Protocol / language | Groups | Readiness lane |
| --- | --- | --- | --- | ---: | --- |
| P01 | [grpc/grpc](https://github.com/grpc/grpc/tree/952c8d46d90530ddc497d22bdf4b4ccfce4e903c) | CLIENT_OR_TEST_SOURCE | gRPC / Python server; Java caller | 5 | ADAPT_NOW_BOUNDED_SUBSET |
| P02 | [vmagamedov/grpclib](https://github.com/vmagamedov/grpclib/tree/0d27158f98aa939493009e6844576d81ec7d8254) | CLIENT_OR_TEST_SOURCE | gRPC / Python | 6 | ADAPT_NOW_BOUNDED_SUBSET |
| P03 | [d5h-foss/grpc-interceptor](https://github.com/d5h-foss/grpc-interceptor/tree/d866a1bd09e9e79a02e0ed42a9d59f30c85bb7bc) | CLIENT_OR_TEST_SOURCE | gRPC / Python | 4 | ADAPT_NOW_BOUNDED_SUBSET |
| P04 | [danielgtaylor/python-betterproto](https://github.com/danielgtaylor/python-betterproto/tree/a3f7fd0c1fd788871c460088446099700f6679e4) | CLIENT_OR_TEST_SOURCE | gRPC / Python / Protobuf | 4 | ADAPT_NOW_BOUNDED_SUBSET |
| P05 | [grpc/grpc-node](https://github.com/grpc/grpc-node/tree/ca7f83f719ba1e69ee9b832b84d5375de15eeca6) | CLIENT_OR_TEST_SOURCE | gRPC / Node.js | 5 | ADAPT_NOW_BOUNDED_SUBSET |
| P06 | [dpkp/kafka-python](https://github.com/dpkp/kafka-python/tree/3fa6acf1931e0beb4b3af9e8859d08a80897a63b) | CLIENT_OR_TEST_SOURCE | Kafka / Python | 6 | ADAPT_NOW_BOUNDED_SUBSET |
| P07 | [aio-libs/aiokafka](https://github.com/aio-libs/aiokafka/tree/8f7357470cbb80913f209d8eae144e36abf20872) | CLIENT_OR_TEST_SOURCE | Kafka / Python asyncio | 6 | ADAPT_NOW_BOUNDED_SUBSET |
| P08 | [nats-io/nats.py](https://github.com/nats-io/nats.py/tree/7fd167171382988c68c1dc00712a6efb1d9eeebc) | CLIENT_OR_TEST_SOURCE | NATS / Python asyncio | 6 | ADAPT_NOW_BOUNDED_SUBSET |
| P09 | [mongodb/mongo-python-driver](https://github.com/mongodb/mongo-python-driver/tree/38abdcfb44edee842025fcfc4f97e075cb776be2) | CLIENT_OR_TEST_SOURCE | MongoDB / Python | 7 | ADAPT_NOW_BOUNDED_SUBSET |
| P10 | [PyMySQL/PyMySQL](https://github.com/PyMySQL/PyMySQL/tree/2cab9ecc641e962565c6254a5091f90c47f59b35) | CLIENT_OR_TEST_SOURCE | MariaDB / Python | 7 | ADAPT_NOW_BOUNDED_SUBSET |
| P11 | [mariadb-corporation/mariadb-connector-j](https://github.com/mariadb-corporation/mariadb-connector-j/tree/1622933546452a9a263731c373798524926141ea) | CLIENT_OR_TEST_SOURCE | MariaDB / Java JDBC | 6 | ADAPT_NOW_BOUNDED_SUBSET |
| A01 | [GoogleCloudPlatform/microservices-demo](https://github.com/GoogleCloudPlatform/microservices-demo/tree/c7cb6d5bfd6ea3aed768634ebbcb98cb7a523712) | MULTISERVICE_APPLICATION | 11 個 microservices；Go/C#/Node.js/Python/Java；HTTP + gRPC + Redis | 6 | ENVIRONMENT_AND_ADAPTER_REQUIRED |
| A02 | [GoogleCloudPlatform/bank-of-anthos](https://github.com/GoogleCloudPlatform/bank-of-anthos/tree/db35fea9fd090150e2398106aadb475576f80d94) | MULTISERVICE_APPLICATION | Python/Java；HTTP + PostgreSQL；ledger/account 多服務 | 6 | ENVIRONMENT_AND_ADAPTER_REQUIRED |
| A03 | [dotnet/eShop](https://github.com/dotnet/eShop/tree/913acea66708a59595351174cee9b29f37179d29) | SERVICE_BASED_APPLICATION | C#/.NET 10；services-based commerce；RabbitMQ + PostgreSQL + Redis | 6 | ENVIRONMENT_AND_ADAPTER_REQUIRED |
| A04 | [spring-petclinic/spring-petclinic-microservices](https://github.com/spring-petclinic/spring-petclinic-microservices/tree/e8b747ab522721076cacb5cf688eb8a54ba8c5c9) | MULTISERVICE_APPLICATION | Java；customers/vets/visits REST + gateway/config/discovery；HSQLDB 或 MySQL | 6 | ENVIRONMENT_AND_ADAPTER_REQUIRED |
| A05 | [eclipse-ditto/ditto](https://github.com/eclipse-ditto/ditto/tree/30435a5feb135342bb9c395af984abe53488b184) | MULTISERVICE_APPLICATION | Java；Policies/Things/Search/Gateway/Connectivity + MongoDB | 6 | ENVIRONMENT_AND_ADAPTER_REQUIRED |
| A06 | [directus/directus](https://github.com/directus/directus/tree/a7d59f95833664ef32f4fe8dd90246a104f4b0d9) | APPLICATION_MONOLITH | TypeScript；真實 API/Studio 產品，非多微服務；REST/GraphQL + SQL | 6 | LICENSE_REVIEW_REQUIRED |
| A07 | [go-gitea/gitea](https://github.com/go-gitea/gitea/tree/884884308d0a1b4de423e80633964e0af505eda7) | APPLICATION_MONOLITH | Go；真實 Git/issue/review 產品，非多微服務；HTTP/Git + DB | 6 | ENVIRONMENT_AND_ADAPTER_REQUIRED |
| A08 | [mattermost/mattermost](https://github.com/mattermost/mattermost/tree/1381b881f4908381a3dd50aa40aa8a1c955bd320) | APPLICATION_MONOLITH | Go/React；單一 Linux binary + PostgreSQL；真實協作產品 | 6 | LICENSE_REVIEW_REQUIRED |
| A09 | [fastapi/full-stack-fastapi-template](https://github.com/fastapi/full-stack-fastapi-template/tree/aabb90126964a164e02aff39e7c385bb0598236d) | APPLICATION_TEMPLATE | Python FastAPI + TypeScript React + PostgreSQL；應用 template，非大型微服務產品 | 6 | ENVIRONMENT_AND_ADAPTER_REQUIRED |

## Layer status and evidence rules

Each group separately records `upstream`, `framework_provider`, `swarm_module`, `native_swarm_agent` and `sit`. `NOT_RECONCILED` means reported observations lack a frozen exact case/layer/run mapping; it does not mean the test was never attempted. `BLOCKED` includes explicit missing admission/runtime/access prerequisites. `NOT_RUN` is retained for unexecuted candidate or native-agent paths.

An upstream PASS never becomes a framework/module/agent/SIT PASS. A canonical product FAIL and successful detection of an intentionally wrong oracle are different facts. Historical observations retain their original versions and never enter the current numerator. Code coverage reports only the stated instrumented files/classes.

Planned group slots: **116 for each of the five layers**, not 580 approved runnable tests. Server fixtures, backlog projects, historical records and component checks are excluded from the 20/116 denominator.

## Scenario groups

### P01 — grpc/grpc

- Source commit: `952c8d46d90530ddc497d22bdf4b4ccfce4e903c`.
- Proposal state: `SELECTED_WITH_BOUNDED_EVIDENCE`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): RG01–RG05 upstream PASS；Java 部分路徑另有證據，streaming framework 未驗證。
- Gaps: Reviewed module supports a finite unary slice; streaming, metadata/auth/TLS and descriptor differences require per-case verification.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P01-SG01 | 已知座標 unary 回傳精確名稱／nested point | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P01-SG02 | 未知座標回傳 proto3 預設值 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P01-SG03 | server streaming 的精確筆數與順序 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P01-SG04 | client streaming 彙總 counts 與 distance | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P01-SG05 | bidi streaming 的回應順序 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |

### P02 — vmagamedov/grpclib

- Source commit: `0d27158f98aa939493009e6844576d81ec7d8254`.
- Proposal state: `SELECTED_WITH_BOUNDED_EVIDENCE`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): GL01–GL06 upstream PASS；不代表 framework 支援 streaming。
- Gaps: Reviewed module supports a finite unary slice; streaming, metadata/auth/TLS and descriptor differences require per-case verification.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P02-SG01 | unary greeting 精確內容 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P02-SG02 | server streaming Hello／Goodbye 順序 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P02-SG03 | client streaming 名單彙總 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P02-SG04 | bidi 的精確筆數與順序 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P02-SG05 | 空 input stream 邊界 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P02-SG06 | 未知 method 回 UNIMPLEMENTED | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |

### P03 — d5h-foss/grpc-interceptor

- Source commit: `d866a1bd09e9e79a02e0ed42a9d59f30c85bb7bc`.
- Proposal state: `SELECTED_PINNED_NOT_RUN`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): 來源已 materialize；此 snapshot 尚無新實跑結果。
- Gaps: Reviewed module supports a finite unary slice; streaming, metadata/auth/TLS and descriptor differences require per-case verification.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P03-SG01 | 正常 unary 完成 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P03-SG02 | unary exception 對應 gRPC status | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P03-SG03 | stream 中途 exception 保留已送資料及錯誤 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P03-SG04 | 取消／deadline 後停止且清理 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |

### P04 — danielgtaylor/python-betterproto

- Source commit: `a3f7fd0c1fd788871c460088446099700f6679e4`.
- Proposal state: `SELECTED_PINNED_NOT_RUN`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): schema-only fixture 必須另標，不等於上游 server 實跑。
- Gaps: Reviewed module supports a finite unary slice; streaming, metadata/auth/TLS and descriptor differences require per-case verification.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P04-SG01 | repeated response 精確元素 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P04-SG02 | 四種 RPC cardinality 的 admission 或明確 unsupported | NOT_RECONCILED | ACCEPTED | ACCEPTED | NOT_RUN | BLOCKED |
| P04-SG03 | 空 repeated／default 邊界 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P04-SG04 | imported well-known type 的 descriptor 處理 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |

P04-SG02 acceptance is limited to **schema-derived owned Java fixture** execution through `grpc_client.v0.3` and the packaged Swarm product-test Module. It does not establish upstream Python, native Swarm-agent or SIT acceptance. Source [`173ee0ec`](https://github.com/hermanwangd/Auto_Regression_Test_Framework/commit/173ee0ecb4212b0e0963aa3c574d6c02c30eb3aa), tree `5d7c8aaa2685c02dc8855630a0457ae9bc616fef`, ran on OpenJDK 21.0.13. [CI 42681020126](https://github.com/hermanwangd/Auto_Regression_Test_Framework/actions/runs/42681020126) built/tested on Java17 (142 root + 7 Module tests); packaged Java17 runtime was not run.

The JSON P04-SG02 row binds all four exact case IDs, input manifests, requests, reviewed handoffs, frozen oracles, canonical results, receipts and cleanup hashes. `TC-P04-SG02-UNARY-ADMIT-001` passed with one real RPC. `TC-P04-SG02-CLIENTSTREAM-REJECT-001`, `TC-P04-SG02-SERVERSTREAM-REJECT-001` and `TC-P04-SG02-BIDISTREAM-REJECT-001` retain genuine failed/UNKNOWN product verdicts and exit1, with the exact unsupported-cardinality diagnostic before channel construction and zero RPCs. Each correct oracle ACCEPT and wrong oracle REJECT references that case's single canonical result; these are four executable cases, not eight executions. All four owners exited0, were reaped, and had closed ports.

Execution seal `p04-packaged-four-review.v3` predates the run. Independent existing-evidence review `p04-packaged-four-postrun.v1` accepted this bounded scope at 2026-11-09T21:07:12Z. This maintenance entry reconciles the original `P04-SG02-PROPOSED-FOUR-CARDINALITY` request mapping without rewriting sealed evidence. Earlier failed attempts and `release_evidence_eligible=false` remain unchanged. Target-branch integration is recorded separately and does not imply a new runtime result.

This adds **one accepted group record in each of two layers**, supported by four cases. Across declared groups, Framework NOT_RECONCILED changes 42→41 and ACCEPTED 0→1; Module BLOCKED changes 43→42 and ACCEPTED 0→1. Other layer states, the 20/116 planning denominator, global UNKNOWN execution/requirement denominators, and all 16 excluded historical case records remain unchanged. It is not 17/116 or four additional groups.

### P05 — grpc/grpc-node

- Source commit: `ca7f83f719ba1e69ee9b832b84d5375de15eeca6`.
- Proposal state: `SELECTED_PINNED_NOT_RUN`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): 來源 metadata、deadline、error-handling examples 已 materialize。
- Gaps: Reviewed module supports a finite unary slice; streaming, metadata/auth/TLS and descriptor differences require per-case verification.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P05-SG01 | Java／Node unary 互通 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P05-SG02 | request／initial／trailer metadata | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P05-SG03 | 短 deadline 與取消 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P05-SG04 | server error status／detail | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P05-SG05 | 關閉服務後 unavailable 與清理 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |

### P06 — dpkp/kafka-python

- Source commit: `3fa6acf1931e0beb4b3af9e8859d08a80897a63b`.
- Proposal state: `SELECTED_WITH_BOUNDED_EVIDENCE`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): KP01–KP14 upstream 14 PASS；9 個 framework Python harness tests PASS；10 個新增 boundary tests PASS；原生 Java／broker 未驗證。
- Gaps: Native Kafka module admission remains a recorded gap at the framework reference commit.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P06-SG01 | keyed partition vectors | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P06-SG02 | 相同 key 穩定 routing | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P06-SG03 | null／empty／binary key 區別 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P06-SG04 | unknown topic 的負向契約 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P06-SG05 | sticky partition 與 batch rotation | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P06-SG06 | stale batch／metadata 更新恢復 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |

### P07 — aio-libs/aiokafka

- Source commit: `8f7357470cbb80913f209d8eae144e36abf20872`.
- Proposal state: `SELECTED_PINNED_NOT_RUN`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): source 已取得；Kafka 3.9.2 fixture 已下載，不等於啟動或通過。
- Gaps: Native Kafka module admission remains a recorded gap at the framework reference commit.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P07-SG01 | publish／consume 的 payload | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P07-SG02 | correlation 與精確筆數 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P07-SG03 | consumer group offset／重讀 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P07-SG04 | broker unavailable 的有界失敗 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P07-SG05 | broker 恢復後重連與重送界線 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |
| P07-SG06 | topic／consumer／process 清理 | NOT_RECONCILED | NOT_RECONCILED | BLOCKED | NOT_RUN | BLOCKED |

### P08 — nats-io/nats.py

- Source commit: `7fd167171382988c68c1dc00712a6efb1d9eeebc`.
- Proposal state: `SELECTED_PINNED_NOT_RUN`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): source 與 NATS Server 2.10.26 已取得；執行未證實。
- Gaps: Client results and server-wire results must be reconciled separately.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P08-SG01 | request／reply 精確內容 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P08-SG02 | pub／sub correlation 與筆數 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P08-SG03 | 無 responder／timeout | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P08-SG04 | 斷線與重連 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P08-SG05 | JetStream ack／redelivery（待確認選定範圍） | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |
| P08-SG06 | subscription／stream／process 清理 | NOT_RECONCILED | NOT_RECONCILED | NOT_RECONCILED | NOT_RUN | BLOCKED |

### P09 — mongodb/mongo-python-driver

- Source commit: `38abdcfb44edee842025fcfc4f97e075cb776be2`.
- Proposal state: `SELECTED_WITH_BOUNDED_EVIDENCE`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): 15 upstream + 5 API tests；真 MongoDB 7.0.16 lifecycle；module→framework→CLI 4 個 frozen cases。原生 Mongo provider／Swarm Agent 未成立。
- Gaps: A CLI-based client test does not establish a native MongoDB provider.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P09-SG01 | URI／BSON normal 與 malformed 契約 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P09-SG02 | seed／filter／update／delete 精確資料 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P09-SG03 | duplicate key 11000 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P09-SG04 | malformed query 2 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P09-SG05 | wrong-row／semantic wrong-oracle 檢出 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P09-SG06 | drop database／shutdown／closed port | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P09-SG07 | auth／timeout（尚未覆蓋） | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |

### P10 — PyMySQL/PyMySQL

- Source commit: `2cab9ecc641e962565c6254a5091f90c47f59b35`.
- Proposal state: `SELECTED_WITH_BOUNDED_EVIDENCE`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): PyMySQL 1.1.1 upstream 10 tests；MariaDB 11.8.8 lifecycle；module→framework→CLI 2 個 frozen cases。不是原生 MariaDB provider PASS。
- Gaps: Vendor driver/client evidence does not establish native MariaDB module support.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P10-SG01 | parameter query 精確 rows | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P10-SG02 | duplicate 1062 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P10-SG03 | missing table 1146 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P10-SG04 | rollback 不留下資料 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P10-SG05 | unknown user rejection | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P10-SG06 | wrong-row oracle 檢出 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P10-SG07 | drop database／shutdown／closed port | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |

### P11 — mariadb-corporation/mariadb-connector-j

- Source commit: `1622933546452a9a263731c373798524926141ea`.
- Proposal state: `SELECTED_PINNED_NOT_RUN`.
- Preconditions: Source selected; executable fixture, layer, inputs and oracle must be frozen before admission.
- License / review: CHECK_SOURCE_LICENSE_BEFORE_EXECUTION.
- Existing source observation (bounded claim): Connector/J 3.5.3 source 與 JAR 已 pin；原生 provider 路徑尚在補。
- Gaps: Vendor driver/client evidence does not establish native MariaDB module support.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| P11-SG01 | connect／CRUD 精確 rows | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P11-SG02 | parameter binding／NULL | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P11-SG03 | commit／rollback | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P11-SG04 | SQL error code 映射 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P11-SG05 | timeout／unavailable | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |
| P11-SG06 | connection／database／process 清理 | NOT_RECONCILED | BLOCKED | BLOCKED | NOT_RUN | BLOCKED |

### A01 — GoogleCloudPlatform/microservices-demo

- Source commit: `c7cb6d5bfd6ea3aed768634ebbcb98cb7a523712`.
- Proposal state: `CANDIDATE_PINNED_NOT_ADAPTED`.
- Preconditions: Kubernetes、11 services、Redis；使用本地 mock payment/email，禁連真實金流
- License / review: Apache-2.0.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: Kubernetes、11 services、Redis；使用本地 mock payment/email，禁連真實金流

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A01-SG01 | catalog 正常查詢與未知商品 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A01-SG02 | cart 新增／修改／讀回與 Redis 一致 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A01-SG03 | checkout→payment→shipping→email 的跨服務成功路徑 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A01-SG04 | 不合法 checkout 不產生成功訂單 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A01-SG05 | 依賴服務 timeout／unavailable 的有界失敗 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A01-SG06 | 隔離 session 與 teardown | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

### A02 — GoogleCloudPlatform/bank-of-anthos

- Source commit: `db35fea9fd090150e2398106aadb475576f80d94`.
- Proposal state: `CANDIDATE_PINNED_NOT_ADAPTED`.
- Preconditions: Kubernetes、accounts-db/ledger-db；僅 artificial accounts 與模擬交易
- License / review: Apache-2.0.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: Kubernetes、accounts-db/ledger-db；僅 artificial accounts 與模擬交易

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A02-SG01 | 登入／JWT 正向 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A02-SG02 | 無效 token／越權拒絕 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A02-SG03 | 模擬轉帳的 ledger 寫入 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A02-SG04 | 餘額與交易歷史一致 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A02-SG05 | 無效交易不寫入 ledger | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A02-SG06 | 測試 account／ledger 清理 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

### A03 — dotnet/eShop

- Source commit: `913acea66708a59595351174cee9b29f37179d29`.
- Proposal state: `CANDIDATE_PINNED_NOT_ADAPTED`.
- Preconditions: Aspire CLI、.NET 10 SDK、OCI runtime；不啟用 Azure/AI provisioning
- License / review: MIT.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: Aspire CLI、.NET 10 SDK、OCI runtime；不啟用 Azure/AI provisioning RabbitMQ scenarios require actual AMQP/queue evidence; HTTP state changes alone do not prove a native MQ adapter.

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A03-SG01 | catalog filter／pagination | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A03-SG02 | basket add／update／readback | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A03-SG03 | checkout／order 跨服務流程 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A03-SG04 | RabbitMQ event 導致預期狀態 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A03-SG05 | duplicate event 的副作用檢查 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A03-SG06 | 錯誤訂單與資料／queue 清理 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

### A04 — spring-petclinic/spring-petclinic-microservices

- Source commit: `e8b747ab522721076cacb5cf688eb8a54ba8c5c9`.
- Proposal state: `CANDIDATE_PINNED_NOT_ADAPTED`.
- Preconditions: Java 17／Docker Compose；MySQL 相容性／持久化驗證須使用 MySQL profile；不啟用 AI keys
- License / review: Apache-2.0.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: Java 17／Docker Compose；MySQL 相容性／持久化驗證須使用 MySQL profile；不啟用 AI keys

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A04-SG01 | owner create/read/update | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A04-SG02 | owner→pet 關聯一致 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A04-SG03 | pet→visit 跨服務寫入／讀回 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A04-SG04 | 無效資料與未知 ID | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A04-SG05 | 服務 unavailable／gateway 行為 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A04-SG06 | MySQL 持久化與測試資料清理 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

### A05 — eclipse-ditto/ditto

- Source commit: `30435a5feb135342bb9c395af984abe53488b184`.
- Proposal state: `CANDIDATE_PINNED_NOT_ADAPTED`.
- Preconditions: Docker Compose 至少 2 CPU/4GB；source build JDK≥25；MQ connector exact scope 待核對
- License / review: EPL-2.0.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: Docker Compose 至少 2 CPU/4GB；source build JDK≥25；MQ connector exact scope 待核對

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A05-SG01 | digital twin create/read/update | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A05-SG02 | policy 允許與拒絕 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A05-SG03 | 更新後 search 一致性 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A05-SG04 | MQ ingress→twin 更新（待 connector 適配） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A05-SG05 | event egress／重複訊息檢查（待適配） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A05-SG06 | broker unavailable 與資料清理 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

### A06 — directus/directus

- Source commit: `a7d59f95833664ef32f4fe8dd90246a104f4b0d9`.
- Proposal state: `CANDIDATE_LICENSE_REVIEW`.
- Preconditions: MSCL 1.0，free-tier/商用適用條件必須先確認；SQL + optional Redis/storage
- License / review: MSCL 1.0（source-available；非一般 OSI license）.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: MSCL 1.0，free-tier/商用適用條件必須先確認；SQL + optional Redis/storage

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A06-SG01 | REST CRUD 精確資料 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A06-SG02 | filter／sort／pagination | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A06-SG03 | field-level role permission | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A06-SG04 | relation／NULL／validation | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A06-SG05 | REST 與 GraphQL 同資料一致 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A06-SG06 | 失敗寫入與資料清理 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

### A07 — go-gitea/gitea

- Source commit: `884884308d0a1b4de423e80633964e0af505eda7`.
- Proposal state: `CANDIDATE_PINNED_NOT_ADAPTED`.
- Preconditions: 隔離本地 instance + DB；所有 repo、帳號與 webhook receiver 都為測試資源
- License / review: MIT.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: 隔離本地 instance + DB；所有 repo、帳號與 webhook receiver 都為測試資源

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A07-SG01 | repository API create/read | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A07-SG02 | issue create/update/list | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A07-SG03 | 權限不足與無效登入 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A07-SG04 | 本地 Git push／clone 後內容一致 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A07-SG05 | webhook payload 與收件次數（待 endpoint 適配） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A07-SG06 | repo／issue／DB 清理 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

### A08 — mattermost/mattermost

- Source commit: `1381b881f4908381a3dd50aa40aa8a1c955bd320`.
- Proposal state: `CANDIDATE_LICENSE_REVIEW`.
- Preconditions: license rights 依 LICENSE.txt 再確認；本地 instance + Postgres，不連真實 workspace
- License / review: 混合／open-core，GitHub NOASSERTION，尚未核對完整 LICENSE.txt.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: license rights 依 LICENSE.txt 再確認；本地 instance + Postgres，不連真實 workspace

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A08-SG01 | channel/message create/read | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A08-SG02 | 非成員／無效 token 拒絕 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A08-SG03 | 訊息 edit/delete 一致 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A08-SG04 | realtime event 與 REST 一致（待 WS 適配） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A08-SG05 | 重連後缺漏／重複訊息檢查 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A08-SG06 | 測試 workspace/channel/DB 清理 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

### A09 — fastapi/full-stack-fastapi-template

- Source commit: `aabb90126964a164e02aff39e7c385bb0598236d`.
- Proposal state: `CANDIDATE_PINNED_NOT_ADAPTED`.
- Preconditions: Docker Compose、PostgreSQL、Mailpit；外部 SMTP/Cloud 不啟用
- License / review: MIT.
- Existing source observation (bounded claim): Candidate planning only; no deployment or adapter acceptance is established.
- Gaps: Docker Compose、PostgreSQL、Mailpit；外部 SMTP/Cloud 不啟用

| Group | Planned behavior | Upstream | Framework provider | Module | Native agent | SIT |
| --- | --- | --- | --- | --- | --- | --- |
| A09-SG01 | JWT login 正向 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A09-SG02 | 無效 token／跨使用者拒絕 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A09-SG03 | items CRUD 精確內容 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A09-SG04 | Pydantic validation 與錯誤回應 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A09-SG05 | local password recovery email（Mailpit） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |
| A09-SG06 | 失敗交易／資料清理 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED |

## Server fixtures — dependencies, not extra client/application projects

| Fixture | Backend | Planned version | Artifact pin |
| --- | --- | --- | --- |
| FX-MONGO | mongodb/mongo | 7.0.16 | VERSION_ONLY_ARTIFACT_PIN_REQUIRED |
| FX-MARIA | MariaDB/server | 11.8.8 | VERSION_ONLY_ARTIFACT_PIN_REQUIRED |
| FX-KAFKA | apache/kafka | 3.9.2 | VERSION_ONLY_ARTIFACT_PIN_REQUIRED |
| FX-NATS | nats-io/nats-server | 2.10.26 | VERSION_ONLY_ARTIFACT_PIN_REQUIRED |

MongoDB 7.0.16 and the separately observed 8.0.4 variant are not interchangeable. Artifact hashes/runtime identities must be added before results are admitted. The historical NATS server wire proof does not validate nats.py cases.

## Bounded observations

- **Kafka frozen addendum**: [authenticated evidence ZIP](https://chatgpt.com/api/library/files/libfile_d6e186154c6c819190f01a1a62efcec0/download), 181,578 bytes, SHA256 `608ddb3e182324a3fafb46fcf5cda3ffab7a5f884a5ba85d29602bb6115cd4cc`. Reported upstream 14 plus 10 boundary tests; framework Python helper 9 tests. Upstream `default.py`/`sticky.py` line coverage is **100/102 = 98.04%**; helper line coverage is **125/395 = 31.65%**. Neither is Java/module or product requirement coverage.
- **Later aiokafka/NATS expansion**: 24 client-to-real-broker assertions were reported by the independent task. This later artifact and exact group mappings have not been imported; no provider/module/agent/SIT promotion is made.
- **gRPC findings**: request/initial metadata, short timeout rounding and empty-descriptor-options behavior have reported counterexamples. The independent repair candidate still needs its own reviewed version/evidence binding.
- **Published Greeter claim**: the framework reference commit records compiled host/module/native-provider execution. A commit statement alone is not imported as per-group run evidence; exact source/input/oracle/run hashes remain required.
- **Historical public CI**: [run 42337071696](https://github.com/hermanwangd/Auto_Regression_Test_Framework/actions/runs/42337071696), framework source `ff582c60…`, SQLite/NATS harness evidence. This does not establish a fresh b95f6b76 run.
- **16 retained historical records**: SQLite, jq, Datasette and PocketBase. The inventory builder recomputed 48 artifact hashes and checked receipt/result identity. Their raw records and hashes are in the JSON; recorded framework validation was not rerun, artifact locations are local-retained rather than public downloads, and none contributes to the proposed denominator/current numerator.
- **Historical classification correction**: SQLite and jq `pin-mismatch` records retain their raw `slot: unavailable`. Their normalized scenario category is `PIN_MISMATCH`; neither establishes dependency-outage coverage or an independently verified admission stage. Both remain outside the current denominator/numerator.

## Capability-case planning crosswalk

Separate denominator: **17 proposed capability categories**. Source observations are attributed planning inputs. Exact executable-case denominator and acceptance rate remain **UNKNOWN**; candidate mappings are proposed applicability, not validated cases.

| ID | Capability | Proposed project mapping | Missing acceptance |
| --- | --- | --- | --- |
| C01 | REST / HTTP | A01, A02, A03, A04, A05, A06, A07, A08, A09 | method/status/header/schema, pagination, auth/authorization, idempotency, timeout/retry, app→DB/MQ full path
| C02 | gRPC | P01, P02, P03, P04, P05, A01 | method/cardinality/schema admissions, binary/duplicate/trailer metadata, deadline/cancellation, exact status; current framework/module/native agent/SIT pins
| C03 | Kafka | P06, P07, A05 | framework/module native route, group/rebalance/offset semantics beyond bounded fixture, security/TLS, retry/idempotency, cleanup; no exactly-once delivery guarantee inferred
| C04 | NATS | P08 | current framework/module path, complete request/reply and JetStream ack/redelivery/durable semantics, security/TLS, persistence/cleanup
| C05 | IBM MQ | Outside current 20-project portfolio; source candidates below | real queue manager put/get, pub/sub, request/reply correlation, syncpoint commit/backout, DLQ/redelivery, reconnect, queue cleanup
| C06 | Oracle | Outside current 20-project portfolio; source candidates below | actual selected Oracle version + JDBC: bind/NULL/type handling, commit/rollback/savepoint, SQL error codes, stored procedure/cursor where used, timeout, schema cleanup
| C07 | IBM Db2 | Outside current 20-project portfolio; source candidates below | actual selected Db2 platform/version + JDBC/CLI: parameters/types, transaction/isolation, SQLSTATE/SQLCODE, stored procedure where used, connection recovery, schema cleanup
| C08 | MariaDB | P10, P11 | native provider integration, auth/TLS, broader types/transactions, exact version constraints; ORM postrun grading pending
| C09 | MongoDB | P09 | native provider integration, auth/TLS, replica-set transactions/read concern where applicable, error/recovery/cleanup; ORM postrun grading pending
| C10 | MinIO / S3-compatible object storage | Outside current 20-project portfolio; source candidates below | actual approved MinIO server + SDK: put/get/list/stat/delete, byte checksum, multipart/abort, presigned URL expiry, forbidden access, retries, bucket/prefix cleanup
| C11 | Redis / RESP | A01, A03 | RESP2/RESP3 shapes, binary/NULL, TTL/expiry, atomic/transaction behavior, reconnect, persistence where used, key-prefix cleanup
| C12 | Kubernetes runner | A01, A02, A04 | pinned cluster/K3s and image digest, Job success/failure/timeout/cancel, pod logs/report upload, RBAC/network/storage, retry and owned-resource cleanup
| C13 | Helm chart lifecycle | Outside current 20-project portfolio; source candidates below | local lint/render/schema/dependency checks AND real install/upgrade/rollback/test/uninstall, persistence, hooks, failure evidence
| C14 | VM runner | Outside current 20-project portfolio; source candidates below | chosen OS/image/architecture, connectivity and account, deploy/service start, reboot/restart behavior, post-deploy tests, logs, owned-data cleanup
| C15 | Ansible VM lifecycle | Outside current 20-project portfolio; source candidates below | syntax/inventory/vars, check-mode limits, actual VM converge, second-run idempotency, deploy, failure/recovery, post-deploy regression, evidence and cleanup
| C16 | External systems | A01, A02, A03, A04, A05, A06, A07, A08, A09 | spec-backed mock contract plus authorized actual endpoint path; auth/TLS, correlation, timeout/retry/idempotency, duplicate/missing events, dependency failure recovery
| C17 | Shared SIT test data | P09, P10, P11, A01, A02, A03, A04 | data owner/schema/version, seed/oracle separation, namespace/tenant/run_id, concurrency, prerequisite readiness, reset, cleanup after failure/cancel, retained receipt

Rows require capability → exact case → input/oracle → run → evidence, bound to implementation version, layer and environment. No repository-count coverage calculation is permitted.

## Reconciliation and declared linked-group states

`NOT_RECONCILED` on a capability is a reconciliation state: no approved capability-to-case/run binding is established. The counts below describe the existing scenario states reached through **proposed project links**, not capability verdicts, approved case mappings, readiness or a new coverage denominator. A group is counted once per capability/layer; groups can occur under multiple capabilities, so do not sum rows.

Capability-level execution assessment remains **UNKNOWN**. The bounded P04-SG02 executions are established separately and do not establish complete C02 capability acceptance. A declared `BLOCKED` or `NOT_RUN` state alone does not establish whether an attempt occurred. Project blocker references are retained in the JSON and describe those projects, not independently graded capability blockers.

| Capability | Linked groups | Upstream | Framework provider | Module | Native agent | SIT |
| --- | ---: | --- | --- | --- | --- | --- |
| C01 | 54 | NOT_RUN: 54 | NOT_RUN: 54 | NOT_RUN: 54 | NOT_RUN: 54 | BLOCKED: 54 |
| C02 | 30 | NOT_RECONCILED: 24; NOT_RUN: 6 | NOT_RECONCILED: 23; NOT_RUN: 6; ACCEPTED: 1 | NOT_RECONCILED: 13; BLOCKED: 10; NOT_RUN: 6; ACCEPTED: 1 | NOT_RUN: 30 | BLOCKED: 30 |
| C03 | 18 | NOT_RECONCILED: 12; NOT_RUN: 6 | NOT_RECONCILED: 12; NOT_RUN: 6 | BLOCKED: 12; NOT_RUN: 6 | NOT_RUN: 18 | BLOCKED: 18 |
| C04 | 6 | NOT_RECONCILED: 6 | NOT_RECONCILED: 6 | NOT_RECONCILED: 6 | NOT_RUN: 6 | BLOCKED: 6 |
| C05 | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups |
| C06 | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups |
| C07 | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups |
| C08 | 13 | NOT_RECONCILED: 13 | BLOCKED: 13 | BLOCKED: 13 | NOT_RUN: 13 | BLOCKED: 13 |
| C09 | 7 | NOT_RECONCILED: 7 | BLOCKED: 7 | BLOCKED: 7 | NOT_RUN: 7 | BLOCKED: 7 |
| C10 | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups |
| C11 | 12 | NOT_RUN: 12 | NOT_RUN: 12 | NOT_RUN: 12 | NOT_RUN: 12 | BLOCKED: 12 |
| C12 | 18 | NOT_RUN: 18 | NOT_RUN: 18 | NOT_RUN: 18 | NOT_RUN: 18 | BLOCKED: 18 |
| C13 | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups |
| C14 | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups |
| C15 | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups | No proposed linked groups |
| C16 | 54 | NOT_RUN: 54 | NOT_RUN: 54 | NOT_RUN: 54 | NOT_RUN: 54 | BLOCKED: 54 |
| C17 | 44 | NOT_RECONCILED: 20; NOT_RUN: 24 | BLOCKED: 20; NOT_RUN: 24 | BLOCKED: 20; NOT_RUN: 24 | NOT_RUN: 44 | BLOCKED: 44 |

## Helm / Ansible component plan

Separate planning denominator: **22 component checks (12 Helm / 10 Ansible)**; executable-case denominator/rate remains **UNKNOWN**. Runner image, external case/config/data bundles and bounded collection are part of delivery; SUT applications remain external.

| ID | Component | Check | Layer | State |
| --- | --- | --- | --- | --- |
| H01 | helm_lifecycle | Chart.yaml/lock/dependency source, chart archive hash, image digest, kubeVersion and required tool versions. | local | NOT_RUN |
| H02 | helm_lifecycle | helm lint; values normal/invalid/boundary sets; JSON schema rejection where schema exists. | local | NOT_RUN |
| H03 | helm_lifecycle | helm template output with fixed inputs and controlled random/time/lookup sources; required resource fields, service ports, labels, probes, requests/limits and RBAC. Do not assume every chart renders deterministically. | local | NOT_RUN |
| H04 | helm_lifecycle | dependencies/subcharts/conditional resources; Secret references without exposing secret values. | local | NOT_RUN |
| H05 | helm_lifecycle | render against target Kubernetes versions; this is not an API-server validation PASS. | local | NOT_RUN |
| H06 | helm_lifecycle | install in owned namespace; release status, readiness and post-install application protocol tests. | K8s SIT | NOT_RUN |
| H07 | helm_lifecycle | upgrade from pinned N to N+1; verify application and data invariants. | K8s SIT | NOT_RUN |
| H08 | helm_lifecycle | controlled failure and rollback; verify resources, app version and data separately. Helm rollback is not a database migration rollback guarantee. | K8s SIT | NOT_RUN |
| H09 | helm_lifecycle | helm test or explicitly authored post-deploy tests, hook ordering and hook-failure retention. | K8s SIT | NOT_RUN |
| H10 | helm_lifecycle | PVC/StorageClass, config/secret references, ingress/service DNS and actual network access. | K8s SIT | NOT_RUN |
| H11 | helm_lifecycle | uninstall/cleanup scoped by release+namespace+run_id; retained PVCs/CRDs/hook resources are recorded, never blindly purged. | K8s SIT | NOT_RUN |
| H12 | helm_lifecycle | chart/app/image/values/cluster pins, ordered commands, exit statuses, rendered manifests, release history, Kubernetes events/pod logs, protocol results, cleanup receipts. | evidence | NOT_RUN |
| A01 | ansible_lifecycle | syntax, inventory parsing, group/host targeting, variable precedence, missing/invalid variables, secret redaction. | local | NOT_RUN |
| A02 | ansible_lifecycle | check/diff on supported modules. This is not a pure local static check: it can contact targets, evaluate lookups, and encounter tasks that override check mode. It does not prove commands, service changes or migrations ran. | target-environment rehearsal, separately authorized | NOT_RUN |
| A03 | ansible_lifecycle | exact OS/image/architecture, SSH host identity, authorized user/become scope, prerequisite and package state. | actual VM | NOT_RUN |
| A04 | ansible_lifecycle | deploy pinned package/config; handlers and service start; post-deploy API/DB/MQ test. | actual VM | NOT_RUN |
| A05 | ansible_lifecycle | second converge unchanged (changed=0, with reviewed documented exceptions); verify resulting state, not just Ansible counters. | actual VM | NOT_RUN |
| A06 | ansible_lifecycle | change config/version then converge; handlers fire correctly; no unrelated host/resource changes. | actual VM | NOT_RUN |
| A07 | ansible_lifecycle | controlled task/dependency failure, partial-state evidence, rescue/recovery/rerun or approved rollback. | actual VM | NOT_RUN |
| A08 | ansible_lifecycle | reboot/reconnect and persistent service/data behavior where applicable. | actual VM | NOT_RUN |
| A09 | ansible_lifecycle | owned test data and temporary resources cleaned, infrastructure retained/deleted only under agreed scope. | actual VM | NOT_RUN |
| A10 | ansible_lifecycle | playbook/role/collection/inventory/vars hashes (no secrets), controller and VM pins, task events/recap, independent post-deploy observations, first/second-run comparison, recovery/cleanup receipts. | evidence | NOT_RUN |

The verified 235-byte text/base64 probe is not a runner execution, native attachment transfer or full artifact retrieval. Helm lint/template or Ansible syntax checks cannot establish K3s/VM end-to-end acceptance.

## Component source pins and constraints

These are source/tool/backend candidates, not extra accepted client/application projects. Exact inherited source text and source-document identities are retained in the JSON.

| Repository | Commit | License metadata | Archived | Execution status |
| --- | --- | --- | --- | --- |
| [ibm-messaging/mq-dev-patterns](https://github.com/ibm-messaging/mq-dev-patterns/blob/f7075e80b2557a44caaa18fb2d34e3689b2a2b34/README.md) | `f7075e80b2557a44caaa18fb2d34e3689b2a2b34` | Apache-2.0 | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [ibm-messaging/mq-helm](https://github.com/ibm-messaging/mq-helm/blob/b49fd79ef8395f1348fac36538f732dae8ead3f0/README.md) | `b49fd79ef8395f1348fac36538f732dae8ead3f0` | Apache-2.0 | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [oracle-samples/oracle-db-examples](https://github.com/oracle-samples/oracle-db-examples/blob/f1b90ba5da8f23b9b323d5929d3a18475b837a3b/README.md) | `f1b90ba5da8f23b9b323d5929d3a18475b837a3b` | NOASSERTION | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [oracle/docker-images](https://github.com/oracle/docker-images/blob/2dc45fd0322db9eb42e0e62426487ec7311ba36f/README.md) | `2dc45fd0322db9eb42e0e62426487ec7311ba36f` | UPL-1.0 | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [IBM/db2-samples](https://github.com/IBM/db2-samples/blob/b6347a3b7fcdb93e3d23f0a543787afe88344b5b/README.md) | `b6347a3b7fcdb93e3d23f0a543787afe88344b5b` | Apache-2.0 | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [minio/minio](https://github.com/minio/minio/blob/7aac2a2c5b7c882e68c1ce017d8256be2feea27f/README.md) | `7aac2a2c5b7c882e68c1ce017d8256be2feea27f` | AGPL-3.0 | true | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [minio/minio-go](https://github.com/minio/minio-go/blob/6bfd31ed6aa196c1c60ca567bdd2b5e36a6498dd/README.md) | `6bfd31ed6aa196c1c60ca567bdd2b5e36a6498dd` | Apache-2.0 | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [redis/redis](https://github.com/redis/redis/blob/5b69ba2ce91fea8e9fc70ebac8ce341b37e75533/README.md) | `5b69ba2ce91fea8e9fc70ebac8ce341b37e75533` | NOASSERTION | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [redis/redis-py](https://github.com/redis/redis-py/blob/4fe8a8c18e289bdbd7a02f01def74aeaf8719b7d/README.md) | `4fe8a8c18e289bdbd7a02f01def74aeaf8719b7d` | MIT | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [nats-io/k8s](https://github.com/nats-io/k8s/blob/a77a1dd187bac238dd8152aeaed40505e00ceba8/README.md) | `a77a1dd187bac238dd8152aeaed40505e00ceba8` | Apache-2.0 | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [minio/operator](https://github.com/minio/operator/blob/34dc3980190390f4ed2f4b4355dbcb936f667a10/README.md) | `34dc3980190390f4ed2f4b4355dbcb936f667a10` | AGPL-3.0 | true | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [Shopify/toxiproxy](https://github.com/Shopify/toxiproxy/blob/49299d8838525126e09c85ebe0ba89940a3261e5/README.md) | `49299d8838525126e09c85ebe0ba89940a3261e5` | MIT | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [wiremock/wiremock](https://github.com/wiremock/wiremock/blob/f562b1f571613343f36c7ebbca87be71f979a99b/README.md) | `f562b1f571613343f36c7ebbca87be71f979a99b` | Apache-2.0 | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [ansible/molecule](https://github.com/ansible/molecule/blob/ada645a132b9332c291696f553d93cce788b5410/README.md) | `ada645a132b9332c291696f553d93cce788b5410` | MIT | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK
| [ansible/ansible](https://github.com/ansible/ansible/blob/fd540eda9f96783955b227e84e229ca51f041cae/README.md) | `fd540eda9f96783955b227e84e229ca51f041cae` | GPL-3.0 | false | SOURCE_VERIFIED_NOT_EXECUTED_BY_THIS_TASK

- MinIO server/operator source snapshots are archived; retain an approved pinned server/chart/image and verify support and licensing. No unqualified latest installation is recommended.
- Observed NATS chart/app 2.15.2 differs from the local 2.10.26 broker fixture. IBM MQ chart 14.0.0/app 10.0.1.0 requires Kubernetes ≥1.29 and appropriate image/entitlement checks.
- Oracle/Db2 sample licenses do not authorize database engines. Select exact backend platform, version, driver and authorized endpoint.
- Ansible check/diff may contact a target and execute lookups; it needs target authorization. A container-based Molecule run is not VM acceptance. Helm rollback is not a database migration rollback.
- WireMock/Toxiproxy are contract/fault fixtures. Their results do not establish actual external-system acceptance.
- Current extension observations: 12 gRPC wire rows and 24 client-to-real-broker assertion rows were reported; 12 local ORM scenarios are reported with independent grading pending. None is added to the 116 scenario-group numerator. MongoEngine and SQLAlchemy belong to a later project-inventory revision.

## Missing protocols / future backlog

Oracle, DB2 and IBM MQ remain explicit selection/environment gaps in this v1 portfolio. Their public examples do not grant a database or queue-manager license. The following entries are outside the 20/116 denominator; admission requires a new inventory version.

| Repository | Pin | State |
| --- | --- | --- |
| oracle-samples/db-sample-schemas | 6660bad68c07bd143430ace58565b3f727e17263 | LICENSED_BACKEND_REQUIRED |
| IBM/db2-samples | b6347a3b7fcdb93e3d23f0a543787afe88344b5b | LICENSED_BACKEND_REQUIRED |
| ibm-messaging/mq-dev-patterns | f7075e80b2557a44caaa18fb2d34e3689b2a2b34 | LICENSED_BACKEND_REQUIRED |
| spring-petclinic/spring-petclinic-rest | 5180cc9bd0163393c4e628bedc1bc4c27cfaab1b | BACKLOG_NOT_IN_V1_DENOMINATOR |
| spring-petclinic/spring-petclinic-angular | fba8956cbdd04e09973f81eb39379955a825bfc3 | BACKLOG_NOT_IN_V1_DENOMINATOR |
| dockersamples/example-voting-app | 63e9150ca17af4ed05880d4245e486481f73fcb4 | BACKLOG_NOT_IN_V1_DENOMINATOR |
| eventuate-tram/eventuate-tram-examples-customers-and-orders | 8632d030301c2a477141350df1e77f3f991f64e0 | BACKLOG_NOT_IN_V1_DENOMINATOR |

## Maintenance

1. Keep JSON as the data source and update this view together. Preserve IDs and prior snapshots; record changes below before altering denominators.
2. Before admitting a result, freeze exact executable case/layer/input/oracle/review and actual runtime versions. Bind canonical run, evidence validation, artifact hashes and source revision. Retain failures, missing evidence, exclusions and stale results.
3. Compute each layer's rate only over its explicit approved executable cases. Keep scope completeness and unknown denominators visible; no pass-rate-to-requirement-coverage conversion.
4. Reuse isolated tester namespaces. Do not modify another worker's outputs or leak holdout material into development.
5. Cross-repository SIT suites stay under `tests/sit/` in an existing product repository. Unit, local and other integration tests retain their owning repository's conventions and paths. Governed knowledge/traceability stays in Product Knowledge repositories, with Cognee retrieval. This tool-repository inventory tracks validation of the testing tooling.
6. Reusable business-flow authoring is deferred until repeated cross-repository workflows justify it; compile to existing v0.3 only after separate concrete review.

## Change history

- `planning-v1`: formalized the verified 20-repository/116-group proposal, assigned stable group IDs, and retained observations separately. Integrated the canonical 17-capability and Helm12/Ansible10 component supplement without changing the product denominator.

- `review-fixes-2026-11-07-r1` (same `planning-v1` denominators): preserved raw historical slots and normalized the two pin-mismatch records; limited `tests/sit/` ownership to cross-repository SIT suites; added descriptive linked-group state summaries and explicit unknown execution attempts. Original proposal text and source pins remain unchanged.

Metadata-only verification for the prior `review-fixes-2026-11-07-r1` revision: **425 invariant assertions passed; 14 negative mutation cases were rejected**, including raw-source changes, denominator changes, history misclassification, duplicate counts, broken references, invented execution state and Markdown drift. The validator ran in memory; its execution record is retained in the tool transcript, not as a committed CI check. This establishes no provider/module/agent/SIT runtime coverage.

- `p04-sg02-reconciliation-2026-11-09-r1` (same `planning-v1` denominators): bound P04-SG02 to four frozen cardinality cases, source173ee0ec and JRE21; recorded Framework-provider/Module acceptance only for the schema-derived owned Java fixture scope under `p04-packaged-four-postrun.v1`. Preserved three failed/UNKNOWN product verdicts, all excluded layers, original artifacts and historical records. Updated only the affected C02 descriptive state counts; no portfolio execution denominator or requirement-coverage rate was introduced.
