# ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions

## Status
Accepted

## Context
- `2026-04-04` 기준 Sigilaris HotStuff baseline은 ADR-0019 canonical `BlockHeader` 를 통해 `height`, `stateRoot`, `bodyRoot`, `timestamp`, `parent` 를 first-class block field로 갖는다.
- 같은 날짜 기준 shipped `Proposal` artifact 는 ADR-0017 / ADR-0019 baseline 위에서 canonical tx hash set 을 함께 운반한다. 따라서 receiver 는 missing tx payload 를 `tx` topic anti-entropy 로 요청하고, anchor 이후 block body 와 post-anchor state transition 을 로컬 replay 로 재구성할 수 있다.
- 반면 현재 proposal retrieval/gossip API 는 finalized-aware 하지 않다. `proposal` 을 topic/cursor/id 로 읽을 수는 있지만, "현재 네가 알고 있는 가장 높은 verifiable finalized block/header 는 무엇인가" 를 직접 물어보는 canonical bootstrap surface 는 없다.
- ADR-0016 은 snapshot/backfill 을 follow-up 범위로 남겼고, ADR-0019 역시 state snapshot transport, remote body/proof serving 을 별도 ADR 또는 plan 으로 분리했다.
- 신규 투입 노드의 bootstrap 은 적어도 아래 문제를 분리해서 다뤄야 한다.
  - 어느 시점의 상태를 snapshot anchor 로 잡을 것인가
  - 그 anchor `stateRoot` 아래 trie 를 어떻게 복원할 것인가
  - anchor 이후 current frontier 까지 어떤 data plane 으로 따라잡을 것인가
  - anchor 이전 history 를 언제 어떤 우선순위로 채울 것인가
- bootstrap anchor 를 단순 "가장 높다고 주장된 block" 에 두면 byzantine peer 나 stale peer 에 끌려갈 수 있다. anchor 는 local node 가 직접 검증 가능한 finalized evidence 에 의해 선택되어야 한다.
- 동시에 신규 노드는 아직 current state 를 갖고 있지 않으므로, finalized proof 검증에 필요한 validator-set continuity 를 peer 응답만으로 신뢰할 수는 없다. bootstrap 은 최소한 하나의 trusted bootstrap root 없이 완전히 self-bootstrapping 되지 않는다.
- 반대로 sync 도중 더 높은 finalized tip 이 보일 때마다 snapshot 을 버리고 재시작하면 bandwidth 와 time-to-readiness 가 크게 낭비된다.
- 또한 snapshot trie node fetch 는 content-addressed verification 으로 individual node admissibility 는 판정할 수 있지만, stale peer 나 malicious peer 의 subtree withholding 자체를 자동으로 해결하지는 못한다.
- 따라서 Sigilaris 는 "best finalized suggestion 으로 anchor discovery 를 수행하고, 선택한 anchor 의 snapshot 을 먼저 완성한 뒤, anchor 이후는 live catch-up 으로 따라잡고, anchor 이전은 background historical backfill 로 채우는" bootstrap contract 를 별도 ADR 로 고정할 필요가 있다.

## Decision
1. **신규 노드 bootstrap 은 `anchor discovery -> snapshot sync -> forward catch-up -> historical backfill` 네 단계로 분리한다.**
   - `anchor discovery` 는 현재 network 가 제시하는 candidate 중 local node 가 검증 가능한 finalized anchor 를 고르는 단계다.
   - `snapshot sync` 는 선택한 anchor 의 `stateRoot` 아래 trie 를 복원하는 단계다.
   - `forward catch-up` 은 anchor 이후 current frontier 까지 consensus/runtime data 를 따라잡는 단계다.
   - `historical backfill` 은 anchor 이전 history 를 genesis 방향으로 채우는 단계다.

