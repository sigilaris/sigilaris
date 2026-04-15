# 0013 - HotStuff Runtime Hardening And Gossip Bridge Cleanup Plan

## Status
Draft

## Created
2026-04-15

## Last Updated
2026-04-15

## Background
- `0011`은 `Wave 5`를 HotStuff runtime hardening tranche로 분리했다. 이 tranche는 split된 runtime/transport seam 위에서 consensus invariant를 type/property gate로 끌어올리는 역할을 가진다.
- current HotStuff surface에는 아래 문제가 동시에 남아 있다.
  - `Policy.scala`의 `require`/`throw` 기반 constructor와 arithmetic
  - `PacemakerRuntime.scala`의 numeric/duration invariant 분산
  - `ValidatorId`, `HotStuffHeight`, `HotStuffView`, `ProposalTxSet`의 stringly / overly-wide surface
  - `SnapshotSync.scala`의 typed failure 손실
  - `GossipIntegration.scala`의 large mixed-responsibility hotspot
  - property-based test 부재
- 이 영역은 consensus safety와 직접 연결되므로, split-only cleanup 이후 별도 plan으로 다루는 편이 낫다.

## Goal
- HotStuff runtime의 policy/value/identifier invariant를 type으로 끌어올린다.
- `SnapshotSync`와 `GossipIntegration`의 failure / bridge seam을 더 명확히 한다.
- consensus safety와 gossip bridge correctness를 property/model test gate로 고정한다.

## Scope
- `0011`의 `W5-B1` ~ `W5-B4`
- `Policy.scala`, `PacemakerRuntime.scala`, `Artifacts.scala`, `SnapshotSync.scala`, `GossipIntegration.scala`
- HotStuff runtime 관련 property/model test 추가

## Non-Goals
- 새로운 consensus variant 도입
- canonical wire/sign-bytes bytes 변경
- validator-set rotation continuity
- bootstrap trust-root semantics 변경
- transport config / transport auth hardening

## Related ADRs And Docs
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0019: Canonical Block Header And Application-Neutral Block View
- ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions
- ADR-0022: HotStuff Pacemaker And View-Change Baseline
- ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots
- ADR-0025: Shared Node Abstraction And Cross-Runtime Module Boundary
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`
- `docs/plans/0011-codebase-quality-improvement-opportunity-catalog-plan.md`

## Decisions To Lock Before Implementation
- current canonical wire/sign-bytes/validator-set semantics는 ADR-0017 / ADR-0019 / ADR-0022 baseline을 그대로 소비한다. 이 plan에서 바꾸지 않는다.
- `require`/`throw` 제거는 constructor / parser / typed policy surface 쪽으로 이동시키는 것이지, runtime-local implicit fallback을 늘리는 작업이 아니다.
- `GossipIntegration.scala` split은 consensus artifact bridge, rejection/validation translation, pacemaker emission seam을 분리하는 것이지 gossip substrate ownership을 다시 정의하는 작업이 아니다.
- property-based test는 optional이 아니라 exit gate다. 특히 `ValidatorSet`, `ProposalTxSet`, `PacemakerRuntime`은 example suite만으로 닫지 않는다.
- consensus semantics가 문서상 새 baseline을 요구하면 companion ADR/spec로 승격한다.

## Change Areas

### Code
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Policy.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/PacemakerRuntime.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Artifacts.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/SnapshotSync.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/GossipIntegration.scala`

