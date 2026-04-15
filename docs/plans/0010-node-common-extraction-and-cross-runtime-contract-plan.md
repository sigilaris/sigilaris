# 0010 - Node Common Extraction And Cross-Runtime Contract Plan

## Status
Phase 4 Complete

## Created
2026-04-09

## Last Updated
2026-04-14

## Background
- ADR-0025는 `sigilaris-core`를 node-agnostic 으로 유지하면서, cross-runtime reusable node contract의 owner를 새 cross-project module `sigilaris-node-common`으로 고정했다.
- `2026-04-09` 기준 repository에는 cross-project `sigilaris-core`와 JVM-only `sigilaris-node-jvm`만 존재한다. gossip/session/bootstrap 관련 shared candidate는 주로 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip` 아래에 있고, 일부 HTTP contract는 `transport.armeria.gossip` 안에서 Tapir endpoint definition과 같이 묶여 있다.
- 이 상태를 그대로 두면 future Scala.js client 또는 Cloudflare Workers runtime은 두 가지 모두 나쁜 선택을 강요받는다.
  - JVM-only `sigilaris-node-jvm`에 비정상적으로 의존한다.
  - 또는 같은 node contract를 다른 runtime 쪽에서 다시 정의한다.
- 동시에 shared extraction은 무작정 크게 시작하면 안 된다.
  - `runtime.gossip` 안에는 transport-neutral model과 함께 Typesafe Config loader, JVM-only config adapter, concrete transport auth/config helper가 섞여 있다.
  - `transport.armeria.gossip` 안에는 shared HTTP shape처럼 보이는 DTO와 함께 Armeria/Tapir server adapter, Java HTTP client outbound transport가 섞여 있다.
  - Tapir definition layer를 shared module로 올릴 수 있는지도 exact JVM + JS target availability 확인이 필요하다.
- 따라서 이번 작업은 "모든 node 코드를 공용화"하는 것이 아니라, `ADR-0025`가 잠근 경계를 실제 build/module/package/test 수준으로 만들고, first shared slice를 안전하게 추출하는 것이다.

## Goal
- `sigilaris-core`와 `sigilaris-node-jvm` 사이에 새 cross-project module `sigilaris-node-common`을 도입한다.
- `sigilaris-node-common`이 JVM + JS 양쪽에서 컴파일되는 shared node contract layer가 되게 한다.
- `node-jvm` 안에 있던 transport-neutral gossip/session/bootstrap contract의 첫 extraction slice를 `org.sigilaris.node` namespace 아래로 옮긴다.
- `sigilaris-node-jvm`이 extracted contract를 consume 하도록 의존 방향을 `core <- node-common <- node-jvm`으로 정렬한다.
- shared HTTP contract가 필요한 경우, Tapir definition layer를 shared module에 둘 수 있는지 검증하고 실패 시 DTO/codec/semantic-model fallback 경로를 고정한다.

## Scope
- `build.sbt`에 새 cross-project module `sigilaris-node-common`을 추가한다.
- `sigilaris-node-common`의 canonical package root `org.sigilaris.node`와 JVM/JS source layout을 만든다.
- `modules/node-jvm` 안의 shared candidate inventory를 만들고, first extraction slice의 target mapping을 고정한다.
- first extraction slice로 적합한 transport-neutral gossip/session/bootstrap contract를 `node-common`으로 이동한다.
- extraction 과정에서 previously co-located shared helper/type를 dedicated shared file로 split 하는 refactor는 허용하되, semantic behavior expansion은 scope 밖에 둔다.
- `node-jvm`이 extracted contract를 참조하도록 import/package/build wiring을 갱신한다.
- 새 module boundary에 맞는 compile/import/dependency rule test를 추가한다.
- `ADR-0025`와 관련 문서에 implementation handoff 링크를 정리한다.

## Non-Goals
- `sigilaris-node-cloudflare-workers` 구현
- Armeria, SwayDB, Typesafe Config, Java HTTP client 같은 JVM-specific integration의 공용화
- current JVM node bundle public surface의 대규모 redesign
- HotStuff runtime 전체를 한 번에 `node-common`으로 이동하는 것
- `transport-*`, `storage-*`, `runtime-*` artifact split
- Tapir client 도입 또는 shared client runtime implementation
- consensus semantics, pacemaker, validator-set continuity, bootstrap trust model 변경

## Related ADRs And Docs
- ADR-0015: JVM Node Bundle Boundary And Packaging
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions
- ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization
- ADR-0025: Shared Node Abstraction And Cross-Runtime Module Boundary
- `docs/plans/0002-sigilaris-node-jvm-extraction-plan.md`
- `docs/plans/0008-multi-node-follow-up-adr-authoring-plan.md`
- `docs/plans/README.md`
- `README.md`

## Decisions To Lock Before Implementation
- `sigilaris-node-common`은 sbt `crossProject(JSPlatform, JVMPlatform)` baseline 으로 도입한다.
- canonical dependency graph는 `sigilaris-core` <- `sigilaris-node-common` <- `sigilaris-node-jvm` 으로 고정한다.
- `sigilaris-node-common`은 `sigilaris-core`만 의존할 수 있고, `sigilaris-node-jvm` 또는 JVM-only library에 reverse/transitive dependency를 만들지 않는다.
- shared package root는 `org.sigilaris.node`로 두고, existing JVM implementation package는 `org.sigilaris.node.jvm` 아래에 유지한다.
- Phase 0의 mandatory output 은 inventory table 이다. canonical format 은 아래 column 을 최소 포함해야 한다.
  - `source package/type`
  - `classification` (`shared` / `jvm-specific` / `defer`)
  - `rationale`
  - `target package` (`shared` candidate 인 경우)
  - `blocking dependency or portability note`
- extraction candidate 판별은 ADR-0025의 litmus test를 따른다.
  - runtime-specific or platform-specific dependency를 transitively 요구하지 않아야 한다.
  - 둘 이상의 runtime module 또는 runtime-specific client/server assembly가 같은 semantic contract를 공유해야 한다.
- `java.time` 은 first extraction baseline 에서 shared public API 에 남길 수 있다.
  - current gossip/session/bootstrap contract 는 이미 `Instant` / `Duration` 을 광범위하게 사용하므로, first extraction 에서 이를 platform-independent custom time model 로 다시 설계하지 않는다.
  - JS target 은 이를 위해 compatible time dependency 를 build wiring 으로 provision 한다.
  - `java.time` 사용이 JS portability friction 의 주원인으로 드러날 때만 별도 follow-up 으로 platform-independent time representation 을 재검토한다.
- Typesafe Config loader, Armeria server adapter, OpenAPI exporter, Java HTTP client outbound transport, Workers binding 같은 concrete platform integration은 first extraction slice에서 공용화하지 않는다.
- shared HTTP contract는 Phase 0 portability review 결과가 허용할 때만 Tapir definition layer로 shared module에 둔다.
- exact Tapir module availability가 JVM + JS target 모두에서 성립하지 않으면, shared HTTP contract는 DTO / codec / semantic request-response model만 `node-common`으로 올리고 Tapir endpoint definitions 자체는 `node-jvm`에 남긴다.
- first extraction slice는 full consensus runtime 이 아니라 gossip/session/bootstrap substrate contract를 우선한다.
- `node-common` 도입은 premature transport/storage artifact split을 의미하지 않는다. current `sigilaris-node-jvm` opinionated bundle은 유지한다.
- single extraction batch 가 아래 중 하나를 만들면 해당 batch 는 더 진행하지 않고 Phase 0 inventory 재분류로 되돌린다.
  - `node-common` -> JVM-only dependency 누수
  - `node-common` 과 `node-jvm` 사이 circular dependency
  - locked non-goal 이었던 config/transport/storage glue move 필요
  - shared candidate 하나를 옮기기 위해 `5`개 이상 JVM-specific residue type 을 추가 이동해야 하는 경우

## Phase 0 Output
- shared HTTP contract strategy는 이번 배치에서 `DTO / codec / semantic model shared, Tapir definition은 runtime-specific 유지` 로 잠갔다.
- first extraction batch 는 transport-neutral gossip/session/bootstrap contract와 tx anti-entropy runtime logic까지만 `sigilaris-node-common`으로 이동하고, JVM config/bootstrap/transport assembly 는 `sigilaris-node-jvm`에 남긴다.

| source package/type | classification | rationale | target package | blocking dependency or portability note |
| --- | --- | --- | --- | --- |
| `org.sigilaris.node.jvm.runtime.gossip.{Model, Contracts, PeerRegistry, ProducerSession, SessionEngine, StaticPeerTransportAuth}` | `shared` | transport-neutral model, service contract, session engine, and shared transport-auth value objects only depend on `core`, Cats, `scodec`, and `java.time`; the overall `node-common` module additionally carries Cats Effect via the `org.sigilaris.node.gossip.tx` extraction below | `org.sigilaris.node.gossip` | JS baseline is green with existing `java.time` compatibility; random UUID generation was replaced with a cross-runtime helper |
| `org.sigilaris.node.jvm.runtime.gossip.tx` tx anti-entropy runtime family, including extracted shared `TxTopic` / `TxIdentity` helpers | `shared` | tx anti-entropy runtime logic is runtime-neutral and depends only on shared gossip contracts, Cats Effect, and `core`; extraction split the shared topic/id helpers into a dedicated shared file under the new package | `org.sigilaris.node.gossip.tx` | `TxGossipRuntimeBootstrap` stays JVM-specific because config/bootstrap assembly is bundle-owned |
| `org.sigilaris.node.jvm.runtime.gossip.StaticPeerTopologyConfig` | `jvm-specific` | Typesafe Config loader for static topology is a JVM deployment concern, not a shared node contract | - | `com.typesafe.config` |
| `org.sigilaris.node.jvm.runtime.gossip.StaticPeerTransportAuthConfig` | `jvm-specific` | config loader for shared transport auth remains JVM-owned assembly glue | - | `com.typesafe.config` |
| `org.sigilaris.node.jvm.runtime.gossip.StaticPeerBootstrapHttpTransportConfig` | `jvm-specific` | outbound HTTP tuning and peer base URI config are runtime-specific transport assembly concerns | - | `com.typesafe.config` |
| `org.sigilaris.node.jvm.runtime.gossip.tx.TxGossipRuntimeBootstrap` | `jvm-specific` | config-driven bootstrap composition depends on JVM config loaders and the opinionated JVM bundle graph | - | `com.typesafe.config`, JVM bootstrap assembly |
| `org.sigilaris.node.jvm.transport.armeria.gossip.GossipTransportAuth` | `jvm-specific` | transport proof/capability implementation uses JCA/JVM crypto APIs and HTTP header glue | - | `javax.crypto`, `java.security.MessageDigest` |
| `org.sigilaris.node.jvm.transport.armeria.gossip.TxGossipArmeriaAdapter` | `defer` | file mixes wire DTOs with Tapir server endpoints, binary wire codec, and Armeria runtime transport logic | `org.sigilaris.node.http.tx` (later, if needed) | exact Tapir shared availability is not locked for this batch; endpoint definitions remain runtime-specific |
| `org.sigilaris.node.jvm.transport.armeria.gossip.HotStuffBootstrapArmeriaAdapter` | `defer` | bootstrap wire DTOs are co-located with Tapir endpoints, Java HTTP client transport, and HotStuff-specific runtime glue | `org.sigilaris.node.http.bootstrap` (later, if needed) | first batch keeps DTOs local; Tapir/OpenAPI/HttpClient stay in `node-jvm` |

Target package mapping:
- `org.sigilaris.node.gossip`: canonical shared gossip/session/bootstrap contract root
- `org.sigilaris.node.gossip.tx`: shared tx anti-entropy/runtime-neutral layer
- `org.sigilaris.node.jvm.runtime.gossip`: JVM config loaders and HTTP bootstrap transport config only
- `org.sigilaris.node.jvm.runtime.gossip.tx`: JVM bootstrap assembly only
- `org.sigilaris.node.jvm.transport.armeria.gossip`: runtime-specific Tapir/Armeria/Java HTTP client transport layer

Boundary rules locked in code/tests:
- `core -X-> org.sigilaris.node.*`
- `node-common -X-> org.sigilaris.node.jvm`
- `node-common -X-> com.linecorp.armeria|swaydb|com.typesafe.config|java.net.http`

## Change Areas

### Code
- `build.sbt`
- 신규 `modules/node-common`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip`
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/tx`
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip`
- `modules/node-jvm/src/test/scala`

