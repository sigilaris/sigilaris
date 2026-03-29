# 0004 - HotStuff Consensus Without Threshold Signatures Plan

## Status
Draft

## Created
2026-03-29

## Last Updated
2026-03-29

## Background
- 이 문서는 ADR-0017의 implementation plan 이다.
- ADR-0016과 plan 0003은 transport-neutral gossip/session substrate를 소유하고, 이 문서는 그 위에서 동작하는 HotStuff consensus runtime과 artifact contract integration을 다룬다.
- 선택한 baseline은 BLS threshold signature를 사용하지 않는 HotStuff 계열이다. 따라서 proposal, vote, QC는 individually signed vote set 기반 validation을 전제로 해야 한다.
- 이전 문서 구조에서는 gossip substrate와 consensus semantics가 섞여 있었지만, 이제는 proposal/vote/QC identity, sign-bytes, QC assembly, validator-set window 검증을 consensus plan 아래에서 독립적으로 관리한다.
- initial deployment baseline은 ADR-0018의 static peer topology, validator/audit node role 분리, same-DC validator placement, `100ms` block production target을 전제로 한다.

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
- pacemaker timeout vote, timeout certificate, new-view wire contract의 최종 형식은 이번 plan에서 완전히 고정하지 않는다. 필요한 seam과 placeholder는 둘 수 있지만 상세 wire contract는 follow-up ADR이 소유한다.
- audit node의 automatic runtime promotion, automatic cross-DC failover, online validator-set reconfiguration protocol은 이번 plan에 넣지 않는다.
- state execution engine, application reducer semantics, block contents 자체의 application-specific meaning은 이 plan의 직접 범위가 아니다.

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0012: Signed Transaction Requirement
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
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
- bounded explicit fetch는 ADR-0016의 `requestById`를 사용한다. HotStuff path에서 허용하는 최대 request id 수와 retry 정책은 Phase 0에서 잠근다.
- emergency promotion from `audit` to `validator`는 explicit operator-managed config change와 validator-set reconfiguration을 전제로 하며, automatic runtime failover contract로 shipped 하지 않는다.
- 위 promotion baseline에서 validator key relocation을 허용한다. operator는 promoted node에 validator identity별 private key를 주입할 수 있지만, old holder는 먼저 fence 되거나 key access를 잃어야 한다.
- 동일 validator private key가 old holder와 new holder에서 동시에 active 상태가 되는 dual-holder baseline은 금지한다.
- initial deployment target block production interval은 `100ms`다. 이는 batching/QoS/pacing budget을 제약하지만, exact pacemaker wire contract를 대신하지 않는다.
- quorum rule은 active validator set의 `2f + 1` vote 로 고정한다.
- equivocation detection key는 baseline으로 `(chainId, validatorId, height, view)` 기준으로 모델링한다. 같은 key에서 서로 다른 target `ProposalId`가 관찰되면 equivocation으로 판정한다.
- pacemaker timeout/new-view는 placeholder seam만 둘 수 있고, production wire contract는 follow-up ADR 없이는 shipped contract 로 고정하지 않는다.
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

## Checklist

### Phase 0: Consensus Contract Lock
- [ ] `BlockId` / `ProposalId` / `VoteId` 타입 분리 계약 확정
- [ ] validator / audit local role contract 확정
- [ ] validator key relocation / old-holder fencing / dual-holder prohibition 확정
- [ ] proposal / vote sign-bytes semantic inputs 확정
- [ ] validator-set window key / quorum rule / equivocation key 확정
- [ ] self-contained justification baseline 확정
- [ ] explicit audit-to-validator promotion baseline 확정
- [ ] `100ms` deployment target scope 확정
- [ ] `requestById` HotStuff policy 상한 확정

### Phase 1: Artifact Model And Validation
- [ ] proposal / vote / QC value model 추가
- [ ] local node role type 및 role-gated emission policy 추가
- [ ] validator key holder state 및 fencing state model 추가
- [ ] canonical deterministic encoding helper 추가
- [ ] proposal signature validation 추가
- [ ] vote signature validation 추가
- [ ] QC validation 및 signer uniqueness 검증 추가
- [ ] equivocation detection 추가

### Phase 2: Gossip Integration And QC Assembly
- [ ] `consensus.proposal` / `consensus.vote` topic contract 구현 추가
- [ ] proposal / vote exact known-set query wiring 추가
- [ ] bounded `requestById` fetch integration 추가
- [ ] vote accumulation / QC assembly runtime 추가
- [ ] consensus QoS priority wiring 추가
- [ ] audit node read-only follow path wiring 추가
- [ ] key relocation after fencing path wiring 추가

### Phase 3: Verification And Docs
- [ ] HotStuff unit / integration / regression test green
- [ ] gossip-substrate dependency rule 검증
- [ ] docs / README 갱신
- [ ] pacemaker follow-up blocker 문서화

## Follow-Ups
- static peer topology, same-DC validator placement, emergency promotion baseline은 ADR-0018이 소유한다.
- operator-managed raw key custody를 대체할 KMS/HSM/remote signer baseline은 별도 ADR로 분리한다.
- timeout vote, timeout certificate, new-view wire contract, leader rotation policy는 별도 ADR 또는 follow-up plan으로 분리한다.
- validator-set commitment derivation의 exact byte contract는 follow-up spec으로 고정한다.
- aggregate signature 또는 threshold signature 기반 최적화가 필요해지면 별도 ADR에서 baseline을 supersede 한다.
