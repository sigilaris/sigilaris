# 0009 - Minimal Static Multi-Node Launch Readiness Plan

## Status
Draft

## Created
2026-04-07

## Last Updated
2026-04-07

## Background
- 이 문서는 `0008` ADR tranche 이후, current Sigilaris baseline 으로 "실제로 static multi-node chain 을 띄울 수 있느냐"를 막는 필수 구현 공백만 정리하는 implementation plan 이다.
- `2026-04-07` 기준 shipped baseline 은 아래를 이미 갖고 있다.
  - static topology + direct-neighbor admission gossip substrate
  - binary peer event stream, `consensus.proposal` / `consensus.vote` topic sync, bounded `requestById`
  - HotStuff proposal / vote / QC model, validation, QC assembly, audit relay
  - canonical `BlockHeader` / `BlockBody` / `BlockView`
  - conflict-free block body selection / verification
  - finalized-anchor suggestion, snapshot sync, forward catch-up, historical backfill runtime seam
- 그러나 이 baseline 만으로는 "별도 프로세스로 여러 노드를 띄워 steady-state 로 block 을 생산하고, leader stall 을 넘기고, newcomer 가 실제 ready 로 들어오는" 최소 운영 경로가 아직 닫히지 않았다.
- current confirmed blocker 는 아래 여섯 가지다.
  - pacemaker artifact 는 semantic model / sign / validate 까지는 있으나, concrete dissemination / timer / backoff / leader-activation runtime 이 아직 follow-up 이다.
  - shipped newcomer bootstrap assembly 는 `ProposalCatchUpReadiness` 에 placeholder `forwardCatchUpUnavailable` hold 를 남기고 있어, replayed/live proposal 이 있는 실제 catch-up 경로가 완전히 ready 로 닫히지 않는다.
  - configured `PeerIdentity` 는 아직 concrete transport credential subject 와 강하게 binding 되지 않고, bootstrap capability header 도 stronger cryptographic binding 없이 transport projection placeholder 에 머문다.
  - historical backfill 이 읽어온 proposal 은 runtime-owned archive seam 까지 들어가지만, shipped baseline backing 이 memory-only 라 process restart 시 누적 history 를 잃는다.
  - ADR-0018 이 상정한 operator-managed validator identity relocation baseline 은 문서 semantics 로는 존재하지만, pre-provisioned remote audit node 로 same-validator identity 를 옮겨 fence + key relocation + restart 로 quorum 을 복구하는 concrete repo-local smoke proof 와 runbook 이 아직 없다.
  - repo 안에는 current runtime/transport seam 을 실제 다중 노드 기동 증거로 묶는 concrete reference launch path 가 없다. `sigilaris-node-jvm` 은 lifecycle seam 과 server builder 는 제공하지만, "이 설정으로 노드를 띄우고 서로 붙인다"는 smoke gate 가 아직 없다.
- 반대로 아래 항목들은 중요하지만 "최소 static multi-node launch"의 즉시 blocker 는 아니다.
  - validator-set rotation / checkpoint / weak-subjectivity trust root
  - dynamic discovery / peer scoring
  - archive-grade historical sync / snapshot compression / proof serving
  - KMS / HSM / remote signer
  - production bandwidth shaping / proposer fairness

## Goal
- current static-topology / static-validator-set baseline 위에서 validator node 여러 개를 별도 프로세스로 기동해 자동으로 session 을 맺고 consensus 를 진행할 수 있게 한다.
- leader stall 이나 missing proposal 상황에서 pacemaker timeout / new-view 경로로 liveness 가 유지되게 한다.
- newcomer 또는 restarted node 가 current static trust-root baseline 아래에서 실제 replayed/live proposal 을 소비하며 ready 상태로 진입하게 한다.
- historical backfill 로 읽어온 proposal 이 memory-only retention 에 머물지 않고 local durable storage 에 보존되게 한다.
- operator-managed DR 경로에서 기존 validator identity/key 를 pre-provisioned audit node 로 재배치하고 재시작해 validator set 변경 없이 quorum 을 복구할 수 있게 한다.
- concrete transport credential binding 과 session-bound bootstrap authorization 을 최소 운영 수준으로 닫는다.
- 위 경로를 repo-local reference launch harness 와 smoke/integration gate 로 검증 가능하게 만든다.

