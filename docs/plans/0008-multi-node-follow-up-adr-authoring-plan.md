# 0008 - Multi-Node Follow-Up ADR Authoring Plan

## Status
Phase 1 Draft Complete; Phase 2 Pending

## Created
2026-04-06

## Last Updated
2026-04-06

## Background
- `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`부터 `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`까지의 shipped baseline으로, Sigilaris 는 static topology 위의 multi-node gossip/session sync, HotStuff artifact/QC, canonical `BlockHeader`, conflict-free block scheduling, snapshot sync + background backfill 기반을 이미 갖췄다.
- 반면 현재 남아 있는 큰 공백은 구현 디테일보다는 장기 계약에 가깝다. 대표적으로 pacemaker/timeouts/new-view, validator-set rotation 과 bootstrap trust root, static-topology peer identity binding, session-bound bootstrap capability revoke 가 그렇다. validator signer custody / remote signer baseline 은 보안 hardening 가치가 있지만, 현재 shipped baseline 을 막는 immediate blocker 라기보다 deferred follow-up 후보에 가깝다.
- 위 항목들은 각각 `ADR-0016`, `ADR-0017`, `ADR-0018`, `ADR-0021` 의 follow-up 으로 이미 암시돼 있지만, 아직 canonical decision 문서가 없거나 existing ADR 의 consequence/follow-up section 에만 남아 있다.
- 특히 `ADR-0018` 은 current baseline 의 사실상 참조점처럼 사용되고 있었지만 문서 status 는 `Proposed` 로 남아 있었다. Phase 0 는 이 mismatch 를 먼저 해소해, 후속 ADR 이 "이미 accepted 된 운영 baseline 위의 확장"인지 "아직 미확정 운영 가정 위의 초안"인지가 흐려지지 않게 만든다.
- 이 상태에서 바로 implementation plan 을 추가로 쌓으면, runtime/transport/storage 경계보다 더 상위의 trust/liveness/authority contract 가 plan 안에서 임시로 고정될 위험이 있다.
- 따라서 다음 단계의 핵심은 "남은 기능을 바로 전부 구현하는 것"이 아니라, multi-node 운영을 위해 장기 유지해야 할 정책을 ADR 로 먼저 잠그는 것이다.
- 이 문서는 그 ADR 작성 순서와 범위를 관리하는 실행 문서다. 문서 작성이 진행되면서 candidate ADR 의 경계가 합쳐지거나 분리될 수 있고, 이 plan 역시 그에 맞춰 계속 업데이트되는 것을 전제로 한다.

## Goal
- multi-node 후속 작업 중 ADR 로 먼저 고정해야 하는 장기 계약을 식별하고 우선순위를 정한다.
- 첫 번째 ADR tranche 를 작성해 pacemaker, validator-set continuity/bootstrap trust root, static-topology peer identity binding/session-bound capability authorization baseline 을 문서화한다.
- 새 ADR 이 landed 되면 기존 ADR/plan/README 의 follow-up placeholder 를 구체적 참조로 치환해, 이후 implementation plan 이 다시 architecture decision 을 떠안지 않도록 만든다.

## Scope
- `0003`~`0007` 이후 residual gap 을 ADR 후보로 inventory 하고, 문서 간 dependency order 를 고정한다.
- 아래 ADR 후보의 초안 작성과 반복 갱신을 이 plan 의 직접 범위로 둔다.
  - `ADR-0022`: HotStuff Pacemaker And View-Change Baseline
  - tentative `ADR-0023`: Validator-Set Rotation And Bootstrap Trust Roots
  - tentative `ADR-0024`: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization
- deferred candidate `ADR-0025`: Validator Signing Custody And Remote Signer Baseline
- 위 ADR 작성 결과를 반영해 `ADR-0016`, `ADR-0017`, `ADR-0018`, `ADR-0021`, 관련 plan 문서의 follow-up / consequence / residual gap 서술을 정렬한다.
- ADR 수용 이후 어떤 구현 작업이 기존 plan 의 phase 확장으로 들어갈지, 별도 plan 으로 분리할지를 backlog 수준으로 정리한다.

