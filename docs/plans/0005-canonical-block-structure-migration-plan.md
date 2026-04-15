# 0005 - Canonical Block Structure Migration Plan

## Status
Phase 4 Complete

## Created
2026-04-03

## Last Updated
2026-04-04

## Background
- 이 문서는 ADR-0019의 implementation plan 이다.
- `2026-04-03` 기준 `sigilaris-node-jvm` HotStuff 도메인의 `Block`은 `parent + payloadHash` 최소형이고, `BlockId`는 그 whole-value hash로 계산된다.
- 현재 shipped HotStuff baseline은 proposal/vote/QC identity, validator-set window, audit relay, bounded `requestById`, exact known-set sync를 이미 갖고 있다.
- 따라서 block 구조 변경은 단순 타입 치환이 아니라 canonical encoding, proposal validation, fixture, gossip payload, query/storage seam을 함께 건드리는 migration 작업이다.
- ADR-0017은 consensus artifact family와 identity/sign-bytes ownership을 고정했고, ADR-0019는 그 위에서 canonical block header와 application-neutral block view contract를 추가로 고정한다.
- ADR-0020은 same-block conflict-free selection을 ordering-independent membership 규칙으로 고정했으므로, ADR-0019 body contract도 proposer-local insertion order와 독립적인 unordered member-set semantics 위에서 deterministic commitment를 만들어야 한다.
- migration 중에도 `ProposalId`, `VoteId`, `BlockId` 분리 baseline, validator/audit role contract, HotStuff gossip ownership boundary는 유지되어야 한다.

## Goal
- `sigilaris-node-jvm` 안에 canonical `BlockHeader` contract를 도입한다.
- `BlockId`를 header-only identity로 재정의하고, `stateRoot`/`bodyRoot`/`timestamp`/`height`를 first-class block field로 승격한다.
- application-neutral `BlockView` surface를 추가해 다양한 application이 consensus 타입에 직접 묶이지 않고 unordered block body membership를 표현할 수 있게 한다.
- HotStuff proposal/validation/runtime이 새 block contract 위에서 계속 동작하도록 마이그레이션한다.

## Scope
- canonical block type, encoding, identity, validation helper를 추가하거나 기존 minimal `Block`을 대체한다.
- HotStuff proposal/validation path를 새 block header contract에 맞게 갱신한다.
- generic block body/view surface와 `bodyRoot` verification helper를 추가한다.
- 관련 unit/integration/regression test를 갱신한다.
- ADR/plan/README 문서를 새 계약에 맞게 정렬한다.

