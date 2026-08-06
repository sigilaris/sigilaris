# Node JVM

`sigilaris-node-jvm`은 `sigilaris-node-common` 위에 얹힌 JVM runtime bundle이다.
이 모듈은 현재 runtime lifecycle seam, config/bootstrap assembly, HotStuff
integration, Armeria HTTP transport adapter, SwayDB-backed persistence helper를
소유한다.

## 현재 Baseline

- `org.sigilaris.node.jvm.runtime.*` 아래의 runtime lifecycle seam
- static peer topology와 transport-auth configuration loader
- session open, long-lived event stream, control batch, bootstrap HTTP flow를
  담당하는 Armeria transport adapter
- HotStuff bootstrap, catch-up, pacemaker, artifact validation runtime
- transaction-pipeline admission, durable replay/convergence, injectable
  identity strategy integration
- 현재 durable baseline을 위한 SwayDB-backed storage helper

## Transaction Pipeline Admission Identity

새 integration은 `TxPipelineAdmissionService`에
`TxPipelineIdentityStrategy[F]`를 주입하는 방식을 권장한다. Admission은 request
normalization 직후 strategy를 정확히 한 번 호출하고, 그 submission 전체에서
반환된 hash/id 쌍을 재사용한다. 기본 구현은
`TxPipelineIdentityStrategy.v1(identityScope)`다.

0.2.12 이전의 `TxPipelineIdGenerator`와 `identityScope`를 받는 constructor도
유지된다. 이 constructor는 두 인자를 새로운 단일 identity-result 경로로
adapt한다. `TxPipelineIdGenerator.deterministicSha256`는 이미 계산된 hash를
사용하며, generator scope와 constructor의 `identityScope`가 같으면 normalized
identity payload를 두 번 hash하지 않는다. 과거부터 서로 다른 scope를 넘긴 경우도
byte compatibility를 유지해 pipeline id는 generator scope, canonical hash는
admission scope를 계속 사용한다. 새 integration은 두 scope를 같게 해야 한다.
기존 custom id generator는 source compatibility를 유지하지만 identity를 replay
lookup 전에 계산하므로 callback은 idempotent replay를 포함한 모든 normalized
submission마다 한 번 실행된다. 따라서 normalization 뒤의 admission 검사에서
거부되는 request에도 callback이 실행된다.

Custom identity strategy의 `canonicalPayloadHash`는 normalized request와 identity
scope에 대해 deterministic하고 collision-resistant해야 한다. Admission은 replay,
alias binding, idempotency key 없는 convergence에서 동일 hash를 동일 canonical
request의 증거로 취급한다. `pipelineId`와 hash/id pair도 안정적으로 유지해야 한다.
Strategy 변경은 모든 writer/reader에 함께 rollout해야 하며, 그렇지 않으면 node
사이의 replay와 collision 판단이 달라질 수 있다.

## 섹션 가이드

- [Bootstrap And Sync](bootstrap-and-sync.md)는 static trust-root verification,
  snapshot sync, historical backfill을 다룬다.
- [HotStuff And Pacemaker](hotstuff-and-pacemaker.md)는 proposal/vote/QC 흐름,
  provider-backed autonomous proposal, timeout/new-view progression을 다룬다.
- [Static Launch](static-launch.md)는 reference smoke harness, minimal config
  shape, operator-owned startup/restart note를 다룬다.

## 현재 제한 사항

- Static peer topology와 static validator inventory가 여전히 배포 baseline이다.
- Restart, fencing, DR sequencing은 여전히 operator-managed다.
- 현재 public repo는 reference harness와 library runtime을 제공하지만,
  productized launcher/orchestrator는 제공하지 않는다.

## 후속 작업

- dynamic discovery와 peer scoring
- validator-set rotation과 더 넓은 trust-root policy 진화
- automatic failover와 remote signer/KMS integration

## 관련 페이지

- [Node Common](../node-common/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