## Non-Goals
- pacemaker, validator-set rotation, static-topology peer identity binding 의 runtime implementation 을 이 plan 안에서 완료 조건으로 두지 않는다.
- dynamic peer discovery, peer scoring, topology management, fee market, proposer fairness 를 이번 ADR tranche 의 mandatory output 으로 두지 않는다.
- archive-grade accelerated backfill, snapshot compression, proof serving, durable archive compaction 을 이번 ADR tranche 에서 최종 고정하지 않는다.
- validator signing custody / remote signer baseline 은 이번 initial ADR tranche 의 mandatory output 으로 두지 않는다. 필요성이 다시 커지면 별도 security-hardening ADR 로 분리한다.
- 모든 exact byte layout, protobuf/json schema, HTTP path shape 같은 low-level wire detail 을 ADR 에서 전부 잠그는 것을 목표로 하지 않는다. 장기 architecture decision 이 아니라 protocol spec 이 더 적합한 항목은 별도 spec 또는 implementation plan 으로 남긴다.
- application-specific transaction migration 또는 `ADR-0020`의 추가 on-wire footprint extension 여부를 이번 plan 의 직접 완료 조건으로 두지 않는다.

## Related ADRs And Docs
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- ADR-0019: Canonical Block Header And Application-Neutral Block View
- ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams
- ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions
- ADR-0022: HotStuff Pacemaker And View-Change Baseline
- `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0005-canonical-block-structure-migration-plan.md`
- `docs/plans/0006-conflict-free-block-scheduling-plan.md`
- `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`
- `README.md`

## Decisions To Lock Before Implementation
- 이 plan 의 primary deliverable 은 code landed 상태가 아니라 ADR landed 상태다. 구현 착수보다 문서 잠금이 앞선다.
- initial ADR tranche 는 아래 순서를 기본 dependency order 로 둔다.
  - `Pacemaker / View-Change`
  - `Validator-Set Rotation / Bootstrap Trust Roots`
  - `Static-Topology Peer Identity Binding / Session-Bound Capability Authorization`
- 위 순서는 번호보다 dependency 가 중요하다. 다른 ADR 이 먼저 추가되면 `0022`~`0024` numbering 은 바뀔 수 있지만, dependency order 는 유지한다.
- 새 ADR 은 "현재 shipped baseline", "이번 ADR 이 새로 잠그는 contract", "여전히 follow-up 으로 남는 항목"을 명시적으로 분리해야 한다.
- 기존 ADR 을 대폭 수정할지 새 ADR 로 분리할지 애매한 항목은 기본적으로 새 ADR 로 분리하되, 기존 ADR 의 scope 를 뒤집지 않도록 consequence / follow-up linkage 로 연결한다.
- implementation plan 이 ADR decision 을 임시로 대체하지 않도록, phase 별 output 은 가능한 한 "새 ADR 문서" 또는 "기존 문서 참조 업데이트" 단위로 정의한다.
- 문서 작성 중 candidate scope 가 너무 커지면, 한 ADR 안에 억지로 넣지 말고 superseding/follow-up ADR 로 쪼갠다.

## Phase 0 ADR-0018 Status Resolution
- Phase 0 baseline decision 은 `ADR-0018` 을 current shipped deployment baseline 으로 간주하고 status 를 `Accepted` 로 올리는 것이다.
- 이후 `ADR-0022`~`ADR-0024` 는 `ADR-0018` 을 뒤집지 않고 그 consequence/follow-up 영역을 확장하는 후속 ADR 로 위치시킨다.
- static topology 자체를 버리거나 same-DC validator placement 전제를 근본적으로 바꾸려면, implementation plan 확장이 아니라 별도 superseding deployment ADR 로 처리한다.