## Scope
- pacemaker runtime integration: timeout artifact dissemination, timer/backoff/jitter, leader activation, view progression, bootstrap vote-hold interaction
- concrete newcomer bootstrap readiness consumer path: replayed/live proposal validation, tx sufficiency re-check, vote eligibility advancement
- shipped `HistoricalProposalArchive` 의 local durable backing 및 restart persistence baseline
- pre-provisioned audit node 대상 operator-managed validator identity relocation DR runbook / config switch / restart proof
- concrete transport credential binding and bootstrap capability hardening for static peers
- repo-local reference launch path 또는 동등한 multi-node smoke harness
- 관련 regression suite 및 operator-facing 최소 문서 갱신

## Non-Goals
- dynamic peer discovery, peer scoring, topology management, validator admission policy
- validator-set rotation, checkpoint trust-root distribution, weak-subjectivity freshness policy의 concrete runtime integration
- audit node를 새 validator identity 또는 새 validator set membership 으로 승격하는 authority transition
- archive-grade accelerated backfill, snapshot compression, Merkle proof serving, remote body/proof fetch optimization
- KMS / HSM / remote signer, multi-operator custody hardening
- fee market, proposer fairness, distributed mempool policy
- public API server, application-specific query/mutation endpoint, OpenAPI catalog
- production packaging, container image, orchestration manifest, zero-downtime rollout policy
- same-DC static deployment baseline 자체를 supersede 하는 cross-DC failover / automatic promotion policy

## Related ADRs And Docs
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- ADR-0019: Canonical Block Header And Application-Neutral Block View
- ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams
- ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions
- ADR-0022: HotStuff Pacemaker And View-Change Baseline
- ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots
- ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization
- `docs/plans/0002-sigilaris-node-jvm-extraction-plan.md`
- `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`
- `docs/plans/0008-multi-node-follow-up-adr-authoring-plan.md`
- `README.md`