2. **Sigilaris 는 일반 proposal retrieval 과 별도로 `best finalized block suggestion` bootstrap API 를 둔다.**
   - 이 API 는 "proposal 을 finalized 만 따로 읽는 API" 가 아니다.
   - 새 API 의 목적은 bootstrap 시작 시점에 각 neighbor 가 현재 locally known finalized frontier 중 무엇을 anchor 후보로 추천하는지 묻는 것이다.
   - request 는 ADR-0016 directional session 및 동일 peer authentication context 에 바인딩되는 runtime-owned service call 이다.
   - static-topology baseline 아래의 configured peer identity binding 과 session-bound child capability ownership 은 ADR-0024 가 소유한다.
   - transport adapter 는 이 service 를 노출할 수 있지만, storage 를 직접 조회하는 transport-owned shortcut 으로 구현해서는 안 된다.

3. **`best finalized block suggestion` response 는 "주장" 이 아니라 local verification 이 가능한 anchor bundle 이어야 한다.**
   - response 는 최소한 아래 의미 정보를 포함해야 한다.
     - canonical `BlockHeader`
     - corresponding `BlockId`
     - anchor `height`
     - anchor `stateRoot`
     - 해당 anchor 가 Sigilaris HotStuff finality rule 아래 finalized 임을 검증하는 데 충분한 proof bundle
   - baseline payload 는 아래와 동등한 의미 구조를 따를 수 있다.

```scala
final case class FinalizedProof(
    child: Proposal,
    grandchild: Proposal,
)

final case class FinalizedAnchorSuggestion(
    proposal: Proposal,
    finalizedProof: FinalizedProof,
)
```

   - 여기서 `proposal` 은 finalized 여부를 검증하려는 anchor proposal `P0` 이고, `child` 와 `grandchild` 는 각각 `P0`, `child` 를 justify 하는 descendant proposal 이다.
   - 이 ADR 에서 `child` / `grandchild` 라는 이름은 justify chain 상의 상대적 위치를 가리키는 약칭이다. 이는 block-tree `parent` link 와 항상 동일하다는 뜻이 아니며, proof 검증의 canonical link 는 각 proposal 안의 `justify` chain 이다.
   - `FinalizedProof` 의 semantic minimum 은 descendant proposal 둘과 그 안에 포함된 justify QC 다. block id 둘 또는 proposal id 둘만으로는 parent/justify chain 과 QC validity 를 자체 검증할 수 없으므로 baseline proof 로는 충분하지 않다.
   - follow-up 이 소유하는 것은 `FinalizedProof` 의 exact wire encoding, fetch optimization, batching 방식이지, 위 semantic minimum 자체가 아니다.
   - response 는 언제나 local node 가 "verifiable finalized" 여부를 판정할 수 있을 정도의 증빙을 포함해야 한다.

4. **`verifiable finalized` 판정은 bootstrap trust root 에 대해 정의한다.**
   - local node 는 finalized proof 와 validator-set continuity 를 검증할 수 있는 최소 bootstrap trust root 를 out-of-band 로 가져야 한다.
   - baseline trust root 는 chain genesis config, operator-supplied trusted checkpoint, weak-subjectivity anchor 또는 그와 동등한 trusted bootstrap material 일 수 있다.
   - 위 trust root 후보들은 서로 동등한 trust model 이 아니다. genesis config 는 static chain root 에 가깝고, operator checkpoint 는 운영자 신뢰를 추가로 요구하며, weak-subjectivity anchor 는 freshness/window assumption 을 전제로 한다.
   - peer 가 제시한 suggestion payload 자체는 validator-set authority 의 근거가 될 수 없다.
   - `FinalizedProof` 검증은 "peer 가 준 proof 가 self-authentic 하다" 가 아니라 "trusted bootstrap root 로부터 따라갈 수 있는 validator-set continuity 와 finality rule 을 만족한다" 는 뜻으로 해석한다.
   - bootstrap trust-root class, precedence, historical validator-set lookup ownership 은 ADR-0023 이 소유한다.

