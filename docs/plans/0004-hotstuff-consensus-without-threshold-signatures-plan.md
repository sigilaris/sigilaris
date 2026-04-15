# 0004 - HotStuff Consensus Without Threshold Signatures Plan

## Status
Phase 3A Complete; ADR-0022 Drafted; Pacemaker Follow-Up Scoped

## Created
2026-03-29

## Last Updated
2026-04-06

## Background
- 이 문서는 ADR-0017의 implementation plan 이다.
- ADR-0016과 plan 0003은 transport-neutral gossip/session substrate를 소유하고, 이 문서는 그 위에서 동작하는 HotStuff consensus runtime과 artifact contract integration을 다룬다.
- 선택한 baseline은 BLS threshold signature를 사용하지 않는 HotStuff 계열이다. 따라서 proposal, vote, QC는 individually signed vote set 기반 validation을 전제로 해야 한다.
- 이전 문서 구조에서는 gossip substrate와 consensus semantics가 섞여 있었지만, 이제는 proposal/vote/QC identity, sign-bytes, QC assembly, validator-set window 검증을 consensus plan 아래에서 독립적으로 관리한다.
- initial deployment baseline은 ADR-0018의 static peer topology, validator/audit node role 분리, same-DC validator placement, `100ms` block production target을 전제로 한다.
- `2026-04-02` 문서-구현 대조 리뷰 기준으로 artifact model, validation, topic contract, in-memory loopback baseline, concrete JVM bootstrap wiring, explicit bootstrap/service seam, audit follower relay, same-window retry budget enforcement, vote exact-known regression lock, dependency boundary test, shipped-baseline 문서 정렬이 compile/test 기준으로 landed 되었다.
- `2026-04-04` 기준 HotStuff proposal path는 ADR-0019 canonical `BlockHeader`와 header-only `BlockId` contract를 소비하지만, `ProposalId` / `VoteId` / validator-set window ownership baseline은 그대로 유지한다.
- 위 리뷰는 `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`, `docs/adr/0017-hotstuff-consensus-without-threshold-signatures.md`, `README.md`, `org.sigilaris.node.jvm.runtime.consensus.hotstuff` 구현, HotStuff test suite 를 대조하는 방식으로 수행했다.
- 기존 Phase 0-3 checklist 는 landed HotStuff artifact/runtime slice 를 기록했고, Phase 3A 는 concrete bootstrap/service closure 와 dependency/doc alignment 를 마무리했다. `2026-04-06` 기준 pacemaker / view-change semantic baseline 은 ADR-0022 초안으로 분리되었고, 이 plan 의 남은 residual work 는 그 runtime integration follow-up 으로 한정한다.

## Goal
- `sigilaris-node-jvm` 안에 HotStuff non-threshold-signature consensus runtime을 도입한다.
- proposal, vote, QC의 canonical identity/sign-bytes/validation rule을 코드 구조와 테스트로 고정한다.
- ADR-0016 gossip substrate 위에서 `consensus.proposal`, `consensus.vote` topic sync와 QC assembly가 동작하도록 연결한다.

## Scope
- `org.sigilaris.node.jvm.runtime.consensus.hotstuff` 계열 package에 proposal/vote/QC model, validation rule, runtime service contract를 추가한다.
- proposal id, vote id, block id, validator-set window key, QC verification contract를 구현 단위로 고정한다.
- `consensus.proposal` / `consensus.vote` topic을 gossip substrate의 generic topic contract seam에 연결한다.
- proposal justification payload, vote exact known-set, bounded `requestById` fetch integration, consensus-side QoS priority 요구사항을 반영한다.
- validator node와 audit node의 local behavior gating과 read-only audit follow path를 반영한다.
- HotStuff runtime과 gossip substrate 사이의 loopback / integration / failure test를 추가한다.

