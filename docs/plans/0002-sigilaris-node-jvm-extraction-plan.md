# 0002 - Sigilaris Node JVM Extraction

## Status
Phase 4 Complete

## Created
2026-03-28

## Last Updated
2026-03-28

## Background
- `ADR-0015`에서 `sigilaris`는 공통 JVM node infrastructure를 `sigilaris-node-jvm`으로 제공하고, application-specific domain은 downstream application에 남기기로 결정했다.
- 현재 downstream node 모듈에는 runtime bootstrap, HTTP serving, OpenAPI export, persistence wiring, application-specific domain assembly가 함께 섞여 있다.
- 이 상태에서는 reusable JVM node baseline을 `sigilaris` 쪽에서 발전시키기 어렵고, downstream application도 공통 인프라와 도메인 변경을 분리해 관리하기 어렵다.
- 동시에 transport/storage를 별도 artifact로 premature split 하는 것은 현재 요구 대비 비용이 크므로, 1차 목표는 Armeria + SwayDB를 포함하는 opinionated JVM node bundle을 안전하게 추출하는 것이다.
- 이번 작업은 새 모듈 추가, 패키지 경계 설정, downstream adoption, 회귀 검증, 문서 갱신이 함께 움직이는 다중 단계 변경이다.

## Goal
- `sigilaris`가 `sigilaris-core` 위에 쌓이는 JVM 전용 node bundle `sigilaris-node-jvm`을 제공하게 한다.
- downstream application이 새 공통 runtime seam을 import해 기존 node startup/execution path를 유지하도록 만든다.
- Armeria / SwayDB 기반 JVM node infrastructure를 `sigilaris`로 올리되, application-specific domain과 조립 코드는 downstream에 남긴다.
- 내부 package seam(`runtime`, `transport`, `storage`)을 실제 코드와 검증 절차로 유지해, 추후 artifact 분리 가능성을 남긴다.

## Scope
- `sigilaris/build.sbt`에 JVM 전용 `sigilaris-node-jvm` 모듈을 추가한다.
- 새 모듈의 canonical package root와 internal seam(`runtime`, `transport`, `storage`)을 만든다.
- runtime bootstrap / lifecycle과 generic node execution flow를 공통 seam 기준으로 먼저 추출한다.
- Armeria server wiring, streaming wiring, OpenAPI export helper, SwayDB persistence wiring을 새 모듈로 이동한다.
- downstream application이 `sigilaris-node-jvm`을 의존하도록 전환하고, 중복된 공통 인프라를 제거한다.
- package dependency / import rule 체크와 code review checklist 기준을 plan 범위 안에서 정의한다.
- 새 모듈 도입에 맞춰 필요한 문서와 build wiring을 갱신한다.

## Non-Goals
- `sigilaris-armeria`, `sigilaris-storage-swaydb` 같은 세분 artifact를 이번 plan에서 추가하지 않는다.
- Cloudflare Workers 등 비 JVM runtime 지원은 이번 plan 범위에 넣지 않는다.
- payment / escrow / credit / debt 같은 application-specific domain을 `sigilaris`로 이동하지 않는다.
- application-specific transaction, reducer, query/view model 전체를 공통화하지 않는다.
- consensus, P2P, multi-node networking 같은 새로운 node capability를 도입하지 않는다.

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0013: Application Package Realignment
- ADR-0015: JVM Node Bundle Boundary And Packaging
- `docs/plans/plan-template.md`
- `README.md`