5. **bootstrap requester 는 live neighbor 전체에 `best finalized block suggestion` 을 fan-out 하고, local verification 을 통과한 candidate 중 `BlockHeader.height` 가 가장 높은 것을 anchor 로 선택한다.**
   - requester 는 validator neighbor 와 audit neighbor 를 구분하지 않는다. finalized suggestion 을 제공할 수 있는 authenticated live peer 라면 source 가 될 수 있다.
   - verification 에 실패한 suggestion 은 discard 한다.
   - candidate 가 없거나 모든 candidate 검증이 실패하면 node 는 bootstrap discovery 상태를 유지하고, operator-visible diagnostics 를 남기며, bounded backoff 로 discovery 를 재시도해야 한다. exact retry interval 과 budget 은 follow-up plan 이 고정한다.
   - 여러 valid candidate 가 같은 `BlockId` 와 같은 `height` 를 가리키면 어느 peer 응답을 anchor source 로 택해도 된다.
   - 같은 최고 `height` 에 대해 서로 다른 `BlockId` 를 가진 둘 이상의 valid finalized suggestion 이 동시에 성립하면 이는 bootstrap tie-break 로 덮을 일이 아니라 safety fault 로 취급한다. node 는 arbitrary lexicographic tie-break 를 수행하지 않고 bootstrap 을 중단하고 fault 를 surface 해야 한다.

6. **선택된 anchor 는 현재 bootstrap session 동안 고정한다.**
   - snapshot sync 도중 더 높은 valid finalized suggestion 이 새로 보이더라도, in-progress anchor 를 버리고 새 snapshot 을 처음부터 다시 시작하는 것을 baseline 동작으로 두지 않는다.
   - current session 은 먼저 선택된 anchor 의 snapshot completion 을 목표로 진행한다.
   - newer finalized tip 은 current anchor 이후의 `forward catch-up` target 으로 흡수한다.
   - explicit operator action, fatal verification failure, local restart policy 같은 예외 상황이 아닌 한 mid-session re-anchor 는 baseline 에서 허용하지 않는다.

7. **snapshot sync 의 canonical anchor 는 선택된 finalized block 의 `stateRoot` 다.**
   - consumer 는 anchor `stateRoot` hash 에 대응하는 trie root node 를 먼저 가져오고, 이후 content-addressed child hash 를 따라 자손 node 를 반복적으로 fetch 한다.
   - snapshot trie node fetch 는 여러 peer 에서 섞어 받아도 된다. 동일 node hash 에 대한 content-addressed verification 이 canonical admissibility rule 이다.
   - snapshot completion 은 "anchor `stateRoot` 에서 도달 가능한 node graph 가 local store 에 완전히 닫히고, root commitment re-verification 이 성공한 상태" 로 정의한다.
   - content-addressed verification 만으로 snapshot liveness 가 보장되지는 않는다. peer 가 valid 하지만 incomplete 한 subtree 만 계속 제공하거나 일부 subtree 를 withholding 할 수 있으므로, runtime 은 retry, peer switching, timeout, scoring 같은 liveness policy 를 함께 가져야 한다.
   - snapshot node fetch 는 ADR-0016 의 parent directional session 과 동일 auth context 에 바인딩되어야 한다.

8. **anchor 이후 `forward catch-up` 은 finalized-only proposal API 가 아니라 기존 proposal/vote/tx artifact plane 위에서 수행한다.**
   - `Proposal` retrieval API 자체를 finalized-aware 로 바꾸지 않는다.
   - 일반 proposal retrieval, replay, request-by-id surface 는 그대로 generic gossip/consensus path 로 유지한다.
   - anchor 이후 새로운 proposal 을 받았을 때 receiver 는 proposal 이 commit 한 canonical tx hash set 을 보고 missing tx payload 를 `tx` topic anti-entropy 로 요청할 수 있다.
   - anchor state 가 준비되어 있고, anchor 이후 proposal/header 와 tx payload 를 충분히 모으면 node 는 block body 와 post-anchor state transition 을 local replay 로 재구성할 수 있다.
   - 따라서 anchor 이후 state trie node 전체를 다시 remote fetch 하는 것을 baseline mandatory contract 로 두지 않는다.
   - node 가 anchor 이후 이미 지나간 proposal artifact 자체를 놓쳤다면, 일반 proposal replay/backfill surface 를 같은 gossip substrate 위에서 사용해 따라잡는다. 이 retrieval surface 는 finalized 전용 API 로 분기하지 않는다.

