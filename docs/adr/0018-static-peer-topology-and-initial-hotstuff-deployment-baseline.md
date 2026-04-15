# ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline

## Status
Accepted

## Context
- ADR-0016은 transport-neutral gossip/session substrate를 고정하고, ADR-0017은 HotStuff non-threshold-signature artifact semantics를 고정한다.
- 그러나 실제 첫 deployment를 어떻게 시작할지에 대해서는 peer discovery, topology management, validator admission, node role, block cadence target 같은 운영 baseline이 따로 필요하다.
- 현재 시점의 요구는 dynamic peer discovery ecosystem보다, 제한된 수의 노드를 예측 가능한 topology 아래에서 빠르게 운영하는 것이다.
- 또한 HotStuff validator를 같은 데이터센터 안에 모아 낮은 latency를 확보하고, 일부 read-only node를 다른 데이터센터에 둬서 장애 시 운영자가 role을 바꿔 대응하는 형태가 초기 운영 모델로 더 현실적이다.
- 이 운영 baseline은 gossip substrate 자체의 wire contract나 HotStuff artifact identity와는 다른 관심사이므로 별도 ADR로 고정하는 편이 낫다.
- `2026-04-06` 기준 이 문서가 정의한 static peer config bootstrap, validator/audit role 분리, same-DC validator placement, operator-managed key relocation baseline 은 이미 `0003`, `0004`, `0007`, `ADR-0021` 에서 current shipped reference baseline 으로 소비되고 있다.

## Decision
1. **초기 node registry와 direct-neighbor topology는 static config로 관리한다.**
   - JVM baseline deployment에서는 `application.conf` 또는 동등한 static configuration source에 node 목록을 명시한다.
   - 각 node는 최소한 stable node identity, network address, role metadata, direct-neighbor set을 config에서 얻을 수 있어야 한다.
   - initial deployment baseline에서는 dynamic peer discovery나 peer scoring에 의존하지 않는다.

2. **local node role은 `validator`와 `audit` 두 종류를 둔다.**
   - `validator`는 active validator set에 포함되어 proposal/vote를 emit 하고 quorum 계산에 참여하는 node다.
   - `audit` node는 proposal/vote/QC와 기타 replicated artifact를 수신, 검증, 저장, 재전송할 수 있지만 quorum 계산에는 참여하지 않고 local consensus vote/proposal을 emit 하지 않는다.
   - gossip/session substrate는 두 role 모두에 대해 동일한 transport/session contract를 재사용할 수 있어야 하며, role-specific emission policy는 consensus/runtime layer가 소유한다.

3. **validator node는 같은 데이터센터 안에 배치하는 것을 초기 운영 baseline으로 둔다.**
   - 목적은 proposal/vote/QC propagation latency와 pacemaker variance를 최소화하는 것이다.
   - cross-DC validator quorum을 baseline으로 최적화하지 않는다.
   - remote data center에는 일부 `audit` node를 둘 수 있다.

4. **audit node의 validator 승격은 explicit operator-managed reconfiguration으로만 처리한다.**
   - 장애 대응 시 remote `audit` node 일부를 `validator`로 바꿀 수 있다.
   - initial deployment baseline에서는 관리자가 모든 validator identity에 대응하는 consensus private key material을 관리할 수 있다.
   - 위 전환은 automatic runtime failover가 아니라 operator가 config와 validator set을 변경하는 운영 절차로 수행한다.
   - promotion은 "새 validator를 즉석에서 만든다"기보다 기존 validator identity의 signing key를 다른 node로 재배치하는 validator identity relocation baseline으로 수행할 수 있다.
   - operator는 승격할 각 node에 validator identity 하나당 consensus private key 하나를 할당하고, 기존 holder node에서는 같은 key를 내려야 한다.
   - 같은 validator private key가 old holder와 new holder에 동시에 active 상태로 존재해서는 안 된다. new holder를 validator로 활성화하기 전 old holder를 fence 하거나 확실히 중지시켜 dual-sign/equivocation 가능성을 없애야 한다.
   - automatic online promotion, automatic leader failover, automatic validator-set reconfiguration protocol은 이 ADR에서 고정하지 않는다.

