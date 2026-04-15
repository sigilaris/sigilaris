# 0015 - Typelevel Site Current Implementation Alignment Plan

## Status
Completed

## Created
2026-04-15

## Last Updated
2026-04-15

## Background
- `site/src` 아래 Typelevel site는 여전히 초기 `sigilaris-core` 중심 서사를 강하게 유지하고 있다.
  - `site/src/README.md`와 `site/src/laika.conf`는 Sigilaris를 cryptographic operations library처럼 설명한다.
  - 같은 문서에는 `Consensus Algorithms`, `P2P Networking`이 `Coming Soon`으로 남아 있다.
  - 반면 루트 `README.md`는 이미 `sigilaris-node-common`, `sigilaris-node-jvm`, static peer topology, HotStuff bootstrap, reference launch smoke, current limitations를 현재 구현 기준으로 설명한다.
- 내비게이션도 현재 구현 범위를 반영하지 못한다.
  - `site/src/en/directory.conf`, `site/src/ko/directory.conf`는 `byte-codec`, `json-codec`, `crypto`, `merkle`, `assembly`, `application`, `performance`만 노출한다.
  - `datatype` 문서는 실제로 존재하지만 top-level nav에는 빠져 있다.
  - `node-common`, `node-jvm`, HotStuff, static launch baseline에 대한 narrative page는 아예 없다.
  - 반면 `build.sbt`의 unidoc 설정은 이미 `core.jvm`, `nodeCommon.jvm`, `nodeJvm`을 `/api`로 내보내고 있다. 즉 tooling보다 content/IA가 뒤처진 상태다.
- 일부 narrative page는 현재 도메인 모델과도 어긋난다.
  - `site/src/en/application/accounts.md`와 대응 한국어 문서는 `type KeyId20 = BigInt`, `nonce: BigInt :| Positive0` 같은 ADR-era alias를 예시로 사용한다.
  - 실제 구현은 `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/domain/AccountsTypes.scala`에서 `KeyId20`, `AccountNonce`, `NonEmptyKeyIds`, `NonEmptyKeyIdDescriptions` 같은 concrete domain type을 이미 제공한다.
  - `site/src/en/application/group.md`, `site/src/en/application/README.md`, `site/src/en/assembly/README.md`와 대응 한국어 문서도 `GroupNonce`, `MemberCount`, `NonEmptyGroupAccounts`, 실제 `Account`/`GroupId` surface 대신 simplified placeholder에 더 의존한다.
- 현재 site page는 Laika navigation과 별개로 `[← Main]`, 언어 전환 링크, `© 2025 Sigilaris. All rights reserved.` 같은 수동 chrome을 반복한다.
  - 이 패턴은 구조 개편 시 중복 수정 지점을 늘리고 Typelevel site의 기본 navigation 가치를 낮춘다.
- public repo의 배포 경로는 이미 `.github/workflows/site.yml`에서 `sbt -v -J-XX:MaxMetaspaceSize=1G ";unidoc;tlSite"`로 고정돼 있다.
  - 이번 작업의 핵심은 site build 체계 신규 도입이 아니라, 현재 구현과 narrative site를 다시 맞추는 것이다.
- `mdoc`도 현재 repo에 이미 연결돼 있다.
  - `build.sbt`는 `mdocIn := baseDirectory.value / "site" / "src"`를 사용한다.
  - 현재 site page 다수는 이미 `mdoc`, `mdoc:silent`, `mdoc:compile-only` fenced block을 포함한다.
  - 따라서 이번 작업은 mdoc 신규 도입보다, touched page별 snippet mode를 다시 분류하고 stale example를 validated example 또는 명시적 pseudocode로 정리하는 문제가 더 가깝다.

## Goal
- Typelevel site가 현재 공개 구현 기준의 Sigilaris surface를 정확히 설명하게 만든다.
- `sigilaris-core`뿐 아니라 `sigilaris-node-common`, `sigilaris-node-jvm`, static topology/HotStuff/launch baseline까지 site에서 발견 가능하게 만든다.
- stale application/assembly/accounts/group narrative를 현재 public type/module boundary에 맞게 정리한다.
- EN/KO top-level 정보 구조를 맞추고, `sbt ";unidoc;tlSite"`를 통과하는 문서 baseline을 만든다.