## Non-Goals
- full execution engine, reducer semantics, transaction application pipeline 자체를 이번 plan에서 새로 도입하지 않는다.
- state snapshot transport protocol, block body sync protocol, proof-serving protocol은 이번 plan에서 완전히 구현하지 않는다.
- pacemaker timeout/new-view, validator-set reconfiguration, aggregate signature 같은 HotStuff follow-up은 이번 plan의 직접 범위가 아니다.
- application별 concrete transaction/result/event schema를 공용 runtime contract로 고정하지 않는다.
- bodyRoot를 receiptRoot/eventRoot 등 여러 sub-root로 세분화하는 최종 형식은 이번 plan에서 mandatory baseline으로 고정하지 않는다.
- body membership conflict rule 자체는 이번 plan의 직접 범위가 아니다. ADR-0020 기반 body-level schedulability enforcement는 별도 plan(0006)의 follow-up integration이 소유한다.

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- ADR-0019: Canonical Block Header And Application-Neutral Block View
- ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0006-conflict-free-block-scheduling-plan.md`
- `docs/plans/plan-template.md`
- `README.md`

## Decisions To Lock Before Implementation
- canonical block identity artifact는 `BlockHeader`로 고정하고, `BlockId`는 header canonical encoding hash로 계산한다.
- `BlockHeader` baseline field는 `parent`, `height`, `stateRoot`, `bodyRoot`, `timestamp`로 고정한다.
- `bodyRoot`는 unordered block-body member set의 canonical sorted serialization commitment baseline으로 두고, 추가 sub-root는 follow-up 으로 분리한다.
- `BlockBody.records` baseline membership는 unordered `Set` 또는 그와 동등한 set semantics 로 둔다.
- `BlockRecordHash` baseline은 whole `BlockRecord` (`tx`, `result`, `events`)의 canonical encoding 에 대한 `hash(canonical-encoded BlockRecord)` 또는 그와 동등한 deterministic per-record commitment 로 둔다.
- `BlockBody` canonical serialization order는 `recordHash.bytes` lexicographic ASC 로 고정한다.
- 동일 `recordHash`를 가진 두 member가 같은 `BlockBody` 안에 동시에 존재하는 경우는 invalid 로 reject 한다. 이 rule 은 semantic content equality 가 아니라 per-record commitment identity 기준이며, hash collision 도 같은 방식으로 reject 한다.
- runtime collection 의 `equals`/`hashCode` 기반 dedup 은 canonical uniqueness contract 가 아니다. Phase 1 validation helper 는 `recordHash` 를 별도로 계산해 duplicate 를 검사해야 한다.
- "같은 tx 가 한 block 에 두 번 들어가면 안 된다" 같은 rule 이 필요하면 그것은 `recordHash` 가 아니라 application-owned tx identity 또는 ADR-0020 conflict-free scheduling rule 이 소유한다.
- `BlockView`/`BlockBody` public surface는 `TxRef`, `ResultRef`, `Event` generic parameter를 사용한다. consensus runtime은 이 concrete 타입을 알 필요가 없다.
- HotStuff proposal path는 header-first contract를 따른다. proposal identity/sign-bytes는 `BlockId`를 commit 하고, full `BlockView` mandatory carriage는 baseline requirement로 두지 않는다.
- proposal은 full body 대신 canonical tx hash set을 함께 운반할 수 있다. 이는 body 전체를 wire에 싣지 않고도 validator가 missing tx payload를 `tx` topic anti-entropy로 요청할 수 있게 하는 minimum availability seam 이다.
- initial migration 동안 `proposal.window.height`와 `BlockHeader.height`는 둘 다 유지할 수 있지만, validation은 equality를 mandatory 로 강제해야 한다.
- `proposal.window.height == header.height`의 canonical enforcement point는 proposal validation path다. relay/vote/QC assembly는 이 validation을 통과한 proposal만 대상으로 한다.
- proposer는 proposal 시점에 final `stateRoot`를 이미 알고 있어야 한다. placeholder `stateRoot`를 넣고 같은 header를 later rewrite 하는 flow는 허용하지 않는다.
- `BlockTimestamp` baseline은 proposer-declared UTC unix epoch milliseconds다. stricter monotonicity/skew policy는 validation follow-up 으로 둔다.
- genesis baseline은 `parent.isEmpty`를 유지한다. sentinel parent hash는 도입하지 않는다.
- block public model이 HotStuff 전용 package에 영구 고정되지 않도록 package ownership을 Phase 0에서 확정한다. initial public package root는 `org.sigilaris.node.jvm.runtime.block`으로 고정한다.
- landed migration phase는 모두 compile/test green을 유지해야 한다. Phase 1은 intentionally broken intermediate를 허용하지 않고, 필요하면 기존 minimal `Block`과 새 `BlockHeader`를 bridge/adapter 상태로 일시 공존시킨다.
- Phase 2까지는 HotStuff runtime이 full in-memory `BlockView` 또는 동등한 body-bearing proposal payload를 계속 들고 다녀도 된다. persistent header/body split lookup과 remote body fetch는 Phase 3 requirement로 미룬다.
- shipped persisted block store가 old `BlockId` contract에 이미 의존하지 않는 한 backward-compatible on-disk `BlockId` continuity는 mandatory 가 아니다. 반대로 그런 surface가 implementation 시점에 존재하면 Phase 1 landing 전에 explicit migration/reset/dual-read policy를 문서화해야 한다.
- `2026-04-04` 기준 shipped runtime/storage surface에는 old `BlockId` whole-block contract를 실제 persisted compatibility surface로 노출한 구현이 없다. `StorageLayout`의 block path reservation 만 존재하므로 initial migration baseline은 explicit reset/no-compat 정책으로 충분하다.
- 기존 `payloadHash` 명칭은 migration bridge에서만 허용하고, 새 public 문서/코드는 `bodyRoot` 또는 동등한 explicit name을 사용한다.

## Change Areas

### Code
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
- 필요 시 새 consensus-neutral block package
- 필요 시 block query/storage seam을 둘 package와 bootstrap wiring
- `modules/node-jvm/src/test/scala` 아래 HotStuff / block model unit / integration / regression test

### Tests
- header-only `BlockId` identity test
- permutation-invariant `bodyRoot` verification test
- duplicate `recordHash` reject test
- genesis / parent linkage / height equality validation test
- `ProposalId != BlockId` regression test
- gossip/request-by-id/replay regression test
- application-neutral `BlockView` generic surface compile/use test

### Docs
- `docs/adr/0017-hotstuff-consensus-without-threshold-signatures.md`
- `docs/adr/0019-canonical-block-header-and-application-neutral-block-view.md`
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0005-canonical-block-structure-migration-plan.md`
- 필요 시 `README.md`