### Tests
- `modules/node-common` JVM/JS compile and unit smoke
- `modules/node-jvm` import/dependency rule suite
- gossip/runtime/transport adapter regression suites impacted by package moves

### Docs
- `docs/adr/0025-shared-node-abstraction-and-cross-runtime-module-boundary.md`
- 본 plan 문서
- 필요 시 `README.md`

## Implementation Phases

### Phase 0: Inventory And Portability Lock
- `modules/node-jvm` 안의 shared candidate를 inventory 하고, `node-common`으로 옮길 대상과 JVM-specific 으로 남길 대상을 분리한다.
- inventory output 은 이 문서 또는 companion inventory note 에 아래 column 을 가진 표로 남긴다.
  - `source package/type`
  - `classification`
  - `rationale`
  - `target package`
  - `blocking dependency or portability note`
- 최소한 아래 범주를 분리한다.
  - transport-neutral model / service contract
  - JVM-specific config loader / adapter
  - transport-specific HTTP DTO / endpoint description
  - transport-specific client/server wiring
- `java.time`, Typesafe Config, Java HTTP client, Tapir, sttp, Armeria 같은 dependency가 JVM + JS target에서 어떤 제약을 가지는지 검토한다.
- shared HTTP contract에 대해 다음 둘 중 하나를 canonical output으로 고정한다.
  - Tapir definition layer shared
  - DTO / codec / semantic model shared, Tapir definition은 runtime-specific 유지
