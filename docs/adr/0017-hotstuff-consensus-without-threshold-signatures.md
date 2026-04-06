# ADR-0017: HotStuff Consensus Without Threshold Signatures

## Status
Accepted

## Context
- `sigilaris`는 gossip/session substrate와 consensus artifact semantics를 별도 문서로 관리하기로 했다. transport-neutral gossip/session 규약은 ADR-0016이 소유한다.
- `2026-04-01` 기준 ADR-0016 아래의 tx-topic gossip/session HTTP baseline은 이미 shipped 되었고, static peer topology bootstrap, topic-neutral producer polling/QoS seam, half-open recovery baseline까지 `sigilaris-node-jvm`에 landed 되었다.
- `2026-04-02` 기준 HotStuff non-threshold baseline도 `sigilaris-node-jvm`에 landed 되었고, static-topology-compatible bootstrap, explicit bootstrap/service seam, audit follower relay, same-window `requestById` retry budget, vote-topic regression lock, dependency boundary test가 shipped baseline에 포함된다.
- 현재 합의 알고리즘 후보는 BLS threshold signature를 적용하지 않은 HotStuff 계열이다.
- threshold signature가 없으면 quorum certificate는 단일 aggregated signature가 아니라 개별 validator vote 집합 또는 그와 동등한 검증 가능 구조로 표현되어야 한다.
- 이 경우 proposal, vote, quorum certificate의 identity, sign-bytes, validation rule을 gossip envelope와 별도로 고정해야 한다.
- block hash, proposal id, vote id를 같은 값으로 취급하면 dedup, replay, exact known-set sync, QC assembly, leader justification 검증이 서로 엉킬 수 있다.
- 또한 `tx` anti-entropy와 consensus artifact sync는 같은 gossip substrate 위에서 공존할 수 있지만, consensus artifact의 의미론은 gossip runtime이 아니라 consensus runtime이 소유해야 한다.
- 따라서 consensus follow-up은 shipped tx runtime을 재작성하는 대신 ADR-0016 substrate seam과 generic producer/polling hook을 재사용하는 방향으로 붙어야 한다.
- 초기 deployment baseline은 static peer topology, validator/audit node role 분리, same-DC validator 배치, `100ms` block production target을 전제로 할 수 있다. 이 운영 baseline은 ADR-0018이 소유하고, 이 ADR은 그 위에서 필요한 consensus artifact semantics를 고정한다.

## Decision
1. **Sigilaris consensus baseline은 threshold signature를 사용하지 않는 HotStuff 계열 프로토콜로 둔다.**
   - baseline safety model은 validator가 individually signed vote를 발행하고, quorum이 모이면 QC를 형성하는 구조다.
   - 이 ADR의 baseline은 chained HotStuff 계열을 기본값으로 둔다. 따라서 steady-state에서는 proposal당 하나의 canonical vote artifact를 기준으로 모델링한다.
   - 향후 multi-phase variant나 다른 pacemaker contract를 도입하더라도, 그것은 이 baseline을 명시적으로 supersede 하거나 확장하는 후속 ADR이 소유한다.

2. **local node role은 ADR-0018이 정의한 `validator` / `audit` 구분을 따른다.**
   - consensus runtime 관점에서 `validator`만 active validator set에 포함될 수 있고 proposal/vote를 emit 하며 quorum 계산에 참여한다.
   - `audit` node는 proposal/vote/QC를 수신, 검증, 저장, 재전송할 수 있지만 quorum 계산에는 참여하지 않고 local proposal/vote를 emit 하지 않는다.

3. **consensus artifact family는 block, proposal, vote, QC를 구분한다.**
   - `block`은 state transition payload와 parent linkage를 담는 실행 대상 artifact다.
   - `proposal`은 특정 `(chainId, height, view)`에서 validator에게 제안되는 consensus artifact다.
   - `vote`는 validator가 특정 proposal에 대해 발행하는 individually signed approval artifact다.
   - `QC`는 validator set window 안에서 quorum을 만족하는 vote 집합 또는 그와 동등한 검증 가능 certificate다.

4. **block identity, proposal identity, vote identity는 서로 다른 contract로 취급한다.**
   - `BlockId`는 ADR-0019 canonical `BlockHeader` bytes의 deterministic hash다.
   - `ProposalId`는 canonical proposal bytes의 deterministic hash다.
   - `VoteId`는 canonical vote bytes의 deterministic hash다.
   - 구현은 `ProposalId == BlockId`라고 암묵적으로 가정해서는 안 된다. proposal이 block 외에 justify QC, proposer binding, view metadata를 서명으로 commit 하면 둘은 다른 값일 수 있다.
   - proposal이 가리키는 실행 대상은 `BlockId`이고, proposal artifact 자체의 dedup/known-set/replay는 `ProposalId` 기준으로 수행한다.