## Scope
- `site/src/README.md`
- `site/src/laika.conf`
- `site/src/en/**`, `site/src/ko/**`
- `site/src/en/directory.conf`, `site/src/ko/directory.conf` 및 새 section용 `directory.conf`
- node/runtime narrative page 추가에 필요한 `site/src/en/*`, `site/src/ko/*` 구조 확장
- site content 변경으로 드러나는 최소한의 `build.sbt` 조정

## Non-Goals
- 문서에 맞추기 위한 public API 변경
- production 운영 매뉴얼 전체를 새로 작성하는 작업
- generated `/api`를 narrative markdown으로 대체하는 작업
- public export workflow 자체의 재설계
- generic codec pages 전체를 무차별 재작성하는 작업

## Related ADRs And Docs
- `README.md`
- `build.sbt`
- `.github/workflows/site.yml`
- ADR-0009: Blockchain Application Architecture
- ADR-0010: Blockchain Account Model And Key Management
- ADR-0011: Blockchain Account Group Management
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions
- ADR-0022: HotStuff Pacemaker And View-Change Baseline
- ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization
- ADR-0025: Shared Node Abstraction And Cross-Runtime Module Boundary
- `docs/plans/0009-minimal-static-multi-node-launch-readiness-plan.md`
- `docs/plans/0010-node-common-extraction-and-cross-runtime-contract-plan.md`

## Decisions To Lock Before Implementation
- site landing page는 루트 `README.md`의 verbatim mirror가 아니라 curated overview다. 다만 module inventory, shipped baseline, current limitation에 대한 사실 관계는 루트 `README.md`와 충돌하지 않아야 한다.
- future work는 `Coming Soon` 식의 모호한 문구보다 `Current Baseline` / `Current Limitations` / `Follow-Up Work` 형태로 분리한다. 이미 구현된 surface를 future tense로 설명하지 않는다.
- top-level 정보 구조는 core docs와 node docs를 명시적으로 분리한다.
  - 최소 baseline nav는 `datatype`, `byte-codec`, `json-codec`, `crypto`, `merkle`, `assembly`, `application`, `node-common`, `node-jvm`, `performance`를 노출한다.
  - HotStuff, bootstrap, static launch note는 별도 homepage dumping이 아니라 `node-jvm` section 아래 page로 정리한다.
- generated `/api`는 canonical API reference로 유지한다. narrative page는 package/module overview, usage boundary, configuration/limitations 설명에 집중하고 상세 method inventory는 `/api`로 보낸다.
- public example는 두 종류만 허용한다.
  - 현재 API에 대해 `mdoc`로 실제 검증되는 example
  - 명시적으로 non-compiling pseudocode라고 선언된 explanatory snippet
- `application`, `assembly`, `accounts`, `group`처럼 current model을 소개하는 page에서는 historical alias나 placeholder를 current public type처럼 보이게 두지 않는다.
- EN/KO는 같은 top-level section inventory를 유지한다. 구현 순서는 EN-first가 가능하지만 완료 조건은 KO parity까지 포함한다.
- Typelevel/Laika navigation이 기본 탐색 수단이므로, 수동 `Main`/footer chrome은 touched page부터 제거하거나 대폭 축소한다.
- 새 node narrative의 page depth는 open-ended로 늘리지 않는다.
  - exact page split은 `Appendix B. Target IA And Page Outline`에서 Phase 0에 잠근다.
  - 기본 baseline은 section overview `README` 중심이며, 신규 focused subpage는 `node-common`은 최소화하고 `node-jvm`도 overview 포함 최대 4 page 범위에서 닫는 것을 기본값으로 둔다. 더 넓어지면 이 plan을 먼저 갱신한다.

## Phase 0 Deliverables
- Phase 0 산출물은 기본적으로 별도 scratch 문서가 아니라 이 plan 본문에 appendices 형태로 기록한다.
- Phase 0에서 채워야 하는 최소 산출물은 아래 세 가지다.
  - `Appendix A. Page Ownership Matrix`
  - `Appendix B. Target IA And Page Outline`
  - `Appendix C. Writing / Snippet / Navigation Policy`
- `Appendix A`에는 page owner/source of truth뿐 아니라 touched page의 snippet mode(`mdoc`, `mdoc:compile-only`, explicit pseudocode)를 같이 기록한다.
- appendix별 최소 항목은 대응하는 `Phase 0` checklist 항목과 1:1로 유지한다. 이후 appendices를 확장하더라도 checklist gate와 분리해서 관리하지 않는다.
- Phase 1은 위 appendices가 채워져 entry gate 역할을 할 수 있을 때만 시작한다.
- appendices 중 하나가 지나치게 커지면 companion doc로 분리할 수 있지만, 그 경우에도 이 plan에는 요약과 링크를 남긴다.