- target package mapping과 import/dependency rule list를 이 문서 또는 companion inventory note에 남긴다.

### Phase 1: Module Skeleton And Build Wiring
- `build.sbt`에 `sigilaris-node-common` crossProject를 추가한다.
- root aggregate와 inter-module dependency를 `core <- node-common <- node-jvm`으로 갱신한다.
- `modules/node-common/shared`, `modules/node-common/jvm`, `modules/node-common/js` source layout을 만든다.
- JS target에 필요한 최소 compatibility dependency를 추가한다. 여기에는 locked decision 에 따라 `java.time` compatibility provision 이 포함될 수 있다.
- `node-common`이 `node-jvm` 없이 독립 compile 되는 smoke baseline을 만든다.

### Phase 2: First Shared Contract Extraction
- first extraction slice를 `org.sigilaris.node` namespace 아래로 이동한다.
- 우선순위는 아래 순서를 기본으로 둔다.
  - canonical rejection / identifier / session/topic model
  - peer registry / authenticator / topic contract 같은 transport-neutral service contract
  - session engine / producer-side state처럼 JVM-independent 인 runtime-neutral logic
  - shared HTTP DTO / schema / semantic request-response model
- config loader, Typesafe Config parsing, Armeria/Tapir adapter, Java HTTP client outbound transport는 `node-jvm`에 남긴다.
- package move에 따라 `node-jvm` import와 reference를 모두 갱신한다.
- 각 extraction batch 뒤에는 `node-common` JVM compile 과 JS compile 을 둘 다 green 으로 확인한다.
- extraction batch 가 locked rollback trigger 를 건드리면 해당 batch 는 더 밀지 않고 inventory 재분류 후 smaller slice 로 다시 쪼갠다.