## Decisions To Lock Before Implementation
- canonical artifact name은 `sigilaris-node-jvm`으로 유지한다.
- `sigilaris-node-jvm`은 `sigilaris-core`에 의존하고, `sigilaris-core`는 `sigilaris-node-jvm`을 의존하지 않는다.
- 1차 배포는 opinionated bundle로서 Armeria + SwayDB를 함께 제공한다.
- internal seam은 최소한 `org.sigilaris.node.jvm.runtime`, `org.sigilaris.node.jvm.transport.armeria`, `org.sigilaris.node.jvm.storage`(abstract contract), `org.sigilaris.node.jvm.storage.memory`, `org.sigilaris.node.jvm.storage.swaydb` 수준으로 나눈다.
- 위 `storage`는 artifact 추가가 아니라 internal package seam이다. ADR-0015의 "single opinionated bundle" 결정은 유지한다.
- 첫 이동 단위는 runtime bootstrap / lifecycle과 generic node execution flow로 고정한다.
- Armeria `Server` 같은 최상위 runtime handle 노출은 허용하지만, 공통 runtime/service trait가 Armeria builder나 SwayDB collection 타입을 직접 요구하는 것은 금지한다.
- `runtime -> storage` 의존은 abstract storage contract package(`org.sigilaris.node.jvm.storage`)까지만 허용하고, `runtime -> storage.memory|storage.swaydb` 같은 구현 package 의존은 금지한다.
- seam 검증 규칙은 Phase 1부터 적용한다. implementation plan 실행 중 package dependency / import rule 체크와 code review checklist를 함께 운용한다.
- Phase 2로 넘어가는 gate(= Phase 1 완료 gate)는 downstream application이 새 공통 runtime seam을 import해 기존 node startup/execution path를 유지하는 것을 확인하는 것이다.
- Phase 3로 넘어가는 gate는 downstream application이 migration된 runtime seam 위에서 Armeria + SwayDB wiring을 사용해 기존 node startup/execution path를 계속 유지하는 것을 확인하는 것이다.
- Phase 4 완료 gate는 verification/docs 완료와 Acceptance Criteria 1-6 충족을 확인하는 것이다.
- detailed phase gate의 canonical record는 이 문서의 `Phase Gate Appendix`에 둔다.

## Change Areas

