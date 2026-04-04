# 0007 - Snapshot Sync And Background Backfill Plan

## Status
Draft

## Created
2026-04-05

## Last Updated
2026-04-05

## Background
- 이 문서는 ADR-0021의 implementation plan 이다.
- `2026-04-05` 기준 shipped HotStuff baseline 은 ADR-0017 / ADR-0019 아래에서 proposal/vote/QC artifact contract, canonical `BlockHeader`, header-first proposal path, canonical tx hash set carriage, bounded `requestById`, exact known-set sync 를 이미 갖고 있다. 이 중 `bounded requestById` 와 exact known-set sync baseline 은 ADR-0017 / plan 0004 가, canonical `BlockHeader` 와 `stateRoot` anchor baseline 은 ADR-0019 / plan 0005 가 소유한다.
- 따라서 신규 노드 bootstrap 의 남은 핵심 문제는 "모르는 proposal 을 어떻게 받느냐" 자체보다, "어느 finalized anchor 를 믿고 state 를 복원할 것인가", "그 anchor state 를 어떻게 실제 local trie 로 재구성할 것인가", "그 뒤 frontier 와 historical history 를 어떤 우선순위로 따라잡을 것인가" 로 이동했다.
- 현재 runtime 에는 아래 gap 이 남아 있다.
  - `best finalized block suggestion` bootstrap API 가 없다.
  - local HotStuff sink/source 는 proposal/vote/QC 를 저장하지만, best finalized anchor 와 그에 대응하는 self-contained `FinalizedAnchorSuggestion` 을 materialize 하는 explicit finalization tracker 가 없다.
  - `StorageLayout.state.snapshot` / `state.nodes` path 는 예약돼 있지만, snapshot metadata / trie-node persistence service 는 없다.
  - `BlockStore` 는 in-memory baseline 만 제공하고, newcomer bootstrap 을 위한 state snapshot / historical proposal replay / background backfill runtime 은 없다.
  - current proposal/vote fetch 는 same-window anti-entropy baseline 이라 bootstrap 시작점의 self-contained finalized anchor 검증을 직접 대체하지 않는다.
- ADR-0021 은 bootstrap 을 `anchor discovery -> snapshot sync -> forward catch-up -> historical backfill` 네 단계로 고정했고, `FinalizedAnchorSuggestion = anchor proposal P0 + FinalizedProof(P1, P2)` semantic minimum, anchor pinning, self-contained bootstrap verification, background historical backfill baseline 을 이미 잠갔다.
- current HotStuff bootstrap config 는 `HotStuffBootstrapConfig.validatorSet` 을 통해 static validator-set baseline 을 입력으로 요구하고, proposal validation 도 현재 validator set 기준 justify QC 검증을 사용한다.
- 따라서 initial rollout 은 current static-validator-set baseline 위에서 bootstrap trust root 를 먼저 구현하고, future validator-set rotation / historical lookup seam 은 explicit abstraction 으로 남겨야 한다.

## Goal
- runtime-owned `best finalized block suggestion` service 를 추가해 신규 노드가 self-contained finalized anchor 를 발견하고 검증할 수 있게 한다.
- 선택된 anchor 의 `stateRoot` 아래 trie 를 node-by-hash fetch 로 복원하고 local persistence / completion verification 까지 수행하는 snapshot coordinator 를 도입한다.
- snapshot 완료 뒤 기존 proposal/vote/tx artifact plane 을 재사용해 forward catch-up 을 수행하고, proposal별 vote readiness 를 data sufficiency 기준으로 제어한다.
- anchor 이전 history 는 background historical backfill 로 계속 채우되, initial validator readiness 와 분리한다.
- 위 bootstrap/sync 기능을 현재 HotStuff / gossip / storage seam 안에 넣고 테스트와 문서로 회귀를 잠근다.

## Scope
- local finalized-anchor derivation 과 `FinalizedAnchorSuggestion` materialization runtime 추가
- bootstrap trust root input, finalized proof verification, anchor selection logic 추가
- snapshot metadata store, trie node store, node-by-hash fetch service, snapshot completion verification 추가
- bootstrap coordinator state machine, discovery retry/backoff, anchor pinning, readiness diagnostics 추가
- snapshot 완료 이후 proposal/vote/tx catch-up integration 과 proposal replay/backfill seam 추가
- background historical backfill baseline 과 observability 추가
- 관련 unit / integration / loopback / docs 갱신

