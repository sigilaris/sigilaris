# 0009 - Minimal Static Multi-Node Launch Readiness Plan

## Status
Complete; Phase 0-5 Complete

## Created
2026-04-07

## Last Updated
2026-04-08

## Current Implementation Status
### Implemented In Repo
- `2026-04-07` 기준 repo 는 runtime-owned pacemaker loop, durable historical archive backing, static-peer HMAC transport auth/capability hardening, same-validator relocation DR smoke, test-only static multi-node launch harness, 그리고 operator-facing launch note 를 landed 했다.
- current reference launch harness 는 static full-mesh session set 을 자동으로 형성하고 background event drain 을 유지한 뒤, runtime-owned proposal / vote / timeout-vote / new-view progression, archive restart persistence, same-validator relocation DR recovery 를 검증한다.
- newcomer bootstrap 관련 concrete readiness helper (`ProposalCatchUpReadiness.fromBlockQuery`) 도 landed 되어 tx sufficiency / body-view validation / vote eligibility 판단 자체는 구현돼 있다.
- shipped `HotStuffRuntimeBootstrap.fromConfig/fromTopology` 는 이제 explicit override 가 없을 때 application-neutral proposal body fallback readiness 를 기본 조립으로 wire 하며, non-empty replay/live catch-up 을 caller-supplied readiness injection 없이 닫는다.
- shipped config loader 는 static peer bootstrap HTTP transport (`sigilaris.node.gossip.peers.bootstrap.peer-base-uris`) 를 읽어 newcomer/restarted node remote bootstrap path 를 기본 assembly 로 provision 한다.
- reference launch smoke 는 harness-side `ProposalCatchUpReadiness.ready` stub 대신 shipped default newcomer path 를 사용해 contiguous height advancement, timeout recovery, newcomer ready bounded gate, archive restart persistence, same-validator relocation DR recovery 를 end-to-end 로 검증한다.

### Remaining To Implement In This Plan
- none. remaining follow-up 은 아래 Follow-Ups 섹션에 남긴 non-goal / post-launch hardening 항목으로 한정한다.

## Background
- 이 문서는 `0008` ADR tranche 이후, current Sigilaris baseline 으로 "실제로 static multi-node chain 을 띄울 수 있느냐"를 막는 필수 구현 공백만 정리하는 implementation plan 이다.
- `2026-04-07` 기준 shipped baseline 은 아래를 이미 갖고 있다.
  - static topology + direct-neighbor admission gossip substrate
  - binary peer event stream, `consensus.proposal` / `consensus.vote` topic sync, bounded `requestById`
  - HotStuff proposal / vote / QC model, validation, QC assembly, audit relay
  - canonical `BlockHeader` / `BlockBody` / `BlockView`
  - conflict-free block body selection / verification
  - finalized-anchor suggestion, snapshot sync, forward catch-up, historical backfill runtime seam
- 이 plan 은 원래 "여러 runtime instance 를 띄워 steady-state 로 block 을 생산하고, leader stall 을 넘기고, newcomer 가 실제 ready 로 들어오는" 최소 운영 경로를 닫기 위한 blocker inventory 로 시작했다.
- implementation 시작 시점에 추적하던 blocker 는 아래 여섯 가지였다.
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
- current static-topology / static-validator-set baseline 위에서 validator runtime 여러 개를 별도 runtime instance 로 기동해 자동으로 session 을 맺고 consensus 를 진행할 수 있게 한다.
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
- 위 `Ready` in `3` bootstrap retry cycle 기준은 bootstrap catch-up/readiness path 자체의 bounded convergence gate 로만 해석한다. cluster-wide pacemaker livelock, network partition, 또는 homogeneous TC quorum 미형성 같은 consensus liveness loss 상황에서는 이 기준을 success guarantee 로 적용하지 않는다.
- 이번 plan 의 완료 조건은 "실제 여러 노드가 자동으로 session 을 맺고 block 생산/timeout recovery/bootstrap ready 를 수행한다"는 end-to-end 증거다. unit test green 만으로 완료로 보지 않는다.