### Phase 3: JVM Integration And Boundary Enforcement
- `node-jvm`이 extracted contract를 `node-common`에서 import 하도록 정리한다.
- `core`, `node-common`, `node-jvm` 사이 import/dependency boundary를 검증하는 test를 추가한다.
- 최소한 아래 금지 규칙을 검증한다.
  - `core -X-> node.*`
  - `node-common -X-> org.sigilaris.node.jvm`
  - `node-common -X-> com.linecorp.armeria|swaydb|com.typesafe.config|java.net.http`
- existing runtime/transport tests가 package move 뒤에도 semantic regression 없이 유지되는지 확인한다.

### Phase 4: Verification And Docs
- `node-common` JVM + JS compile/test를 green 으로 만든다.
- `node-jvm` targeted regression suite를 실행한다.
- `ADR-0025` Follow-Up / References를 새 plan 기준으로 정리한다.
- 필요 시 `README`에 새 module graph와 intended runtime layering을 짧게 반영한다.

## Test Plan
- Phase 0 Success: inventory가 shared candidate와 JVM-specific residue를 명확히 나누고, shared HTTP contract strategy가 Tapir shared 또는 DTO fallback 중 하나로 잠기는지 검토한다.
- Phase 0 Success: inventory table 이 `source package/type`, `classification`, `rationale`, `target package`, `blocking dependency or portability note` column 을 실제로 포함하는지 검토한다.
- Phase 1 Success: `node-common` JVM + JS target이 `node-jvm` 없이 독립 compile 된다.
- Phase 1 Failure: `node-common`에 JVM-only dependency가 스며들면 compile 또는 dependency check에서 즉시 실패한다.
- Phase 2 Success: first extraction slice가 `org.sigilaris.node` namespace 아래로 이동하고, `node-jvm`은 local duplicate 없이 이를 참조한다.
- Phase 2 Success: 각 extraction batch 뒤 `node-commonJS/compile` 이 계속 green 이다.
- Phase 2 Failure: Typesafe Config, Armeria, Java HTTP client, OpenAPI exporter 같은 JVM-specific integration이 실수로 shared source set에 들어오지 않는지 검증한다.
- Phase 2 Failure: single batch 가 rollback trigger 를 충족하면 즉시 reclassify 되고, non-goal boundary 를 넘긴 migration 이 계속되지 않는지 검증한다.
- Phase 3 Success: import/dependency rule test가 `core <- node-common <- node-jvm` 경계를 강제한다.
- Phase 3 Success: existing gossip/runtime/transport suite가 package move 뒤에도 semantic regression 없이 통과한다.
- Phase 4 Success: docs가 새 module graph와 shared HTTP contract strategy를 일관되게 설명한다.

