# ADR-0015: JVM Node Bundle Boundary And Packaging

## Status
Accepted

## Context
- 현재 downstream application 저장소의 node 모듈은 application-specific domain과 reusable JVM node infrastructure를 함께 담고 있다.
- `payment` / `escrow` / `credit` / `debt` 같은 application-specific domain과, `NodeRuntime`, HTTP server wiring, OpenAPI export, SwayDB persistence wiring이 같은 모듈 경계 안에 섞여 있다.
- `sigilaris`는 현재 `core` 중심 라이브러리이지만, application-specific private blockchain을 구현할 때 재사용 가능한 node-side infrastructure가 점점 명확해지고 있다.
- JVM 환경에서 블록체인 노드를 구동할 때 Armeria 기반 HTTP serving과 SwayDB 기반 persistence는 당분간 공통 표준 구성으로 재사용할 가능성이 높다.
- 반면 현재 시점에서 transport나 storage를 독립 artifact로 조합해 바꿔 끼울 실질적인 요구는 낮다. 이를 너무 일찍 `transport-*` / `storage-*` artifact로 분리하면 build, publish, dependency surface, migration cost만 먼저 늘어난다.
- 블록체인 생태계에서 외부에 드러나는 실행체 용어는 `host`보다 `node`가 더 자연스럽고, downstream 사용자도 `node` artifact를 더 바로 이해할 가능성이 높다.

## Decision
1. downstream application과 `sigilaris`의 경계를 application specificity 기준으로 다시 잡는다.
   - downstream application에는 application-specific domain과 조립 코드를 남긴다.
   - `sigilaris`에는 다른 application-specific private blockchain 구현에서도 재사용 가능한 JVM node infrastructure를 올린다.

2. 공통 JVM node infrastructure는 새 JVM 전용 모듈 `sigilaris-node-jvm`으로 제공한다.
   - 이 모듈은 현재 시점에서 opinionated bundle로서 Armeria + SwayDB 기반 구성을 함께 제공한다.
   - artifact는 하나로 묶되, 내부 코드 구조는 transport / storage / runtime seam을 유지하도록 설계한다.
   - 의존 방향은 `sigilaris-core` <- `sigilaris-node-jvm`으로 고정한다. `sigilaris-core`는 계속 transport/storage/node-runtime 의존 없이 유지한다.

3. 초기 단계에서는 `sigilaris-armeria`, `sigilaris-storage-swaydb` 같은 세분 artifact를 만들지 않는다.
   - 현재 요구는 “교체 가능한 adapter ecosystem”보다 “재사용 가능한 JVM node baseline”에 가깝다.
   - transport와 storage를 실제로 독립 배포/교체해야 하는 두 번째 구현 압력이 생길 때 artifact 분리를 재평가한다.

4. 모듈 naming은 기술 세부 구현보다 실행체 개념을 우선한다.
   - JVM용 표준 노드 번들은 `sigilaris-node-jvm`으로 명명한다.
   - 향후 다른 runtime이 필요하면 같은 규칙으로 `sigilaris-node-<runtime>` 형식을 따른다.
   - 예: Cloudflare Workers 기반 runtime은 `sigilaris-node-cloudflare-workers`.

5. `sigilaris-node-jvm`의 public surface는 가능하면 implementation detail leakage를 줄인다.
   - 내부 구현은 Armeria와 SwayDB를 사용하더라도, public API는 bootstrap, runtime lifecycle, node service wiring 같은 공통 개념을 중심으로 설계한다.
   - 단, 초기 단계에서는 개발 속도와 migration 단순성을 위해 Armeria / SwayDB 타입 노출을 완전히 금지하지는 않는다. 다만 public API 깊숙이 고착시키는 것은 피한다.
   - 허용 예:
     - 최상위 bootstrap 또는 startup 결과에서 Armeria `Server` 같은 runtime handle을 반환하는 것
     - SwayDB-backed persistence를 생성하는 factory/helper를 별도 wiring entry point로 제공하는 것
   - 비허용 예:
     - 공통 runtime/service trait가 Armeria `ServerBuilder`나 Armeria route DSL을 직접 요구하는 것
     - 공통 persistence abstraction이나 runtime service signature에 SwayDB collection/map 타입을 직접 노출하는 것

6. internal seam은 package 구조와 검증 규칙으로 유지한다.
   - `runtime`은 `transport` / `storage`의 구현 세부사항을 직접 참조하지 않고, 조립 지점 또는 명시된 wiring entry point를 통해 연결한다.
   - 이 검증 규칙은 첫 이동 단계(runtime bootstrap / lifecycle 이동)부터 적용한다.
   - seam 검증은 최소한 다음 두 수단을 사용한다:
     - implementation plan에 package dependency / import rule 체크를 포함한다.
     - code review checklist에 runtime이 transport/storage 구현 타입에 직접 결합되지 않는지 확인 항목을 포함한다.