## Phase 1 Checkpoint Review
- `ADR-0018` status 정리 결과는 pacemaker scope 를 넓히지 않는다. Phase 1 은 current accepted static-topology / same-DC deployment baseline 위의 consensus liveness / view-change contract 만 고정한다.
- validator-set continuity, historical validator-set lookup, bootstrap trust root precedence 는 계속 Phase 2 `ADR-0023` 범위로 남는다. Phase 1 은 current active validator-set input 을 pacemaker 가 소비하는 contract 만 전제로 둔다.
- `ADR-0021` bootstrap lifecycle 과 `ProposalCatchUpReadiness` / `BootstrapVoteReadiness` 동등 vote-hold baseline 은 그대로 유지한다. Phase 1 pacemaker draft 는 local observed view progression 과 quorum-participation eligibility 를 분리하고, bootstrap hold 를 우회하지 않는 방향으로 잠근다.

## Phase 0 Inventory Summary

| Inventory Item | Phase 0 Classification | Owner Scope / Output | Primary Affected Docs | Dependency Note |
| --- | --- | --- | --- | --- |
| `ADR-0018` status mismatch (`Proposed` vs shipped reference baseline) | Existing ADR amendment | `ADR-0018` status 를 `Accepted` 로 정리하고, 후속 ADR 이 accepted deployment baseline 위에 쌓인다는 전제를 고정한다. | `ADR-0018`, `0008` | 선행 prerequisite 없음 |
| pacemaker / timeout vote / timeout certificate / new-view / leader rotation / timing-domain separation | New ADR (`ADR-0022`) | chained HotStuff liveness artifact 와 pacemaker ownership 경계를 고정한다. | `ADR-0017`, `ADR-0018`, plan `0004` | `ADR-0018` status 정리 후 Phase 1 진입 |
| validator-set rotation / validator-set continuity / bootstrap trust root / historical validator-set lookup | New ADR (`ADR-0023`) | authority continuity 와 finalized-proof verification trust model 을 고정한다. | `ADR-0018`, `ADR-0021`, 필요 시 `ADR-0019`, `ADR-0020`, plan `0007` | pacemaker draft 의 새 전제가 scope 를 바꾸지 않는지 checkpoint 후 진행 |
| configured peer identity binding / session-bound bootstrap capability authorization / revoke cascade | New ADR (`ADR-0024`) | static topology 아래의 peer identity 와 bootstrap capability ownership 을 고정한다. | `ADR-0016`, `ADR-0021`, plans `0003`, `0007` | trust-root / authority continuity draft 결과를 review 한 뒤 진행 |
| existing follow-up placeholder 와 shipped-vs-pending wording 정렬 | Existing ADR / plan amendments | 새 ADR landed 뒤 placeholder 문구를 concrete reference 로 치환한다. | `ADR-0016`, `ADR-0017`, `ADR-0018`, `ADR-0021`, plans `0003`, `0004`, `0007`, 필요 시 `README.md` | 각 ADR draft/acceptance 이후 순차 정렬 |
| exact `validatorSetHash` byte derivation, timeout artifact wire shape, capability token format, HTTP path / JSON / protobuf schema | Protocol spec or implementation plan | low-level wire / storage / transport detail 로 남기고 ADR 은 semantic contract 만 고정한다. | follow-up spec, plans `0003`, `0004`, `0007` | 각 ADR 의 semantic 경계가 잠긴 뒤 후속 문서에서 고정 |
| dynamic peer discovery / peer scoring / topology management / validator admission policy | Explicit defer | initial ADR tranche 밖의 deployment/discovery follow-up 으로 남긴다. | `ADR-0016`, `ADR-0018`, plans `0003`, `0007`, 필요 시 `README.md` | static topology baseline 을 당장 supersede 하지 않음 |
| fee market / proposer fairness / distributed mempool policy | Explicit defer | current trust/liveness/authority tranche 밖의 economics/mempool follow-up 으로 남긴다. | 필요 시 `README.md`, future deployment or mempool plan | current ADR tranche blocker 아님 |
| automatic audit-to-validator promotion / automatic cross-DC failover policy | Explicit defer | current operator-managed promotion baseline 을 유지하고, 자동화 계약은 별도 운영 ADR 로 분리한다. | `ADR-0018`, plan `0004` | `ADR-0023` 이 authority continuity 를 고정하더라도 자동 failover 까지 포함하지 않음 |
| archive-grade historical sync / snapshot batching-compression / Merkle proof serving / remote body/proof fetch / durable archive compaction | Explicit defer | snapshot/archive optimization 후속 ADR 또는 implementation plan 으로 남긴다. | `ADR-0019`, `ADR-0021`, plans `0005`, `0007` | bootstrap baseline 위의 성능/serving 확장으로 취급 |
| validator signing custody / KMS / HSM / remote signer baseline | Explicit defer; tentative `ADR-0025` | operator-managed raw key baseline 의 security-hardening follow-up 으로 남긴다. | `ADR-0018`, plan `0004` | multi-operator deployment, external validator participation, custody incident 가 re-open trigger |
| application transaction-shape migration / dynamic-discovery schedulability follow-up | Existing `ADR-0020` follow-up, outside this tranche | multi-node trust/liveness ADR 이 아니라 application scheduling migration 으로 남긴다. | `ADR-0020`, plan `0006` | current tranche blocker 아님 |