## Current Documentation Gap Inventory

| gap | current evidence | desired response |
| --- | --- | --- |
| product positioning drift | `site/src/README.md`, `site/src/laika.conf`는 crypto library tone이고 `README.md`는 node/runtime까지 설명한다 | landing page와 metadata를 현재 module stack 기준으로 재작성 |
| navigation drift | `site/src/en/directory.conf`, `site/src/ko/directory.conf`에 `datatype`/node docs가 없다 | top-level nav 재구성 및 section 추가 |
| missing node narrative | `/api`에는 node docs가 생성되지만 narrative page는 없다 | `node-common`, `node-jvm`, HotStuff/static launch baseline page 추가 |
| domain-model drift | accounts/group/application/assembly page가 `BigInt` alias와 simplified placeholder에 의존한다 | current domain type과 실제 package/module boundary 기준으로 예제 갱신 |
| duplicated manual chrome | `[← Main]`, 언어 링크, `© 2025` footer가 page마다 반복된다 | Laika navigation 중심 구조로 단순화 |

## Change Areas

### Code
- `site/src/laika.conf`
- `site/src/en/directory.conf`
- `site/src/ko/directory.conf`
- 새 section용 `site/src/en/node-common/**`, `site/src/en/node-jvm/**`
- 새 section용 `site/src/ko/node-common/**`, `site/src/ko/node-jvm/**`
- 필요 시 `site/src/en/datatype/directory.conf`, `site/src/ko/datatype/directory.conf`
- site build assumption이 문서 구조와 어긋날 때만 `build.sbt`

### Tests
- `sbt ";unidoc;tlSite"`
- mdoc compile/compile-only snippet validation
- generated `target/docs/site`의 nav/link/API 존재 여부 확인

### Docs
- `site/src/README.md`
- `site/src/en/application/**`, `site/src/ko/application/**`
- `site/src/en/assembly/**`, `site/src/ko/assembly/**`
- 새 node narrative page 전반
- 필요 시 current baseline을 설명하는 README cross-link 정리

## Implementation Phases

### Phase 0: IA And Source-Of-Truth Lock
- 현재 site page inventory를 `homepage / core docs / node docs missing / stale examples / duplicated chrome` 관점으로 다시 분류한다.
- 어떤 사실은 루트 `README.md`를 authority로 삼고, 어떤 설명은 ADR/plan을 authority로 삼는지 page owner matrix를 고정한다.
- top-level nav와 새 node section outline을 확정한다.
- 이 문서의 `Appendix A` / `Appendix B` / `Appendix C`를 직접 채우고, touched page별 snippet mode(`mdoc`, `mdoc:compile-only`, pseudocode) inventory를 잠근다.
- touched page에 적용할 공통 서술 규칙을 잠근다.
  - current baseline vs limitation vs follow-up wording
  - generated `/api` link policy
  - manual navigation/footer 제거 방침

### Phase 1: Landing Page And Navigation Realignment
- `site/src/README.md`를 현재 module inventory와 docs entrypoint 중심으로 재작성한다.
- `site/src/laika.conf` metadata description을 현재 project positioning에 맞춘다.
- `site/src/en/directory.conf`, `site/src/ko/directory.conf`를 갱신해 `datatype`과 node sections를 노출한다.
- 새 section directory와 ordering을 추가해 docs discovery를 homepage link에 의존하지 않도록 만든다.

### Phase 2: Node Narrative Rollout
- `node-common` section을 추가해 shared node abstraction, gossip/session substrate, transport-neutral contract, tx anti-entropy runtime logic를 설명한다.
- `node-jvm` section을 추가해 runtime lifecycle seam, transport/storage adapters, Armeria/SwayDB ownership, config/bootstrap assembly를 설명한다.
- exact page count와 subpage split은 `Appendix B. Target IA And Page Outline`에서 확정한 범위를 따른다. Phase 2는 그 outline을 구현하는 단계이지, 페이지 종류를 다시 늘리는 단계가 아니다.
- `node-jvm` 하위 page에서 아래 current baseline을 narrative로 정리한다.
  - static peer topology / transport auth baseline
  - HotStuff bootstrap / snapshot sync / historical backfill baseline
  - pacemaker / view-change / current limitation
  - reference launch smoke와 static launch notes