## Non-Goals
- HotStuff finality rule 자체를 새로 설계하거나 chained HotStuff baseline 을 바꾸지 않는다.
- finalized-only gossip topic 을 새로 도입하지 않는다.
- dynamic peer discovery, peer scoring, validator admission policy 는 이번 plan 의 범위에 넣지 않는다.
- online validator-set reconfiguration protocol 의 최종 형식은 이번 plan 에서 완전히 구현하지 않는다. initial rollout 은 current static-validator-set baseline 을 재사용한다.
- light-client proof, zk proof, Merkle proof compression, archive-grade accelerated historical sync 는 이번 plan 의 범위에 넣지 않는다.
- pacemaker timeout/new-view wire contract, leader rotation policy 는 이번 plan 의 범위에 넣지 않는다.
- post-anchor full block body transfer 를 baseline mandatory contract 로 만들지 않는다. existing proposal tx-set + tx anti-entropy + local replay seam 을 우선 사용한다.

## Related ADRs And Docs
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- ADR-0019: Canonical Block Header And Application-Neutral Block View
- ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions
- `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0005-canonical-block-structure-migration-plan.md`
- `docs/plans/plan-template.md`
- `README.md`

## Decisions To Lock Before Implementation
- initial rollout 의 bootstrap trust root 는 current shipped static-validator-set baseline 위에서 시작한다. 즉 `HotStuffBootstrapConfig.validatorSet` 과 동등한 bootstrap material 이 mandatory input 이고, operator-supplied checkpoint 나 weak-subjectivity anchor 는 additive follow-up 으로 남길 수 있지만 validator-set authority 자체를 peer response 에서 배우지는 않는다.
- `FinalizedAnchorSuggestion` 의 semantic minimum 은 `proposal: P0` 와 `FinalizedProof(child: P1, grandchild: P2)` 로 고정한다. `P1`, `P2` 는 justify chain 상에서 각각 `P0`, `P1` 을 justify 하는 descendant proposal 이며, block id 둘 또는 proposal id 둘만으로는 baseline proof 로 충분하지 않다.
- local finalized anchor derivation 은 proposal reception order 나 highest observed height 가 아니라 justify chain 기준 3-chain finality 를 canonical rule 로 사용한다.
- `best finalized block suggestion` 은 runtime-owned bootstrap service 이고, bootstrap 시작 시점의 finalized anchor 검증은 self-contained suggestion response 만으로 시작할 수 있어야 한다.
- snapshot sync 의 verification unit 은 Merkle trie node hash 다. batching, transport framing, peer mixing 은 허용되지만 admissibility 는 언제나 content-addressed hash verification 으로 판정한다.
- snapshot trie node transport 는 existing proposal/vote `requestById` control op 를 재사용하지 않고, ADR-0021 이 요구한 session-bound bootstrap service family 로 분리한다. 다만 ownership, auth binding, rejection family 는 existing gossip substrate seam 을 재사용한다.
- snapshot metadata 와 trie node persistence baseline 은 `StorageLayout.state.snapshot` / `StorageLayout.state.nodes` 를 사용한다. `BlockStore` / `BlockQuery` 는 block header/body query seam 으로 유지하고 snapshot state store 를 implicit 하게 대체하지 않는다.
- snapshot completion gate 는 선택된 anchor `stateRoot` 에서 도달 가능한 node graph 가 local store 에 완전히 닫히고 root commitment re-verification 이 성공한 상태다.
- forward catch-up 은 snapshot 완료 후 기존 proposal/vote/tx artifact plane 을 재사용한다. finalized-only proposal API 나 finalized-only gossip topic 은 추가하지 않는다.
- gap window catch-up 은 anchor-forward-first contiguous apply 를 canonical merge rule 로 사용한다. fetch 는 batch/parallel 이어도 되지만, replay/apply/readiness advancement 는 `anchor.height + 1` 부터 current frontier 까지의 contiguous prefix 순서로만 전진한다. live arrival 이 replay frontier 앞을 뛰어넘으면 buffer/queue 하고, contiguous gap 이 닫힌 뒤 merge 한다.
- bootstrap node 는 relay / fetch / replay 는 계속 수행할 수 있지만, proposal별 readiness 를 만족하기 전까지는 vote 를 emit 하지 않는다.
- historical backfill 은 background / low-priority objective 이고 validator readiness 의 prerequisite 가 아니다.
- current static-validator-set baseline 을 구현 출발점으로 사용하되, validator-set lookup abstraction 은 Phase 0 에서 surface 를 먼저 고정해 future rotation support 를 막지 않도록 한다.

## Change Areas

### Code
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip`
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/block`
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage/swaydb`
- 필요 시 `modules/core/shared/src/main/scala/org/sigilaris/core/merkle`
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip`