### Tests
- `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
- 필요한 경우 shared generator/law harness consumer test

### Docs
- 본 plan 문서
- `docs/plans/0011-codebase-quality-improvement-opportunity-catalog-plan.md`
- 필요 시 ADR-0022 / companion protocol note

## Implementation Phases

### Phase 0: Invariant And Contract Lock
- 어떤 invariant가 local typed constructor로 닫히는지, 어떤 변경이 wire/spec escalation을 부르는지 분리한다.
- `Policy.scala`, `Artifacts.scala`, `PacemakerRuntime.scala`, `SnapshotSync.scala`, `GossipIntegration.scala`에서 constructor / parser / bridge / failure ownership을 분류한다.
- property/model test exit gate를 batch별로 고정한다.

### Phase 1: Policy And Value Totalization
- `HotStuffRequestPolicy`, `HotStuffDeploymentTarget`, `HotStuffPacemakerPolicy`의 partial constructor를 typed parse result로 이동시킨다.
- `HotStuffHeight.+`, `HotStuffView.+`, `HotStuffWindow.apply`, `EquivocationKey.apply`의 raw numeric partiality를 줄인다.
- numeric/duration invariant가 runtime `require`/`throw` 밖으로 이동했는지 확인한다.

### Phase 2: Identifier / Failure / Bridge Cleanup
- `ValidatorId`, `HotStuffHeight`, `HotStuffView`, `ProposalTxId` 같은 identifier/value family를 더 약한 power의 abstraction으로 정리한다.
- `SnapshotSync.scala`에서 typed storage failure channel이 유지되게 정리한다.
- `GossipIntegration.scala`를 artifact bridge, rejection/validation translation, pacemaker/runtime emission seam으로 분리한다.

### Phase 3: Consensus Property And Model Verification
- `ValidatorSet` law, `ProposalTxSet` canonicalization law를 property test로 넣는다.
- `PacemakerRuntime` timeout/backoff/held-state/duplicate-stale artifact 처리 성질을 generative model test로 넣는다.
- regression suite와 property suite를 함께 green으로 만든다.

## Test Plan
- Phase 0 Success: local typed constructor로 닫을 invariant와 ADR/spec escalation이 필요한 invariant가 분리된다.
- Phase 1 Success: policy/value constructor partiality가 줄고 existing regression suite가 green이다.
- Phase 1 Failure: wire/sign-bytes/canonical bytes semantics가 의도치 않게 바뀌지 않는지 검증한다.
- Phase 2 Success: `SnapshotSync` typed failure 손실이 줄고 `GossipIntegration` seam split 뒤 semantic regression이 없다.
- Phase 2 Failure: gossip artifact apply/validation/rejection translation 의미가 drift하지 않는지 검증한다.
- Phase 3 Success: `ValidatorSet`, `ProposalTxSet`, `PacemakerRuntime` property/model test가 green이다.

## Risks And Mitigations
- local typed tightening이 wire/spec change와 섞일 수 있다. escalation trigger를 먼저 lock하고 문서 밖 semantic change를 막는다.
- `GossipIntegration.scala`는 HotStuff와 gossip substrate를 동시에 건드리므로 split이 과도해질 수 있다. bridge/translation/emission seam만 먼저 분리한다.
- `SnapshotSync.scala`는 bootstrap plan과도 연결된다. typed failure 유지에 집중하고 bootstrap semantics 자체는 바꾸지 않는다.
- property/model test generator가 부족할 수 있다. `0011`의 shared harness direction을 소비해 reusable generator를 먼저 만든다.

## Acceptance Criteria
1. HotStuff policy/value surface의 `require`/`throw` 기반 partiality가 typed constructor 쪽으로 이동한다.
2. `ValidatorId` / `HotStuffHeight` / `HotStuffView` / `ProposalTxId` / `SnapshotSync` / `GossipIntegration`가 더 좁고 명확한 seam 위에 정리된다.
3. `ValidatorSet`, `ProposalTxSet`, `PacemakerRuntime` 관련 property/model test가 added-and-green 상태가 된다.

## Checklist

### Phase 0: Invariant And Contract Lock
- [ ] local typed tightening vs ADR/spec escalation 경계를 문서화한다.
- [ ] batch별 property/model exit gate를 고정한다.

### Phase 1: Policy And Value Totalization
- [ ] `Policy.scala` constructor/arithmetic totalization landed
- [ ] `PacemakerRuntime.scala` policy invariant tightening landed
- [ ] impacted regression suite green

### Phase 2: Identifier / Failure / Bridge Cleanup
- [ ] identifier/value family tightening landed
- [ ] `SnapshotSync.scala` typed failure/common algebra cleanup landed
- [ ] `GossipIntegration.scala` bridge split landed
- [ ] hotstuff/gossip integration regression suite green

### Phase 3: Consensus Property And Model Verification
- [ ] `ValidatorSet` / `ProposalTxSet` property tests green
- [ ] `PacemakerRuntime` generative model test green
- [ ] plan/doc handoff updated

## Follow-Ups
- consensus wire/sign-bytes/canonical bytes 변경이 필요할 경우 companion ADR/spec
- validator-set rotation continuity / historical lookup는 ADR-0023 follow-up
- transport/config hardening tranche는 별도 plan