## Decisions To Lock Before Implementation
- minimal launch baseline 은 계속 `ADR-0018` 의 static topology + static validator set + same-DC validator placement 를 사용한다. dynamic discovery 나 rotation continuity 는 이번 plan 의 prerequisite 가 아니다.
- emergency DR baseline 도 계속 `ADR-0018` 의 operator-managed validator identity relocation 을 사용한다. 이는 existing validator identity/key holder 를 pre-provisioned remote node 로 옮기는 절차이지, validator membership / canonical ordering / trust root 를 바꾸는 authority transition 이 아니다.
- pacemaker runtime 은 current static validator-set baseline 위에 먼저 land 한다. historical leader-order lookup, rotated validator-set continuity, timeout-proof historical lookup 은 defer 한다.
- pacemaker artifact 는 out-of-band local helper 로 두지 않고, runtime-owned dissemination / recovery surface 를 가져야 한다. exact wire shape 가 커지면 companion spec 으로 분리할 수 있지만, runtime owner 와 operational path 는 이 plan 이 고정한다.
- newcomer bootstrap readiness closure 는 current `HotStuffRuntimeBootstrap` / `BootstrapCoordinator` / `BlockQuery` / `ProposalTxSync` seam 을 재사용해 닫고, 새 trust-root class 나 archive acceleration 을 prerequisite 로 요구하지 않는다.
- minimal launch gate 는 static trust root 하나면 충분하다. trusted checkpoint / weak-subjectivity anchor support 는 follow-up 이다.
- concrete transport auth baseline 은 "configured peer principal 과 transport-authenticated principal 이 drift 하지 않는다"는 점만 mandatory 로 둔다. exact mechanism 은 mTLS pinned principal 또는 그와 동등한 strong credential baseline 중 하나를 택할 수 있으나, raw HTTP header self-assertion 은 더 이상 baseline 으로 허용하지 않는다. 이 mechanism family 선택은 Phase 0 에서 잠그고, Phase 4 는 선택된 baseline 을 구현만 한다.
- bootstrap capability transport projection 은 forgeable plain encoding 이어서는 안 된다. parent session 과 authenticated principal 둘 다에 묶인 unforgeable projection 이어야 한다.
- reference launch path 는 `sigilaris-node-jvm` public surface 를 뒤집지 않는 thin assembly 여야 한다. application-specific domain/runtime 을 public main artifact 로 승격하는 것이 목적이 아니다. harness 위치는 Phase 0 에서 `modules/node-jvm` test harness, repo-local `tools`, 또는 그와 동등한 한 위치로 좁히고 이 문서에 기록한다.
- minimal multi-node gate 는 snapshot/forward materialization 에 단순 baseline storage 를 쓸 수는 있지만, historical proposal archive 자체를 memory-only 로 둘 수는 없다. fetched historical proposal 은 local durable backing 에 저장되어 process restart 뒤에도 유지되어야 한다. cross-node replication, archive compaction, accelerated rebuild, proof serving 은 별도 follow-up 으로 남긴다.
- minimal launch gate 는 manual DR relocation proof 를 포함한다. old validator holder 는 먼저 fenced 또는 중지되어야 하고, same validator identity/key 는 pre-provisioned remote audit node 로 옮겨 config swap + restart 로만 활성화된다. automatic cross-DC failover, hot role toggle, new validator-set promotion 은 이번 plan 밖이다.
- Phase 0 의 mandatory output 은 새 ADR 이 아니라 이 plan 본문 갱신이다. completion 시점에는 이 문서가 final launch-blocker inventory, chosen transport credential mechanism family, chosen harness location, 그리고 companion protocol note 필요 여부를 inline 으로 담고 있어야 한다. trust ownership 자체가 바뀌지 않는 한 별도 ADR 은 요구하지 않는다.
- reference smoke gate 의 정량 baseline 은 아래로 둔다.
  - steady-state 에서 contiguous height advancement 최소 `3`회
  - forced leader stall 뒤 timeout-driven leader turnover 최소 `1`회
  - newcomer 또는 restarted node 의 `Ready` 진입은 bootstrap retry cycle `3`회 이내 또는 그와 동등한 deterministic logical deadline 안에서 관찰
- 이번 plan 의 완료 조건은 "실제 여러 노드가 자동으로 session 을 맺고 block 생산/timeout recovery/bootstrap ready 를 수행한다"는 end-to-end 증거다. unit test green 만으로 완료로 보지 않는다.

## Change Areas