## Phase 0 Locked Decisions
- Phase 0 completion trigger 는 이 문서가 merge-ready 상태로 갱신되고, 아래 Phase 0 checklist 가 checked 상태로 고정되는 것이다. Phase 1 구현은 이 경계가 잠긴 뒤에만 시작한다.
- final launch-blocker inventory 는 이 문서 Background 의 여섯 항목으로 고정한다. dynamic discovery, rotation continuity, archive acceleration, custody hardening, production packaging 은 계속 explicit defer 다.
- minimal launch baseline 은 계속 `ADR-0018` 의 static topology + static validator set + operator-preconfigured peer graph 를 사용한다. peer membership, canonical ordering, direct-neighbor admission set, config-owned known peer inventory 는 runtime 중 자동 변경하지 않으며, launch harness 도 같은 static config material 을 그대로 소비한다.
- pacemaker / proposal / vote / new-view path 가 쓰는 `validatorSetHash` 는 existing `ValidatorSet.hash` canonical encoding 을 그대로 상속한다. hash algorithm 자체는 `ADR-0017` / current HotStuff canonical encoding family 가 소유하고, 이번 plan 은 새 hash family 를 도입하지 않는다.
- deterministic leader rotation input 은 current active validator set 의 canonical member ordering 과 target window view 값으로 고정한다. rotation function 자체는 existing `HotStuffPacemaker.deterministicLeader` baseline 을 그대로 사용하고, 이번 plan 은 alternative proposer-order input 을 도입하지 않는다.
- first timeout/new-view edge case 에서도 nil `highestKnownQc` sentinel 은 도입하지 않는다. genesis 이후 첫 pacemaker path 는 bootstrap/root QC subject 를 canonical highest-known QC 로 사용한다.
- pacemaker operational owner 는 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff` 의 HotStuff runtime 이다. dissemination seam 은 current `HotStuffGossipArtifact` / topic registry 를 확장해 `TimeoutVote` 와 `NewView` 를 runtime-owned consensus topic 으로 태우고, `TimeoutCertificate` 는 assembler-local accumulation artifact 로 materialize 한다.
- chosen pacemaker topic family 는 `consensus.timeout-vote` 와 `consensus.new-view` 다. 둘 다 exact-known + bounded `requestById` contract 를 가지는 first-class gossip topic 으로 취급하고, proposal / vote topic 과 같은 same-window retry budget baseline `HotStuffPolicy.requestPolicy.maxRetryAttemptsPerWindow` 에 맞춘다. wire encoding family 는 current `HotStuffGossipArtifact` deterministic byte codec pattern 을 그대로 확장하는 것으로 잠근다. timeout certificate 자체를 별도 wire artifact 로 승격하는 것은 이번 plan 밖으로 둔다. current plan 에서 `TimeoutCertificate` 는 각 validator local runtime 이 자신이 수집한 `TimeoutVote` quorum 으로 조립하는 non-first-class accumulation artifact 이고, dedicated gossip topic / dedicated `requestById` contract 는 갖지 않는다. next leader 는 exclusive assembler 가 아니며, timed-out validator 누구나 local quorum 과 corresponding full `highestKnownQc` 를 이미 보유한 경우에만 TC 를 locally materialize 해 `NewView` 로 실어 보낼 수 있다. timeout-driven proof 의 wire carriage 는 `NewView` payload 안에 raw timeout vote set 을 담은 embedded `TimeoutCertificate` 형태로만 허용하며, receiver 는 embedded `TimeoutCertificate` 를 sender-trust 로 수용하지 않고, embedded raw vote signatures 를 per-vote 검증하는 방식으로 signature / window / quorum rules 와 outer `highestKnownQc` consistency rule 을 함께 확인해야 한다. embedded `TimeoutCertificate` byte encoding 은 repo-local deterministic `TimeoutCertificate` model codec 하나로 고정하며, canonical `subject` 뒤에 canonical deduped vote vector 길이와 vote payload 들을 `TimeoutVoteId` ascending order 로 싣는 형태만 허용한다. 여기서 `TimeoutVoteId` 는 current repo-local `HotStuffPacemakerCanonicalEncoding.timeoutVoteId(subject, voter, signature)` 가 산출한 256-bit digest 를 뜻하고, ascending order 는 그 big-endian raw bytes 의 lexicographic order 다. 이번 minimal static baseline 에서는 `TimeoutCertificate` assembly rule 이 homogeneous `highestKnownQc.subject` 를 요구한다고 명시적으로 잠근다. 여기서 `highestKnownQc.subject` 는 current `QuorumCertificateSubject(window, proposalId, blockId)` 전체 tuple 을 뜻한다. 즉 같은 `(chainId, height, view, validatorSetHash)` quorum 안에서도 서로 다른 highest QC subject 를 가진 timeout vote 는 한 certificate 로 조립하지 않는다. 이 선택이 QC convergence 이전 liveness 를 보수적으로 제한하는 것은 accepted baseline 이며, current same-DC static cluster 는 timeout 전에 proposal/vote/QC gossip 으로 같은 highest QC subject 에 수렴하는 것을 전제한다. local runtime 은 timeout vote accumulation bucket 을 `highestKnownQc.subject` 전체 tuple 별로 분리하고, local runtime 이 full QC 를 이미 보유한 homogeneous bucket 만 effective TC 후보로 취급한다. divergent highest QC subject 를 가진 timeout vote 는 signature/window/sender checks 를 통과하더라도 effective TC accumulation 과 `NewView` carriage 에서는 제외하고, canonical structured diagnostic/log/metric 으로 surface 한다. same height 에서 homogeneous quorum 이 형성되지 않은 timeout round 는 unbounded retry 대상이며, local runtime 은 consecutive failed timeout windows 를 계속 세다가 동일 height 에서 `3` consecutive failed timeout windows 이후부터는 elevated operator alert severity 로 diagnostics 를 내고, progress 또는 shutdown 전까지 retry 는 계속한다. network partition 또는 QC convergence failure 로 homogeneous quorum 이 영구히 형성되지 않아 livelock 이 지속될 수 있다는 점은 이번 minimal baseline 이 받아들이는 explicit residual risk 이며, recovery baseline 은 operator-visible alert 후 네트워크 복구 또는 node restart/re-bootstrap 뿐이다. outer `NewView.highestKnownQc` 는 바로 그 homogeneous subject 에 대응하는 full QC 여야 한다. sender 가 `NewView` 에 실어 보내는 full `highestKnownQc` 는 이미 local runtime 이 proposal/vote/QC path 에서 검증해 보유한 QC 여야 하고, timeout vote 가 참조한 QC subject 에 대해 별도 QC fetch path 를 이번 plan 에 추가하지 않는다. `TimeoutVote` dedupe / replay rule 은 `(validatorId, chainId, height, view)` 당 하나의 effective vote 만 허용하고 exact replay 는 idempotent ignore, 다른 subject 로의 재전송은 equivocation rejection 으로 잠근다. `NewView` dedupe / replay rule 은 `(sender, chainId, height, view)` 당 하나의 effective message 만 허용하고 exact replay 는 idempotent ignore, 같은 sender/window 에서 다른 payload 로 재전송되는 artifact 는 pacemaker conflict rejection 으로 잠근다. proposal / vote / timeout-vote / new-view 는 cross-topic total ordering 을 요구하지 않지만, pacemaker topics 는 proposal backlog 에 의해 starvation 되면 안 되므로 independent bounded queue or equivalent scheduling slot 으로 dispatch fairness 를 보장해야 한다. stale `NewView` 는 receiver local state 가 이미 `newView.window` 를 지나간 경우 malformed artifact 로 보지 않고 stale-window benign drop 으로 처리한다. separate pacemaker topic rollout compatibility 는 이번 baseline 에서 고려하지 않고, static validator cluster 는 atomic upgrade 로 same topic registry 를 함께 올리는 것을 전제로 둔다. 여기서 atomic upgrade 는 mixed topic registry / mixed wire version 을 허용하지 않는 coordinated full-cluster restart procedure 를 뜻한다.
- `TimeoutVote` minimum wire payload 는 timeout subject `(chainId, height, view, validatorSetHash, highestKnownQc.subject)` + voter + signature 로 잠근다. 여기서 `highestKnownQc.subject` 는 current `QuorumCertificateSubject(window, proposalId, blockId)` deterministic tuple 전체다. `NewView` minimum wire payload 는 full next window `(chainId, height, view, validatorSetHash)` + sender + nextLeader + highestKnownQc + embedded raw-vote `TimeoutCertificate` + signature 로 잠근다. consensus signature domain tags 는 existing pacemaker canonical encoding literals `sigilaris.hotstuff.timeout-vote.sign.v1` 와 `sigilaris.hotstuff.new-view.sign.v1` 를 그대로 사용한다. `NewView` sender 는 next leader 로 한정하지 않고 timed-out validator 누구나 될 수 있으며, outer signature 는 relay attestation 이 아니라 sender-owned pacemaker handoff attestation 으로 취급한다. `nextLeader` 는 receiver 가 재계산 가능한 값이지만, self-contained validation 과 receiver-side leader verification/mismatch rejection 을 위해 explicit assertion field 로 유지하고 static validator-set baseline 아래 `newView.window` payload 자체를 입력으로 deterministic leader rotation 결과와 일치해야 한다. field name 은 existing repo model name 을 유지하되, semantic meaning 은 "asserted next leader" 로 읽어야 하고 sender identity claim 으로 읽으면 안 된다. receiver 의 already-advanced local view 는 `nextLeader` recomputation 기준에 사용하지 않으며, payload window 기준 leader 와 불일치하면 validation failure 로 reject 한다. `nextLeader` field 는 sender authorization field 가 아니므로 sender 가 `nextLeader` 와 같아야 한다는 규칙은 도입하지 않는다.
- bootstrap readiness closure input seam 은 current `HotStuffRuntimeBootstrap` -> `HotStuffBootstrapLifecycle` -> `BootstrapCoordinator` -> `ProposalCatchUpReadiness` 조립점으로 고정한다. concrete consumer 는 replayed/live proposal, local tx sufficiency inventory, 그리고 proposal-derived bootstrap block-view inventory 를 소비하며, remote body fetch 나 archive-grade acceleration 을 prerequisite 로 요구하지 않는다.
- durable archive backing technology 는 repo-local SwayDB key-value store 로 고정한다. layout 은 current `StorageLayout.state` 아래의 dedicated historical archive path 를 추가하는 방식으로 닫고, silent in-memory fallback 은 허용하지 않는다. archive primary key 는 `(chainId, proposalId)` deterministic tuple 로 잠그고, duplicate ingestion semantics 는 `proposalId` 기준 idempotent no-op 로 고정한다. `list(chainId)` 의 canonical iteration order 는 storage scan order 가 아니라 decoded proposal window `(height, view)` ascending 후 `proposalId` ascending tie-break 로 재정렬한 결과를 사용한다. archive format 은 SwayDB value boundary 자체를 record framing 으로 사용하고, 각 stored value 는 정확히 `[schemaVersionByte][canonicalProposalPayload]` 순서의 envelope 로 인코딩한다. initial schema version byte 는 `0x01` 이고, `canonicalProposalPayload` 는 current HotStuff deterministic proposal byte codec 이 산출한 bare payload 그대로다. 별도 길이 prefix, side header, optional metadata tail 은 `0x01` baseline 에 포함하지 않는다. unknown schema version 은 DB open 시점 metadata 뿐 아니라 any record read/scan 중 발견되더라도 per-record skip 없이 fatal archive incompatibility 로 처리하고, current archive open/read path 전체를 실패시켜 node bootstrap 또는 dependent read operation 을 abort 해야 한다. single bad/corrupted record 가 whole archive failure 를 일으키는 blast radius 는 이번 minimal baseline 이 받아들이는 explicit residual risk 이며, operator recovery baseline 은 local archive path wipe 후 peer replay/backfill 로 re-bootstrap 하는 것이다. diagnostics 는 incompatible-version byte 와 lower-layer corruption suspicion 을 구분 가능하면 분리해서 surface 해야 하고, 구분 불가능하면 generic archive-corrupt-or-incompatible fatal error 로 묶어도 된다. atomic upgrade 가 깨져 peer 들도 incompatible archive/wire version 으로 올라간 상태라면 wipe + replay 도 회복을 보장하지 않는다는 점 역시 accepted residual risk 이고, baseline recovery 는 cluster software/config version realignment 후 재-bootstrap 뿐이다. archive writes 는 append-only batch write baseline 으로 두고, rollback semantics 는 custom layer 에서 추가하지 않으며 crash-safety/durability 와 partial-write recovery 는 underlying SwayDB guarantees 를 그대로 소비한다. archive backing open/write failure 는 durable archive 를 활성화한 모든 integration path 에서 fatal bootstrap failure 로 surface 되어야 하며, degraded in-memory mode 로 자동 강등되면 안 된다. isolated unit test helper 가 explicit in-memory archive 를 쓰는 것은 허용하지만, Phase 5 reference launch harness 와 shipped config/bootstrap assembly 는 real durable path 를 반드시 provision 해야 한다.
- DR relocation baseline 은 pre-provisioned audit node, same-validator identity/key relocation, old-holder fence/stop 선행, static topology preconfiguration, dual-active startup rejection 으로 고정한다. dual-active rejection mechanism 은 existing `ValidatorKeyHolder` policy/config inventory 에서 same bootstrap material 안에 드러나는 duplicate active holder mapping 을 startup 시점에 거부하는 policy gate 와, DR 절차에서 fenced config swap 없이는 replacement node 가 launch 되지 않도록 하는 operator sequencing 을 함께 baseline 으로 둔다. 이 startup gate 는 independently provisioned remote nodes 사이의 hidden cross-node duplication 을 cryptographically prove 하지는 못하며, 그 경우는 minimal launch baseline 이 받아들이는 explicit residual risk 로 남는다. gossip/session layer 는 duplicate validator identity 자체를 transport-session open 단계에서 판정하려 들지 않고, 여전히 authenticated peer principal 과 configured peer binding 만 검사한다. already-running dual-active holder 를 gossip-observed runtime proof 만으로 자동 fence 하는 mechanism 은 이번 gate 밖이다. 따라서 old holder 미중지 상태에서 replacement 를 띄우는 operator error 는 minimal launch baseline 이 받아들이는 explicit residual risk 로 기록한다. 대신 conflicting signed proposal / vote / pacemaker artifact detection ownership 은 consensus runtime validation path 가 가지고, operator-visible fault 는 canonical validation rejection + structured diagnostic/log/metric surface 를 최소 기준으로 둔다. honest observers 는 conflicting artifacts 를 reject 하고 quorum accumulation, QC assembly, TC assembly, leader advancement 계산에서 그 artifact 를 절대 count 하지 않은 채 자기 로컬 state machine 을 계속 진행한다. local self-halt, remote automatic fence, 또는 conflicting holder artifact salvage/reconciliation 은 이번 plan 밖으로 둔다. validator-set promotion 이나 automatic failover 는 이번 gate 밖이다.
- chosen transport credential mechanism family 는 static peer principal + per-peer pre-shared secret 기반 HMAC request proof 다. raw authenticated-peer header self-assertion 은 baseline 으로 허용하지 않으며, gossip session open / event poll / control batch / bootstrap endpoint 는 같은 canonical principal verifier 를 공유한다. secret provisioning baseline 은 repo-local config material 이 소유하는 per-peer secret entry 로 고정하고, env-var only injection 또는 ad-hoc out-of-band session secret exchange 는 minimal launch prerequisite 로 두지 않는다. raw per-peer secret 는 request proof 와 capability token 에 직접 재사용하지 않고, `HKDF-SHA-256` 으로 domain-separated subkey 를 파생한 뒤 그 derived key 에만 HMAC 를 적용한다. exact HKDF `info` literals 는 request proof 전용 `sigilaris.transport-proof.v1` 와 capability token 전용 `sigilaris.bootstrap-capability.v1` 로 고정한다. HKDF extract salt 는 zero-length byte string 으로 고정하고, interoperability 에서 omitted/null salt 는 같은 zero-length semantics 로 취급한다.
- parent gossip session lifetime / heartbeat / liveness 는 `ADR-0016` 의 existing negotiated session contract 를 그대로 상속한다. bootstrap capability 는 parent session open state 에 종속되고, session close/expiry 뒤에는 재핸드셰이크 + capability 재발급 없이는 더 이상 유효하지 않다.
- chosen transport credential secret revocation model 은 operator-initiated per-peer config swap + affected node restart 다. config material 은 startup 시점에만 읽고 hot reload 는 하지 않는다. compromised peer secret 회전이 cluster-wide full restart 를 의미하는 것은 아니고, 그 secret 을 공유하는 affected peers 의 coordinated restart 범위로 제한한다.
- bootstrap capability projection 은 plain base64 token 이 아니라 authenticated principal, target peer, parent session id, request lineage 를 묶은 HMAC capability token 으로 교체한다. parent session revoke/close, principal mismatch, wrong-peer reuse 는 canonical rejection 으로 막는다. capability HMAC key 는 위 transport credential bullet 에서 잠근 capability-token 전용 derived subkey 를 그대로 사용한다. request lineage 는 minimal baseline 에서 exact outbound bootstrap request 의 `(httpMethod, requestPath, sha256(requestBodyBytes))` deterministic tuple 로 고정한다. capability MAC input serialization 은 current deterministic byte codec pattern 을 따르는 single canonical tuple `(domainTag = sigilaris.bootstrap-capability.v1, authenticatedPrincipal.value, targetPeer.value, parentSessionId.value, httpMethod, requestPath, requestBodySha256)` field order 로 잠그고, textual fields 는 UTF-8 exact bytes, `requestBodySha256` 는 raw 32-byte digest 로 인코딩한다. 즉 raw transport secret 는 direct HMAC input key 로 쓰지 않고, request proof 와 capability token 은 서로 다른 derived subkey 를 사용한다. 이 선택의 consequence 는 per-peer transport secret compromise 가 outstanding capability token 도 함께 무효화할 blast radius 를 가진다는 점이며, independent capability-only rotation 은 이번 minimal baseline 에 포함하지 않는다. 이 blast-radius 는 minimal launch baseline 이 받아들이는 explicit residual risk 로 기록한다. capability token 은 별도 TTL 을 두지 않고 parent session open lifetime 을 그대로 상속한다. secret rotation 뒤에는 old secret 에서 파생된 capability token 을 즉시 invalid 로 간주하고 session-close 이전이라도 재사용하지 않는다. DR relocation 이 peer transport secret compromise 와 함께 발생한 경우에도 mitigation baseline 은 operator-managed secret config rotation + restart 이고, automatic secret rollover 는 이번 plan 밖이다. bootstrap capability verification 대상은 항상 parent gossip session 의 direct counterparty 뿐이며, indirect relay/forwarding capability 는 이번 baseline 에 포함하지 않는다.
- chosen reference launch harness location 은 `modules/node-jvm/src/test/scala` 아래 test-only multi-node harness 다. public library surface 를 더 넓히지 않고, current config loader + runtime bootstrap + Armeria server 를 묶는 thin smoke assembly 를 이 위치에서 유지한다. startup ordering contract 는 gossip topic registry 가 proposal/vote/timeout-vote/new-view 를 all-or-nothing 으로 등록한 뒤에만 session open 과 consensus start 가 허용된다는 것이다. partial registration failure 나 post-registration mismatch 는 background retry 로 흡수하지 않고 fatal startup failure 로 surface 하며, node 는 session open 이전에 abort 되어야 한다.
- proposer function or pacemaker wire field set 이 바뀌는 future change 는 wire-incompatible version bump 와 위 pacemaker topic bullet 에서 정의한 same atomic cluster upgrade procedure 를 요구한다.
- companion protocol note 는 이번 phase 의 mandatory output 이 아니다. current topic names, transport credential family, harness location, rejection policy 를 이 문서 inline decision 으로 유지하고, 별도 protocol note 는 implementation 중 wire shape 가 과도하게 커질 때만 follow-up 으로 분리한다.

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
- Phase 1 Failure: divergent `highestKnownQc.subject` timeout vote 들이 homogeneous TC quorum 으로 잘못 조립되지 않고, elevated diagnostics 만 남긴 채 no-progress/livelock residual risk 가 operator-visible 하게 surface 되는지 검증한다.
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

## Current Acceptance Status
- satisfied: `1`, `2`, `3`, `4`, `5`, `6`, `7`, `8`

## Checklist

### Phase 0: Minimal Launch Boundary Lock
- [x] minimal launch blocker inventory 확정
- [x] static validator-set / static topology baseline 재고정
- [x] pacemaker operational owner / dissemination seam 확정
- [x] bootstrap readiness closure input seam 확정
- [x] durable archive backing technology / implementation boundary 확정
- [x] DR relocation baseline assumptions and fencing boundary 확정
- [x] concrete transport credential mechanism family 확정 및 inline 기록
- [x] reference launch harness boundary 확정
- [x] companion protocol note 필요 여부 확정

### Phase 1: Pacemaker Runtime And Liveness Driver
- [x] timeout artifact model / validation / accumulation primitive landed
- [x] pacemaker artifact dissemination topic / transport contract landed
- [x] timer / backoff / jitter / leader activation 이 shipped node runtime 에 자동 경로로 wire 됨
- [x] bootstrap vote-hold 와 automatic pacemaker progression interaction 이 shipped runtime policy 로 고정됨
- [x] timeout recovery integration test 가 harness-driven manual artifact emission 없이 green

### Phase 2: Bootstrap Readiness Closure
- [x] placeholder `forwardCatchUpUnavailable` hold 제거
- [x] `ProposalCatchUpReadiness.fromBlockQuery` concrete readiness helper landed
- [x] shipped `HotStuffRuntimeBootstrap.fromConfig/fromTopology` 가 non-empty replay/live catch-up 에 concrete readiness consumer 를 기본 조립으로 wire 함
- [x] replayed/live proposal 이 shipped newcomer path 에서 tx sufficiency / body-view validation / vote eligibility advancement 를 거쳐 `Ready` 로 닫힘
- [x] non-empty catch-up ready regression test 가 caller-supplied readiness injection 없이 green
- [x] hold reason / readiness diagnostics regression test green

### Phase 3: Durable Historical Archive Backing
- [x] durable `HistoricalProposalArchive` backing 추가
- [x] shipped bootstrap assembly 의 `HistoricalProposalArchive.inMemory` 제거
- [x] archive reopen / restart persistence / dedupe regression test green
- [x] storage open failure / write failure diagnostics or failure policy 고정

### Phase 4: Concrete Peer Credential Binding And Bootstrap Capability Hardening
- [x] claimed peer header 신뢰 제거 및 authenticated principal binding 추가
- [x] bootstrap capability unforgeable projection 으로 교체
- [x] session revoke / principal mismatch / stale replay rejection 연결
- [x] gossip + bootstrap transport auth policy 정렬
- [x] spoofing / stale capability / revoke regression test green

### Phase 5: Reference Launch Harness And End-To-End Verification
- [x] repo-local reference launch path 또는 동등 smoke harness 추가
- [x] 3-4 validator static cluster scenario 를 test-only harness 로 고정
- [x] newcomer 또는 audit follower bootstrap scenario 를 test-only harness 로 고정
- [x] same-validator identity relocation DR scenario 를 test-only harness 로 고정
- [x] manual artifact injection 없이 automatic static mesh session 형성 + runtime-owned consensus progression proof 확보
- [x] audit/history follower restart 뒤 persisted historical archive 유지 smoke gate green
- [x] old-holder fence + key relocation + validator restart DR smoke gate green
- [x] concrete config shape / startup order / DR runbook 를 담은 operator note 갱신
- [x] config loader 또는 shipped equivalent assembly 가 newcomer/restarted node remote bootstrap transport (`peerBaseUris`, `bootstrapTransport`) 를 harness-only programmatic injection 없이 provision 함
- [x] reference launch smoke 가 `ProposalCatchUpReadiness.ready` stub 대신 concrete readiness consumer path 를 사용함
- [x] contiguous height advancement `3`회 + timeout recovery `1`회 + newcomer ready bounded gate 가 shipped default newcomer path 로 green

## Follow-Ups
- validator-set rotation, trusted checkpoint bundle, weak-subjectivity freshness, historical validator-set lookup runtime 은 계속 `ADR-0023` / plan `0007` follow-up 으로 남긴다.
- dynamic discovery, peer scoring, validator admission policy 는 deployment/discovery ADR 또는 별도 implementation plan 으로 남긴다.
- archive-grade historical sync acceleration, snapshot compression, proof serving, archive compaction/serving 은 storage/sync follow-up 으로 남긴다.
- KMS / HSM / remote signer, multi-operator custody hardening 은 security-hardening follow-up 으로 남긴다.
- production packaging, containerization, orchestration, zero-downtime rollout 은 minimal launch proof 이후 운영 plan 으로 남긴다.
