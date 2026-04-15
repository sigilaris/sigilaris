# ADR-0022: HotStuff Pacemaker And View-Change Baseline

## Status
Proposed

## Context
- ADR-0017은 Sigilaris HotStuff baseline의 proposal / vote / QC identity, sign-bytes, validator-set window, gossip integration contract를 고정했지만, pacemaker, timeout vote, timeout certificate, new-view, leader rotation policy는 follow-up으로 남겼다.
- ADR-0018은 static peer topology, validator / audit role, same-DC validator placement, `100ms` block production target을 initial deployment baseline으로 고정했다. 그러나 이 target은 deployment/performance baseline이지, exact pacemaker timeout contract를 대신하지 않는다.
- ADR-0016은 session-open handshake, heartbeat, liveness timeout을 transport/session domain에 고정했다. 이 값들은 peer session health 판정용 기본값이지 consensus view-change timer가 아니다.
- ADR-0021은 bootstrap 을 `anchor discovery -> snapshot sync -> forward catch-up -> historical backfill` 로 고정하고, `ProposalCatchUpReadiness` / `BootstrapVoteReadiness` 와 동등한 vote-hold baseline을 shipped runtime에 반영했다. 즉 current runtime 은 proposal별 data sufficiency 가 충족되기 전 vote emission 을 보류할 수 있지만, 이 readiness hold 와 pacemaker 의 관계는 아직 canonical ADR 로 잠기지 않았다.
- `2026-04-06` 기준 shipped JVM baseline 은 proposal / vote / QC, finalized-anchor bootstrap, vote-hold gating 까지는 landed 되었지만, timeout vote / timeout certificate / new-view / leader rotation 의 semantic owner는 아직 명시적 ADR 로 고정되지 않았다.
- pacemaker contract 가 비어 있으면 `100ms` target, transport heartbeat/liveness, bootstrap readiness hold, leader failover policy 가 implementation-local assumption 으로 섞일 위험이 있다.
- 반대로 이 시점에 validator-set rotation continuity 나 bootstrap trust root 까지 함께 잠그면 scope 가 커진다. authority continuity 는 follow-up ADR-0023이 소유하고, 이 ADR은 current active validator-set baseline 위의 liveness / view-change contract만 고정한다.

## Decision
1. **Sigilaris HotStuff pacemaker 는 consensus-owned view progression layer다.**
   - pacemaker 는 `view` progression, local timeout progression, timeout-driven leader handoff, next-view activation policy를 소유한다.
   - ADR-0017의 proposal / vote / QC 는 steady-state safety artifact family를 계속 소유한다.
   - ADR-0016의 heartbeat / liveness timeout 은 transport/session health 를 소유하고, ADR-0021의 bootstrap readiness 는 vote eligibility / data sufficiency gate 를 소유한다.
   - transport liveness, bootstrap readiness, pacemaker view progression 을 같은 timer/state machine 으로 취급해서는 안 된다.

2. **pacemaker 는 chained HotStuff baseline 위에 timeout-driven artifact family를 추가한다.**
   - baseline pacemaker family 는 `TimeoutVote`, `TimeoutCertificate`, `NewView` semantic family 를 포함한다.
   - `TimeoutVote` 는 validator 가 특정 HotStuff window 에 대해 local timeout 을 선언하는 signed pacemaker artifact 다.
   - `TimeoutCertificate` 는 같은 window 에 대한 quorum-sized timeout vote 집합 또는 그와 동등한 검증 가능 certificate 다.
   - `NewView` 는 next leader 가 새 view 를 시작하는 데 필요한 pacemaker handoff semantic 이다. 최소한 highest known QC 와 timeout-driven next-view proof 를 운반해야 한다.
   - exact wire shape 가 explicit `NewView` artifact 인지, `TimeoutVote` + `TimeoutCertificate` 조합에서 leader-local 로 materialize 되는지 는 follow-up spec 또는 implementation plan 이 고정한다.

3. **timeout-driven pacemaker subject 는 proposal/vote/QC window 와 같은 progress key 위에 정의한다.**
   - baseline key 는 `(chainId, height, view, validatorSetHash)` 다.
   - pacemaker 는 block height 를 버리고 pure view-only clock 으로 재정의하지 않는다.
   - current static-validator-set baseline 에서는 active validator set 과 `validatorSetHash` 가 이미 known input 이고, pacemaker 는 그 input 을 소비한다.
   - future validator-set rotation continuity 와 historical lookup 은 ADR-0023 이 소유한다.

4. **timeout vote family 는 proposal vote family 와 별도 contract 로 취급한다.**
   - `TimeoutVote` target 은 `ProposalId` 가 아니다.
   - `TimeoutVote` identity / sign-bytes 는 `VoteId` 와 같지 않다.
   - proposal vote 와 timeout vote 는 다른 semantic family 이므로, 같은 validator 가 같은 view 에서 proposal vote 를 냈다는 사실만으로 timeout vote 를 automatic equivocation 으로 취급하지 않는다.
   - 대신 timeout vote family 안의 equivocation detection key 는 baseline 으로 `(chainId, validatorId, height, view)` 다. 같은 key 에서 서로 다른 timeout subject 가 관찰되면 pacemaker equivocation 으로 판정한다.
   - exact sign-bytes byte layout, artifact id derivation, canonical whole-value encoding 은 follow-up spec 이 고정한다.