## Non-Goals
- BLS threshold signature, aggregate signature 기반 QC는 이번 plan의 범위에 넣지 않는다.
- 다른 consensus 알고리즘(PBFT, Tendermint, Narwhal/Bullshark 등)은 이번 plan의 범위에 넣지 않는다.
- peer discovery, peer scoring, topology management는 이번 plan에 넣지 않는다.
- pacemaker timeout vote, timeout certificate, new-view wire contract의 최종 형식은 이번 plan에서 완전히 고정하지 않는다. 필요한 seam과 placeholder는 둘 수 있지만 상세 wire contract는 ADR-0022와 그 후속 spec 이 소유한다.
- audit node의 automatic runtime promotion, automatic cross-DC failover, online validator-set reconfiguration protocol은 이번 plan에 넣지 않는다.
- state execution engine, application reducer semantics, block contents 자체의 application-specific meaning은 이 plan의 직접 범위가 아니다.

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0012: Signed Transaction Requirement
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- ADR-0019: Canonical Block Header And Application-Neutral Block View
- ADR-0022: HotStuff Pacemaker And View-Change Baseline
- `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- `docs/plans/0005-canonical-block-structure-migration-plan.md`
- `docs/plans/plan-template.md`
- `README.md`

## Decisions To Lock Before Implementation
- consensus public package root는 `org.sigilaris.node.jvm.runtime.consensus.hotstuff`으로 고정한다.
- baseline consensus variant는 chained HotStuff 로 고정한다. steady-state vote는 proposal당 하나의 canonical vote artifact를 기준으로 한다.
- local node role은 `validator`와 `audit`로 구분한다. `validator`만 proposal/vote를 emit 할 수 있고 quorum 계산에 참여한다. `audit` node는 artifact를 수신/검증/저장/재전송할 수 있지만 local proposal/vote를 emit 하지 않는다.
- validator private key material은 validator identity에 대응하는 signing secret으로 취급한다. initial baseline에서는 operator-managed key custody를 허용한다.
- `BlockId`, `ProposalId`, `VoteId`는 서로 다른 value type으로 모델링한다. compile-time surface에서 혼용하지 않는다.
- proposal sign-bytes는 최소한 `chainId`, `height`, `view`, proposer identity, `validatorSetHash`, target `BlockId`, justify QC subject를 포함해야 한다.
- vote sign-bytes는 최소한 `chainId`, `height`, `view`, voter identity, `validatorSetHash`, target `ProposalId`를 포함해야 한다.
- `ProposalId`는 proposal whole-value deterministic encoding의 hash이고, `BlockId`와 동일하다고 가정하지 않는다.
- `VoteId`는 vote whole-value deterministic encoding의 hash다.
- gossip exact known-set window key는 `(chainId, height, view, validatorSetHash)` 로 고정한다.
- proposal known-set은 `ProposalId` 집합, vote known-set은 `VoteId` 집합 기준으로 동작한다.
- proposal은 receiver가 validation을 시작할 수 있을 정도의 justify QC subject를 self-contained 하게 운반하는 것을 baseline으로 둔다.
- 별도 `consensus.qc` gossip topic은 baseline에 두지 않는다. QC는 proposal justification payload 또는 locally assembled cache로 다룬다.
- bounded explicit fetch는 ADR-0016의 `requestById`를 사용한다. HotStuff path의 baseline request cap 은 proposal `128`, vote `512`, same window retry budget `2`회로 Phase 0에서 잠근다.
- emergency promotion from `audit` to `validator`는 explicit operator-managed config change와 validator-set reconfiguration을 전제로 하며, automatic runtime failover contract로 shipped 하지 않는다.
- 위 promotion baseline에서 validator key relocation을 허용한다. operator는 promoted node에 validator identity별 private key를 주입할 수 있지만, old holder는 먼저 fence 되거나 key access를 잃어야 한다.
- 동일 validator private key가 old holder와 new holder에서 동시에 active 상태가 되는 dual-holder baseline은 금지한다.
- initial deployment target block production interval은 `100ms`다. 이는 batching/QoS/pacing budget을 제약하지만, exact pacemaker wire contract를 대신하지 않는다.
- quorum rule은 active validator set의 `n - floor((n - 1) / 3)` vote 로 고정한다. `n = 3f + 1` 배치에서는 이는 `2f + 1`과 같다.
- equivocation detection key는 baseline으로 `(chainId, validatorId, height, view)` 기준으로 모델링한다. 같은 key에서 서로 다른 target `ProposalId`가 관찰되면 equivocation으로 판정한다.
- pacemaker timeout/new-view는 placeholder seam만 둘 수 있고, production wire contract는 ADR-0022 및 후속 spec 없이는 shipped contract 로 고정하지 않는다.
- consensus runtime은 gossip substrate의 generic rejection class 위에 topic-local reason을 얹을 수 있지만, transport-neutral projection은 ADR-0016 rejection family를 따른다.

## Change Areas

### Code
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip`의 topic contract registry wiring
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime`의 bootstrap wiring 확장
- 필요 시 `modules/node-jvm/src/test/scala` 아래 HotStuff unit / loopback / integration test

### Tests
- proposal / vote / QC identity and sign-bytes test
- validator-set window / quorum validation test
- equivocation detection test
- gossip known-set / request-by-id integration test
- proposal justification self-contained validation test
- validator vs audit local behavior gating test
- key relocation / old-holder fencing / dual-holder prohibition test
- HotStuff runtime loopback / replay / failure test

### Docs
- `docs/adr/0017-hotstuff-consensus-without-threshold-signatures.md`
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- 필요 시 `README.md`의 consensus 관련 설명 보강

## Implementation Phases

### Phase 0: Consensus Contract Lock
- proposal/vote/QC value model, identity contract, sign-bytes semantic inputs를 고정한다.
- validator/audit local role model, explicit promotion baseline, `100ms` deployment target의 semantic scope를 고정한다.
- validator key relocation baseline, old-holder fencing requirement, dual-holder prohibition을 고정한다.
- validator-set window key, quorum rule, equivocation detection key를 고정한다.
- `ProposalId != BlockId` baseline과 self-contained justification baseline을 문서와 타입에 반영한다.
- `requestById` HotStuff policy 상한, retry policy, unsupported pacemaker artifact handling을 고정한다.

### Phase 1: Artifact Model And Validation
- `BlockId`, `ProposalId`, `VoteId`, `ValidatorId`, `QuorumCertificate` value type을 추가한다.
- local node role type(`validator` / `audit`)과 role-gated emission policy를 추가한다.
- validator key holder state와 old-holder fenced state를 표현할 수 있는 minimal runtime model을 추가한다.
- canonical deterministic encoding helper와 sign-bytes 생성 규칙을 구현한다.
- proposal signature validation, vote signature validation, QC validation, duplicate/duplicate-validator rejection, equivocation detection을 구현한다.
- proposal justification subject와 target `BlockId` 관계를 검증하는 runtime validator를 추가한다.

### Phase 2: Gossip Integration And QC Assembly
- Phase 2는 plan 0003 Phase 1의 generic topic contract seam과 plan 0003 Phase 2의 runtime-owned gossip state store, service contract, atomic apply baseline이 먼저 준비된 뒤에만 시작한다.
- `consensus.proposal` / `consensus.vote` topic contract implementation을 gossip substrate registry에 연결한다.
- proposal known-set query, vote known-set query, bounded `requestById` fetch path를 구현한다.
- vote accumulation과 QC assembly를 runtime component로 추가한다.
- proposal reception 시 justify QC validation과 local QC cache/update path를 연결한다.
- consensus topic QoS priority를 gossip substrate queue model에 연결한다.
- audit node가 proposal/vote/QC를 read-only로 따라가되 local proposal/vote emission은 하지 않는 path를 연결한다.
- key relocation 후 promoted node만 local proposal/vote를 emit 하고 old holder는 더 이상 signer로 동작하지 않는 path를 연결한다.

### Phase 3: Verification And Docs
- unit, loopback, integration test를 green 으로 만든다.
- gossip substrate와 consensus runtime 사이의 ownership / dependency rule을 검증한다.
- 문서, README, follow-up dependency를 갱신한다.
- pacemaker artifact follow-up과 미구현 blocker를 문서에 명시한다.

### Phase 3A: Residual Integration And Bootstrap Closure
- Phase 3A residual closure item 은 `3A-1` 부터 `3A-6` 까지의 stable id 로 추적한다. Test plan, acceptance criteria, checklist 는 이 id 를 기준으로 서로 대응한다.
- `3A-1` concrete JVM baseline 에서 사용할 HotStuff bootstrap wiring 을 추가한다. static peer topology / gossip runtime bootstrap 과 일관된 loader or bootstrap contract 위에 HotStuff runtime source/sink/topic-contract wiring 이 올라가야 한다.
- `3A-2` `HotStuffNodeRuntime.create` 가 test-only in-memory assembly 에 머물지 않도록 runtime-owned service contract 와 concrete bootstrap surface 를 분리한다. 최소 범위는 local role, validator-set, key-holder state, local signer map, gossip registry wiring 이다.
- `3A-3` audit follower 가 수신한 proposal/vote artifact 를 검증/저장만 하지 않고 policy-controlled re-broadcast source 에 반영할 수 있도록 source/sink ownership 을 재구성한다. 구현은 dedicated relay source 또는 runtime-owned sink-to-source feed-through 중 하나를 택할 수 있지만, validator-only local emission policy 를 우회하거나 새로운 validator signature 를 합성해서는 안 된다.
- `3A-4` same-window retry budget `2`회 baseline 을 control/request path 에 실제로 연결한다. `(chainId, height, view, validatorSetHash, topic)` 또는 동등 key 기준 retry state 를 두고 상한 초과 시 rejection 또는 no-op policy 를 고정한다.
- `3A-5` proposal 중심으로만 잠겨 있는 exact-known/request-by-id integration coverage 를 vote topic 까지 확장한다. `consensus.vote` 의 exact known-set query, bounded `requestById`, wrong-window rejection, duplicate relay semantics 를 regression test 로 고정한다.
- `3A-6` gossip substrate 와 consensus runtime 사이의 dependency rule 검증을 문서 선언이 아니라 compile/test asset 으로 추가하고, README / ADR / plan 텍스트를 shipped artifact contract, 미완료 bootstrap/relay/retry work, follow-up pacemaker dependency 기준으로 다시 정렬한다. 검증 메커니즘은 import-rule test, compile-time module boundary assertion, CI 에서 실행되는 dependency snapshot assertion 중 무엇이든 사용할 수 있지만, 결과는 compile/test path 에 포함되어야 한다.

## Test Plan
- Phase 1 Success: pure unit test로 `BlockId`, `ProposalId`, `VoteId`가 서로 다른 타입과 equality contract를 가지는지를 검증한다.
- Phase 1 Success: pure unit test로 `validator`와 `audit` local role이 proposal/vote emission 권한을 다르게 가지는지를 검증한다.
- Phase 1 Success: pure unit test로 validator key holder state가 local role과 분리돼 모델링되고, same validator key의 dual-holder active state가 invalid 인variant로 취급되는지를 검증한다.
- Phase 1 Success: pure unit test로 proposal sign-bytes와 vote sign-bytes가 gossip envelope field가 아니라 canonical whole-value contract를 입력으로 사용하는지를 검증한다.
- Phase 1 Success: pure unit test로 proposal signature validation, vote signature validation, duplicate validator vote rejection, duplicate vote dedup, equivocation detection을 검증한다.
- Phase 1 Success: pure unit test로 QC validation이 aggregate signature가 아니라 validator vote set cardinality와 signer uniqueness를 기준으로 수행되는지를 검증한다.
- Phase 1 Failure: pure unit test로 wrong `validatorSetHash`, wrong target `ProposalId`, insufficient quorum, malformed justification subject를 reject 하는지를 검증한다.
- Phase 2 Success: integration test로 `consensus.proposal` exact known-set, `consensus.vote` exact known-set, bounded `requestById`, proposal justification self-contained validation, vote accumulation 후 QC assembly가 동작하는지를 검증한다.
- Phase 2 Success: integration test로 audit node가 proposal/vote/QC를 검증하고 저장하지만 local proposal/vote는 emit 하지 않는 read-only follow path를 검증한다.
- Phase 2 Success: integration test로 operator-managed key relocation 뒤 promoted node만 해당 validator identity로 proposal/vote를 emit 하고 old holder는 더 이상 signer로 동작하지 않는지를 검증한다.
- Phase 2 Success: integration test로 proposal/vote topic QoS priority가 tx backlog에 막히지 않는지를 검증한다.
- Phase 2 Failure: integration test로 audit node에서 local validator-only action이 요청되면 rejection 또는 no-op policy로 처리되는지를 검증한다.
- Phase 2 Failure: integration test로 same validator private key가 old/new holder에 동시에 active 하게 남아 있는 dual-holder 상태가 reject 되거나 startup/admission failure로 막히는지를 검증한다.
- Phase 2 Failure: integration test로 unknown proposal request, oversize request, stale justification, conflicting vote, wrong window key, missing justify QC subject를 rejection path로 투영하는지를 검증한다.
- Phase 3 Regression: gossip substrate regression suite와 HotStuff suite가 함께 green 상태를 유지해야 한다.
- Phase 3A Success (검증 대상: `3A-1`, 완료 체크: Acceptance Criteria 8): bootstrap test로 static topology / gossip bootstrap 과 호환되는 HotStuff runtime bootstrap 이 local role, validator set, holder state, signer inventory, topic registry 를 실제 서비스 graph 에 연결하는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-2`, 완료 체크: Acceptance Criteria 9): bootstrap/service test로 runtime-owned service contract 가 in-memory test assembly 와 분리되고, concrete bootstrap surface 가 local role, validator set, holder state, signer inventory, gossip registry wiring 을 명시적으로 드러내는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-3`, 완료 체크: Acceptance Criteria 10): integration test로 audit follower 가 remote proposal/vote 를 검증/저장한 뒤 local validator-only signing 없이 relay source 에 반영하고, downstream peer 가 이를 재수신할 수 있는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-4`, 완료 체크: Acceptance Criteria 11): control-path test로 same window 에 대한 proposal/vote `requestById` retry 가 `2`회를 넘기면 baseline policy 대로 reject 또는 terminal no-op 으로 처리되는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-5`, 완료 체크: Acceptance Criteria 12): integration test로 `consensus.vote` exact known-set, bounded `requestById`, wrong-window rejection, duplicate replay dedupe 가 proposal topic 과 대칭적으로 동작하는지를 검증한다.
- Phase 3A Failure (검증 대상: `3A-6`, 완료 체크: Acceptance Criteria 13): import/dependency rule test로 `runtime.gossip` 가 `runtime.consensus.hotstuff` 를 직접 import 하지 않고, `runtime.consensus.hotstuff` 가 transport/storage concrete package 를 직접 소유하지 않는지를 검증한다. 구현 메커니즘은 import-rule assertion, compile-time module boundary check, dependency snapshot assertion 중 하나로 고정한다.
- Phase 3A Success (검증 대상: `3A-6`, 완료 체크: Acceptance Criteria 14): docs regression check 로 `README.md`, ADR-0017, plan 0004 가 shipped HotStuff baseline 과 pending residual work 를 일관된 용어로 기술하는지 검증한다.

## Risks And Mitigations
- `ProposalId`, `BlockId`, `VoteId`를 느슨하게 다루면 replay와 QC assembly에서 버그가 생길 수 있다. 별도 타입과 dedicated test로 혼용을 막는다.
- non-threshold QC payload는 aggregate signature보다 크다. proposal justification self-contained baseline을 유지하되 bounded request-by-id와 cache policy로 보완한다.
- gossip substrate ownership과 consensus ownership이 다시 섞일 수 있다. package root 분리와 import/dependency 검증으로 경계를 강제한다.
- operator-managed raw key custody는 operational blast radius를 키운다. initial baseline에서는 허용하되 KMS/HSM/remote signer 대안은 follow-up 으로 남긴다.
- `100ms` target은 same-DC validator placement와 aggressive QoS를 요구한다. pacemaker artifact를 미루는 동안에는 이 값을 hard guarantee가 아니라 deployment target으로만 취급해 과도한 계약 고정을 피한다.
- pacemaker artifact를 미루면 full liveness contract가 비어 있을 수 있다. placeholder seam과 explicit follow-up 문서로 숨은 가정을 남기지 않는다.

## Acceptance Criteria
1. HotStuff non-threshold-signature runtime package가 추가되고 proposal/vote/QC identity, sign-bytes, validation contract가 compile/test 로 고정된다.
2. `consensus.proposal` / `consensus.vote` topic이 ADR-0016 gossip substrate 위에서 exact known-set, bounded `requestById`, QoS priority와 함께 동작한다.
3. QC validation이 validator vote set 기반으로 수행되고 aggregate signature 가정이 남지 않는다.
4. `ProposalId`, `BlockId`, `VoteId`, `(chainId, height, view, validatorSetHash)` window contract가 문서와 테스트에 일관되게 반영된다.
5. audit node가 read-only follow role로 동작하고 local validator-only emission을 하지 않는 contract가 문서와 테스트에 반영된다.
6. operator-managed key relocation에서 same validator private key의 dual-holder active 상태가 금지되고, old-holder fencing contract가 문서와 테스트에 반영된다.
7. pacemaker timeout/new-view의 follow-up dependency와 미구현 blocker가 문서에 명시되고 silent assumption 이 남지 않는다.
8. (`3A-1`) concrete JVM baseline 에서 사용할 HotStuff runtime bootstrap 이 static topology / gossip bootstrap 과 일관된 서비스 graph 로 제공된다.
9. (`3A-2`) runtime-owned service contract 와 concrete bootstrap surface 가 test-only in-memory assembly 와 분리돼, bootstrap input/output surface 가 문서와 코드에서 명시적으로 드러난다.
10. (`3A-3`) audit follower 는 local proposal/vote signing 없이도 validated artifact relay path 를 통해 proposal/vote 를 downstream peer 에 재전파할 수 있다.
11. (`3A-4`) same-window retry budget `2`회 baseline 이 proposal/vote `requestById` path 에 실제 enforcement 로 연결되고 테스트로 고정된다.
12. (`3A-5`) `consensus.vote` exact known-set / bounded `requestById` / wrong-window rejection coverage 가 proposal topic 과 대칭적으로 regression lock 된다.
13. (`3A-6`) gossip substrate 와 consensus runtime 사이의 dependency rule 이 compile/test asset 으로 enforce 되고, `runtime.gossip -X-> runtime.consensus.hotstuff`, `runtime.consensus.hotstuff -X-> transport/storage concrete package` ownership 침범이 방지된다.
14. (`3A-6`) README / ADR / plan 이 shipped HotStuff artifact baseline 과 residual implementation gap 을 일관되게 기술한다.

## Checklist

### Phase 0: Consensus Contract Lock
- [x] `BlockId` / `ProposalId` / `VoteId` 타입 분리 계약 확정
- [x] validator / audit local role contract 확정
- [x] validator key relocation / old-holder fencing / dual-holder prohibition 확정
- [x] proposal / vote sign-bytes semantic inputs 확정
- [x] validator-set window key / quorum rule / equivocation key 확정
- [x] self-contained justification baseline 확정
- [x] explicit audit-to-validator promotion baseline 확정
- [x] `100ms` deployment target scope 확정
- [x] `requestById` HotStuff policy 상한 확정

### Phase 1: Artifact Model And Validation
- [x] proposal / vote / QC value model 추가
- [x] local node role type 및 role-gated emission policy 추가
- [x] validator key holder state 및 fencing state model 추가
- [x] canonical deterministic encoding helper 추가
- [x] proposal signature validation 추가
- [x] vote signature validation 추가
- [x] QC validation 및 signer uniqueness 검증 추가
- [x] equivocation detection 추가

### Phase 2: Gossip Integration And QC Assembly
- [x] `consensus.proposal` / `consensus.vote` topic contract 구현 추가
- [x] proposal / vote exact known-set query wiring 추가
- [x] bounded `requestById` fetch integration 추가
- [x] vote accumulation / QC assembly runtime 추가
- [x] consensus QoS priority wiring 추가
- [x] audit node read-only follow path wiring 추가
- [x] key relocation after fencing path wiring 추가

### Phase 3: Verification And Docs
- [x] HotStuff unit / integration / regression test green
- [x] gossip-substrate dependency rule 검증
- [x] docs / README 갱신
- [x] pacemaker follow-up blocker 문서화

### Phase 3A: Residual Integration And Bootstrap Closure
- [x] `3A-1` HotStuff concrete JVM bootstrap wiring 추가
- [x] `3A-2` HotStuff runtime-owned service contract / bootstrap surface 분리
- [x] `3A-3` audit follower relay source/sink wiring 추가
- [x] `3A-4` same-window retry budget enforcement 추가
- [x] `3A-5` `consensus.vote` exact-known/request-by-id regression 확장
- [x] `3A-6` gossip/consensus dependency rule test 및 README / ADR / plan shipped-vs-pending 상태 정렬

## Follow-Ups
- `P1`: current static-validator-set baseline 위에서 `TimeoutVote` / `TimeoutCertificate` / `NewView` runtime model, validation, equivocation detection, bootstrap vote-hold interaction 을 이 plan 에서 구현한다.
- `P2`: pacemaker artifact dissemination 을 ADR-0016 gossip substrate 위에 연결한다. exact known-set / bounded `requestById` / replay / rejection / batching policy 는 이 plan 또는 별도 protocol spec 에서 고정한다.
- `P3`: pacemaker timer/backoff/jitter constant, diagnostics, test gate 는 implementation follow-up 으로 고정한다. timing-domain separation 자체는 ADR-0022 baseline 을 그대로 유지한다.
- `P4`: historical leader-order lookup 과 rotated validator-set continuity input 은 ADR-0023 및 plan `0007` 이 제공하는 seam 을 소비한다. current static-validator-set baseline 을 먼저 깨지 않고 단계적으로 확장한다.
- `P5`: static peer topology, same-DC validator placement, emergency promotion baseline은 ADR-0018이 소유하고, canonical block header/body contract 및 residual body sync follow-up 은 ADR-0019와 `docs/plans/0005-canonical-block-structure-migration-plan.md`가 소유한다.
- `P6`: operator-managed raw key custody를 대체할 KMS/HSM/remote signer baseline은 별도 ADR로 분리하고, aggregate signature 또는 threshold signature 기반 최적화가 필요해지면 별도 ADR에서 baseline을 supersede 한다.
