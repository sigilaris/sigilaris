# ADR-0019: Canonical Block Header And Application-Neutral Block View

## Status
Accepted

## Context
- `2026-04-03` 기준 `sigilaris-node-jvm` 의 HotStuff 도메인 `Block`은 `parent + payloadHash`만 담는 최소형 artifact다.
- 현재 baseline에서 consensus metadata(`chainId`, `height`, `view`, `validatorSetHash`)와 proposal-level tx membership hash set은 `HotStuffWindow`와 `Proposal`이 소유하고, `BlockId`는 minimal `Block` whole-value hash로 계산된다.
- 이 구조는 proposal/vote/QC identity를 고정하는 데는 충분하지만, canonical block header contract, post-state commitment, block timestamp, generic block query surface를 제공하지 않는다.
- state snapshot sync, block explorer/query, execution 결과 추적, body/header 분리 저장 같은 후속 기능은 `parent + payloadHash`만으로는 stable contract를 갖기 어렵다.
- ADR-0020은 same-block conflict-free selection을 ordering-independent membership 규칙으로 고정했다. 따라서 canonical block body contract는 proposer-local insertion order와 독립적인 body membership semantics를 가질 수 있어야 하며, 동시에 deterministic commitment와 query projection은 유지해야 한다.
- 동시에 block public type이 특정 application의 raw transaction ADT, signed payload codec, event schema에 직접 묶이면 consensus/runtime 경계가 다시 흐려질 수 있다.
- `ProposalId`, `VoteId`, `BlockId`를 분리한 ADR-0017의 baseline은 유지되어야 하고, block contract 변경이 proposal/vote identity contract를 다시 섞어서는 안 된다.
- 따라서 Sigilaris는 consensus metadata와 application-facing block body를 분리한 canonical block family를 별도 ADR로 고정할 필요가 있다.

## Decision
1. **Sigilaris canonical block family는 `BlockHeader`, `BlockBody`, `BlockView` 계층으로 분리한다.**
   - identity와 chain linkage를 대표하는 canonical artifact는 `BlockHeader`다.
   - application-facing body membership는 `BlockBody`가 소유한다.
   - query/inspection surface는 `BlockView(header, body)`다.
   - body 안의 record schema는 application-neutral generic type parameter로 모델링한다.
   - initial public package root는 `org.sigilaris.node.jvm.runtime.block`으로 고정한다.
   - HotStuff runtime은 이 package를 import 하는 consumer이고, block public model owner가 아니다.

2. **`BlockHeader` baseline은 최소한 아래 semantic field를 포함해야 한다.**
   - `parent: Option[BlockId]`
   - `height: BlockHeight`
   - `stateRoot: StateRoot`
   - `bodyRoot: BodyRoot`
   - `timestamp: BlockTimestamp`
   - initial baseline에서 `stateRoot`와 `bodyRoot`는 `UInt256`-backed commitment 또는 그와 동등한 deterministic commitment type으로 모델링한다.
   - `bodyRoot`는 unordered block-body member set의 canonical sorted serialization commitment이며, auxiliary receipt/event sub-root는 baseline mandatory field로 두지 않는다.
   - `timestamp`는 proposer가 header에 commit 하는 UTC unix epoch milliseconds baseline으로 고정한다.
   - `timestamp`는 consensus가 별도로 합성한 monotonic clock value가 아니다. clock skew 상한이나 stricter monotonicity policy는 validation follow-up이 소유한다.
   - implementation baseline value type은 `BlockHeight`, `StateRoot`, `BodyRoot`, `BlockTimestamp`로 분리하고, 각각 `BigNat`, `UInt256`, `UInt256`, signed `Long` epoch milliseconds 기반 deterministic encoding을 사용한다.

3. **`BlockId`는 `BlockHeader` canonical deterministic encoding의 hash다.**
   - `BlockId`는 `BlockView` whole-value hash가 아니다.
   - body/query projection, local cache shape, serialization format이 달라져도 `BlockHeader`가 같으면 `BlockId`는 같아야 한다.
   - block identity는 content-addressed contract로 유지하고, chain binding은 enclosing runtime/consensus context가 소유한다.