### Tests
- HotStuff finalization tracker / finalized suggestion verification unit test
- bootstrap trust root / conflicting suggestion / retry-backoff test
- snapshot trie node integrity / completion / withholding behavior test
- bootstrap coordinator / readiness / anchor pinning integration test
- proposal replay / tx hole-filling / vote-hold integration test
- historical backfill background behavior / observability test

### Docs
- `docs/adr/0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md`
- `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`
- 필요 시 `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- 필요 시 `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- 필요 시 `README.md`

## Implementation Phases

### Phase 0: Contract Lock And Bootstrap Baseline
- Phase 0 은 코드 없는 문서 단계가 아니다. 이 phase 의 deliverable 은 coordinator 구현 전제인 runtime-owned trait/type skeleton 과 문서 lock 이다.
- current static-validator-set bootstrap baseline 과 future validator-set lookup seam 의 경계를 명시한다.
- `FinalizedProof`, `FinalizedAnchorSuggestion`, bootstrap trust root, snapshot metadata, bootstrap diagnostics 의 runtime-owned value model 을 고정한다.
- 최소 코드 산출물로 `BootstrapTrustRoot`, `ValidatorSetLookup`, `SnapshotMetadata`, `BootstrapDiagnostics`, `FinalizedAnchorSuggestionService`, `SnapshotNodeFetchService`, `ProposalReplayService`, `HistoricalBackfillService` 또는 동등한 trait/type skeleton 을 추가한다.
- `best finalized block suggestion`, snapshot trie node fetch, forward proposal replay/backfill, historical backfill 의 service ownership 과 session binding 을 고정한다.
- snapshot persistence ownership 을 `StorageLayout.state.snapshot` / `state.nodes` 로 잠근다.
- Phase 1 finalization tracker 와 Phase 2 snapshot store/runtime 은 이 phase 의 value model / service trait 가 landed 되면 병렬 진행할 수 있고, 둘의 실제 조립은 Phase 3 이 소유한다.

### Phase 1: Local Finalization Tracker And Anchor Suggestion
- proposal / QC history 위에서 justify-chain finalization 을 추적하는 tracker 를 추가한다.
- local node 가 현재 best finalized anchor 를 `FinalizedAnchorSuggestion` 으로 materialize 하는 service surface 를 추가한다.
- trust root 기반 anchor verification helper 를 추가하고, conflicting valid finalized suggestion 을 explicit safety fault 로 surface 한다.
- in-memory HotStuff diagnostics 와 source/sink snapshot 에 finalized anchor visibility 를 추가한다.

### Phase 2: Snapshot Store And Trie Fetch Runtime
- snapshot metadata store 와 trie-node store abstraction 을 추가한다.
- in-memory baseline + `StorageLayout` backing seam 을 제공해 snapshot progress 와 trie nodes 를 저장할 수 있게 한다.
- root node fetch / node-by-hash fetch / batch fetch helper 와 verification path 를 추가한다.
- snapshot node fetch 는 existing proposal/vote `requestById` 가 아니라 별도 `SnapshotNodeFetchService` 를 통해 수행하고, requester 는 authenticated live neighbor 집합 중에서 peer switching / multi-peer mixing 을 허용한다.
- snapshot coordinator 를 도입해 selected anchor 의 `stateRoot` 에서 closure 가 닫힐 때까지 missing nodes 를 반복 fetch / persist / verify 한다.

### Phase 3: Bootstrap Coordinator And Forward Catch-Up
- `best finalized block suggestion` fan-out, retry/backoff, candidate verification, anchor selection, anchor pinning 을 소유하는 bootstrap coordinator 를 추가한다.
- snapshot completion 후 proposal/vote/tx artifact plane 에 진입하는 forward catch-up path 를 연결한다.
- proposal replay/backfill seam 을 추가해 anchor 와 current frontier 사이의 gap window 를 메우고, normal proposal stream 과 merge 한다.
- gap window 처리는 anchor-forward-first contiguous replay 를 baseline 으로 둔다. fetch 는 batch/parallel 이어도 되지만, readiness 와 vote eligibility 는 contiguous prefix 가 닫힐 때만 전진하고, live stream 이 더 앞선 proposal 을 보내면 queue 후 replay frontier 와 merge 한다.
- `ProposalTxSync` 와 proposal body/view validation seam 을 bootstrap coordinator 가 재사용할 수 있도록 연결한다.
- readiness diagnostics 와 vote-hold gating 을 concrete runtime 에 연결한다.