7. 이동 대상은 “JVM node 공통 인프라”로 제한한다.
   - `sigilaris`로 이동할 후보:
     - runtime bootstrap / lifecycle
     - generic node execution flow
     - OpenAPI export 및 server startup helper
     - SwayDB-backed persistence wiring
     - Armeria-backed HTTP serving 및 streaming wiring
   - downstream application에 남길 후보:
     - payment / escrow / credit / debt 도메인
     - application-specific transaction, reducer, query model, view model
     - application assembly
   - 첫 이동 단위는 runtime bootstrap / lifecycle과 generic node execution flow를 우선한다. Armeria / SwayDB wiring은 그 공통 runtime seam이 만들어진 뒤에 옮긴다.

## Consequences
- `sigilaris`는 `core`만 제공하는 라이브러리에서, JVM 환경에서 바로 구동 가능한 opinionated node bundle까지 제공하는 구조로 확장된다.
- downstream application은 application-specific domain에 더 집중된 저장소/모듈 구조를 갖게 된다.
- downstream 사용자는 `sigilaris-core`와 `sigilaris-node-jvm`을 조합해 “도메인 + JVM node infrastructure”를 더 명확하게 이해할 수 있다.
- `sigilaris-node-jvm`은 `sigilaris-core` 위에 쌓이는 상위 모듈이 되며, reverse dependency를 만들지 않으므로 core의 transport-neutral 성격은 유지된다.
- artifact 수를 최소화하므로 초기 migration과 release management는 단순해진다.
- 대신 Armeria와 SwayDB가 초기 `sigilaris-node-jvm`의 기본 선택으로 굳어지므로, 추후 다른 transport/storage를 도입할 때 내부 seam 품질이 중요해진다.

## Rejected Alternatives
1. `sigilaris-armeria`만 먼저 만든다.
   - Armeria adapter 자체는 재사용 가능하지만, 이번에 옮기려는 공통성은 HTTP adapter 하나보다 넓다.
   - SwayDB persistence, runtime bootstrap, node assembly까지 같이 다뤄야 하므로 이름과 범위가 어긋난다.

2. `sigilaris-host-jvm`처럼 `host` 용어를 사용한다.
   - 내부 개념으로는 가능하지만, 외부 artifact 이름으로는 블록체인 문맥에서 `node`보다 덜 직관적이다.
   - 사용자에게는 “블록체인 노드 실행체”라는 의미가 더 직접적으로 전달되는 `node`가 적합하다.

3. 처음부터 `transport-*` / `storage-*` / `runtime-*` artifact로 세분화한다.
   - 장기적으로는 가능하지만, 현재는 실제 교체 수요보다 설계 비용이 크다.
   - premature modularization은 build complexity, publish surface, migration burden을 먼저 증가시킨다.

## Migration Guidance
1. 현재 downstream node 모듈에서 reusable JVM node infrastructure와 application-specific domain/application assembly를 먼저 식별한다.
2. `sigilaris`에 `sigilaris-node-jvm` 모듈을 추가하고, 내부 패키지는 최소한 `runtime`, `transport`, `storage` 수준으로 분리한다.
3. runtime bootstrap / lifecycle과 generic node execution flow를 먼저 옮겨 공통 seam을 만든다.
4. downstream application이 새 공통 runtime seam을 import해 기존 node startup/execution path를 유지할 수 있음을 확인한 뒤, OpenAPI export, Armeria server wiring, SwayDB persistence wiring을 그 seam 위로 이동한다.
5. application-specific API model이나 domain reducer는 마지막까지 downstream application에 남긴다.
6. downstream application은 새 `sigilaris-node-jvm`을 의존하도록 전환한 뒤, 남아 있는 application-specific 조립 코드만 유지한다.
7. migration 작업은 별도의 implementation plan 문서에서 phase, validation 범위, package dependency check를 관리한다.

## Follow-Up
- `sigilaris-node-jvm` 도입을 실제 파일/패키지 이동 단위로 쪼갠 implementation plan을 작성한다.
- runtime bootstrap, transport wiring, storage wiring의 internal seam을 문서화해 향후 artifact 분리 기준으로 재사용한다.
- runtime / transport / storage 간 package dependency rule을 검증하는 방법을 implementation plan에서 구체화한다.
- Cloudflare Workers 등 두 번째 runtime 구현 압력이 생기면 `sigilaris-node-<runtime>` naming과 shared node abstraction을 재검토한다.
- Armeria / SwayDB 의존이 public API에 과도하게 스며드는지 migration 과정에서 점검한다.