4. **application-facing block body는 generic record surface로 모델링한다.**
   - baseline sketch는 아래와 동등한 구조를 따른다.

```scala
final case class BlockHeader(
    parent: Option[BlockId],
    height: BlockHeight,
    stateRoot: StateRoot,
    bodyRoot: BodyRoot,
    timestamp: BlockTimestamp,
)

final case class BlockRecord[TxRef, ResultRef, Event](
    tx: TxRef,
    result: Option[ResultRef],
    events: Vector[Event],
)

final case class BlockBody[TxRef, ResultRef, Event](
    records: Set[BlockRecord[TxRef, ResultRef, Event]],
)

final case class BlockView[TxRef, ResultRef, Event](
    header: BlockHeader,
    body: BlockBody[TxRef, ResultRef, Event],
)
```

   - `TxRef`, `ResultRef`, `Event`는 application package가 소유한다.
   - baseline block contract는 raw transaction payload를 mandatory public field로 요구하지 않는다.
   - application은 hash, summary, signed reference, thin event projection 등 자신에게 필요한 concrete type을 꽂을 수 있다.
   - sketch의 `Set[...]` 표기는 unordered membership semantics를 설명하기 위한 것이다. concrete runtime collection의 `equals`/`hashCode` 또는 implementation-local dedup 동작이 canonical contract를 대신하지는 않는다.
   - `BlockBody.records` membership는 unordered다. `Set` iteration order 또는 equivalent runtime collection order는 canonical contract가 아니다.
   - `BlockRecord.events`는 여전히 ordered projection일 수 있다. body membership가 unordered라는 사실이 intra-record event ordering까지 unordered로 만든다는 뜻은 아니다.

5. **HotStuff consensus metadata는 계속 `Proposal`/`HotStuffWindow`가 소유한다.**
   - `chainId`, `view`, `validatorSetHash`, justify QC subject는 block header가 아니라 consensus artifact가 소유한다.
   - `BlockHeader`는 HotStuff-specific `view` 또는 validator-set membership metadata를 포함하지 않는다.
   - block contract는 consensus algorithm과 독립적으로 재사용 가능해야 한다.

6. **HotStuff baseline에서는 `Proposal.window.height`와 `BlockHeader.height`의 equality를 validation contract로 강제한다.**
   - ADR-0017이 이미 `(chainId, height, view, validatorSetHash)` window를 progress key로 고정했으므로, initial migration에서는 `height`를 proposal window와 block header 양쪽에 유지할 수 있다.
   - 이 경우 receiver는 proposal validation에서 `proposal.window.height == carriedBlockHeader.height` 또는 동등 contract를 반드시 검증해야 한다.
   - canonical enforcement point는 proposal validation path다. local relay, local vote emission, QC assembly는 모두 이 validation을 통과한 proposal만 대상으로 해야 한다.
   - non-genesis proposal에서는 `header.parent == justify.subject.blockId`를 유지한다.
   - genesis baseline은 sentinel parent hash 대신 `parent.isEmpty`를 사용한다.

7. **consensus proposal contract는 header-first baseline을 따른다.**
   - proposal identity/sign-bytes가 commit 하는 block target은 `BlockId`다.
   - proposal은 full `BlockView` 대신 canonical tx hash set을 함께 운반할 수 있다. 이 tx hash set은 block body 전체를 대체하지 않지만, receiver가 missing tx payload를 별도 `tx` topic anti-entropy로 요청할 수 있게 하는 minimum availability seam 이다.
   - proposal이 full `BlockView`를 항상 wire payload로 운반해야 한다는 requirement는 두지 않는다.
   - body dissemination, body fetch, storage layout은 header contract 위에서 별도 seam으로 발전시킨다.

