# ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots

## Status
Proposed

## Context
- ADR-0017은 HotStuff proposal / vote / QC window key를 `(chainId, height, view, validatorSetHash)`로 고정했고, `validatorSetHash`를 해당 window의 quorum membership commitment로 사용한다. 그러나 non-static 또는 historical validator set을 어떻게 복원할지는 intentionally 비워 두었다.
- ADR-0018은 static peer topology, validator / audit role, same-DC validator placement, operator-managed key relocation을 initial deployment baseline으로 수용했다. 이 baseline은 "같은 validator identity를 어느 node가 들고 있느냐"는 정하지만, validator membership / ordering 자체가 바뀌는 rotation continuity contract는 고정하지 않는다.
- ADR-0021은 `best finalized block suggestion` bootstrap contract와 `BootstrapTrustRoot` / `ValidatorSetLookup` seam을 도입했지만, shipped JVM baseline은 여전히 `HotStuffBootstrapConfig.validatorSet` 및 `BootstrapTrustRoot.StaticValidatorSet`과 동등한 static validator-set trust root만 구현했다.
- ADR-0022는 pacemaker, timeout vote, timeout certificate, new-view, deterministic leader rotation semantics를 고정했지만, leader ordering continuity source와 historical validator-set lookup ownership은 intentionally ADR-0023으로 남겼다.
- `2026-04-06` 기준 Sigilaris에는 proposal / vote / QC validation, finalized proof verification, snapshot anchor verification, future pacemaker timeout proof verification이 모두 존재하거나 follow-up으로 예정돼 있다. 이 path들은 모두 "historical HotStuff window의 `validatorSetHash`를 canonical ordered validator set으로 어떻게 resolve 하는가"라는 같은 authority contract를 필요로 한다.
- local node가 historical QC / finalized proof를 검증할 때 current active validator set으로 fallback 하거나, peer가 준 bootstrap payload에서 validator-set authority를 직접 배우면 safety와 bootstrap trust assumption이 흐려진다.
- 반대로 exact `validatorSetHash` byte derivation, checkpoint file format, persistence index/schema, operator UX, reconfiguration transaction wire shape까지 지금 ADR에서 전부 잠그면 문서 scope가 과도하게 커진다.
- 따라서 이번 phase의 목표는 validator-set continuity와 bootstrap trust-root precedence를 semantic baseline으로 잠그고, exact encoding / storage / transport detail은 후속 spec 또는 implementation plan으로 남기는 것이다.

## Decision
1. **`validatorSetHash`는 canonical ordered validator set commitment다.**
   - `validatorSetHash`가 commit 하는 대상은 특정 HotStuff window에서 quorum 계산과 leader ordering에 사용되는 canonical ordered validator set이다.
   - membership만 같은 unordered 집합은 baseline semantic target이 아니다. ordering까지 포함된 authority input이 commitment 대상이다.
   - transport address, current network endpoint, audit peer inventory, KMS/HSM handle 같은 deployment-local metadata는 `validatorSetHash` semantic input이 아니다.
   - exact canonical byte encoding과 hash derivation formula는 follow-up spec 또는 implementation plan이 고정한다.

2. **ADR-0018의 operator-managed key relocation은 validator-set rotation과 다른 계약이다.**
   - existing validator identity의 signing key holder를 다른 node로 옮기거나, 같은 validator identity가 다른 data center / host에서 살아나는 것은 deployment/operations baseline이다.
   - 위 relocation만으로 validator membership 또는 canonical ordering이 바뀌지 않으면 `validatorSetHash`도 바뀌지 않는다.
   - 반대로 validator membership이나 canonical ordering이 바뀌어 `validatorSetHash`가 달라진다면, 그것은 local config drift가 아니라 explicit validator-set transition으로 취급한다.
   - 따라서 "누가 같은 validator identity를 들고 있느냐"와 "어떤 validator set이 authoritative 하냐"를 같은 절차로 취급해서는 안 된다.

3. **historical validator-set lookup은 consensus/bootstrap-owned mandatory seam이다.**
   - proposal, vote, QC, timeout-driven pacemaker proof, finalized proof를 검증할 때 receiver는 해당 artifact window의 `validatorSetHash`에 대응하는 validator set을 resolve 해야 한다.
   - verifier는 `validatorSetHash` mismatch가 나면 current active validator set으로 fallback 하거나 peer-advertised set을 임시 사용해서는 안 된다.
   - historical set을 resolve할 수 없으면 canonical 결과는 "현재 local continuity evidence로는 검증 불가"여야 한다. 이는 acceptance가 아니라 explicit verification failure다.
   - current shipped `ValidatorSetLookup.static`은 모든 target window가 same static hash를 가리키는 degenerate baseline일 때만 유효하다.