### Code
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip`
- 필요 시 `modules/node-jvm/src/test/scala` 아래 multi-node integration / smoke harness
- 필요 시 `tools` 또는 동등 repo-local reference launch harness 위치

### Tests
- pacemaker artifact/runtime/dissemination integration test
- timeout/new-view liveness recovery integration test
- bootstrap readiness closure regression test
- durable historical archive persistence / restart recovery regression test
- operator-managed validator identity relocation DR smoke / fence regression test
- transport auth spoofing / session revoke / capability misuse rejection test
- repo-local multi-node launch smoke test

### Docs
- `docs/plans/0009-minimal-static-multi-node-launch-readiness-plan.md`
- `docs/plans/0008-multi-node-follow-up-adr-authoring-plan.md`
- 필요 시 `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- 필요 시 `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- 필요 시 `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`
- 필요 시 `README.md`

## Implementation Phases

### Parallelization Note
- Phase 1 과 Phase 2, 그리고 Phase 3 은 Phase 0 boundary lock 이후 병렬 진행 가능하다.
- 단, Phase 2 의 마지막 gate 인 vote eligibility advancement 와 ready 전이는 Phase 1 이 고정한 pacemaker progression / vote-hold interaction 과 충돌하지 않도록 재통합 검증이 필요하다.
- Phase 4 와 Phase 5 는 각각 앞선 phase output 을 소비하므로 기본적으로 순차 진행한다.

### Phase 0: Minimal Launch Boundary Lock
- Phase 0 은 code-first phase 가 아니라 decision-lock phase 다. mandatory deliverable 은 이 문서 자체의 업데이트이며, 새 ADR 은 기본 output 이 아니다.
- "필수 launch blocker" 와 "중요하지만 defer 가능한 항목"을 다시 분리한다.
- pacemaker runtime 이 current static validator-set baseline 위에서 소비할 입력과 ownership 을 확정한다.
- bootstrap readiness closure 가 current snapshot/replay/materialization seam 중 무엇을 직접 소비해야 하는지 고정한다.
- DR relocation scenario 의 경계를 고정한다. pre-provisioned audit node, same validator identity relocation, old-holder fencing, static topology preconfiguration 을 baseline 으로 둔다.
- concrete transport credential binding baseline 하나를 고른다. exact protocol detail 이 길어지면 별도 spec 으로 빼더라도, canonical peer principal ownership 과 rejection policy 는 이 phase 에서 잠근다.
- reference launch harness 의 위치와 boundary 를 고정한다. public library surface 를 넓힐지, repo-local tool/test harness 로 둘지 결정한다.
- companion protocol note 가 필요한지, 아니면 이 plan inline decision 만으로 충분한지 결정한다.

### Phase 1: Pacemaker Runtime And Liveness Driver
- `TimeoutVote` / `TimeoutCertificate` / `NewView` 를 current runtime 에 concrete state machine 으로 연결한다.
- pacemaker artifact dissemination 을 gossip substrate operational path 에 연결한다.
- local timer/backoff/jitter, timeout escalation, deterministic leader activation, proposal-eligibility advancement 를 추가한다.
- bootstrap vote-hold 와 pacemaker artifact progression 의 interaction 을 concrete runtime policy 로 고정한다.
- steady-state progress, silent leader, duplicate timeout vote, wrong-window new-view, timeout recovery regression 을 추가한다.

### Phase 2: Bootstrap Readiness Closure
- `HotStuffRuntimeBootstrap` 안의 placeholder `ProposalCatchUpReadiness` 를 concrete readiness pipeline 으로 교체한다.
- replayed/live proposal 이 local `BlockQuery` / body/view validation / tx sufficiency 확인을 거쳐 vote eligibility 를 실제로 전진시키게 한다.
- no-op catch-up 뿐 아니라 non-empty replay/live catch-up 경로도 `Ready` 로 닫히는지 검증한다.
- bootstrap diagnostics 가 "why held" 와 "what advanced" 를 operator-visible 하게 유지하는지 회귀를 추가한다.

### Phase 3: Durable Historical Archive Backing
- `HistoricalProposalArchive` 에 local durable implementation 을 추가한다.
- shipped bootstrap assembly 가 `HistoricalProposalArchive.inMemory` 대신 durable backing 을 사용하게 한다.
- historical backfill 로 읽어온 proposal 이 process restart 뒤에도 재조회 가능하고, repeated ingestion 시 duplicate entry 를 만들지 않는지 고정한다.
- minimal gate 는 local single-node durability 만 요구한다. archive acceleration, remote archive serving, compaction, bandwidth shaping 은 계속 defer 한다.

### Phase 4: Concrete Peer Credential Binding And Bootstrap Capability Hardening
- transport layer 가 peer 가 보낸 claimed identity 를 그대로 신뢰하지 않도록, authenticated principal extraction 과 configured peer binding 을 추가한다.
- bootstrap capability projection 을 unforgeable 형태로 바꾸고, parent session revoke/close 및 principal mismatch 시 즉시 무효화되게 한다.
- gossip session open, event stream poll, control batch, bootstrap service family 가 같은 canonical principal 판정을 공유하도록 정렬한다.
- spoofed peer header, stale capability replay, wrong-peer capability reuse, re-auth mismatch, revoke-after-open regression 을 추가한다.

### Phase 5: Reference Launch Harness And End-To-End Verification
- current runtime + Armeria server + config loader 를 실제 다중 노드 기동 graph 로 묶는 thin reference launch path 를 추가한다.
- 최소 3-4 validator static cluster 와 newcomer/audit follower join 시나리오를 smoke gate 로 고정한다.
- operator-managed DR relocation 시나리오를 추가한다. primary validator holder 를 fence/stop 한 뒤 same validator identity/key 를 pre-provisioned audit node 에 배치하고 validator role 로 재시작해 quorum recovery 를 관찰한다.
- launch harness 는 block production, timeout recovery, newcomer bootstrap ready, transport-authenticated peer admission 을 모두 검증해야 한다.
- launch harness 는 audit/history follower 의 historical archive 가 restart 후에도 유지되는 baseline 을 함께 검증해야 한다.
- smoke pass 기준은 steady-state contiguous height advancement `3`회 이상, timeout-driven leader turnover `1`회 이상, newcomer 또는 restarted node ready 가 bootstrap retry cycle `3`회 이내 관찰되는 것이다.
- README 또는 companion operator note 에 필요한 최소 config shape, startup order, DR fencing/key relocation/restart order, known limitation 을 문서화한다.

## Test Plan
- Phase 0 Success: new plan 이 dynamic discovery / rotation / archive acceleration / custody hardening 을 explicit defer 로 밀어내되, memory-only historical retention 은 launch blocker 로 승격됐는지 검토한다.
- Phase 0 Success: 이 문서가 final launch-blocker inventory, chosen transport credential mechanism family, chosen harness location, companion protocol note 필요 여부를 inline 으로 담는지 검토한다.
- Phase 0 Success: 이 문서가 DR scenario 를 "new validator-set promotion" 이 아니라 "same-validator identity relocation on pre-provisioned audit nodes" 로 제한하고, fencing / restart 전제를 명시하는지 검토한다.
- Phase 1 Success: integration test 로 leader 가 정상 동작할 때 manual intervention 없이 proposal/vote/QC progression 이 이어지는지 검증한다.
- Phase 1 Success: integration test 로 current leader 가 멈추면 timeout vote / timeout certificate / new-view 경로를 거쳐 다음 leader 에서 진행이 재개되는지 검증한다.
- Phase 1 Failure: wrong-window timeout artifact, duplicate validator timeout vote, mismatched new-view leader, bootstrap hold precedence 위반이 reject 되는지 검증한다.
- Phase 2 Success: bootstrap integration test 로 replayed/live proposal 이 존재하는 non-empty catch-up 에서 readiness hold 가 실제로 해제되고 vote eligibility 가 전진하는지 검증한다.
- Phase 2 Failure: body/view unavailable, missing tx payload, ancestry mismatch, validation failure 상황에서 readiness 가 premature ready 로 승격되지 않는지 검증한다.
- Phase 3 Success: storage/integration test 로 historical backfill 로 적재된 proposal 이 runtime recreation 또는 process-equivalent reopen 뒤에도 남아 있고, duplicate ingestion 이 duplicate archive entry 를 만들지 않는지 검증한다.
- Phase 3 Failure: archive storage open 실패 또는 write failure 가 silent in-memory fallback 으로 가려지지 않고 explicit failure 또는 diagnostics 로 surface 되는지 검증한다.
- Phase 4 Success: transport integration test 로 configured peer 와 다른 principal 이 동일 HTTP path 를 호출해도 session open/bootstrap access 가 거부되는지 검증한다.
- Phase 4 Failure: forged bootstrap capability, stale capability replay, wrong-peer capability reuse, session revoke 이후 access 가 canonical rejection 으로 막히는지 검증한다.
- Phase 5 Success: repo-local multi-node smoke test 로 static cluster 가 별도 runtime instance 들로 부팅되고, session 형성, contiguous height advancement `3`회 이상, timeout-driven leader turnover `1`회 이상, newcomer 또는 restarted node ready 가 bootstrap retry cycle `3`회 이내에 모두 end-to-end 로 관찰되며, audit/history follower 의 persisted historical archive 가 restart 뒤에도 유지되는지 검증한다.
- Phase 5 Success: manual DR smoke test 로 old validator holder 를 fence/stop 하고, same validator identity/key 를 remote audit node 에 재배치한 뒤 validator role 로 재시작했을 때 validator set 변경 없이 quorum recovery 와 contiguous height advancement 재개가 관찰되는지 검증한다.
- Phase 5 Failure: dual-active key holder, missing relocated signer, stale static topology config, peer auth mismatch, pacemaker misconfiguration, bootstrap hold deadlock, archive reopen failure 가 operator-visible failure 로 surface 되고 silent hang 으로 남지 않는지 검증한다.

## Risks And Mitigations
- pacemaker scope 가 rotation / trust-root continuity 까지 다시 끌고 들어가면 구현이 멈출 수 있다. static validator-set baseline 을 explicit prerequisite 로 고정하고 historical continuity 는 defer 한다.
- durable archive retention 을 계기로 full storage-engine 재설계까지 scope 가 번지면 launch plan 이 멈출 수 있다. minimal bar 는 local single-node durable backing, reopen, dedupe, restart persistence 로 고정하고, archive compaction/replication/serving 은 defer 한다.
- bootstrap readiness closure 가 archive-grade replay acceleration 을 선결 조건처럼 요구하면 scope 가 불필요하게 커진다. current materialization seam + local durable archive backing + local block/view/tx sufficiency 소비까지만 mandatory 로 둔다.
- DR relocation 시 old/new holder fencing 이 느슨하면 dual-sign/equivocation 위험이 즉시 열린다. same-validator relocation 만 허용하고, old-holder fence/stop 선행, dual-active rejection, relocation smoke gate 로 운영 절차를 잠근다.
- static topology baseline 에서 DR node peer graph 가 미리 준비돼 있지 않으면 재시작 후 quorum 형성이 막힐 수 있다. pre-provisioned audit node topology 를 baseline 으로 고정하고, DR smoke 에서 실제 config swap 경로를 검증한다.
- transport auth hardening 이 production-wide PKI 설계 문제로 번질 수 있다. initial baseline 은 configured peer principal drift 를 막는 최소 strong credential binding 하나만 고정한다.
- repo-local reference launcher 가 application boundary 를 흐릴 수 있다. public reusable seam 과 repo-local launch proof 를 분리하고, application-specific domain main 은 여전히 non-goal 로 둔다.
- multi-node smoke 가 flaky 하거나 timing-sensitive 하면 gate 가치가 떨어진다. same-DC static baseline, bounded cluster size, deterministic test clock/helper 사용으로 변동성을 줄인다.

## Acceptance Criteria
1. current static topology / static validator-set baseline 위에서 여러 validator node 가 manual artifact injection 없이 steady-state block progression 을 만든다.
2. leader stall 또는 missing proposal 상황에서 timeout/new-view 경로로 view progression 이 재개되고 finality progression 이 계속된다.
3. newcomer 또는 restarted node 는 replayed/live proposal 이 존재하는 실제 catch-up 경로에서도 bootstrap hold 를 해제하고 ready 상태로 진입한다.
4. historical backfill 로 읽어온 proposal 은 memory-only retention 이 아니라 local durable backing 에 저장되고, node restart 뒤에도 재조회 가능하다.
5. operator-managed DR path 에서 existing validator identity/key 를 pre-provisioned audit node 로 재배치하고 재시작해 validator set 변경 없이 quorum recovery 를 수행할 수 있으며, old holder 미중지나 missing signer 는 explicit failure 로 surface 된다.
6. configured `PeerIdentity` 와 transport-authenticated principal 이 강하게 binding 되고, bootstrap capability projection 은 forgeable plain token 이 아니다.
7. repo 안에 current baseline 을 실제 다중 노드 기동 증거로 묶는 reference launch harness 또는 동등 smoke gate 가 존재한다.
8. dynamic discovery, rotation trust root, archive acceleration, custody hardening 이 없어도 "minimal static multi-node launch"가 가능하다는 경계가 문서와 테스트에 일관되게 반영된다.

## Checklist

### Phase 0: Minimal Launch Boundary Lock
- [ ] minimal launch blocker inventory 확정
- [ ] static validator-set / static topology baseline 재고정
- [ ] pacemaker operational owner / dissemination seam 확정
- [ ] bootstrap readiness closure input seam 확정
- [ ] durable archive backing technology / implementation boundary 확정
- [ ] DR relocation baseline assumptions and fencing boundary 확정
- [ ] concrete transport credential mechanism family 확정 및 inline 기록
- [ ] reference launch harness boundary 확정
- [ ] companion protocol note 필요 여부 확정

### Phase 1: Pacemaker Runtime And Liveness Driver
- [ ] timeout artifact runtime state / validation / accumulation 연결
- [ ] pacemaker artifact dissemination path 연결
- [ ] timer / backoff / jitter / leader activation 추가
- [ ] bootstrap vote-hold 와 pacemaker interaction 고정
- [ ] timeout recovery / wrong-window / equivocation regression test green

### Phase 2: Bootstrap Readiness Closure
- [ ] placeholder `forwardCatchUpUnavailable` hold 제거
- [ ] replayed/live proposal readiness consumer path 연결
- [ ] tx sufficiency / body-view validation / vote eligibility advancement 연결
- [ ] non-empty catch-up ready regression test green
- [ ] hold reason / readiness diagnostics regression test green

### Phase 3: Durable Historical Archive Backing
- [ ] durable `HistoricalProposalArchive` backing 추가
- [ ] shipped bootstrap assembly 의 `HistoricalProposalArchive.inMemory` 제거
- [ ] archive reopen / restart persistence / dedupe regression test green
- [ ] storage open failure / write failure diagnostics or failure policy 고정

### Phase 4: Concrete Peer Credential Binding And Bootstrap Capability Hardening
- [ ] claimed peer header 신뢰 제거 및 authenticated principal binding 추가
- [ ] bootstrap capability unforgeable projection 으로 교체
- [ ] session revoke / principal mismatch / stale replay rejection 연결
- [ ] gossip + bootstrap transport auth policy 정렬
- [ ] spoofing / stale capability / revoke regression test green

### Phase 5: Reference Launch Harness And End-To-End Verification
- [ ] repo-local reference launch path 또는 동등 smoke harness 추가
- [ ] 3-4 validator static cluster launch scenario 고정
- [ ] newcomer 또는 audit follower join scenario 고정
- [ ] same-validator identity relocation DR scenario 고정
- [ ] contiguous height advancement `3`회 + timeout recovery `1`회 + newcomer ready bounded smoke gate green
- [ ] audit/history follower restart 뒤 persisted historical archive 유지 smoke gate green
- [ ] old-holder fence + key relocation + validator restart DR smoke gate green
- [ ] minimal operator note / README wording 갱신

## Follow-Ups
- validator-set rotation, trusted checkpoint bundle, weak-subjectivity freshness, historical validator-set lookup runtime 은 계속 `ADR-0023` / plan `0007` follow-up 으로 남긴다.
- dynamic discovery, peer scoring, validator admission policy 는 deployment/discovery ADR 또는 별도 implementation plan 으로 남긴다.
- archive-grade historical sync acceleration, snapshot compression, proof serving, archive compaction/serving 은 storage/sync follow-up 으로 남긴다.
- KMS / HSM / remote signer, multi-operator custody hardening 은 security-hardening follow-up 으로 남긴다.
- production packaging, containerization, orchestration, zero-downtime rollout 은 minimal launch proof 이후 운영 plan 으로 남긴다.