5. **`TimeoutCertificate` 는 QC 와 다른 역할을 가지지만 같은 validator-set quorum rule 을 소비한다.**
   - `TimeoutCertificate` quorum cardinality 는 current active validator set 의 `n - floor((n - 1) / 3)` baseline 을 따른다.
   - duplicate validator timeout vote 는 cardinality 에 한 번만 계산한다.
   - `TimeoutCertificate` 는 block justification / finality proof 를 대체하지 않는다.
   - proposal chain safety justification 은 계속 QC 가 소유하고, timeout certificate 는 failed view 를 넘기는 liveness proof 로만 사용한다.

6. **`NewView` 는 next leader 가 self-contained next-view activation 을 시작할 수 있을 정도의 pacemaker handoff 를 제공해야 한다.**
   - next leader 는 `NewView` input 만으로 current highest QC 와 timeout-driven next-view proof 를 검증할 수 있어야 한다.
   - proposal after-timeout path 는 highest QC 와 pacemaker next-view proof 를 모두 구분해서 다뤄야 한다.
   - timeout-driven next-view proof 가 존재해도 proposal justify QC subject 자체를 생략할 수는 없다.
   - exact inline / attachment / fetch-by-id carriage 방식은 follow-up spec 또는 implementation plan 이 고정한다.

7. **leader rotation 은 deterministic function 이고, pacemaker 는 validator ordering input 을 소비만 한다.**
   - baseline leader selection 은 active validator set 의 canonical ordering 위에서 view-dependent deterministic round-robin 을 사용한다.
   - current static baseline 에서는 operator/bootstrap material 이 제공한 validator ordering 을 입력으로 사용한다.
   - pacemaker 는 validator ordering 을 어떻게 historical continuity 로 복원하는지 소유하지 않는다. 그 ownership 은 ADR-0023 이 가진다.
   - transport disconnect, peer scoring, manual operator preference 는 canonical leader selection rule 이 아니다.

8. **view advancement 는 transport signal 이 아니라 valid QC 또는 valid timeout-driven proof 위에서만 일어난다.**
   - local node 는 higher QC, valid `TimeoutCertificate`, valid `NewView` handoff 와 동등한 consensus proof 를 근거로만 higher view 로 advance 할 수 있다.
   - negotiated heartbeat miss, session close, reconnect, peer unreachability 는 operator diagnostics 나 peer-health input 이 될 수는 있어도 그 자체로 canonical view-change proof 는 아니다.
   - pacemaker 는 view regression 을 허용하지 않는다. local observed view 는 monotonic 하게만 증가해야 한다.

9. **bootstrap readiness / vote-hold / partial data sufficiency 는 pacemaker 와 분리된 emission gate 로 유지한다.**
   - ADR-0021 bootstrap lifecycle 과 `BootstrapVoteReadiness` / `ProposalCatchUpReadiness` 동등 gate 는 계속 data sufficiency 와 vote eligibility 를 판정한다.
   - local timeout expiry 만으로 bootstrap hold 나 proposal-specific data hold 를 우회해서는 안 된다.
   - node 가 bootstrap hold 상태이면 proposal vote 뿐 아니라 timeout vote 와 local proposal emission 도 quorum-contributing action 으로 emit 해서는 안 된다.
   - 반면 held node 도 remote proposal / timeout artifact 를 relay, fetch, verify 하고 higher observed view 를 따라갈 수는 있다.
   - 따라서 pacemaker 는 local observed view progression 을 소유하지만, local quorum participation eligibility 자체를 소유하지 않는다.

10. **`100ms` deployment target, pacemaker timeout, transport heartbeat/liveness 는 서로 다른 timing domain 이다.**
    - `100ms` 는 same-DC validator placement 와 low-latency steady-state block cadence 목표다.
    - pacemaker timeout 은 failed leader 또는 stalled view 를 감지하기 위한 consensus-local timer다.
    - session heartbeat / liveness timeout 은 peer session health 와 reconnect / dead 판정을 위한 transport timer 다.
    - exact pacemaker timeout constant, backoff curve, timeout-reset rule, jitter policy 는 follow-up spec 또는 implementation plan 이 고정한다.
    - 위 세 timing domain 을 같은 숫자나 같은 config knob 로 묶는 것은 canonical baseline 이 아니다.

11. **pacemaker artifact dissemination 은 consensus-owned follow-up seam 으로 둔다.**
    - pacemaker artifact 는 ADR-0016 substrate 위를 타더라도 ownership 은 gossip runtime 이 아니라 HotStuff consensus runtime 이 가진다.
    - exact topic naming, exact known-set structure, bounded `requestById` policy, rejection surface, batching policy 는 follow-up spec 또는 implementation plan 이 고정한다.
    - correctness-sensitive pacemaker artifact 는 probabilistic filter 만으로 정합성을 판정하는 baseline 위에 올려서는 안 된다.