## Phase 0 Explicit Defer List
- `Dynamic peer discovery`, `peer scoring`, `topology management`, `validator admission policy` 는 static topology baseline 을 supersede 하는 별도 deployment/discovery ADR 로 미룬다.
- `Automatic audit-to-validator promotion`, `automatic cross-DC failover`, fully automated emergency operator policy 는 current operator-managed baseline 밖으로 둔다.
- `Archive-grade historical sync`, `snapshot batching/compression`, `Merkle proof serving`, `remote body/proof fetch`, `durable archive compaction` 은 snapshot/archive optimization follow-up 으로 남긴다.
- `Fee market`, `proposer fairness`, distributed mempool policy 는 current trust/liveness/authority tranche 와 분리한다.
- `Validator signing custody`, `KMS/HSM`, `remote signer` 는 tentative `ADR-0025` security-hardening candidate 로 defer 한다.
- `ADR-0020` application-specific scheduling extension 과 future dynamic-discovery transaction migration 은 current multi-node ADR tranche 의 mandatory output 으로 두지 않는다.

## Phase 0 ADR-vs-Spec-vs-Plan Boundary Rules
- `ADR-0022` 는 timeout/new-view/leader rotation/pacemaker ownership, bootstrap vote-hold interaction, session heartbeat 와 consensus timeout 의 timing-domain 분리를 고정한다. exact timeout constant, retry budget, serialization, transport path 는 follow-up spec 또는 implementation plan 이 소유한다.
- `ADR-0023` 는 static-set baseline precedence, rotation continuity, bootstrap trust-root class, historical validator-set lookup responsibility, finalized-proof verification continuity 를 고정한다. exact `validatorSetHash` byte layout, checkpoint file format, storage index/schema, operator UX 는 follow-up spec 또는 implementation plan 으로 남긴다.
- `ADR-0024` 는 configured `peerId` 와 authenticated counterparty identity binding, session-bound capability ownership, parent-session revoke/close cascade, runtime-vs-transport authorization ownership 을 고정한다. exact TLS/certificate mechanism, capability token encoding, HTTP header/path shape 는 follow-up spec 또는 implementation plan 이 소유한다.
- protocol spec / implementation plan 은 exact wire encoding, JSON/protobuf schema, HTTP path, batching/concurrency default, retry/backoff numbers, persistence layout, adapter assembly 순서를 소유한다. 단, 해당 detail 이 long-lived trust/liveness/authority precedence 를 바꾸는 경우에만 ADR 로 승격한다.
- existing ADR amendment 는 status correction, shipped-vs-follow-up wording 정렬, concrete cross-reference 보강까지로 제한한다. 기존 ADR 의 scope 자체를 뒤집어야 하면 amendment 대신 새 ADR 또는 superseding ADR 을 쓴다.

## Change Areas