## Implementation Phases

### Phase 0: Contract Lock And Package Ownership
- ADR-0019를 기준으로 field inventory, identity contract, generic body surface를 확정한다.
- `BlockHeader`/`BlockView` public package root는 `org.sigilaris.node.jvm.runtime.block`으로 고정한다.
- `BlockHeight`, `StateRoot`, `BodyRoot`, `BlockTimestamp`의 value type / encoding contract를 확정한다.
- `BlockRecordHash` 표현, canonical serialization rule, duplicate `recordHash` reject rule, runtime collection dedup 과 canonical uniqueness 검사의 분리 contract를 확정한다.
- 기존 minimal `Block`에서 새 contract로 가는 compatibility bridge(`payloadHash -> bodyRoot`)의 허용 범위를 문서화한다.
- proposal header-first + tx-hash-set baseline과 body-lazy-fetch baseline을 명시한다.
- persisted `BlockId` migration gate는 "no shipped persisted compatibility surface, so explicit reset/no-compat allowed" baseline으로 잠근다.

### Phase 1: Core Block Model And Encoding
- `BlockHeader`, `BlockBody`, `BlockRecord`, `BlockView` 또는 동등 model을 추가한다.
- `BlockId` 계산을 header-only deterministic hash로 옮긴다.
- `BlockRecordHash` helper 를 추가한다.
- `bodyRoot` verification helper를 추가한다.
- `recordHash` duplicate 검사를 runtime collection semantics 와 독립적으로 수행하는 validation helper 를 추가한다.
- canonical encoding helper와 fixture를 갱신한다.
- migration 중 temporary bridge type, alias, adapter를 둘 수 있지만, landed state의 canonical public surface는 `runtime.block.BlockHeader`이고 HotStuff package는 이를 직접 소비해야 한다.
- 이 Phase의 landed state는 compile/test green 이어야 한다.

### Phase 2: HotStuff Integration Migration
- `UnsignedProposal`, `Proposal`, validation path를 새 block header contract로 갱신한다.
- `proposal.targetBlockId == computeId(header)` contract를 유지한다.
- `proposal.window.height == header.height` validation을 추가한다.
- non-genesis parent/justify linkage, genesis empty-parent rule을 새 header shape에 맞게 유지한다.
- gossip topic contract와 exact known-set/request-by-id path가 새 block payload shape에서도 회귀 없이 동작하도록 조정한다.
- 이 Phase까지는 full in-memory `BlockView` carriage를 허용한다. header/body 분리 persistence 또는 remote body fetch는 아직 완료 조건이 아니다.

### Phase 3: Body/View Surface And Storage/Query Seam
- generic `BlockView`와 `BlockBody`를 consensus critical path와 분리된 query/storage surface로 연결한다.
- header/body 분리 저장 또는 동등 seam이 필요하면 이를 추가한다.
- body fetch가 아직 없더라도, `bodyRoot` 기준으로 local verification 가능한 helper와 storage contract를 먼저 고정한다.
- application package가 concrete `TxRef`/`ResultRef`/`Event`를 주입할 수 있는 예시 또는 test fixture를 추가한다.