9. **consensus participation readiness 는 `snapshot complete` 를 baseline gate 로 두되, proposal별 vote readiness 는 data sufficiency 에 따라 개별적으로 판정한다.**
   - snapshot 이 끝나고 local node 가 anchor state 위에서 proposal validation/replay 를 수행할 수 있으면 consensus participation 을 시작할 수 있다.
   - 특정 proposal 검증에 필요한 tx 나 proposal artifact 가 아직 부족하면 node 는 계속 fetch 를 진행하면서 해당 proposal 에 대한 vote 를 보류할 수 있다.
   - anchor 와 current frontier 사이에 아직 replay 되지 않은 gap window 가 남아 있는 동안, bootstrap node 는 relay/fetch/replay 는 계속 수행하되 자신이 개별 proposal readiness 를 만족하지 못한 proposal 에 대해서는 quorum contributor 로 가정되지 않는다.
   - baseline 운영 가정은 network 가 bootstrap 중인 node 의 일시적 vote withholding 없이도 progress 할 수 있을 만큼 충분한 ready validator 를 이미 보유한다는 것이다. exact rolling maintenance, validator admission, emergency promotion policy 는 ADR-0018 또는 follow-up 운영 문서가 소유한다.
   - 즉 readiness 는 "historical backfill 완료" 가 아니라 "anchor state 가 준비되어 있고, 현재 frontier proposal 을 필요한 만큼 따라잡을 수 있는가" 에 의해 판정한다.

10. **anchor 이전 `historical backfill` 은 background objective 로 유지한다.**
   - node 는 anchor 이전 history 를 genesis 방향으로 채우는 것을 목표로 계속 동작할 수 있다.
   - 그러나 이 작업은 initial validator readiness 의 prerequisite 가 아니다.
   - background historical backfill 은 낮은 우선순위로 실행할 수 있고, consensus participation 이 시작된 뒤에도 계속 진행할 수 있다.
   - primary 목적은 archive completeness, auditability, explorer/query, local disaster recovery surface 다.

11. **bootstrap 관련 서비스는 runtime-owned, session-bound service family 로 유지한다.**
    - `best finalized block suggestion`
    - snapshot trie node fetch
    - forward proposal replay/backfill
    - historical backfill
    - 위 서비스들은 모두 ADR-0016 directional session 과 동일 peer authentication context 아래에 묶여야 한다.
    - parent directional session 이 종료되거나 revoke 되면 그 session 에 종속된 bootstrap capability 도 더 이상 새 data 를 승인해서는 안 된다.
    - configured peer identity binding, session-bound capability ownership, parent-session revoke cascade semantic baseline 은 ADR-0024 가 소유한다.

12. **shipped tx-set-carrying proposal baseline 은 post-anchor body availability 의 기본 seam 으로 사용한다.**
    - `Proposal` 이 canonical tx hash set 을 sign-bytes 와 identity input 에 포함하므로, malicious relay 가 anchor 이후 proposal membership 를 임의로 바꾸는 공격을 body fetch seam 밖에서 허용하지 않는다.
    - validator 는 local replay 로 body membership 와 execution result 를 재구성하고, ADR-0019 `bodyRoot` 및 ADR-0020 body-level rule 위에서 필요한 검증을 수행할 수 있다.
    - 따라서 새 ADR 의 baseline mandatory follow-up 은 "best finalized anchor discovery" 와 "snapshot trie node fetch" 이며, post-anchor full block body transfer 를 1차 필수 조건으로 두지 않는다.