- operator manual 수준의 과도한 절차서가 아니라, 현재 공개 구현을 이해하고 `/api` 및 ADR로 더 내려갈 수 있는 overview를 제공한다.

### Phase 3: Core Narrative Refresh And Language Parity
- `application`, `assembly`, `accounts`, `group` page의 stale alias와 placeholder example를 현재 public domain type과 module boundary에 맞게 갱신한다.
- 실제 타입을 소개하는 문단에서는 `KeyId20`, `AccountNonce`, `GroupNonce`, `MemberCount`, `NonEmptyKeyIds`, `NonEmptyGroupAccounts`, `GroupId`, `Account` 등을 반영한다.
- Phase 0의 snippet mode inventory를 기준으로 touched example를 `mdoc`, `mdoc:compile-only`, explicit pseudocode 중 하나로 재분류한다.
- simplified example가 꼭 필요하면 pseudocode임을 명확히 표시하고, current model 설명 문단과 분리한다.
- EN 갱신 후 대응 KO page도 같은 section inventory와 의미를 유지하도록 동기화한다.
- generic codec/crypto/merkle docs는 inventory 기반으로 spot-check하고, 실제 drift가 확인된 page만 수정한다.

### Phase 4: Verification And Publication Readiness
- `sbt ";unidoc;tlSite"`를 실행해 site build와 generated `/api` 매핑이 유지되는지 검증한다.
- `target/docs/site`에서 top-level nav, internal links, `/api` 존재, EN/KO section parity를 확인한다.
- build를 위해 추가한 예외적 site/build 설정이 있다면 최소화하고 이유를 문서화한다.
- 남은 문서 갭이 implementation scope 밖이면 `Follow-Ups`로 분리한다.

## Test Plan
- Phase 0 Success: `Appendix A` / `Appendix B` / `Appendix C`가 이 문서 안에 채워지고, page owner/source-of-truth matrix와 snippet mode inventory, target IA가 문서화된다.
- Phase 1 Success: site landing page와 metadata가 현재 module stack을 설명하고, nav가 `datatype` 및 node sections를 노출한다.
- Phase 2 Success: `node-common`, `node-jvm`, HotStuff/static launch baseline narrative가 site에서 탐색 가능해진다.
- Phase 3 Success: touched current-model page의 example가 `mdoc` 또는 `mdoc:compile-only`로 검증되거나, compile 대상이 아니면 explicit pseudocode로 표시된다. 그리고 targeted grep에서 `type KeyId20 = BigInt`, `nonce: BigInt :| Positive0`, `memberCount: BigInt :| Positive0`가 current-model 설명 surface에 `0`건이다.
- Phase 4 Success: `sbt ";unidoc;tlSite"`가 green이고 generated site에 `/api`가 존재한다.

## Risks And Mitigations
- 루트 `README.md`를 그대로 복제하면 homepage가 길고 중복이 심해질 수 있다. site는 curated entrypoint로 유지하고, 세부 baseline은 section page로 분산한다.
- node narrative가 ADR/plan의 future work를 shipped feature처럼 오해하게 만들 수 있다. 모든 node page에 current baseline / limitation / follow-up 구획을 강제한다.
- bilingual scope가 커서 EN/KO drift가 다시 생길 수 있다. section inventory와 page slug를 먼저 고정하고, 체크리스트에서 language parity를 별도 gate로 둔다.
- mdoc snippet이 실제 API 변화에 취약할 수 있다. compile 가능한 최소 예제만 두고, 설명용 pseudocode는 명시적으로 분리한다.
- manual chrome 제거가 부분 적용되면 page 스타일이 더 불균일해질 수 있다. touched page 기준으로 일괄 제거하고, untouched page는 후속 tranche로 남긴다.

## Acceptance Criteria
1. Typelevel site landing page와 metadata가 현재 Sigilaris를 `core` + `node-common` + `node-jvm` stack으로 설명하고, 이미 shipped된 consensus/networking baseline을 `Coming Soon`으로 남기지 않는다.
2. EN/KO top-level nav가 `datatype`과 node sections를 포함한 target IA를 노출한다.
3. `site/src/en/application/**`, `site/src/ko/application/**`, `site/src/en/assembly/**`, `site/src/ko/assembly/**`의 touched current-model page에서는 `type KeyId20 = BigInt`, `nonce: BigInt :| Positive0`, `memberCount: BigInt :| Positive0` 같은 legacy alias가 current model 설명 문맥에서 제거되고, 남는 단순화 예제는 explicit pseudocode로 표시된다.
4. `sbt ";unidoc;tlSite"`가 성공하고 generated site에 `/api`가 포함된다.