### Code
- 필수 runtime behavior change 는 없다.
- 다만 ADR 본문에서 concrete 이름이 필요해지는 경우 아래 package 에 trait / type skeleton 을 최소한으로 추가할 수 있다.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime`
- 위 skeleton 추가는 문서에서 고정한 surface 를 애매하게 두지 않기 위한 보조 수단이며, runtime semantics landed 를 의미하지 않는다.

### Tests
- 필수 신규 runtime test landed 는 없다.
- 문서 변경과 함께 최소한 아래 검증은 유지한다.
- 문서 변경으로 인해 existing test/ADR naming 과 충돌하는 서술이 없는지 review 한다.
- skeleton 이 추가되면 compile/test baseline 과 import-rule suite 가 계속 green 인지 확인한다.

### Docs
- 새 ADR 후보 문서 3개 또는 그에 준하는 split/merge 결과물
- `docs/adr/0022-hotstuff-pacemaker-and-view-change-baseline.md`
- `docs/adr/0016-multiplexed-gossip-session-sync.md`
- `docs/adr/0017-hotstuff-consensus-without-threshold-signatures.md`
- `docs/adr/0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md`
- 필요 시 `docs/adr/0019-canonical-block-header-and-application-neutral-block-view.md`
- 필요 시 `docs/adr/0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md`
- `docs/adr/0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md`
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`
- 필요 시 `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- 필요 시 `README.md`

## Implementation Phases

### Phase 0: ADR Inventory And Boundary Lock
- `0003`~`0007` 과 `ADR-0016`~`ADR-0021` 의 residual gap 을 한 번 더 대조해, 새 ADR 이 필요한 항목과 기존 ADR amendment 로 충분한 항목을 분리한다.
- tentative ADR title, owner scope, primary affected docs, prerequisite 관계를 표 또는 동등한 inventory 로 정리한다.
- Phase 0 inventory 는 별도 scratch note 로 두지 않고 이 plan 본문에 요약 표 또는 동등한 inline section 으로 반영하는 것을 기본 output 으로 둔다. 별도 작업 메모가 필요하면 보조 문서로 둘 수 있지만, phase completion 기준은 이 plan 자체가 갱신되는 것이다.
- 이번 tranche 에 포함하지 않을 항목을 explicit defer list 로 남긴다. 예를 들어 dynamic discovery/scoring, archive acceleration, fee market, signer custody hardening 은 여기서 defer 가능해야 한다.
- 각 ADR 에서 "무엇을 ADR 로 잠그고, 무엇을 protocol spec/implementation plan 으로 남길지" 경계 규칙을 정한다.
- `ADR-0018` 의 `Proposed` status 가 current baseline reality 와 맞지 않는다면, 이 phase 에서 status update 또는 explicit note 추가 여부를 먼저 정리한다. 후속 ADR 은 그 결과를 전제로 링크되어야 한다.

### Phase 1: Pacemaker And View-Change ADR Draft
- Phase 1 시작 전, Phase 0 inventory 와 `ADR-0018` status 정리 결과가 pacemaker scope 를 바꾸지 않는지 checkpoint review 를 수행한다.
- chained HotStuff baseline 위에서 `timeout vote`, `timeout certificate`, `new-view`, `leader rotation`, pacemaker-owned liveness/timeout progression 의 semantic scope 를 고정한다.
- pacemaker artifact 가 existing proposal/vote/QC family 와 어떤 관계를 가지는지 정한다.
- bootstrap readiness / vote-hold / partial data sufficiency 와 pacemaker 의 상호작용을 정리한다.
- `100ms` deployment target 과 session heartbeat/liveness, pacemaker timeout 의 관계를 다시 정리해, 서로 다른 timing domain 이 섞이지 않게 한다.
- output 은 `ADR-0022` 와 `ADR-0017` / plan `0004` follow-up section 정렬이다.

### Phase 2: Validator-Set Rotation And Bootstrap Trust Roots ADR Draft
- Phase 2 시작 전, Phase 1 pacemaker draft 가 validator-set continuity / finalized-proof verification 전제에 어떤 새 요구를 추가했는지 checkpoint review 를 수행한다.
- static validator-set baseline 을 supersede 하거나 확장하는 rotation contract 를 정의한다.
- `validatorSetHash` commitment derivation ownership, historical validator-set lookup, finalized-proof verification continuity 를 고정한다.
- bootstrap trust root 후보군인 genesis config, operator checkpoint, weak-subjectivity anchor 의 역할과 제약을 분리한다.
- `ADR-0021`의 finalized anchor verification 과 snapshot bootstrap 이 rotation 이후에도 어떤 trust model 아래에서 동작하는지 고정한다.
- 필요 시 `ADR-0019` 의 canonical block/header commitment 서술과 `ADR-0020` 의 body-level verifier assumptions 에 validator-set continuity 관련 wording update 가 필요한지 함께 확인한다.
- output 은 tentative `ADR-0023` 와 `ADR-0018` / `ADR-0021` / plan `0007` follow-up 정렬이다.

### Phase 3: Static-Topology Peer Identity Binding And Session-Bound Capability ADR Draft
- Phase 3 시작 전, Phase 2 trust-root / authority continuity draft 가 peer identity binding scope 를 과도하게 확장시키지 않는지 checkpoint review 를 수행한다.
- static topology config 가 제공하는 configured `peerId` 와 실제 transport/session counterparty identity 를 어떻게 binding 할지 고정한다.
- full discovery/scoring system 없이도 필요한 최소 authorization contract 로서 session-open 이후 capability ownership 을 고정한다.
- bootstrap service family 와 parent directional session 의 binding, revoke/close cascade, re-authentication failure contract 를 정의한다.
- gossip runtime/service ownership 과 transport adapter ownership 을 다시 정리해, auth shortcut 이 transport/storage direct path 로 새지 않게 한다.
- static topology baseline 을 유지하더라도, "configured trusted peer 에게만 pull 을 허용한다"는 가정을 무엇으로 판정하는지 여기서 먼저 잠근다.
- output 은 tentative `ADR-0024` 와 `ADR-0016` / `ADR-0021` / plans `0003`, `0007` 정렬이다.

### Phase 4: Cross-ADR Alignment And Implementation Handoff
- 새 ADR 들 사이의 용어와 state model 을 다시 대조해 중복/모순을 정리한다.
- `0004`, `0007`, 필요 시 새 후속 plan 이 어떤 순서로 implementation 을 받아야 하는지 backlog 를 갱신한다.
- 기존 문서의 "follow-up 예정" 문장을 새 ADR 참조로 치환하고, still-open gap 을 더 좁은 구현 단위로 재기술한다.
- 필요하면 이 plan 의 status 를 `In Progress` 또는 `Superseded` 로 갱신하고, implementation-focused 후속 plan 으로 handoff 한다.

## Test Plan
- Phase 0 Success: `0003`~`0007` 과 `ADR-0016`~`ADR-0021` 에 남은 architecture-level gap 이 모두 "새 ADR", "기존 ADR amendment", "명시적 defer" 셋 중 하나로 분류되는지 검증한다.
- Phase 0 Success: inventory output 이 이 plan 본문 안에 inline section 또는 동등한 summary 형태로 반영되고, 별도 메모에만 남지 않는지 검증한다.
- Phase 0 Failure: `ADR-0018` status mismatch 가 unresolved 인 채로 Phase 1~3 초안이 그 위에 사실상 accepted baseline 처럼 쌓이면 reject 한다.
- Phase 1 Success: pacemaker ADR 초안이 timeout/new-view/leader rotation/liveness ownership 을 포함하고, `ADR-0017` 과 모순 없이 `proposal/vote/QC` baseline 위에 올라가는지 review 한다.
- Phase 1 Failure: pacemaker ADR 초안이 session heartbeat 와 consensus timeout 을 혼동하거나, bootstrap vote-hold 와 상충하는 hidden assumption 을 남기면 reject 한다.
- Phase 1 Regression: pacemaker draft 결과가 validator-set / trust-root ADR scope 를 materially 바꾸면, Phase 2 진입 전에 inventory 와 dependency order 가 다시 업데이트되는지 확인한다.
- Phase 2 Success: validator-set / trust-root ADR 초안이 rotation continuity, historical lookup, finalized-proof verification, bootstrap material 역할 분리를 모두 포함하는지 review 한다.
- Phase 2 Failure: peer-provided validator set 을 trust root 처럼 취급하거나, static set baseline 과 rotation baseline 의 precedence 가 모호하면 reject 한다.
- Phase 2 Regression: Phase 2 draft 결과가 peer identity binding ADR scope 를 materially 바꾸면, Phase 3 진입 전에 inventory 와 dependency note 가 다시 업데이트되는지 확인한다.
- Phase 3 Success: static-topology peer identity binding ADR 초안이 configured peer identity, session-bound bootstrap capability, revoke cascade, runtime-vs-transport ownership 을 모두 포함하는지 review 한다.
- Phase 3 Failure: identity binding 이 "known address 에 접속했다" 수준에 머물거나, parent-session revoke contract 가 빠지면 reject 한다.
- Phase 4 Regression: 새 ADR 과 기존 ADR/plan/README 간 cross-reference 가 일관되고, "shipped baseline" 과 "pending follow-up" 경계가 문서마다 다르게 쓰이지 않는지 검증한다.
- Optional Compile Gate: ADR drafting 중 type skeleton 을 추가했다면 compile/test baseline 과 import-rule suite 가 green 상태를 유지해야 한다.

## Risks And Mitigations
- ADR 하나에 너무 많은 항목을 밀어 넣으면 scope 가 커져 검토가 멈출 수 있다. 한 ADR 당 하나의 중심 contract 를 유지하고, 필요하면 follow-up ADR 로 분리한다.
- pacemaker 와 validator-set rotation 이 서로를 선결 조건처럼 요구하면 문서 작성이 교착될 수 있다. Phase 1 에서는 pacemaker artifact/liveness contract 만, Phase 2 에서는 authority continuity contract 만 우선 잠가 경계를 유지한다.
- `ADR-0018` 의 status 가 `Proposed` 인 채로 사실상 accepted baseline 처럼 소비되면 문서 precedence 가 흔들릴 수 있다. Phase 0 에서 status update 또는 explicit caveat 를 정리한 뒤 다음 phase 로 진행한다.
- 기존 shipped baseline 과 target contract 를 문서에서 섞어 쓰면 구현 상태 오해가 생긴다. 모든 ADR 에서 shipped / new / follow-up 세 구획을 명시한다.
- 문서가 implementation detail 을 과도하게 잠그면 이후 transport/runtime 선택지가 줄어든다. long-lived contract 와 protocol-spec detail 을 분리한다.
- 새 ADR 이 늘어나며 기존 follow-up 문구가 stale 상태로 남을 수 있다. 각 phase 의 output 에 관련 문서 정렬 작업을 포함한다.
- `ADR-0025` defer 가 indefinite backlog 로 사라질 수 있다. multi-operator deployment, external validator participation, operator key compromise incident, 또는 raw key custody 가 운영 병목으로 드러나는 시점을 explicit re-open trigger 로 문서화한다.
- 문서 초안 단계에서 번호가 변할 수 있다. numbering 대신 title 과 dependency order 를 canonical reference 로 본다.

## Acceptance Criteria
1. `0008` 이 multi-node 후속 작업 중 ADR 로 먼저 잠가야 하는 항목과 defer 할 항목을 명확히 구분한다.
2. pacemaker, validator-set rotation/bootstrap trust root, static-topology peer identity binding/session-bound capability authorization 를 다루는 ADR 작성 순서와 결정 inventory 가 문서에 고정된다.
3. 새 ADR 이 drafted/accepted 되면 기존 `ADR-0016`, `ADR-0017`, `ADR-0018`, `ADR-0021`, 관련 plan 문서가 새 참조를 기준으로 업데이트될 수 있는 구조가 마련된다.
4. Phase 0 inventory, defer list, dependency order 가 이 plan 본문에 반영되고, Phase 1~3 draft 결과로 scope 가 바뀌면 같은 문서 안에서 추적 가능하게 갱신된다.
5. 기존 follow-up placeholder 가 최소한 pacemaker, validator-set/trust-root, static-topology peer identity binding 영역에서는 구체적 ADR 참조 또는 explicit defer note 로 치환된다.

## Checklist

### Phase 0: ADR Inventory And Boundary Lock
- [x] `0003`~`0007` / `ADR-0016`~`ADR-0021` residual gap inventory 정리
- [x] Phase 0 inventory 를 이 plan 본문에 inline summary 로 반영
- [x] tentative ADR tranche scope / order / dependency 확정
- [x] explicit defer list 작성
- [x] ADR-vs-spec-vs-plan 경계 규칙 문서화
- [x] `ADR-0018` status update 또는 explicit caveat 정리

### Phase 1: Pacemaker And View-Change ADR Draft
- [x] Phase 0 output 기준 Phase 1 scope checkpoint review
- [x] tentative pacemaker ADR 초안 작성
- [x] pacemaker artifact / timeout / new-view / leader rotation baseline 정리
- [x] Phase 1 draft 결과가 validator-set / trust-root scope 를 materially 바꾸면 inventory / dependency order 갱신
- [x] `ADR-0017` / plan `0004` follow-up 참조 업데이트

### Phase 2: Validator-Set Rotation And Bootstrap Trust Roots ADR Draft
- [ ] Phase 1 output 기준 Phase 2 scope checkpoint review
- [ ] tentative validator-set / trust-root ADR 초안 작성
- [ ] rotation continuity / historical lookup / bootstrap material baseline 정리
- [ ] Phase 2 draft 결과가 peer identity binding scope 를 materially 바꾸면 inventory / dependency note 갱신
- [ ] 필요 시 `ADR-0019` / `ADR-0020` wording impact 확인
- [ ] `ADR-0018` / `ADR-0021` / plan `0007` follow-up 참조 업데이트

### Phase 3: Static-Topology Peer Identity Binding And Session-Bound Capability ADR Draft
- [ ] Phase 2 output 기준 Phase 3 scope checkpoint review
- [ ] tentative peer-identity-binding / capability ADR 초안 작성
- [ ] configured peer identity / revoke cascade / bootstrap capability baseline 정리
- [ ] `ADR-0016` / `ADR-0021` / plans `0003`, `0007` 참조 업데이트

### Phase 4: Cross-ADR Alignment And Implementation Handoff
- [ ] 새 ADR 간 용어/경계/precedence 정렬
- [ ] existing plan follow-up 을 새 ADR 참조 기준으로 재작성
- [ ] implementation backlog 와 후속 plan 분리 기준 정리
- [ ] 이 plan status 와 next-step handoff 갱신

## Follow-Ups
- dynamic peer discovery, peer scoring, validator admission policy 가 static topology baseline 을 대체해야 하면 별도 ADR 또는 superseding deployment ADR 로 분리한다.
- archive-grade historical sync, snapshot compression, Merkle proof serving, remote body/proof fetch 는 별도 ADR 또는 implementation plan 으로 분리한다.
- application-specific scheduling extension 이 on-wire footprint 또는 receipt projection 같은 새 canonical artifact 를 요구하면 `ADR-0020` follow-up ADR 로 분리한다.
- multi-operator deployment, external validator participation, operator key compromise incident, 또는 raw key custody 가 운영 병목으로 드러나면 tentative `ADR-0025` `Validator Signing Custody And Remote Signer Baseline` 을 별도 security-hardening follow-up 으로 분리한다.
- 위 ADR tranche 가 수용된 뒤, implementation 중심 후속 plan 은 pacemaker/runtime integration, rotation/bootstrap implementation, static-topology identity binding/runtime wiring 순으로 분리할 수 있다.