5. **초기 block production target은 `100ms`로 둔다.**
   - 이는 deployment/performance target이지 gossip wire field나 consensus artifact field가 아니다.
   - 이 target은 same-DC validator placement, proposal/vote immediate flush, bounded block payload, 낮은 local validation overhead를 전제로 한다.
   - ADR-0016의 heartbeat/liveness 기본값은 transport/session liveness 값이며 위 `100ms` target과 직접 동일시해서는 안 된다.
   - exact pacemaker timeout, timeout certificate, new-view pacing contract는 ADR-0022 또는 follow-up protocol spec이 고정한다.

## Consequences
- 초기 운영은 static config만으로 시작할 수 있어 peer discovery 구현을 뒤로 미룰 수 있다.
- validator와 audit node 역할을 분리하므로 same-DC quorum cluster와 remote read-only observer를 함께 운용할 수 있다.
- 장애 대응용 audit-to-validator promotion 경로를 문서화할 수 있지만, automatic failover complexity는 baseline에서 제외된다.
- operator-managed key relocation으로 remote audit node를 빠르게 validator holder로 전환할 수 있다.
- `100ms` target을 운영 baseline으로 드러내므로 validator placement와 QoS tuning 방향이 명확해진다.
- 대신 관리자 또는 운영 key store가 모든 validator private key를 보유하므로 key custody risk가 커지고, fencing 실패 시 equivocation 위험이 직접 operational incident로 이어진다.
- 반면 dynamic topology, automatic promotion, online validator-set reconfiguration은 후속 설계가 필요하다.

## Rejected Alternatives
1. **처음부터 dynamic peer discovery를 baseline으로 둔다**
   - 장기적으로는 가능하지만, 현재는 운영 단순성과 예측 가능성이 더 중요하다.
   - 첫 deployment를 discovery subsystem readiness에 묶을 필요가 없다.

2. **모든 node를 validator로 둔다**
   - 운영은 단순해 보이지만 quorum latency와 failure domain이 커진다.
   - remote observer와 disaster-recovery node까지 같은 quorum contract에 넣는 것은 초기 목표와 맞지 않는다.

3. **audit node 승격을 자동 runtime failover로 둔다**
   - 자동 승격은 validator-set reconfiguration, pacemaker, safety/liveness rollback policy까지 함께 고정해야 한다.
   - 현재 baseline에는 과도하다.

4. **validator key를 old/new node에 동시에 남겨둔 채 승격한다**
   - 동일 validator private key가 두 node에 동시에 살아 있으면 double-sign/equivocation 위험이 즉시 생긴다.
   - baseline은 explicit key relocation과 old-holder fencing을 요구한다.

5. **`100ms`를 protocol invariant로 고정한다**
   - 현재는 deployment target으로 두는 편이 더 현실적이다.
   - exact timeout/new-view contract 없이 invariant처럼 쓰면 숨은 가정만 늘어난다.

## Follow-Up
- static peer config schema와 loader wiring은 `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`에서 구현한다.
- validator/audit local role gating과 read-only audit follow path는 `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`에서 구현한다.
- pacemaker, timeout vote, new-view, `100ms` deployment target과 consensus timeout의 timing-domain separation baseline은 ADR-0022가 소유한다.
- validator-set rotation continuity, bootstrap trust-root class, historical validator-set lookup baseline은 ADR-0023이 소유한다. 이 ADR의 operator-managed key relocation은 existing validator identity relocation baseline으로 남고, validator membership / ordering change 자체의 canonical continuity contract를 직접 고정하지는 않는다.
- automatic audit-to-validator promotion이나 fully automated online validator-set reconfiguration policy가 필요해지면 별도 운영 ADR을 작성한다.
- operator-managed raw key custody를 대체할 KMS/HSM/remote signer baseline이 필요해지면 별도 ADR을 작성한다.

## References
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](0016-multiplexed-gossip-session-sync.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots](0023-validator-set-rotation-and-bootstrap-trust-roots.md)
- [0003 - Multiplexed Gossip Session Sync Plan](../plans/0003-multiplexed-gossip-session-sync-plan.md)
- [0004 - HotStuff Consensus Without Threshold Signatures Plan](../plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md)