5. **proposal과 vote는 gossip envelope가 아니라 canonical whole-value deterministic encoding에 서명한다.**
   - proposal sign-bytes는 최소한 `chainId`, `height`, `view`, proposer identity, `validatorSetHash`, target `BlockId`, justify QC subject를 semantic input으로 포함해야 한다.
   - proposal이 full body를 직접 운반하지 않는 baseline에서도, proposal이 해당 block의 transaction membership를 canonical tx hash set으로 commit 한다면 그 tx hash set 역시 proposal sign-bytes와 identity input에 포함되어야 한다.
   - vote sign-bytes는 최소한 `chainId`, `height`, `view`, voter identity, `validatorSetHash`, target `ProposalId`를 semantic input으로 포함해야 한다.
   - chained HotStuff baseline에서는 steady-state vote type을 하나로 둔다. 따라서 baseline vote identity는 별도 `phase` field 없이도 canonical하게 정의할 수 있다.
   - 후속 variant가 proposal당 여러 vote phase를 도입하면, 그때는 `phase` discriminator가 vote identity와 sign-bytes에 mandatory input으로 추가되어야 한다.

6. **view와 validator set window는 QC validation과 gossip exact-known-set sync의 핵심 key다.**
   - HotStuff runtime의 canonical progress dimension은 `view`다.
   - gossip substrate는 이 progress dimension을 HotStuff topic contract가 해석하는 topic-specific metadata로만 취급해야 하며, 자체적으로 다른 consensus progress 용어로 재해석해서는 안 된다.
   - proposal, vote, QC 검증은 최소한 `(chainId, height, view, validatorSetHash)` window 안에서 수행한다.
   - `validatorSetHash`는 해당 view에서 quorum membership을 결정하는 validator set commitment다.
   - quorum rule은 baseline으로 active validator set의 `n - floor((n - 1) / 3)` vote를 요구한다. `n = 3f + 1` 배치에서는 이는 `2f + 1`과 같다.

7. **QC는 threshold signature가 아니라 exact validator vote set을 전제로 검증한다.**
   - receiver는 QC를 단일 aggregate signature로 검증하지 않는다.
   - receiver는 QC가 포함하는 vote 집합이 validator set window와 quorum rule을 만족하는지 검증해야 한다.
   - 동일 validator의 중복 vote는 QC cardinality에 한 번만 계산한다.
   - conflicting proposal에 대한 equivocation detection key는 baseline으로 `(chainId, validatorId, height, view)`다. 같은 key에서 서로 다른 target `ProposalId`가 관찰되면 equivocation으로 판정한다.

8. **gossip substrate 위의 consensus topic은 `consensus.proposal`과 `consensus.vote`를 baseline으로 사용한다.**
   - proposal artifact는 ADR-0016의 generic event envelope 위에서 `topic = consensus.proposal`로 운반한다.
   - vote artifact는 ADR-0016의 generic event envelope 위에서 `topic = consensus.vote`로 운반한다.
   - proposal exact known-set sync는 `(chainId, height, view, validatorSetHash)` window 안의 `ProposalId` 집합 기준으로 수행한다.
   - vote exact known-set sync는 같은 window 안의 `VoteId` 집합 기준으로 수행한다.
   - bounded explicit fetch가 필요하면 ADR-0016의 `requestById` control op를 사용해 missing `ProposalId` 또는 `VoteId`를 요청할 수 있다.
   - proposal이 canonical tx hash set만 운반하고 full tx payload를 직접 싣지 않는 경우, receiver는 그 tx id set을 기준으로 별도 `tx` topic의 `requestById.tx` control op를 사용해 missing tx payload를 요청할 수 있다.
   - initial baseline request cap 은 proposal `128`, vote `512`, same window retry budget `2`회다.

9. **QC dissemination baseline은 self-contained justification을 선호한다.**
   - 어떤 proposal이 justify QC를 필요로 하면, receiver가 그 proposal 하나만으로 validation을 시작할 수 있을 정도의 QC subject를 함께 운반하는 것을 baseline으로 둔다.
   - implementation은 저장된 vote set 또는 cached QC object를 내부적으로 재사용할 수 있지만, protocol contract 차원에서는 receiver가 정당화 정보를 reconstruct하지 못해 validation이 막히는 hidden dependency를 두지 않는다.
   - 별도 `consensus.qc` gossip topic은 baseline에 두지 않는다. 필요해지면 follow-up ADR에서 추가한다.

10. **initial deployment target block production interval은 ADR-0018이 고정한 `100ms` baseline을 전제로 한다.**
   - 이는 initial deployment/performance target이지 wire field나 consensus envelope field가 아니다.
   - consensus runtime은 위 target을 만족할 수 있도록 proposal/vote immediate flush와 bounded local processing budget을 가정할 수 있다.
   - ADR-0016의 heartbeat/liveness 기본값은 transport/session liveness 값이며, 위 `100ms` cadence target과 직접 동일시해서는 안 된다.
   - exact pacemaker timeout, timeout certificate, new-view pacing contract는 ADR-0022와 그 후속 spec / implementation plan 이 고정한다.

11. **QoS와 scheduling은 gossip substrate 위에서 consensus artifact를 우선시해야 한다.**
   - proposal/vote delivery는 `tx` backlog에 막혀서는 안 된다.
   - fixed even/odd time window처럼 proposal과 vote를 교대로만 전송하는 방식은 implementation-local optimization일 수 있지만, HotStuff correctness/liveness contract로 고정해서는 안 된다.
   - runtime은 proposal과 vote를 immediate flush 또는 동등한 high-priority lane으로 다룰 수 있어야 한다.