## Risks And Mitigations
- shared candidate 범위가 너무 넓어지면 extraction이 멈출 수 있다. first slice를 gossip/session/bootstrap contract로 제한하고 full consensus runtime 이동은 defer 한다.
- shared source set에 JVM-only dependency가 섞이면 JS compile이 막힌다. Phase 0 portability review와 Phase 1 JS compile smoke를 early gate로 둔다.
- `java.time` 사용이 JS portability friction 을 만들 수 있다. first extraction baseline 에서는 compatible dependency 로 수용하고, friction 이 지속될 때만 separate follow-up 으로 representation redesign 을 검토한다.
- Tapir shared strategy가 실제 target availability와 맞지 않으면 implementation 중간에 뒤집힐 수 있다. Phase 0에서 availability 검증을 먼저 하고, 실패 시 DTO fallback을 canonical rule로 즉시 잠근다.
- package rename churn이 커지면 regression debug 비용이 커진다. thin slice 단위로 move 하고 각 extraction batch 뒤 JVM/JS compile 을 모두 돌린다.
- config model과 semantic model이 한 파일에 섞여 있으면 extraction 중 경계가 흐려질 수 있다. loader/parser는 runtime-specific, validated value object는 shared candidate로 분해한다.
- single batch 가 예상보다 많은 JVM-specific residue 나 circular dependency를 만들면 손실이 커질 수 있다. rollback trigger 를 명시해 batch 중단과 inventory 재분류를 바로 수행한다.
- `node-common` 도입이 artifact split pressure로 오해될 수 있다. plan과 ADR 모두에서 opinionated `node-jvm` bundle 유지 원칙을 계속 명시한다.