## Consequences
- 신규 노드는 일반 proposal retrieval API 를 finalized-aware 로 다시 설계하지 않고도 verifiable finalized anchor 를 찾을 수 있다.
- snapshot anchor selection 이 "highest claimed tip" 이 아니라 "highest locally verifiable finalized suggestion" 으로 고정되므로 byzantine/stale peer 에 덜 취약해진다.
- bootstrap 은 완전히 trustless zero-state discovery 가 아니라 최소 하나의 trusted bootstrap root 를 필요로 한다.
- anchor 를 session 동안 고정하므로 repeated restart 없이 time-to-readiness 를 줄일 수 있다.
- anchor 이후 catch-up 은 이미 landed 한 tx-set-carrying proposal 과 `tx` anti-entropy seam 을 재사용하므로, post-anchor body transfer mandatory contract 를 늦출 수 있다.
- bootstrap 중인 validator 는 gap window 동안 일부 proposal vote 를 보류할 수 있으므로, 운영 측면에서는 이미 ready 한 quorum 이 남아 있다는 가정이 필요하다.
- snapshot trie fetch 는 content-addressed integrity 는 강하지만 withholding 에 대한 liveness policy 를 별도로 요구한다.
- historical backfill 을 readiness 와 분리하므로 신규 validator 는 archive completeness 를 기다리지 않고 network 에 합류할 수 있다.
- 대신 `FinalizedProof` wire encoding/transport optimization, concrete checkpoint/root bundle shape, runtime historical validator-set lookup integration, snapshot node batching/rate-limit, proposal replay budget 같은 follow-up spec 이 더 필요해진다.
- conflicting valid finalized suggestion 을 explicit safety fault 로 surface 하므로, bootstrap path 가 consensus safety anomaly 를 조용히 삼키지 않게 된다.

## Implementation Status
- `2026-04-05` 기준 shipped JVM baseline 은 runtime-owned `FinalizedAnchorSuggestion` discovery/verification, snapshot metadata + trie persistence seam, anchor-pinned bootstrap coordinator, tx-aware forward catch-up vote-hold gating, low-priority historical backfill worker, 그리고 관련 diagnostics/test coverage 까지 landed 했다.
- shipped bootstrap trust root 는 현재 `HotStuffBootstrapConfig.validatorSet` 과 동등한 static validator-set baseline 이다.
- validator-set rotation continuity, trust-root class/precedence, historical validator-set lookup semantic baseline 은 ADR-0023 에서 drafted 됐지만, runtime integration 은 아직 follow-up 이다.
- session-bound bootstrap capability authorization, configured peer identity binding, parent-session revoke cascade semantic baseline 은 ADR-0024 에서 drafted 됐고, shipped transport 는 parent session open-state revalidation path 를 이미 사용한다.
- operator checkpoint / weak-subjectivity bootstrap material concrete format, archive-grade accelerated backfill, peer scoring/bandwidth shaping 은 여전히 follow-up 범위다.

## Rejected Alternatives
1. **가장 높다고 주장된 block/header 를 proof 없이 anchor 로 채택한다**
   - 구현은 단순해 보이지만 stale peer 나 byzantine peer 에 쉽게 흔들린다.
   - snapshot sync 는 expensive operation 이므로 unverifiable tip claim 에 기대는 것은 부적절하다.

2. **peer 가 제시한 validator set 또는 finalized proof 를 추가 trust root 없이 그대로 신뢰한다**
   - initial bootstrap paradox 를 해결하지 못하고, malicious peer 가 validator-set authority 를 위조할 여지를 남긴다.
   - bootstrap verification 은 trusted root 와 validator-set continuity 규칙을 전제로 해야 한다.

3. **proposal retrieval API 자체를 finalized 전용/비finalized 전용으로 나눈다**
   - 일반 proposal dissemination, replay, request-by-id contract 까지 finality-aware branch 로 복잡해진다.
   - bootstrap 에 필요한 것은 general proposal read path 재설계가 아니라 best finalized anchor discovery surface 다.

4. **더 높은 finalized suggestion 을 볼 때마다 즉시 snapshot anchor 를 갈아탄다**
   - bootstrap bandwidth 와 completion latency 가 과도하게 흔들린다.
   - current anchor 완성 후 forward catch-up 으로 흡수하는 편이 더 안정적이다.