8. **`stateRoot`는 snapshot/state sync의 canonical anchor다.**
   - block contract는 post-state commitment를 first-class field로 포함해야 한다.
   - `BlockId`가 whole header에 commit 하므로 proposer는 proposal sign/emit 이전에 final `stateRoot`를 알아야 한다. placeholder를 넣고 commit/finalization 시점에 같은 block header를 rewrite 하는 baseline은 허용하지 않는다.
   - exact execution engine mechanics는 이 ADR의 직접 범위가 아니지만, baseline proposal flow는 execute-then-propose 또는 그와 동등한 deterministic precomputed post-state commitment flow를 전제한다.
   - state sync 또는 proof-serving follow-up은 이 `stateRoot`를 기준 anchor로 사용한다.
   - state execution engine의 내부 reducer semantics는 이 ADR의 직접 범위가 아니지만, block contract 차원에서는 state commitment의 존재를 더 이상 optional로 두지 않는다.

9. **body verification은 `bodyRoot`와 `BlockBody` 사이의 deterministic commitment contract로 수행한다.**
   - body membership는 unordered지만, canonical serialization order는 mandatory다.
   - `recordHash` baseline은 whole `BlockRecord` (`tx`, `result`, `events`)의 canonical encoding에 대한 `hash(canonical-encoded BlockRecord)` 또는 그와 동등한 deterministic per-record commitment다.
   - implementation baseline value type은 `BlockRecordHash`이며 `UInt256`-backed commitment로 모델링한다.
   - `BlockBody` canonical serialization은 `recordHash.bytes` lexicographic ascending order로 정렬한 뒤 수행한다.
   - implementation-local `Set` iteration order에 body commitment가 의존해서는 안 되며, runtime collection의 자체 dedup으로 uniqueness를 충족했다고 간주해서도 안 된다.
   - 동일 `recordHash`를 가진 두 member가 한 `BlockBody` 안에 동시에 존재해서는 안 된다. 이 rule은 semantic content equality가 아니라 per-record commitment identity 기준이며, 극히 드문 hash collision이어도 같은 `recordHash`면 body는 invalid다.
   - "같은 tx가 한 block에 두 번 들어갈 수 있는가"는 `recordHash`가 아니라 application-owned tx identity/validation rule이 소유한다. ADR-0020 conflict-free selection이나 equivalent application rule이 그 제약을 별도로 강제할 수 있다.
   - `BlockView` receiver는 필요 시 `bodyRoot == hash(canonical-serialized body)` 또는 동등한 검증을 수행할 수 있어야 한다.
   - body fetch/storage/query path가 존재하더라도 `BlockId` contract는 변하지 않는다.

10. **현재 minimal `payloadHash` 모델은 장기 public contract가 아니라 transitional baseline으로 본다.**
    - migration 동안 기존 `payloadHash`는 `bodyRoot`로 재해석할 수 있다.
    - 그러나 public 문서와 후속 구현은 `payloadHash`라는 모호한 명칭 대신 `BlockHeader`와 explicit commitment field를 사용해야 한다.
    - bridge 허용 범위는 Phase 1-2의 HotStuff migration adapter로 제한한다. 새 public package와 문서는 `payloadHash` 명칭을 직접 노출하지 않는다.

## Consequences
- block identity와 application-facing body/query surface를 분리하므로, consensus artifact contract와 block explorer/storage contract가 덜 섞인다.
- `stateRoot`를 block header에 올리면 snapshot/state sync follow-up이 더 자연스러운 anchor를 갖는다.
- `bodyRoot`와 generic `BlockView`를 도입하면 다양한 application이 같은 block contract를 공유하면서도 자신만의 tx/result/event projection을 유지할 수 있다.
- block body membership은 unordered로 유지하면서도 canonical serialization order를 `recordHash` 기반으로 고정하므로, proposer-local insertion order나 in-memory collection order가 `bodyRoot`를 흔들지 않는다.
- 따라서 구현은 runtime `Set` 자체의 dedup에 의존하지 않고, `recordHash` 집합을 별도로 계산해 uniqueness를 검증해야 한다.
- `ProposalId`, `VoteId`, `BlockId` 분리 baseline은 그대로 유지되며, proposal/vote exact known-set sync semantics가 block body 변화에 흔들리지 않는다.
- 대신 block model, canonical encoding, validation, fixture, storage/query seam이 함께 바뀌므로 migration cost가 있다.
- `height`가 proposal window와 block header 양쪽에 존재하므로, equality validation이 빠지면 drift bug가 생길 수 있다.
- proposer는 proposal 이전에 final `stateRoot`를 계산해야 하므로, "header는 먼저 만들고 state commitment는 나중에 채운다" 같은 파이프라인은 baseline에서 허용되지 않는다.
- per-record hash contract와 duplicate `recordHash` rejection rule을 같이 고정해야 하므로 body validation contract가 기존 ordered-vector baseline보다 조금 더 복잡해진다.
- `bodyRoot` 단일 commitment baseline은 단순하지만, receipt/event를 별도 root로 first-class expose 하려면 follow-up ADR이 더 필요할 수 있다.