12. **이 ADR 은 current static-validator-set baseline 위의 pacemaker / view-change contract 만 고정하고, authority continuity 는 의도적으로 남긴다.**
    - current baseline 에서는 `HotStuffBootstrapConfig.validatorSet` 또는 동등 active validator-set input 을 pacemaker 가 그대로 소비한다.
    - validator-set rotation continuity, historical validator-set lookup, weak-subjectivity/bootstrap trust root 는 ADR-0023 이 소유한다.
    - multi-phase HotStuff variant, threshold-signature pacemaker compression, dynamic leader scoring/fairness 는 이 ADR 의 baseline scope 밖에 둔다.

## Consequences
- proposal / vote / QC baseline 위에 timeout-driven liveness contract 가 별도 owner 를 갖게 된다.
- `100ms` deployment target, transport heartbeat/liveness, pacemaker timeout 이 서로 다른 timer domain 임이 문서상으로 명시된다.
- bootstrap hold 상태의 validator 가 timeout path 를 통해 우회적으로 quorum contributor 가 되는 hidden assumption 을 막을 수 있다.
- next leader activation 에 필요한 `highest QC` 와 timeout-driven proof 의 역할이 분리돼, proposal justify QC 와 pacemaker proof 를 혼동할 위험이 줄어든다.
- deterministic leader rotation baseline 이 문서화되므로, operator policy 나 transport health 를 canonical leader election 과 혼동하지 않게 된다.
- 대신 exact artifact encoding, topic contract, retry budget, timeout constants, backoff curve 는 후속 spec/plan 이 더 필요하다.
- validator-set rotation continuity 를 이 ADR 밖으로 남겼으므로, phase 2 전까지는 pacemaker 가 current active validator-set input 에 의존한다.

## Rejected Alternatives
1. **transport heartbeat/liveness timeout 을 pacemaker timer 로 그대로 쓴다**
   - session health 와 consensus view-change 는 다른 domain 이다.
   - negotiated transport timeout 을 놓친 사실만으로 canonical next-view proof 를 만들 수는 없다.

2. **`TimeoutCertificate` 를 QC 대체물로 본다**
   - timeout certificate 는 failed view 를 넘기는 liveness proof 이지 block justification / finality proof 가 아니다.
   - proposal safety chain 은 계속 QC 가 소유해야 한다.

3. **bootstrap hold 상태여도 timeout vote 는 liveness 를 위해 허용한다**
   - bootstrap / catch-up / data sufficiency hold 를 우회하는 quorum contribution 이 생긴다.
   - current shipped bootstrap readiness baseline 과도 충돌한다.

4. **leader failover 를 transport disconnect 나 operator preference 로 직접 판정한다**
   - consensus proof 가 아니라 deployment-local heuristic 에 의존하게 된다.
   - static topology baseline 에서는 deterministic leader selection 과 explicit view-change proof 를 유지하는 편이 안전하다.

5. **validator-set rotation continuity 와 pacemaker 를 한 ADR 에서 같이 고정한다**
   - authority continuity 와 liveness contract 가 함께 커져 Phase 1/2 경계가 무너진다.
   - current tranche 에서는 pacemaker 가 active validator-set input 을 소비만 하도록 두고, continuity 는 ADR-0023 으로 분리하는 편이 낫다.

## Follow-Up
- concrete pacemaker runtime integration, transport topic/path choice, policy constants, test gate 는 `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md` 또는 그 후속 implementation plan 이 소유한다.
- exact `TimeoutVote` / `TimeoutCertificate` / `NewView` byte layout, artifact id derivation, batching, fetch policy, rejection surface 는 follow-up spec 이 고정한다.
- validator-set rotation continuity, historical validator-set lookup, leader-order continuity source 는 ADR-0023 이 소유한다.
- static topology peer identity binding, session-bound bootstrap capability authorization, parent-session revoke cascade 는 ADR-0024 가 소유한다.
- timeout vote exact-known / request-by-id / replay semantics 가 shipped gossip baseline 에 붙는 시점에는 ADR-0016 substrate contract 와 plan `0003` transport/runtime seam 을 다시 정렬한다.
- multi-phase HotStuff variant, threshold-signature compression, dynamic proposer fairness 가 필요해지면 후속 ADR 이 이 baseline 을 supersede 또는 extend 한다.

## References
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](0016-multiplexed-gossip-session-sync.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions](0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md)
- [0004 - HotStuff Consensus Without Threshold Signatures Plan](../plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md)
- [0007 - Snapshot Sync And Background Backfill Plan](../plans/0007-snapshot-sync-and-background-backfill-plan.md)
- [0008 - Multi-Node Follow-Up ADR Authoring Plan](../plans/0008-multi-node-follow-up-adr-authoring-plan.md)
