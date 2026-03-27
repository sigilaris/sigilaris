# 0002 - Sigilaris Node JVM Extraction

## Status
Draft

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
- internal seam은 최소한 `org.sigilaris.node.jvm.runtime`, `org.sigilaris.node.jvm.transport.armeria`, `org.sigilaris.node.jvm.storage.swaydb` 수준으로 나눈다.
- 첫 이동 단위는 runtime bootstrap / lifecycle과 generic node execution flow로 고정한다.
- Armeria `Server` 같은 최상위 runtime handle 노출은 허용하지만, 공통 runtime/service trait가 Armeria builder나 SwayDB collection 타입을 직접 요구하는 것은 금지한다.
- seam 검증 규칙은 Phase 1부터 적용한다. implementation plan 실행 중 package dependency / import rule 체크와 code review checklist를 함께 운용한다.
- Phase 2로 넘어가는 gate는 downstream application이 새 공통 runtime seam을 import해 기존 node startup/execution path를 유지하는 것을 확인하는 것이다.
- Phase 3로 넘어가는 gate는 downstream application이 migration된 runtime seam 위에서 Armeria + SwayDB wiring을 사용해 기존 node startup/execution path를 계속 유지하는 것을 확인하는 것이다.

## Change Areas

### Code
- `build.sbt`
- 신규 `modules/node-jvm`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage/swaydb`
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
- runtime seam이 transport/storage 구현 타입에 직접 의존하지 않도록 첫 package dependency / import rule 체크를 도입한다.
- downstream application이 새 runtime seam을 import해 기존 node startup/execution path를 유지하는 최소 integration을 만든다.

### Phase 2: Transport And Storage Migration
- `transport.armeria` 아래로 server startup, streaming, OpenAPI export 관련 공통 코드를 이동한다.
- `storage.swaydb` 아래로 persistence wiring과 관련 helper를 이동한다.
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
2. `sigilaris-node-jvm` 안에 `runtime`, `transport.armeria`, `storage.swaydb` seam이 존재하고, `runtime`은 transport/storage 구현 타입에 직접 결합되지 않는다.
3. downstream application은 새 공통 runtime seam을 import해 기존 node startup/execution path를 유지할 수 있고, 이는 downstream application workspace/CI smoke path로 검증된다.
4. Armeria server wiring, OpenAPI export, SwayDB persistence wiring은 `sigilaris-node-jvm`으로 이동하고, application-specific domain/reducer/query/view model은 downstream에 남는다.
5. package dependency / import rule 체크와 review checklist가 plan 범위 안에서 정의되고 실제 검증 흐름에 연결된다.
6. 관련 문서가 `sigilaris-node-jvm`의 역할과 `sigilaris-core`와의 관계를 설명하도록 갱신된다.

## Checklist

### Phase 0: Inventory And Boundary Lock
- [ ] 공통 JVM node infrastructure와 application-specific 영역 inventory 정리 완료
- [ ] target package mapping 정리 완료
- [ ] `sigilaris-node-jvm` package root와 internal seam 확정 완료
- [ ] seam 검증 방식과 Phase 2/Phase 3 gate 문서화 완료
- [ ] 이 문서의 `Phase 0 Artifacts` appendix 갱신 완료

### Phase 1: Module Skeleton And Runtime Seam
- [ ] `sigilaris-node-jvm` build wiring 추가 완료
- [ ] `sigilaris-node-jvm -> sigilaris-core` 의존 방향 구성 완료
- [ ] `runtime` seam 추출 완료
- [ ] 첫 package dependency / import rule 체크 연결 완료
- [ ] downstream application이 새 runtime seam으로 기존 startup/execution path를 유지하는 최소 integration 확인 완료

### Phase 2: Transport And Storage Migration
- [ ] Armeria wiring 이동 완료
- [ ] SwayDB wiring 이동 완료
- [ ] 공통 runtime/service trait에서 금지된 Armeria/SwayDB 타입 노출 제거 완료
- [ ] package dependency / import rule 체크를 transport/storage까지 확장 완료
- [ ] downstream application이 새 runtime seam 위에서 기존 node를 계속 구동하는 smoke path 확인 완료
- [ ] Phase 3 진입 gate 충족 완료

### Phase 3: Downstream Adoption And Cleanup
- [ ] downstream application의 `sigilaris-node-jvm` 정식 의존 전환 완료
- [ ] downstream node 모듈의 공통 인프라 중복 구현 제거 완료
- [ ] application-specific domain/reducer/query/view/assembly가 downstream에 남아 있는지 점검 완료
- [ ] application-specific dependency 역류 없음 확인 완료

### Phase 4: Verification And Docs
- [ ] `sigilaris-node-jvm` compile/test와 downstream integration 검증 완료
- [ ] OpenAPI export / server startup / SwayDB persistence smoke-regression 기록 완료
- [ ] 문서 갱신 완료
- [ ] 후속 과제 정리 완료

## Phase 0 Artifacts

### Inventory Appendix
- Phase 0에서 현재 공통 JVM node infrastructure와 downstream application 전용 영역의 분리 결과를 여기에 기록한다.

### Target Mapping Appendix
- Phase 0에서 source package/file 영역이 `runtime`, `transport.armeria`, `storage.swaydb`, 또는 downstream retained 영역 중 어디로 가는지 여기에 기록한다.

## Follow-Ups
- transport/storage를 실제로 독립 artifact로 분리할 시점과 기준 재평가
- Cloudflare Workers 등 두 번째 runtime을 위한 shared node abstraction 재검토
- `sigilaris-node-jvm` public API surface를 더 좁힐 수 있는 후속 정리
- 필요 시 downstream migration guide를 별도 문서로 승격