## Rejected Alternatives
1. **현재 `Block(parent, payloadHash)` minimal shape를 그대로 유지한다**
   - proposal identity에는 충분하지만, state snapshot anchor와 canonical block query surface가 없다.
   - 장기적으로 body/header 분리 저장이나 generic application support를 문서화하기 어렵다.

2. **`Block[T]`처럼 transaction ADT만 generic parameter로 둔다**
   - tx만 generic이면 result/event surface는 다시 hard-coded field나 ad-hoc payload blob으로 밀려날 가능성이 크다.
   - 선택한 `BlockRecord[TxRef, ResultRef, Event]` baseline은 tx reference뿐 아니라 result/event projection도 함께 application-owned generic seam으로 드러내므로 경계를 더 명확히 유지한다.

3. **HotStuff `view`나 `validatorSetHash`를 block header 안으로 옮긴다**
   - block contract가 consensus-variant-specific metadata를 소유하게 되어 재사용성이 떨어진다.
   - block identity와 consensus window identity를 같은 층위에서 다루게 되어 책임 분리가 약해진다.

4. **`BlockId`를 `BlockView` whole-value hash로 계산한다**
   - body/query projection이 변하면 block identity까지 흔들린다.
   - header-only validation, body-lazy fetch, storage/query projection 분리가 어려워진다.

5. **`BlockBody.records`를 ordered `Vector` public surface로 유지한다**
   - ordered `Vector`도 deterministic commitment를 만들 수는 있지만, public membership semantics와 canonical serialization order를 같은 층위로 섞게 된다.
   - ADR-0020이 block selection을 ordering-independent membership 규칙으로 고정했으므로, canonical block body도 unordered membership와 deterministic serialization order를 분리하는 편이 더 자연스럽다.

## Follow-Up
- concrete migration 단계와 test gate는 `docs/plans/0005-canonical-block-structure-migration-plan.md`가 소유한다.
- `2026-04-04` 기준 canonical `BlockHeader` / `BlockBody` / `BlockView` model, HotStuff header-first integration, `runtime.block.BlockStore` query/storage seam, local `bodyRoot` re-verification baseline은 landed 상태다.
- `2026-04-04` 기준 shipped HotStuff baseline proposal artifact는 full body 대신 canonical tx hash set을 함께 운반한다.
- `bodyRoot`를 여러 sub-root로 세분화할 필요가 생기면 후속 ADR에서 supersede 또는 extend 한다.
- unordered member-set baseline이 insufficient 하거나 multiset semantics가 필요해지면 후속 ADR에서 supersede 또는 extend 한다.
- remote body fetch, state snapshot transport, proof-serving, execution/result persistence format은 별도 ADR 또는 plan으로 분리한다.
- shipped baseline에는 old `BlockId` contract를 전제로 한 persisted block store가 없다. 따라서 initial migration은 on-disk dual-read compatibility를 mandatory 로 요구하지 않고, persisted block surface가 도입되는 시점에 explicit reset/migration policy를 별도 문서화한다.

## References
- [ADR-0009: Blockchain Application Architecture](0009-blockchain-application-architecture.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams](0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md)
- [0004 - HotStuff Consensus Without Threshold Signatures Plan](../plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md)
- [0005 - Canonical Block Structure Migration Plan](../plans/0005-canonical-block-structure-migration-plan.md)