### Code
- `build.sbt`
- 신규 `modules/node-jvm`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime` (Phase 1)
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage` (`KeyValueStore`, `HashStore`, `SingleValueStore`, `StoreIndex`) (Phase 1)
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage/memory` (Phase 1)
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria` (Phase 2)
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage/swaydb` (Phase 2)
- 필요 시 `modules/node-jvm/src/test/scala`
- downstream application의 node 모듈 import/build wiring

### Tests
- `modules/node-jvm/src/test/scala`
- package dependency / import rule check 정의 또는 관련 test/check wiring
- downstream application 쪽 node integration test/build wiring 변경

### Docs
- `README.md`
- 필요 시 `docs/README.md` 또는 module introduction 문서
- 새 plan/ADR 간 상호 링크
- downstream migration note 또는 adoption note

## Implementation Phases

### Phase 0: Inventory And Boundary Lock
- 현재 downstream node 모듈에서 공통 JVM node infrastructure와 application-specific domain/application assembly를 inventory로 분리한다.
- runtime bootstrap / lifecycle, generic execution flow, Armeria wiring, SwayDB wiring, OpenAPI export를 각각 어느 패키지로 옮길지 target mapping을 확정한다.
- `sigilaris-node-jvm`의 package root와 internal seam을 잠근다.
- seam 검증 방식(package dependency / import rule 체크, code review checklist)과 Phase 2 gate 조건을 실제 작업 항목으로 문서화한다.
- Phase 0 산출물은 이 문서의 `Phase 0 Artifacts` appendix를 채우는 방식으로 저장소에 남긴다. PR description만으로 대체하지 않는다.

### Phase 1: Module Skeleton And Runtime Seam
- `sigilaris/build.sbt`에 JVM 전용 `sigilaris-node-jvm` 모듈을 추가한다.
- 새 모듈이 `sigilaris-core`에만 의존하도록 build wiring을 구성한다.
- `runtime` package 아래에 공통 bootstrap / lifecycle / execution seam을 만든다.
- `NodeInitializationService` 관련 bootstrap invocation contract boundary를 `runtime` seam 기준으로 먼저 고정한다.
- `storage` package 아래에 `runtime`이 참조할 수 있는 abstract storage contract 파일(`KeyValueStore`, `HashStore`, `SingleValueStore`, `StoreIndex`)을 만든다.
- runtime seam smoke/test를 위해 `storage.memory` 아래에 최소 in-memory implementation을 함께 만든다.
- runtime seam이 transport/storage 구현 타입에 직접 의존하지 않도록 첫 package dependency / import rule 체크를 도입한다.
- downstream application이 새 runtime seam을 import해 기존 node startup/execution path를 유지하는 최소 integration을 만든다.

### Phase 2: Transport And Storage Migration
- `transport.armeria` 아래로 server startup, streaming, OpenAPI export 관련 공통 코드를 이동한다.
- `storage.swaydb` 아래로 persistence wiring과 관련 helper를 이동한다.
- `NodeInitializationService`의 concrete split 필요성을 재평가하고, runtime seam에 남길 contract와 downstream retained 구현 경계를 확정한다.
- streaming/event persistence 연계는 transport가 storage를 직접 참조하지 않도록 runtime/downstream service contract 또는 wiring parameter 형태로 정리한다.
- 공통 runtime/service trait에는 Armeria builder나 SwayDB collection 타입이 새지 않도록 API surface를 정리한다.
- package dependency / import rule 체크를 transport/storage까지 확장한다.
- downstream application이 새 runtime seam 위에서 Armeria + SwayDB wiring을 사용해 기존 node를 계속 구동할 수 있는지 확인한다. 이 확인을 Phase 3 진입 gate로 사용한다.

### Phase 3: Downstream Adoption And Cleanup
- downstream application이 `sigilaris-node-jvm`을 정식 의존하도록 build를 전환한다.
- 새 모듈로 이동한 공통 인프라의 중복 구현을 downstream node 모듈에서 제거한다.
- application-specific domain, reducer, query/view model, assembly가 downstream에 남아 있는지 점검한다.
- `sigilaris`와 downstream 사이에 application-specific dependency 역류가 없는지 확인한다.

### Phase 4: Verification And Docs
- `sigilaris-node-jvm` 단독 compile/test와 downstream integration test를 함께 실행한다.
- OpenAPI export, server startup, SwayDB-backed persistence path에 대한 smoke/regression을 기록한다.
- README와 관련 문서에 `sigilaris-node-jvm`의 역할, `sigilaris-core`와의 관계, opinionated bundle 성격을 반영한다.
- 후속 분리 기준이 될 seam policy와 adoption note를 남긴다.

## Test Plan
- `sigilaris-node-jvm`이 `sigilaris-core`를 의존하면서 독립적으로 컴파일되는지 확인한다.
- `sigilaris` 저장소에서는 `sigilaris-node-jvm` compile/test, package dependency rule, local smoke test를 검증한다.
- Phase 1에서 downstream application이 새 runtime seam만 import해 기존 node startup/execution path를 유지하는 smoke test를 만든다. 이 smoke test는 downstream application workspace에서 실행하고, adoption 이후에는 downstream CI에 연결한다.
- Phase 2에서 Armeria startup과 OpenAPI export가 새 모듈 경유로 동작하는지 확인한다.
- Phase 2에서 SwayDB persistence wiring이 새 모듈 경유로 동작하는지 확인한다.
- Phase 2의 migrated runtime seam + Armeria + SwayDB 구동 smoke는 downstream application workspace에서 실행하고, Phase 3 진입 gate로 사용한다.
- package dependency / import rule 체크로 `runtime`이 `transport.armeria` / `storage.swaydb` 구현 타입에 직접 결합되지 않는지 검증한다.
- downstream application regression으로 application-specific domain path가 깨지지 않았는지 확인한다.
- public API에 금지된 타입 노출이 생기지 않았는지 review checklist와 compile surface inspection으로 확인한다.

## Risks And Mitigations
- 공통 인프라 추출 중 application-specific code가 `sigilaris`로 잘못 이동할 수 있다. inventory와 Phase 3 cleanup check로 경계를 반복 확인한다.
- runtime seam이 이름만 있고 실제로는 Armeria/SwayDB에 직접 결합될 수 있다. package dependency / import rule 체크와 API review example을 함께 사용한다.
- `sigilaris-node-jvm` public surface에 implementation detail leakage가 누적될 수 있다. 허용/비허용 예시를 리뷰 기준으로 사용하고, builder/collection 타입 노출은 차단한다.
- build wiring 추가로 publish/CI 구성이 복잡해질 수 있다. Phase 1에서 모듈 wiring만 먼저 안정화하고 이후 migration을 진행한다.
- downstream adoption 단계에서 startup path가 깨질 수 있다. Phase 2 진입 전에 runtime seam import 기반 smoke path를 gate로 둔다.

## Acceptance Criteria
1. `sigilaris`는 새 JVM 전용 artifact `sigilaris-node-jvm`을 build에 포함하고, 이 모듈은 `sigilaris-core` 위에 쌓이는 구조로 컴파일된다.
2. `sigilaris-node-jvm` 안에 `runtime`, `storage`, `storage.memory`, `transport.armeria`, `storage.swaydb` seam이 존재하고, `runtime`은 transport/storage implementation package에 직접 결합되지 않는다.
3. downstream application은 새 공통 runtime seam을 import해 기존 node startup/execution path를 유지할 수 있고, 이는 downstream application workspace/CI smoke path로 검증된다.
4. Armeria server wiring, OpenAPI export, SwayDB persistence wiring은 `sigilaris-node-jvm`으로 이동하고, application-specific domain/reducer/query/view model은 downstream에 남는다.
5. package dependency / import rule 체크와 review checklist가 plan 범위 안에서 정의되고 실제 검증 흐름에 연결된다.
6. 관련 문서가 `sigilaris-node-jvm`의 역할과 `sigilaris-core`와의 관계를 설명하도록 갱신된다.

## Checklist

### Phase 0: Inventory And Boundary Lock
- [x] 공통 JVM node infrastructure와 application-specific 영역 inventory 정리 완료
- [x] target package mapping 정리 완료
- [x] `sigilaris-node-jvm` package root와 internal seam 확정 완료
- [x] seam 검증 방식과 Phase gate 문서화 완료
- [x] 이 문서의 `Phase 0 Artifacts` appendix 갱신 완료
- [x] Phase 0 sign-off commit 기준 정리 완료

### Phase 1: Module Skeleton And Runtime Seam
- [x] `sigilaris-node-jvm` build wiring 추가 완료
- [x] `sigilaris-node-jvm -> sigilaris-core` 의존 방향 구성 완료
- [x] `runtime` seam 추출 완료 (`NodeRuntime` lifecycle shell 기준)
- [x] `NodeInitializationService` bootstrap invocation contract boundary 고정 완료
- [x] `storage` abstract contract 파일 추가 완료
- [x] `storage.memory` in-memory implementation 추가 완료
- [x] 첫 package dependency / import rule 체크 연결 완료
- [x] downstream application이 새 runtime seam으로 기존 startup/execution path를 유지하는 최소 integration 확인 완료

### Phase 2: Transport And Storage Migration
- [x] Armeria wiring 이동 완료
- [x] SwayDB wiring 이동 완료
- [x] `NodeInitializationService` concrete split 재평가 및 runtime/downstream 경계 확정 완료
- [x] streaming/event persistence 연계 contract 정리 완료 (`transport.armeria -X-> storage.*` 유지)
- [x] 공통 runtime/service trait에서 금지된 Armeria/SwayDB 타입 노출 제거 완료
- [x] package dependency / import rule 체크를 transport/storage까지 확장 완료
- [x] downstream application이 새 runtime seam 위에서 기존 node를 계속 구동하는 smoke path 확인 완료
- [x] Phase 3 진입 gate 충족 완료

### Phase 3: Downstream Adoption And Cleanup
- [x] downstream application의 `sigilaris-node-jvm` 정식 의존 전환 완료
- [x] downstream node 모듈의 공통 인프라 중복 구현/소스 파일 제거 완료
- [x] application-specific domain/reducer/query/view/assembly가 downstream에 남아 있는지 점검 완료
- [x] application-specific dependency 역류 없음 확인 완료

### Phase 4: Verification And Docs
- [x] `sigilaris-node-jvm` compile/test와 downstream integration 검증 완료
- [x] OpenAPI export / server startup / SwayDB persistence smoke-regression 기록 완료
- [x] 문서 갱신 완료
- [x] 후속 과제 정리 완료

## Phase 0 Artifacts

### Inventory Appendix
- Phase 0에서 현재 공통 JVM node infrastructure와 downstream application 전용 영역의 분리 결과를 여기에 기록한다.
- 여기서 `bbgo3`는 `sigilaris-node-jvm`을 채택할 예정인 현재 downstream application 저장소를 뜻한다.

| Source area | Current responsibility | Classification | Phase target | Notes |
| --- | --- | --- | --- | --- |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/store/{KeyValueStore,HashStore,SingleValueStore,StoreIndex}.scala` | generic key-value, single-value, hash, ordered index abstraction | reusable JVM node infrastructure | `org.sigilaris.node.jvm.storage` | 현재 `store/*` 공통 infra 범위는 위 4개 contract 파일로 고정한다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/store/interpreter/*` | SwayDB-backed store resource opening/close, byte codec bridge | reusable JVM node infrastructure | `org.sigilaris.node.jvm.storage.swaydb` | opinionated SwayDB bundle의 핵심 구현이다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/store/memory/InMemoryStores.scala` | in-memory test/runtime store implementation | reusable JVM node infrastructure | `org.sigilaris.node.jvm.storage.memory` | Phase 1 runtime seam smoke와 downstream test 유지에 필요하다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/runtime/StorageLayout.scala` | persistent storage directory layout policy | reusable JVM node infrastructure | `org.sigilaris.node.jvm.storage.swaydb` | SwayDB 전용 layout 이므로 runtime package가 아니라 storage seam에 둔다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/runtime/NodeRuntime.scala` | runtime resource lifecycle + storage mode 선택 + repo/service assembly | mixed | split across `org.sigilaris.node.jvm.runtime` and downstream retained wiring | generic lifecycle shell은 공통화하고, BBGO repo/service assembly는 downstream adapter로 남긴다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/service/NodeInitializationService.scala` | startup 시 genesis bootstrap 실행 | mixed | Phase 1에서 `runtime` seam 위 bootstrap invocation contract를 고정하고, concrete block/state repository split은 downstream retained 상태로 Phase 2 이후 재평가 | 초기 실행 흐름은 공통 seam 후보지만 현재는 BBGO block/repo 타입에 직접 묶여 있다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/NodeApp.scala` | Armeria server startup, Tapir endpoint aggregation, streaming endpoint wiring | mixed | split across `org.sigilaris.node.jvm.transport.armeria` and downstream retained endpoint list | server resource / builder helper는 공통화하고, BBGO endpoint catalog는 downstream에 남긴다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/OpenApiExporter.scala` | OpenAPI export CLI and endpoint documentation aggregation | mixed | split across `org.sigilaris.node.jvm.transport.armeria` and downstream retained endpoint catalog | serialization / file writing helper는 공통화 가능하나 documented endpoint list는 downstream-specific 이다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/NodeMain.scala` | config load, runtime selection, application assembly, lifecycle entrypoint | downstream application assembly | downstream retained | `sigilaris-node-jvm` seam을 import 하는 adoption 대상이다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/repository/*` | block/tx/state/event repository contract + BBGO schema/state wiring | downstream application domain/integration | downstream retained | core feature schema와 BBGO API/domain 타입을 함께 참조하므로 공통 infra로 올리지 않는다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/api/*` | query/post-tx/static validation endpoint service | downstream application domain/integration | downstream retained | application-specific API contract와 query/view model을 가진다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/payment/*` | payment domain blueprint/reducer | downstream application domain | downstream retained | ADR 범위 밖이다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/escrow/*` | escrow domain blueprint/reducer | downstream application domain | downstream retained | ADR 범위 밖이다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/credit/*` | credit domain blueprint/reducer | downstream application domain | downstream retained | ADR 범위 밖이다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/debt/*` | debt domain blueprint/reducer | downstream application domain | downstream retained | ADR 범위 밖이다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/commerce/*` | commerce helper / UTXO support | downstream application domain | downstream retained | payment/credit/escrow 흐름에 묶여 있다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/ClientFailureMessage.scala` | application-facing error message mapping | downstream application integration | downstream retained | public error surface는 downstream contract로 본다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/ConflictMessage.scala` | application-facing conflict message mapping | downstream application integration | downstream retained | public error surface는 downstream contract로 본다. |
| `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/SigilarisFailureClassifier.scala` | sigilaris/core failure -> BBGO API failure bridge | downstream application integration | downstream retained | downstream HTTP/API 정책에 속한다. |

### Target Mapping Appendix
- Phase 0에서 source package/file 영역이 `runtime`, `storage`, `storage.memory`, `transport.armeria`, `storage.swaydb`, 또는 downstream retained 영역 중 어디로 가는지 여기에 기록한다.

| Source package/file | Target package | Extraction action |
| --- | --- | --- |
| `bbgo3/.../node/runtime/NodeRuntime.scala` | `org.sigilaris.node.jvm.runtime` | runtime selection, storage mode 표현, resource lifecycle shell을 먼저 추출하고 BBGO repo/service 조립은 downstream adapter로 남긴다. |
| `bbgo3/.../node/service/NodeInitializationService.scala` | `org.sigilaris.node.jvm.runtime` + downstream retained | Phase 1에서는 bootstrap invocation contract만 runtime seam으로 고정하고, concrete repo/block 타입 의존 구현은 downstream retained 상태로 둔다. |
| `bbgo3/.../node/store/KeyValueStore.scala` | `org.sigilaris.node.jvm.storage` | package rename + downstream imports 전환 |
| `bbgo3/.../node/store/HashStore.scala` | `org.sigilaris.node.jvm.storage` | package rename + downstream imports 전환 |
| `bbgo3/.../node/store/SingleValueStore.scala` | `org.sigilaris.node.jvm.storage` | package rename + downstream imports 전환 |
| `bbgo3/.../node/store/StoreIndex.scala` | `org.sigilaris.node.jvm.storage` | package rename + downstream imports 전환 |
| `bbgo3/.../node/store/memory/InMemoryStores.scala` | `org.sigilaris.node.jvm.storage.memory.InMemoryStores` | implementation move (Phase 1) |
| `bbgo3/.../node/store/interpreter/Bag.scala` | `org.sigilaris.node.jvm.storage.swaydb.Bag` | implementation move (Phase 2) |
| `bbgo3/.../node/store/interpreter/KeyValueSwayStore.scala` | `org.sigilaris.node.jvm.storage.swaydb.KeyValueSwayStore` | implementation move (Phase 2) |
| `bbgo3/.../node/store/interpreter/StoreIndexSwayInterpreter.scala` | `org.sigilaris.node.jvm.storage.swaydb.StoreIndexSwayInterpreter` | implementation move (Phase 2) |
| `bbgo3/.../node/store/interpreter/SwayStores.scala` | `org.sigilaris.node.jvm.storage.swaydb.SwayStores` | implementation move (Phase 2) |
| `bbgo3/.../node/runtime/StorageLayout.scala` | `org.sigilaris.node.jvm.storage.swaydb.StorageLayout` | implementation move (Phase 2); downstream은 as-is layout policy를 consume하고 별도 확장 포인트는 두지 않는다. |
| `bbgo3/.../node/NodeApp.scala` | `org.sigilaris.node.jvm.transport.armeria` + downstream retained | Armeria server resource / endpoint-to-service helper를 추출하고 BBGO endpoint catalog는 downstream에 유지한다. |
| `bbgo3/.../node/OpenApiExporter.scala` | `org.sigilaris.node.jvm.transport.armeria` + downstream retained | OpenAPI write helper를 추출하고 BBGO endpoint catalog + CLI entrypoint는 downstream adapter로 남긴다. |
| `bbgo3/.../node/NodeMain.scala` | downstream retained | 새 runtime seam / transport / storage entrypoint를 import 하는 adoption 대상 |
| `bbgo3/.../node/repository/*` | downstream retained | 새 storage/runtime seam을 사용하도록 imports만 전환 |
| `bbgo3/.../node/api/*` | downstream retained | 새 runtime/transport seam을 사용하도록 imports만 전환 |
| `bbgo3/.../node/payment/*`, `escrow/*`, `credit/*`, `debt/*`, `commerce/*` | downstream retained | 변경 없음. extraction 이후 dependency 역류만 점검한다. |

### Seam Validation Appendix
- package root는 `org.sigilaris.node.jvm.runtime`, `org.sigilaris.node.jvm.transport.armeria`, `org.sigilaris.node.jvm.storage`, `org.sigilaris.node.jvm.storage.memory`, `org.sigilaris.node.jvm.storage.swaydb`로 고정한다.
- `runtime` source는 `transport.armeria`, `storage.memory`, `storage.swaydb` package import를 금지한다.
- Phase 1에서는 `modules/node-jvm/src/test/scala`에 source-level import rule test를 추가해 `runtime`이 `com.linecorp.armeria`, `sttp.tapir.server.armeria`, `swaydb`, `org.sigilaris.node.jvm.transport.armeria`, `org.sigilaris.node.jvm.storage.memory`, `org.sigilaris.node.jvm.storage.swaydb`를 직접 import하지 않는지 검사한다.
- 위 import rule은 `modules/node-jvm/src/main/scala` production source에 적용하고, test source는 runtime seam smoke를 위해 `storage.memory`를 사용할 수 있다.
- Phase 2에서는 위 rule을 `transport.armeria` / `storage.memory` / `storage.swaydb` 내부 package까지 확장한다.
- 허용된 dependency 방향:
  - `runtime -> storage` (abstract contract package only)
  - `transport.armeria -> runtime`
  - `transport.armeria -X-> storage.*` (streaming/event persistence 연계도 runtime/downstream stream service abstraction 또는 wiring parameter로만 전달하며, wiring parameter는 service abstraction, endpoint collection, config value object만 담을 수 있고 concrete storage implementation type/opaque handle을 담지 않는다)
  - `storage.memory -> storage`
  - `storage.swaydb -> storage`
  - `downstream -> runtime|transport.armeria|storage.*`
- code review checklist:
  - `runtime` public API가 Armeria builder/service DSL 또는 SwayDB collection/map 타입을 직접 요구하지 않는가
  - `transport.armeria` helper가 downstream endpoint list를 들고 있지 않고 endpoint/service collection을 인자로 받는가
  - `storage.swaydb` helper가 application-specific schema/repository 타입을 import하지 않는가
  - downstream retained package에 남겨야 할 payment/escrow/credit/debt/query/view/assembly 코드가 `sigilaris`로 이동하지 않았는가

### Phase Gate Appendix
- Phase 0 sign-off:
  - 이 appendix와 checklist 갱신을 담은 commit을 Phase 0 baseline으로 사용한다.
- Phase 1 완료 gate:
  - `sigilaris`에 `sigilaris-node-jvm` 모듈이 추가되고 `compile`이 성공한다.
  - `org.sigilaris.node.jvm.storage` abstract contract 파일이 생성되고 `runtime` seam과 함께 compile 된다.
  - `org.sigilaris.node.jvm.storage.memory` in-memory implementation이 compile 되고 runtime seam smoke/test path에서 동작한다.
  - `bbgo3`의 runtime entry path가 `org.sigilaris.node.jvm.runtime` seam을 import해 기존 `NodeMain -> runtime 선택 -> initialization -> server resource` 흐름을 유지한다.
  - `runtime` import rule test가 green 이다.
- Phase 2 완료 gate:
  - `org.sigilaris.node.jvm.storage.swaydb` 아래 SwayDB wiring/helper와 `StorageLayout` 이동이 완료되고 compile/test 된다.
  - `bbgo3`의 persistent runtime path가 `org.sigilaris.node.jvm.storage.swaydb.StorageLayout` 및 SwayDB store helper를 사용한다.
  - `bbgo3`의 server/OpenAPI path가 `org.sigilaris.node.jvm.transport.armeria` helper를 사용한다.
  - `NodeInitializationService` split 재평가 결과가 문서/체크리스트에 반영되고, runtime/downstream 경계가 확정된다.
  - streaming/event persistence path가 `transport.armeria -> runtime/service abstraction` 경유로 유지되고 `transport.armeria -> storage.*` 직접 의존이 없으며 wiring parameter에도 concrete storage implementation type/opaque handle이 없다.
  - `bbgo3`가 migrated runtime seam + Armeria + SwayDB 경유로 기존 node를 계속 구동할 수 있는 smoke path를 유지한다.
  - transport/storage까지 확장된 package dependency / import rule 체크가 green 이다.
  - `runtime` public surface에 Armeria builder / SwayDB collection 타입 누수가 없음을 compile surface와 review checklist로 확인한다.
- Phase 3 완료 gate:
  - `bbgo3` build가 published/local `sigilaris-node-jvm`을 정식 dependency로 사용하고, 중복 infra source를 제거한 상태로 compile/test 된다.
  - `sigilaris`에서 application-specific dependency가 생기지 않고, `bbgo3` application-specific package가 downstream에 남아 있다.
- Phase 4 완료 gate:
  - `sigilaris-node-jvm` compile/test, package dependency rule, smoke/regression 기록, README/adoption note 갱신이 모두 완료된다.
  - Acceptance Criteria 전체가 충족되었음을 검토 결과와 검증 로그로 확인한다.

### Completion Appendix
- `NodeInitializationService` concrete split 재평가 결과:
  - 공통 모듈에는 `NodeInitializer` 기반 bootstrap invocation contract만 유지한다.
  - BBGO의 `NodeInitializationService` concrete implementation은 `BlockRepo`, `StateRepo`, genesis/application bootstrap semantics에 직접 묶여 있으므로 downstream retained 로 확정한다.
- streaming/event persistence contract 정리 결과:
  - `bbgo3`의 `NodeApp`은 endpoint catalog 와 `QueryService`/`TxStreamService` assembly 만 유지한다.
  - 공통 `org.sigilaris.node.jvm.transport.armeria.ArmeriaServer` helper 는 `ArmeriaServerConfig` 와 `List[ServerEndpoint[Any, F]]` 만 받고 storage type 이나 opaque handle 을 직접 받지 않는다.
  - event streaming/persistence 연계는 downstream service abstraction 경유로만 유지되며 `transport.armeria -> storage.*` 직접 의존은 없다.
- downstream adoption / cleanup 결과:
  - `bbgo3/build.sbt` 는 `org.sigilaris:sigilaris-node-jvm_3:0.1.2-SNAPSHOT` 을 정식 dependency 로 사용한다.
  - `bbgo3/modules/node` 의 공통 store/runtime infrastructure source 는 제거하고, domain/reducer/query/view/assembly 코드는 downstream 에 유지했다.
- verification log (2026-03-28):
  - `sigilaris`: `sbt nodeJvm/test`
  - `sigilaris`: `sbt publishLocal`
  - `bbgo3`: `sbt node/compile`
  - `bbgo3`: `sbt node/test`

## Follow-Ups
- transport/storage를 실제로 독립 artifact로 분리할 시점과 기준 재평가
- Cloudflare Workers 등 두 번째 runtime을 위한 shared node abstraction 재검토
- `sigilaris-node-jvm` public API surface를 더 좁힐 수 있는 후속 정리
- 필요 시 downstream migration guide를 별도 문서로 승격