### Phase 4: Verification And Docs
- unit/integration/regression suite를 green 상태로 맞춘다.
- ADR-0017, ADR-0019, plan 0004, plan 0005, README 용어를 정렬한다.
- migration residue, storage/body sync follow-up, sub-root follow-up을 문서에 남긴다.

## Test Plan
- Phase 1 Success: pure unit test로 `BlockId`가 `BlockHeader`만으로 안정적으로 계산되고 `BlockView` body 변화가 `BlockId`를 바꾸지 않는지를 검증한다.
- Phase 1 Success: pure unit test로 `bodyRoot`가 unordered `BlockBody.records` membership 에 대해 stable 하고, input construction order 나 equivalent `Set` iteration order 변화가 commitment 를 바꾸지 않는지를 검증한다.
- Phase 1 Success: golden fixture 기반 unit test로 canonical serialization 이 `recordHash.bytes` lexicographic ASC 규칙을 따르는지를 검증한다.
- Phase 1 Success: permutation regression 또는 property-style unit test 로 input construction order 변화가 canonical serialization order 와 `bodyRoot` 를 바꾸지 않는지를 검증한다.
- Phase 1 Failure: pure unit test로 duplicate `recordHash` body 를 reject 하는지를 검증한다.
- Phase 1 Failure: pure unit test로 runtime collection 이 distinct member 로 보더라도 `recordHash` 가 같으면 reject 하는지를 검증한다.
- Phase 1 Regression: temporary migration bridge 를 제거한 landed state에서도 compile/test green 이 유지되고, landed commit 이 intentionally broken intermediate 에 의존하지 않는지를 확인한다.
- Phase 1 Failure: pure unit test로 negative timestamp/height constructor input 을 reject 하고, generic `BlockView` validation 이 wrong `bodyRoot` 를 reject 하는지를 검증한다.
- Phase 2 Success: HotStuff validation test로 `proposal.targetBlockId == computeId(header)`, `proposal.window.height == header.height`, non-genesis `header.parent == justify.subject.blockId`를 검증한다.
- Phase 2 Failure: HotStuff validation test로 wrong `BlockId`, wrong height, wrong parent linkage, genesis parent present 를 reject 하는지를 검증한다.
- Phase 2 Regression: existing proposal/vote/QC exact known-set, replay dedupe, bounded `requestById`, audit relay path가 block shape 변경 후에도 유지되는지를 검증한다.
- Phase 3 Success: block view test로 application-owned concrete `TxRef`/`ResultRef`/`Event` 타입이 consensus runtime import 없이 `BlockView`에 꽂히는지를 검증한다.
- Phase 3 Success: storage/query seam test로 header/body를 분리 저장하거나 동등 contract로 조회할 수 있고 `bodyRoot` 재검증이 가능한지를 검증한다.
- Phase 4 Regression: docs-referenced compile/test baseline과 fixture가 새 block contract 기준으로 일관되게 갱신되었는지 확인한다.
- Phase 4 Success: old `BlockId` contract를 전제로 한 persisted surface가 존재한다면 migration/reset/dual-read policy가 명시되고 필요한 검증이 추가되었는지 확인한다.

## Risks And Mitigations
- `proposal.window.height`와 `header.height`가 drift 하면 consensus validation bug가 생길 수 있다. equality validation과 dedicated regression test로 막는다.
- block body generic surface가 application ADT를 consensus package로 다시 끌고 들어올 수 있다. `TxRef`/`ResultRef`/`Event` generic boundary와 import rule review로 경계를 고정한다.
- `BlockId` 재정의는 fixture, persisted data, gossip expectations를 깨뜨릴 수 있다. explicit migration note, fixture refresh, header-only identity regression test, persisted surface 존재 시 migration/reset/dual-read policy gate로 대응한다.
- unordered member-set baseline 위의 canonical serialization rule이 불명확하면 bodyRoot mismatch bug가 생길 수 있다. `recordHash` contract, lexicographic sort rule, duplicate `recordHash` reject test로 잠근다.
- `bodyRoot`를 너무 이르게 고정하면 future receipt/event sub-root 요구와 충돌할 수 있다. initial baseline은 single body commitment로 두고, follow-up ADR에서 additive extension 여부를 평가한다.
- header/body 분리 저장이 조기에 storage coupling을 키울 수 있다. query/storage seam은 최소 surface만 먼저 두고, transport/proof protocol은 후속 plan으로 분리한다.

