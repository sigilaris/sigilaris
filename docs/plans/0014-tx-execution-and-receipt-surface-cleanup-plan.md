# 0014 - TxExecution And Receipt Surface Cleanup Plan

## Status
Implemented

## Created
2026-04-15

## Last Updated
2026-04-15

## Background
- `0011`은 `W6-B3`를 execution / receipt surface cleanup tranche로 분리했고, `TxExecution`, `StateModuleExecutor`, `CurrentApplicationBatchRuntime`가 witness-vs-continuation-vs-public-receipt 의미를 같이 들고 있다고 정리했다.
- current code surface는 이미 그 tension을 드러낸다.
  - `TxExecution`은 canonical continuation surface인 `nextState`와 backward-compat inspection surface인 `observedState`를 함께 가진다.
  - `StateModuleExecutor`는 execution witness를 돌려주는 API와 tuple `(StoreState, (Result, Events))`를 돌려주는 API를 둘 다 유지하며, legacy tuple surface는 `observedState`를 사용한다.
  - `CurrentApplicationBatchRuntime`의 receipt는 `TxExecution[?, ?]` 전체를 그대로 저장한다.
- 이 상태는 내부 witness log, chaining state, 외부에 노출할 receipt projection을 분리하기 어렵게 만든다.
- 특히 `observedState`는 synthetic view라서 실제 continuation state와 inspection witness를 한 type 안에서 다시 섞는다.

## Goal
- `TxExecution`을 single-transaction execution witness로 더 명확히 고정한다.
- chaining API는 canonical continuation state만 사용하게 하고, legacy tuple surface는 축소한다.
- batch receipt는 full witness가 아니라 explicit projection을 저장하는 방향으로 정리한다.

## Scope
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/execution/TxExecution.scala`
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/execution/StateModuleExecutor.scala`
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationBatchRuntime.scala`
- execution/receipt surface에 직접 연결된 test/doc update

## Non-Goals
- accounts/group domain typing
- reducer helper dedup
- scheduling law/property tranche 전체
- `documentedCompatibilityFamilies` closed enum화

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0010: Blockchain Account Model And Key Management
- ADR-0011: Blockchain Account Group Management
- ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams
- ADR-0026: TxExecution Witness And Receipt Projection Boundary
- `docs/plans/0011-codebase-quality-improvement-opportunity-catalog-plan.md`

## Decisions To Lock Before Implementation
- canonical chaining surface는 `TxExecution.nextState`다. inspection witness를 continuation state로 다시 노출하지 않는다.
- `TxExecution`은 internal execution witness이며, public batch receipt/storage surface는 explicit projection을 사용한다.
- legacy tuple API를 완전히 즉시 제거할지, transitional wrapper로 남길지는 이 plan 안에서 정하되, canonical path는 execution-first API로 고정한다.
- plain/routed executor surface는 의미상 하나의 algebra로 수렴시키고, routed 여부는 implementation detail로 최대한 밀어넣는다.
- 이 작업은 ADR-0026 draft와 정렬된 상태에서 진행한다.

## Change Areas

### Code
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/execution/TxExecution.scala`
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/execution/StateModuleExecutor.scala`
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationBatchRuntime.scala`

### Tests
- `modules/core/shared/src/test/scala/org/sigilaris/core/application/execution`
- `modules/core/shared/src/test/scala/org/sigilaris/core/application/feature/scheduling`

### Docs
- 본 plan 문서
- `docs/adr/0026-tx-execution-witness-and-receipt-projection-boundary.md`
- `docs/plans/0011-codebase-quality-improvement-opportunity-catalog-plan.md`

## Implementation Phases

### Phase 0: Semantic Lock
- `TxExecution`, `nextState`, `observedState`, receipt projection, compatibility wrapper의 ownership을 문서화한다.
- 어떤 consumer가 full witness를 필요로 하는지, 어떤 consumer는 projection만 있으면 되는지 분리한다.
- canonical public/internal surface를 ADR-0026 draft와 맞춘다.

### Phase 1: Executor Surface Cleanup
- `StateModuleExecutor`의 plain/routed duplication을 줄인다.
- execution-first API와 compatibility tuple API의 관계를 단방향 wrapper로 정리한다.
- chaining surface가 `nextState`를 기준으로만 이어지게 만든다.

### Phase 2: Receipt Projection Rollout
- `CurrentApplicationBatchRuntime`의 receipt가 full `TxExecution` 대신 explicit projection을 저장하게 만든다.
- projection에 반드시 포함해야 할 result/events/footprint/diagnostic surface를 고정한다.
- internal witness가 필요한 path는 receipt와 분리된 internal-only surface로 남긴다.

### Phase 3: Verification And Docs
- executor regression suite와 batch runtime regression suite를 갱신한다.
- synthetic `observedState` 의존 path가 canonical chaining path에 남아 있지 않은지 검증한다.
- ADR/plan 문서를 actual landed surface 기준으로 정리한다.

## Test Plan
- Phase 0 Success: witness / continuation / receipt projection ownership이 문서상으로 잠긴다.
- Phase 1 Success: executor API가 execution-first canonical path를 갖고, compatibility tuple path는 wrapper로 축소된다.
- Phase 1 Failure: chaining이 여전히 `observedState`를 continuation state처럼 사용하지 않는지 검증한다.
- Phase 2 Success: batch receipt가 explicit projection을 저장하고, 필요한 consumer regression이 green이다.
- Phase 2 Failure: projection 축소로 result/events/footprint/diagnostic consumer가 깨지지 않는지 검증한다.
- Phase 3 Success: ADR-0026와 plan/implementation surface가 일치한다.

## Risks And Mitigations
- `TxExecution` consumer가 생각보다 많을 수 있다. 먼저 consumer inventory를 만든 뒤 projection rollout을 한다.
- compatibility tuple API를 급하게 제거하면 test/support code churn이 커질 수 있다. canonical path를 먼저 고정하고 legacy wrapper는 단계적으로 줄인다.
- receipt projection이 너무 약하면 diagnostics 가치가 떨어질 수 있다. 반드시 필요한 operator/test surface를 먼저 lock한다.
- execution witness와 public receipt semantics가 다시 섞일 수 있다. ADR-0026에서 ownership 문장을 먼저 고정한다.

## Acceptance Criteria
1. `TxExecution`은 canonical continuation state와 inspection witness의 ownership이 명확한 single-tx witness surface로 정리된다.
2. `StateModuleExecutor`는 execution-first canonical API를 중심으로 정리되고, legacy tuple path는 wrapper 또는 transitional path로 축소된다.
3. `CurrentApplicationBatchRuntime` receipt는 explicit projection을 저장하고, full witness 전체를 public receipt surface로 보존하지 않는다.

## Checklist

### Phase 0: Semantic Lock
- [x] `TxExecution` / executor / receipt ownership 문서화
- [x] consumer inventory와 required projection surface 정리
- [x] ADR-0026와 terminology alignment

### Phase 1: Executor Surface Cleanup
- [x] plain/routed duplication 축소
- [x] execution-first canonical path 고정
- [x] `observedState` continuation 의존 path 제거 또는 축소

### Phase 2: Receipt Projection Rollout
- [x] batch receipt projection type landed
- [x] runtime state / receipt storage surface 갱신
- [x] impacted consumer regression suite green

### Phase 3: Verification And Docs
- [x] executor/batch runtime regression suite green
- [x] ADR / plan / `0011` 링크 정리

## Follow-Ups
- `documentedCompatibilityFamilies` closed enum화 tranche
- reducer/helper dedup tranche
- 필요한 경우 receipt projection compatibility policy 문서화