5. **anchor 이전 genesis backfill 완료 전까지 consensus participation 을 금지한다**
   - validator readiness 와 archive completeness 를 불필요하게 결합한다.
   - snapshot complete 이후 frontier catch-up 이 가능하면 consensus participation 자체는 시작할 수 있다.

6. **anchor 이후도 full block body 또는 full post-state trie 를 모두 원격 전송해야 한다고 본다**
   - shipped proposal tx-set seam 과 `tx` anti-entropy, local replay capability 를 활용하지 못한다.
   - baseline mandatory follow-up 은 snapshot anchor discovery 와 trie node fetch 이고, post-anchor full transfer 는 optional optimization 또는 별도 follow-up 으로 남기는 편이 낫다.

## Follow-Up
- concrete implementation sequence 는 `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md` 에서 추적한다.
- **Phase 1 / blocking**
  - 이 ADR 에서 고정한 `FinalizedProof(child, grandchild)` semantic minimum 을 기준으로, anchor proposal `P0` 와 proof bundle(`P1`, `P2`)을 `best finalized block suggestion` response 에 어떻게 encode / batch / inline 할지 wire contract 를 별도 spec 또는 implementation plan 에서 고정한다.
  - trusted bootstrap root class / precedence 와 validator-set continuity semantic baseline 은 ADR-0023 이 소유한다. plan `0007` 은 concrete checkpoint/root bundle format 과 runtime integration 만 follow-up 으로 남긴다.
  - historical validator-set lookup seam 의 semantic ownership 은 ADR-0023 이 가진다. plan `0007` 은 과거 finalized evidence 검증 시 current validator set 가정에 의존하지 않도록 concrete runtime/store seam 을 follow-up 한다.
  - configured peer identity binding, session-bound bootstrap capability authorization, parent-session revoke cascade semantic baseline 은 ADR-0024 가 소유한다. plan `0007` 은 concrete transport/runtime enforcement 와 diagnostics 만 follow-up 으로 남긴다.
  - `best finalized block suggestion`, snapshot trie node fetch, proposal replay/backfill, historical backfill 의 concrete runtime/service interface 와 rejection class 를 implementation plan 에서 구체화한다. 이때 bootstrap 시작 시점의 finalized anchor 검증은 self-contained suggestion response 만으로 시작할 수 있어야 하고, normal proposal/vote fetch plane 은 그 이후 catch-up 단계에서 재사용한다.
- **Phase 2 / baseline-hardening**
  - anchor discovery retry/backoff, no-candidate diagnostics, bootstrap pause/resume policy 를 implementation plan 에서 구체화한다.
  - snapshot trie node fetch 의 batching, concurrency, retry budget, peer scoring, bandwidth cap 을 follow-up plan 에서 고정한다.
  - forward catch-up readiness metric, vote-hold diagnostics, background historical backfill observability 를 runtime diagnostics 에 반영한다.
  - 필요하면 archive node 또는 audit-heavy deployment 를 위한 accelerated historical backfill optimization 을 별도 ADR 또는 plan 으로 분리한다.

## References
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](0016-multiplexed-gossip-session-sync.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0019: Canonical Block Header And Application-Neutral Block View](0019-canonical-block-header-and-application-neutral-block-view.md)
- [ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams](0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md)
- [ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots](0023-validator-set-rotation-and-bootstrap-trust-roots.md)
- [ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization](0024-static-topology-peer-identity-binding-and-session-bound-capability-authorization.md)
- [0003 - Multiplexed Gossip Session Sync Plan](../plans/0003-multiplexed-gossip-session-sync-plan.md)
- [0004 - HotStuff Consensus Without Threshold Signatures Plan](../plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md)
- [0005 - Canonical Block Structure Migration Plan](../plans/0005-canonical-block-structure-migration-plan.md)
- [0006 - Conflict-Free Block Scheduling Plan](../plans/0006-conflict-free-block-scheduling-plan.md)
- [0007 - Snapshot Sync And Background Backfill Plan](../plans/0007-snapshot-sync-and-background-backfill-plan.md)