## Acceptance Criteria
1. 새 cross-project module `sigilaris-node-common`이 repository에 추가되고, canonical dependency graph가 `sigilaris-core` <- `sigilaris-node-common` <- `sigilaris-node-jvm`으로 정렬된다.
2. `sigilaris-node-common`은 JVM + JS 양쪽에서 compile 가능하고, `node-jvm`이나 JVM-only transport/storage dependency를 직접 또는 transitively 요구하지 않는다.
3. first shared extraction slice가 `org.sigilaris.node` namespace 아래로 이동하고, `node-jvm`은 local duplicate 대신 shared contract를 사용한다.
4. shared HTTP contract strategy가 Tapir shared 또는 DTO fallback 중 하나로 명시적으로 잠기고, build/test/doc에서 일관되게 반영된다.
5. import/dependency rule test가 `core`, `node-common`, `node-jvm` 경계를 강제한다.
6. targeted regression suite와 docs update가 extraction 뒤에도 green / consistent 상태를 유지한다.

## Checklist

### Phase 0: Inventory And Portability Lock
- [x] shared candidate vs JVM-specific residue inventory를 작성한다.
- [x] inventory table 을 locked column format 으로 남긴다.
- [x] shared HTTP contract strategy를 Tapir shared 또는 DTO fallback 중 하나로 잠근다.
- [x] `java.time` compatibility handling 을 build/dependency 관점에서 확인한다.
- [x] target package mapping과 boundary rule 목록을 문서에 남긴다.

### Phase 1: Module Skeleton And Build Wiring
- [x] `build.sbt`에 `sigilaris-node-common` crossProject를 추가한다.
- [x] root aggregate와 `core <- node-common <- node-jvm` dependency wiring을 갱신한다.
- [x] `modules/node-common` JVM/JS source layout과 최소 compile smoke를 만든다.

### Phase 2: First Shared Contract Extraction
- [x] first extraction slice를 `org.sigilaris.node` namespace 아래로 이동한다.
- [x] `node-jvm` import/reference를 shared contract 기준으로 갱신한다.
- [x] JVM-specific config/transport glue가 shared source set에 남지 않도록 정리한다.
- [x] 각 extraction batch 뒤 `node-common` JVM/JS compile green 을 확인한다.
- [x] rollback trigger 발생 시 batch 를 재분류하고 smaller slice 로 다시 쪼갠다.

### Phase 3: JVM Integration And Boundary Enforcement
- [x] `core`, `node-common`, `node-jvm` import/dependency rule test를 추가한다.
- [x] package move로 영향 받은 gossip/runtime/transport regression suite를 정리한다.
- [x] JS compile failure 또는 boundary violation이 CI-visible 하게 surface 되도록 한다.

### Phase 4: Verification And Docs
- [x] `node-common` JVM + JS compile/test를 green 으로 만든다.
- [x] `node-jvm` targeted regression suite를 실행한다.
- [x] ADR-0025와 관련 docs를 새 plan 기준으로 정리한다.

## Follow-Ups
- future `sigilaris-node-cloudflare-workers` runtime plan
- first extraction slice 이후 remaining consensus/runtime/shared contract inventory를 다룰 별도 follow-up extraction plan
- shared HTTP contract strategy가 DTO fallback으로 잠길 경우, later Tapir portability 재평가 plan
- 필요 시 `node-common` public API curation / compatibility policy 문서화