## Checklist

### Phase 0: IA And Source-Of-Truth Lock
- [x] `Appendix A` page ownership matrix와 snippet mode inventory 작성
- [x] `Appendix B` target nav / section outline 확정
- [x] `Appendix C` wording / snippet / navigation policy 고정

### Phase 1: Landing Page And Navigation Realignment
- [x] `site/src/README.md` rewrite
- [x] `site/src/laika.conf` metadata update
- [x] EN/KO top-level nav update
- [x] `datatype` 및 새 node section discoverability 확보

### Phase 2: Node Narrative Rollout
- [x] `node-common` section landed
- [x] `node-jvm` overview landed
- [x] HotStuff / bootstrap / launch baseline page landed
- [x] EN/KO node docs skeleton parity 확보

### Phase 3: Core Narrative Refresh And Language Parity
- [x] `application` / `assembly` narrative refresh
- [x] `accounts` / `group` current-model drift 제거
- [x] touched page의 manual chrome cleanup
- [x] EN/KO parity review

### Phase 4: Verification And Publication Readiness
- [x] `sbt ";unidoc;tlSite"` green
- [x] generated site nav / link / `/api` 확인
- [x] 남은 문서 갭 follow-up 정리

## Follow-Ups
- operator-focused deployment/runbook이 필요해지면 site section이 아니라 별도 docs tranche로 분리한다.
- node/runtime surface가 더 안정되면 long-lived policy/semantics는 ADR 또는 dedicated reference doc로 승격한다.
- touched page 밖에 남아 있는 legacy chrome cleanup은 별도 doc hygiene tranche로 분리할 수 있다.
- untouched page에 남아 있을 수 있는 `© 2025` footer / copyright year cleanup은 별도 doc hygiene follow-up으로 추적한다.

## Phase 0 Appendices

### Appendix A: Page Ownership Matrix
| page path | owner / source of truth | snippet mode | EN/KO parity | notes |
| --- | --- | --- | --- | --- |
| `site/src/README.md` | root `README.md`, `build.sbt`, `.github/workflows/site.yml` | prose only | N/A | curated landing page; facts must not drift from root README |
| `site/src/laika.conf` | current public module stack in root `README.md` | N/A | N/A | metadata only |
| `site/src/en/directory.conf`, `site/src/ko/directory.conf` | target IA locked in Appendix B | N/A | same inventory required | top-level discovery must not rely on homepage links |
| `site/src/en/application/directory.conf`, `site/src/ko/application/directory.conf` | touched section inventory in this appendix | N/A | same inventory required | expose `accounts` and `group` in section nav |
| `site/src/en/application/README.md`, `site/src/ko/application/README.md` | ADR-0009, root `README.md`, public package surface under `org.sigilaris.core.application.*` | `mdoc:compile-only` for public surface examples; prose elsewhere | required | no historical placeholder schema sold as current public API |
| `site/src/en/application/accounts.md`, `site/src/ko/application/accounts.md` | ADR-0010, `AccountsTypes.scala`, `AccountsTransactions.scala`, `CurrentApplicationDomainTypeSuite.scala` | `mdoc:compile-only` for current type/tx examples; explicit pseudocode only for operational recovery flow | required | remove `type KeyId20 = BigInt`, raw `BigInt :| Positive0` current-model examples |
| `site/src/en/application/group.md`, `site/src/ko/application/group.md` | ADR-0011, `GroupTypes.scala`, `GroupTransactions.scala`, `CurrentApplicationDomainTypeSuite.scala` | `mdoc:compile-only` for current type/tx examples; explicit pseudocode only for coordinator workflow narrative | required | reflect `GroupId`, `GroupNonce`, `MemberCount`, `NonEmptyGroupAccounts` |
| `site/src/en/assembly/README.md`, `site/src/ko/assembly/README.md` | ADR-0009, public package surface under `org.sigilaris.core.assembly.*` | `mdoc:compile-only` for DSL examples; prose elsewhere | required | explain assembly as mount/wiring layer, not placeholder domain model |
| `site/src/en/node-common/README.md`, `site/src/ko/node-common/README.md` | root `README.md`, ADR-0024, ADR-0025, `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/**` | prose only | required | one-page overview section; point detailed API usage to `/api` |
| `site/src/en/node-jvm/README.md`, `site/src/ko/node-jvm/README.md` | root `README.md`, ADR-0018, ADR-0021, ADR-0022, ADR-0024, ADR-0025, `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/**` | prose only | required | section overview for lifecycle/config/storage/transport seams |
| `site/src/en/node-jvm/bootstrap-and-sync.md`, `site/src/ko/node-jvm/bootstrap-and-sync.md` | root `README.md`, ADR-0018, ADR-0021, `Bootstrap.scala`, `BootstrapCoordinator.scala`, `SnapshotSync.scala`, `HistoricalBackfillWorker.scala` | explicit pseudocode for config shape only; prose otherwise | required | describe current bootstrap baseline, trust root, snapshot sync, backfill |
| `site/src/en/node-jvm/hotstuff-and-pacemaker.md`, `site/src/ko/node-jvm/hotstuff-and-pacemaker.md` | root `README.md`, ADR-0022, `HotStuffNodeRuntime.scala`, `Pacemaker.scala`, `PacemakerRuntime.scala`, `Validation.scala` | prose only | required | current baseline vs current limitations vs follow-up work must be separated |
| `site/src/en/node-jvm/static-launch.md`, `site/src/ko/node-jvm/static-launch.md` | root `README.md`, `HotStuffLaunchSmokeSuite.scala`, `HotStuffRuntimeBootstrapSuite.scala`, `.github/workflows/site.yml` | explicit pseudocode for config shape and commands | required | smoke harness, static peer config, restart/DR notes, operator-owned steps |