4. **validator-set authority continuity는 finalized chain continuity 또는 trusted checkpoint로만 확장된다.**
   - live chain에서 new validator set이 authoritative 하게 되려면 local node가 old authority에서 new authority로 이어지는 continuity를 검증 가능해야 한다.
   - 그 continuity source는 finalized chain-carried transition material, operator-trusted finalized checkpoint, weak-subjectivity anchor 또는 그와 동등한 locally trusted bootstrap material이어야 한다.
   - local config reload, peer inventory update, bootstrap response payload 자체는 authority transition의 충분조건이 아니다.
   - exact rotation artifact shape가 state-transition record인지, checkpoint bundle인지, 별도 proof family인지는 follow-up spec 또는 implementation plan이 고정한다.

5. **bootstrap trust root는 local bootstrap material class로만 정의한다.**
   - baseline trust-root class는 `genesis config`, `operator-supplied trusted checkpoint`, `weak-subjectivity anchor`다.
   - `genesis config`는 chain birth 시점의 canonical ordered validator set과 chain identity를 고정하는 최하위 root다.
   - `operator-supplied trusted checkpoint`는 later finalized boundary를 local operator가 수용하는 root다. 이 root는 해당 anchor window의 validator-set continuity를 재개할 수 있을 정도의 validator-set material을 함께 제공해야 한다.
   - `weak-subjectivity anchor`는 later finalized boundary를 root로 쓸 수 있지만, explicit freshness/window assumption 없이는 indefinite root가 아니다.
   - peer-provided validator set, peer-provided checkpoint, bootstrap suggestion payload는 local trust-root class가 아니다.

6. **bootstrap trust-root precedence는 "local root -> chain-derived continuity -> peer claim" 순서를 따른다.**
   - peer 응답은 local root를 대체하거나 승격하지 못한다.
   - chain-derived continuity는 selected local root에서 출발해 finalized evidence를 따라 확장되는 authority chain이다.
   - later checkpoint 또는 weak-subjectivity anchor가 genesis보다 더 좁은 acceptance boundary를 제공하려면 explicit local/operator choice가 필요하다.
   - 여러 local root가 동시에 존재해도 bootstrap session은 하나의 selected trust-root baseline 위에서 candidate proof를 판정해야 하며, peer claim이 그 선택을 바꾸는 것은 허용하지 않는다.

7. **ADR-0021 finalized-anchor verification은 selected trust root와 historical lookup 위에서만 성립한다.**
   - `best finalized block suggestion` response는 candidate proof bundle이지 trust root가 아니다.
   - local node는 anchor proposal `P0`와 `FinalizedProof(P1, P2)`를 검증할 때, 각 proposal / QC / timeout-related subject가 속한 window별로 appropriate historical validator set을 resolve 해야 한다.
   - proof chain이 validator-set rotation boundary를 가로지르면 각 window는 서로 다른 resolved validator set을 사용할 수 있다.
   - selected trust root에서 target anchor까지 continuity를 재구성할 수 없으면 그 suggestion은 "highest claimed"여도 verifiable finalized candidate가 아니다.

8. **ADR-0022 pacemaker는 validator ordering을 소비만 하고, ordering continuity는 이 ADR이 소유한다.**
   - deterministic leader rotation은 resolved validator set의 canonical ordering을 입력으로 사용한다.
   - pacemaker는 ordering source를 transport health, peer scoring, operator preference에서 유추하지 않는다.
   - historical leader-order continuity를 어떻게 복원할지는 validator-set continuity lookup의 일부다.
   - exact leader-order serialization, checkpoint carriage, runtime cache layout은 follow-up spec 또는 implementation plan이 고정한다.

9. **ADR-0019 / ADR-0020의 public block contract는 그대로 유지한다.**
   - `validatorSetHash`, authority continuity, bootstrap trust-root precedence는 consensus/bootstrap layer가 소유한다.
   - canonical `BlockHeader` / `BlockBody` / `BlockView`는 validator-set metadata를 새 mandatory field로 받지 않는다.
   - body-level schedulability / verifier assumption도 current conflict-free scheduling contract를 그대로 유지한다.