### Phase 4: Background Historical Backfill
- anchor 이전 history 를 genesis 방향으로 채우는 background worker 를 추가한다.
- live catch-up / readiness 와 historical backfill 간 우선순위 분리를 concrete scheduler / policy 로 반영한다.
- pause/resume, progress diagnostics, terminal stop condition(genesis reached) 을 추가한다.

### Phase 5: Verification And Docs
- unit / integration / loopback / transport-adapter test 를 green 상태로 맞춘다.
- ADR-0021, plan 0003, plan 0004, plan 0007, README 용어를 정렬한다.
- current static-validator-set bootstrap baseline 과 future rotation/backfill optimization residual gap 을 문서에 남긴다.

## Test Plan
- Phase 1 Success: unit test 로 local tracker 가 `P0 <-justify- P1 <-justify- P2` 구조에서 `P0` finalized anchor 를 materialize 하는지 검증한다.
- Phase 1 Success: unit test 로 `FinalizedAnchorSuggestion` 검증이 anchor proposal `P0` 와 `FinalizedProof(P1, P2)` 만으로 가능하고, 별도 proposal/vote fetch 가 없어도 시작 가능한지를 검증한다.
- Phase 1 Failure: unit test 로 wrong justify chain, wrong QC, trust root mismatch, conflicting same-height finalized suggestion 을 reject 또는 safety fault 로 surface 하는지 검증한다.
- Phase 2 Success: unit/integration test 로 snapshot coordinator 가 root node 와 child nodes 를 hash 기준으로 검증하며 session-bound `SnapshotNodeFetchService` 를 통해 multi-peer fetch 결과를 섞어도 completion 이 가능한지 검증한다.
- Phase 2 Failure: unit/integration test 로 wrong node hash, missing subtree, incomplete closure 를 snapshot complete 로 잘못 승격하지 않는지 검증한다.
- Phase 3 Success: integration test 로 bootstrap coordinator 가 live peer fan-out 후 highest verifiable finalized anchor 를 선택하고, newer tip arrival 중에도 current anchor 를 pin 하는지 검증한다.
- Phase 3 Success: integration test 로 snapshot complete 뒤 proposal replay + `tx` anti-entropy + local replay 를 통해 forward catch-up 이 진행되고, gap window replay 가 anchor-forward contiguous order 를 유지하며, missing tx 가 있는 proposal 은 vote 가 보류되다가 readiness 충족 후만 vote 가능한지 검증한다.
- Phase 3 Failure: integration test 로 candidate 가 없거나 모두 verification fail 이면 bootstrap discovery 상태와 diagnostics 가 유지되고 bounded backoff retry 가 시작되는지 검증한다.
- Phase 4 Success: integration test 로 historical backfill 이 background 에서 진행되면서 live proposal catch-up / vote readiness 를 막지 않는지 검증한다.
- Phase 4 Failure: integration test 로 historical backfill failure 가 initial readiness 를 silent-fail 시키지 않고 background error/diagnostic 으로 분리되는지 검증한다.
- Phase 5 Regression: existing HotStuff proposal/vote/QC validation, request-by-id, proposal tx-set sync, block-view validation, gossip substrate regression suite 가 bootstrap/sync 추가 후에도 green 상태를 유지해야 한다.

## Risks And Mitigations
- current static-validator-set assumption 이 bootstrap 구현 깊숙이 박히면 future validator-set rotation support 가 어려워질 수 있다. bootstrap trust root 와 validator-set lookup 을 explicit abstraction 으로 먼저 분리한다.
- finalization tracker 가 justify chain 과 block-tree parent chain 을 혼동하면 wrong anchor suggestion 을 만들 수 있다. `FinalizedProof` 검증과 finalization derivation 을 justify chain 중심으로 테스트로 잠근다.
- snapshot trie fetch 는 integrity 는 강하지만 withholding 으로 liveness 가 막힐 수 있다. retry, peer switching, timeout, diagnostics 를 baseline 정책으로 둔다.
- background historical backfill 이 live catch-up 자원을 잡아먹을 수 있다. low-priority scheduling 과 explicit budget separation 으로 완화한다.
- bootstrap start 시점에 self-contained proof 가 없으면 verification 이 normal artifact plane 가용성에 의존하게 된다. suggestion response 의 self-contained baseline 과 테스트로 막는다.
- forward catch-up 이 current proposal stream 과 historical replay 를 중복 적용하거나 순서를 틀리면 readiness bug 가 생길 수 있다. explicit coordinator state machine 과 replay/stream merge regression test 로 완화한다.