### Appendix B: Target IA And Page Outline
- top-level nav order는 EN/KO 모두 아래 순서로 고정한다.
  - `datatype`
  - `byte-codec`
  - `json-codec`
  - `crypto`
  - `merkle`
  - `assembly`
  - `application`
  - `node-common`
  - `node-jvm`
  - `performance`
- `application` section required pages:
  - `README.md`
  - `accounts.md`
  - `group.md`
  - `api.md`
- `assembly` section required pages:
  - `README.md`
  - `api.md`
- `node-common` section required pages:
  - `README.md`
  - section depth cap: overview only, no Phase 2 subpage expansion
- `node-jvm` section required pages:
  - `README.md`
  - `bootstrap-and-sync.md`
  - `hotstuff-and-pacemaker.md`
  - `static-launch.md`
  - section depth cap: overview 포함 최대 4 page에서 닫는다
- homepage required outgoing discovery links:
  - core docs entry (`datatype`, `assembly`, `application`)
  - node docs entry (`node-common`, `node-jvm`)
  - generated API reference (`/api`)
- top-level nav/section naming rules:
  - slug는 EN/KO 모두 동일하게 유지한다
  - page split은 overview 중심으로 유지하고, implementation detail dump는 `/api` 또는 ADR 링크로 보낸다
  - `performance`는 existing page inventory를 유지하고 이번 tranche에서 확장하지 않는다

### Appendix C: Writing / Snippet / Navigation Policy
- baseline wording:
  - 이미 public repo에 landed 된 surface는 `Current Baseline` 또는 현재 시제 문장으로 서술한다
  - 아직 미구현이거나 partial인 항목은 `Current Limitations` 또는 `Follow-Up Work`로 분리한다
  - `Coming Soon` 같은 모호한 미래 시제 heading은 touched page에서 금지한다
- API reference policy:
  - narrative page는 module/package overview, boundary, config, operational caveat를 설명한다
  - method/class inventory는 generated `/api`를 canonical reference로 둔다
  - touched page는 가능한 한 section 말미에 `/api`로 내려가는 링크를 유지한다
- snippet policy:
  - compiling surface를 보여주는 snippet은 `mdoc` 또는 `mdoc:compile-only`만 사용한다
  - output 자체가 설명에 필요하지 않으면 `mdoc:compile-only`를 기본값으로 둔다
  - compile 대상으로 삼지 않는 설명용 snippet은 본문 바로 위에 `Pseudocode` 또는 `Non-compiling configuration sketch`라고 명시한다
  - current model 소개 문단에서는 legacy alias / placeholder를 public type처럼 보이게 두지 않는다
- navigation/chrome policy:
  - touched page에서는 수동 `[← Main]`, 수동 language switch, 수동 `© 2025 ...` footer를 제거한다
  - Laika navigation을 기본 탐색 수단으로 사용하고, cross-link는 본문 내 contextual link로 최소화한다
  - untouched page에 남아 있는 legacy chrome은 이번 tranche outside scope로 두되, Follow-Ups에 남긴다