12. **pacemaker, timeout vote, new-view wire contract는 ADR-0022로 분리한다.**
   - baseline HotStuff artifact contract는 proposal, vote, QC와 그 identity/sign-bytes ownership을 먼저 고정한다.
   - timeout vote, timeout certificate, new-view aggregation, leader rotation policy의 exact wire contract는 ADR-0022와 그 후속 spec / implementation plan 에서 구체화한다.
   - ADR-0022 acceptance 및 concrete runtime integration 전까지 implementation은 pacemaker artifact를 out-of-band local contract로 두거나 experimental internal contract로만 사용해야 한다.

## Consequences
- gossip/session substrate는 ADR-0016 아래에서 안정적으로 유지하면서, HotStuff artifact semantics만 별도로 진화시킬 수 있다.
- `runtime.gossip` 와 `runtime.consensus.hotstuff` 사이의 ownership boundary를 import-rule test로 고정해, topic-neutral substrate와 topic-specific consensus semantics가 다시 섞이는 회귀를 줄인다.
- threshold signature를 도입하지 않아도 proposal/vote/QC validation contract를 명시적으로 유지할 수 있다.
- proposal id, vote id, block id를 분리하므로 dedup, exact known-set sync, QC assembly, replay semantics가 더 명확해진다.
- validator와 audit node의 local behavior를 구분하므로 read-only follower나 disaster-recovery observer를 같은 artifact contract 위에서 운용할 수 있다.
- `100ms` target을 deployment baseline으로 드러내므로 same-DC validator placement와 proposal/vote immediate flush 요구가 문서상으로 명시된다.
- 대신 QC payload가 aggregate signature보다 커질 수 있고, validator identity / vote set 검증 비용이 늘어난다.
- pacemaker/timeouts를 후속으로 미루므로 full production liveness contract는 별도 문서가 더 필요하다.

## Rejected Alternatives
1. **proposal id를 block hash와 동일하다고 가정한다**
   - 구현이 단순해 보이지만, proposal이 justify QC나 proposer/view binding을 함께 서명으로 commit 하면 identity가 충돌한다.
   - block execution identity와 proposal transport identity는 분리하는 편이 안전하다.

2. **QC를 별도 aggregate signature artifact라고 가정한다**
   - BLS threshold signature가 없는 baseline과 맞지 않는다.
   - non-threshold mode에서는 QC가 vote 집합 또는 그와 동등한 검증 구조를 전제로 해야 한다.

3. **consensus semantics를 gossip ADR 안에 계속 둔다**
   - gossip substrate 변경과 consensus 알고리즘 변경이 한 문서에서 같이 흔들린다.
   - transport/session policy와 artifact 의미론을 분리하는 편이 장기적으로 덜 취약하다.

4. **처음부터 timeout/new-view까지 전부 고정한다**
   - 장기적으로는 필요하지만, 현재는 proposal/vote/QC identity와 gossip integration contract를 먼저 고정하는 편이 낫다.
   - pacemaker wire contract는 ADR-0022로 분리하는 것이 구현 순서상도 더 현실적이다.

## Follow-Up
- HotStuff proposal/vote/QC runtime, concrete bootstrap/service seam, audit relay, dependency boundary test는 landed baseline이다. pacemaker / timeout vote / timeout certificate / new-view / leader rotation semantic baseline 은 ADR-0022가 소유하고, `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`는 그 구현 follow-up 을 관리한다.
- `2026-04-04` 기준 shipped baseline proposal artifact는 ADR-0019 header-first contract를 유지하면서 canonical tx hash set을 함께 운반한다.
- canonical block header/body contract와 application-neutral block view follow-up은 ADR-0019와 `docs/plans/0005-canonical-block-structure-migration-plan.md`가 소유한다.
- timeout vote, timeout certificate, new-view wire contract, pacemaker policy는 ADR-0022와 그 후속 spec / implementation plan 에서 구체화한다.
- canonical deterministic encoding의 exact byte layout과 signer identity canonicalization rule은 implementation 전에 protocol spec 또는 추가 ADR로 고정한다.
- validator set commitment의 exact derivation contract는 follow-up spec에서 고정한다.
- static peer topology, validator/audit deployment role, emergency promotion baseline은 ADR-0018이 소유한다.

## References
- [ADR-0012: Signed Transaction Requirement](0012-signed-transaction-requirement.md)
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](0016-multiplexed-gossip-session-sync.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0019: Canonical Block Header And Application-Neutral Block View](0019-canonical-block-header-and-application-neutral-block-view.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [0003 - Multiplexed Gossip Session Sync Plan](../plans/0003-multiplexed-gossip-session-sync-plan.md)
- [0004 - HotStuff Consensus Without Threshold Signatures Plan](../plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md)
- [0005 - Canonical Block Structure Migration Plan](../plans/0005-canonical-block-structure-migration-plan.md)