## Consequences
- historical QC / finalized proof / pacemaker proof verification이 공통 `ValidatorSetLookup` responsibility를 공유하게 된다.
- operator-managed key relocation과 true validator-set transition이 분리되므로, ADR-0018 운영 baseline과 authority continuity contract가 섞이지 않는다.
- bootstrap node는 peer가 준 finalized suggestion을 trust root로 승격하지 못하므로, initial bootstrap paradox가 더 명확하게 차단된다.
- later trusted checkpoint나 weak-subjectivity anchor를 도입할 수 있는 semantic slot이 생기지만, 그만큼 freshness / operator trust / checkpoint distribution follow-up이 더 필요해진다.
- `ADR-0019` block header와 `ADR-0020` body validation contract를 건드리지 않고 authority continuity를 consensus/bootstrap layer에 남길 수 있다.
- exact checkpoint encoding, rotation artifact schema, persistence layout, operator UX, runtime cache/backfill integration은 여전히 후속 spec 또는 implementation plan 작업이다.
- current shipped JVM runtime은 계속 static validator-set baseline으로 동작하며, historical rotation lookup / checkpoint root / weak-subjectivity enforcement는 follow-up implementation이 필요하다.

## Implementation Status
- `2026-04-06` 기준 shipped JVM baseline은 `BootstrapTrustRoot.StaticValidatorSet` 및 `ValidatorSetLookup.static`만 제공한다.
- shipped bootstrap verification은 `HotStuffBootstrapConfig.validatorSet`과 동등한 static validator-set root를 사용하며, historical validator-set rotation continuity는 아직 구현되지 않았다.
- ADR-0018의 operator-managed key relocation, same-DC validator placement, static peer topology baseline은 그대로 유효하다.

## Rejected Alternatives
1. **historical QC / finalized proof도 current active validator set으로 검증한다**
   - rotation 이후 proof를 잘못 accept 하거나 잘못 reject 할 수 있다.
   - historical authority continuity contract를 사실상 포기하는 셈이다.

2. **peer가 준 validator set 또는 checkpoint payload를 bootstrap trust root로 바로 승격한다**
   - bootstrap paradox를 해결하지 못한다.
   - byzantine peer가 authority source를 위조할 수 있는 surface를 남긴다.

3. **operator-managed key relocation을 validator-set rotation baseline으로 본다**
   - key holder relocation과 authority membership change가 섞인다.
   - ADR-0018의 운영 절차와 consensus-level continuity contract가 혼동된다.

4. **`validatorSetHash`와 continuity metadata를 canonical block header에 넣는다**
   - ADR-0019가 지키는 application-neutral block contract를 불필요하게 widen 한다.
   - authority continuity는 consensus/bootstrap concern이지 generic block header concern이 아니다.

5. **checkpoint file format, rotation transaction schema, persistence layout을 이 ADR에서 전부 고정한다**
   - semantic contract보다 low-level encoding detail이 앞서게 된다.
   - transport/runtime/storage 선택지를 너무 일찍 잠그게 된다.

## Follow-Up
- concrete trusted checkpoint bundle, weak-subjectivity freshness policy, historical `ValidatorSetLookup` implementation은 `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md` 또는 그 후속 rotation/bootstrap implementation plan이 소유한다.
- pacemaker runtime integration이 landed 되는 시점에는 `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`가 historical leader-order lookup seam을 이 ADR 기준으로 소비한다.
- exact `validatorSetHash` derivation, checkpoint file format, persistence index/schema, operator UX, runtime caching/backfill policy는 follow-up spec 또는 implementation plan이 고정한다.
- automatic validator admission, automatic audit-to-validator promotion, automatic cross-DC failover policy까지 baseline으로 확장하려면 별도 운영 ADR이 필요하다.

## References
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0019: Canonical Block Header And Application-Neutral Block View](0019-canonical-block-header-and-application-neutral-block-view.md)
- [ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions](0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [0007 - Snapshot Sync And Background Backfill Plan](../plans/0007-snapshot-sync-and-background-backfill-plan.md)
- [0008 - Multi-Node Follow-Up ADR Authoring Plan](../plans/0008-multi-node-follow-up-adr-authoring-plan.md)