## Acceptance Criteria
1. local HotStuff runtime 이 current artifact history 에서 `best finalized block suggestion` 을 materialize 할 수 있고, suggestion 은 self-contained `P0 + FinalizedProof(P1, P2)` 로 검증 가능하다.
2. 신규 노드는 live peer fan-out 결과 중 highest verifiable finalized anchor 를 선택하고, conflicting same-height finalized suggestion 은 safety fault 로 surface 한다.
3. snapshot coordinator 가 selected anchor `stateRoot` 아래 trie nodes 를 fetch / persist / verify 하여 completion gate 를 판정할 수 있다.
4. snapshot 완료 후 node 는 existing proposal/vote/tx artifact plane 으로 forward catch-up 을 수행하고, gap window 는 anchor-forward contiguous replay rule 로 merge 되며, proposal별 readiness 전까지 vote 를 보류할 수 있다.
5. historical backfill 은 background low-priority worker 로 동작하고, initial validator readiness 의 prerequisite 로 요구되지 않는다.
6. bootstrap trust root, retry/backoff, no-candidate diagnostics, snapshot withholding handling, vote-hold diagnostics 가 코드와 테스트에 반영된다.
7. `StorageLayout.state.snapshot` / `state.nodes` 를 사용하는 snapshot persistence seam 이 존재하고, snapshot trie node / snapshot metadata 는 `BlockQuery` / `BlockStore` interface 로 조회하거나 저장할 수 없다는 negative invariant 가 코드와 테스트에 반영된다.
8. ADR-0021 와 plan 0007, 필요 시 plan 0003 / 0004 / README 가 shipped bootstrap baseline 과 residual follow-up 을 일관된 용어로 설명한다.

## Checklist

### Phase 0: Contract Lock And Bootstrap Baseline
- [x] static-validator-set bootstrap trust root baseline 과 doc lock 확정
- [x] `ValidatorSetLookup` trait skeleton 추가
- [x] `BootstrapTrustRoot` / `FinalizedProof` / `FinalizedAnchorSuggestion` runtime model 추가
- [x] `SnapshotMetadata` / `BootstrapDiagnostics` runtime model 추가
- [x] bootstrap service trait skeleton 과 session binding contract 추가
- [x] readiness / vote-hold / background backfill baseline 과 Phase 1-2 병렬 경계 문서화

### Phase 1: Local Finalization Tracker And Anchor Suggestion
- [x] justify-chain finalization tracker 추가
- [x] best finalized anchor query/service 추가
- [x] trust-root-based finalized suggestion verifier 추가
- [x] conflicting finalized suggestion safety-fault path 추가
- [x] HotStuff in-memory diagnostics / snapshots 에 finalized anchor visibility 추가

### Phase 2: Snapshot Store And Trie Fetch Runtime
- [x] snapshot metadata store abstraction 추가
- [x] trie node store abstraction 추가
- [x] in-memory snapshot/node store baseline 추가
- [x] `StorageLayout.state.snapshot` / `state.nodes` backing seam 추가
- [x] `SnapshotNodeFetchService` 추가
- [x] node-by-hash fetch / verification helper 추가
- [x] snapshot coordinator completion gate 추가

### Phase 3: Bootstrap Coordinator And Forward Catch-Up
- [x] best finalized suggestion fan-out / retry / anchor selection coordinator 추가
- [x] anchor pinning runtime enforcement 추가
- [x] proposal replay/backfill seam 추가
- [x] anchor-forward contiguous replay / live-queue merge rule 추가
- [x] snapshot-complete -> forward-catch-up transition 추가
- [x] `ProposalTxSync` + body/view validation + vote-hold gating 연결
- [x] bootstrap diagnostics 추가

### Phase 4: Background Historical Backfill
- [ ] genesis 방향 historical backfill worker 추가
- [ ] live catch-up 과 background backfill 우선순위 분리 추가
- [ ] pause/resume / progress / terminal condition diagnostics 추가

### Phase 5: Verification And Docs
- [ ] unit / integration / regression test green
- [ ] ADR / plan / README 용어 정렬
- [ ] static bootstrap baseline 과 future rotation residual gap 문서화

## Follow-Ups
- `P1`: validator-set rotation 이 shipped baseline 으로 도입되면 bootstrap trust root 와 historical validator-set lookup contract 를 별도 ADR 또는 superseding plan 으로 확장한다.
- `P2`: archive-grade accelerated historical backfill, snapshot compression, Merkle proof serving 은 별도 plan 또는 ADR 로 분리한다.
- `P3`: full durable proposal/vote archive retention policy 와 compaction policy 는 별도 storage follow-up 으로 분리한다.
- `P4`: production-grade peer scoring / bandwidth shaping / backpressure policy 는 gossip/deployment follow-up 으로 분리한다.