## Acceptance Criteria
1. canonical `BlockHeader` contract가 문서와 코드에서 존재하고, `parent`, `height`, `stateRoot`, `bodyRoot`, `timestamp` baseline이 고정된다.
2. `BlockId`는 header-only deterministic hash로 계산되고, `BlockView` body projection 변화와 분리된다.
3. `BlockBody.records` 는 unordered membership semantics 를 가지며, `bodyRoot` 는 `recordHash.bytes` lexicographic ASC canonical serialization 기준으로 계산된다.
4. application-neutral `BlockView` surface가 concrete application transaction/result/event ADT에 대한 consensus package 의존 없이 표현된다.
5. HotStuff proposal/validation/runtime은 새 block contract 위에서 계속 동작하고, `ProposalId`, `VoteId`, `BlockId` 분리 baseline이 유지된다.
6. height equality, genesis empty-parent, parent/justify linkage, permutation-invariant `bodyRoot`, duplicate `recordHash` reject 에 대한 테스트가 회귀 잠금된다.
7. ADR-0017, ADR-0019, plan 0004, plan 0005, 필요 시 README가 새 block contract를 일관된 용어로 설명한다.
8. old `BlockId` contract를 전제로 한 persisted surface가 존재하면 explicit migration/reset/dual-read policy가 문서와 구현 gate에 반영되고, 그런 surface가 없으면 비호환성 비요구 baseline이 명시된다.

## Checklist

### Phase 0: Contract Lock And Package Ownership
- [x] ADR-0019 기준 field inventory 및 identity contract 확정
- [x] block public package root 확정
- [x] `BlockHeight` / `StateRoot` / `BodyRoot` / `BlockTimestamp` 표현 확정
- [x] `BlockRecordHash` / canonical serialization / duplicate reject rule 확정
- [x] `payloadHash -> bodyRoot` migration bridge 범위 문서화
- [x] header-only proposal baseline 확정
- [x] Phase 1 compile-green bridge policy 확정
- [x] Phase 2까지 body carriage / lookup baseline 확정
- [x] persisted `BlockId` migration/reset policy gate 확정

### Phase 1: Core Block Model And Encoding
- [x] `BlockHeader` / `BlockBody` / `BlockRecord` / `BlockView` 추가
- [x] header-only `BlockId` 계산 추가
- [x] `BlockRecordHash` helper 추가
- [x] `bodyRoot` verification helper 추가
- [x] canonical encoding / fixture 갱신
- [x] HotStuff public `Block` alias 제거를 포함한 naming migration 정리
- [x] compile/test green bridge 상태 유지

### Phase 2: HotStuff Integration Migration
- [x] proposal/unsigned proposal block field migration
- [x] `targetBlockId == computeId(header)` contract 갱신
- [x] `window.height == header.height` validation 추가
- [x] genesis / justify-parent rule migration
- [x] gossip/request-by-id/replay regression 보정

### Phase 3: Body/View Surface And Storage/Query Seam
- [x] generic `BlockView` query surface 추가
- [x] header/body 분리 저장 또는 동등 seam 추가
- [x] application-owned concrete generic fixture 추가
- [x] local `bodyRoot` re-verification path 추가

### Phase 4: Verification And Docs
- [x] unit/integration/regression test green
- [x] ADR / plan / README 용어 정렬
- [x] residual follow-up 문서화

## Follow-Ups
- state snapshot transport, remote body fetch, proof-serving protocol은 별도 ADR 또는 plan으로 분리한다.
- receiptRoot/eventRoot 같은 auxiliary sub-root가 필요해지면 후속 ADR에서 추가한다.
- block public model이 HotStuff package 바깥 shared surface로 완전히 추출되어야 하는지는 migration 결과를 보고 follow-up 으로 확정한다.
- persisted data migration policy가 필요해지면 storage-specific follow-up 문서로 분리한다.
