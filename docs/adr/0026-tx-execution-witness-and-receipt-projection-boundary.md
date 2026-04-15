# ADR-0026: TxExecution Witness And Receipt Projection Boundary

## Status
Accepted

## Context
- ADR-0009는 application execution을 deterministic state transition과 witnessable effect/result surface 위에 올려 두었지만, single-transaction execution witness와 public batch receipt가 정확히 어떤 관계를 가져야 하는지는 별도 문서로 잠기지 않았다.
- current core execution surface에는 ambiguity가 남아 있다.
  - `TxExecution`은 `nextState`와 `observedState`를 함께 가진다.
  - `nextState`는 fresh-log continuation state이고, `observedState`는 post-trie와 witness access log를 다시 붙인 synthetic inspection view다.
  - `StateModuleExecutor`는 execution witness를 반환하는 path와 legacy tuple `(StoreState, (Result, Events))` path를 둘 다 제공하고, legacy tuple path는 `observedState`를 사용한다.
  - `CurrentApplicationBatchRuntime` receipt는 `TxExecution[?, ?]` 전체를 저장한다.
- 이 구조는 세 가지 층위를 한 surface에 섞는다.
  - internal execution witness
  - 다음 transaction으로 이어질 canonical continuation state
  - 외부 consumer에게 노출할 batch receipt / diagnostic projection
- 이 ambiguity를 계속 두면 이후 execution API cleanup, receipt projection, compatibility layer 정리가 implementation-local convention으로 흩어질 수 있다.

## Decision
1. **`TxExecution`은 canonical chaining state가 아니라 single-transaction execution witness다.**
   - `TxExecution`은 execution 결과를 설명하는 witness object다.
   - witness는 post-execution trie, actual access log, derived footprint, result, emitted events를 소유할 수 있다.
   - 그러나 witness object 자체가 다음 transaction chaining에 그대로 사용되는 canonical state surface는 아니다.

2. **canonical continuation state는 `TxExecution.nextState` 하나로 고정한다.**
   - chaining, batch execution, 다음 transaction application은 fresh-log continuation state 위에서만 일어난다.
   - witness access log를 다시 붙인 synthetic inspection view를 canonical continuation state로 취급하지 않는다.

3. **`observedState`는 legacy inspection compatibility surface일 뿐, normative chaining contract가 아니다.**
   - `observedState`는 old consumer나 transitional helper를 위한 compatibility surface로만 취급한다.
   - 새 API나 새 runtime path는 `observedState`를 canonical state처럼 사용해서는 안 된다.
   - implementation이 준비되면 `observedState`는 축소되거나 internalized 될 수 있다.

4. **public batch receipt는 full execution witness가 아니라 explicit projection을 저장한다.**
   - batch receipt의 canonical role은 operator/debuggable outcome과 stable consumer-facing execution summary를 전달하는 것이다.
   - 따라서 batch receipt는 필요한 result/events/footprint/diagnostic projection을 explicit하게 저장해야 한다.
   - full `TxExecution` witness 전체를 receipt persistence/public surface의 기본 단위로 삼지 않는다.

5. **executor surface는 execution-first canonical path와 compatibility wrapper를 분리한다.**
   - canonical executor API는 `TxExecution` 또는 그와 동등한 execution witness를 중심으로 구성한다.
   - legacy tuple `(StoreState, (Result, Events))` surface는 필요하다면 execution-first path 위의 compatibility wrapper로만 유지한다.
   - plain/routed executor가 같은 의미의 duplicated public surface를 별도로 소유하지 않도록 정리한다.

6. **이 ADR은 semantic ownership만 고정하고 exact type names/field list는 implementation plan이 확정한다.**
   - exact receipt projection type 이름
   - projection에 포함할 최소 field set
   - transitional wrapper removal timing
   - plain/routed executor unification detail
   - 위 사항은 companion implementation plan이 고정한다.

## Consequences
- execution witness와 continuation state의 ownership이 분리된다.
- batch receipt가 internal witness object 전체를 끌고 다니지 않아도 되는 방향이 열린다.
- landed implementation에서는 public batch receipt projection으로 `TxExecutionReceiptProjection`을 사용하고, `actualFootprint` / `result` / `events`만 저장한다.
- `StateModuleExecutor` API는 execution-first canonical path를 중심으로 정리할 수 있다.
- old compatibility surface는 즉시 제거할 필요 없이 legacy wrapper로 축소할 수 있다.
- 대신 receipt projection의 최소 required surface와 consumer inventory를 implementation에서 먼저 정리해야 한다.

## Rejected Alternatives
1. **`observedState`를 계속 canonical continuation state처럼 취급한다**
   - witness access log와 chaining state가 다시 섞인다.
   - synthetic inspection view가 normative state contract처럼 굳어진다.

2. **batch receipt에 full `TxExecution` witness를 계속 저장한다**
   - public/stored receipt surface와 internal execution witness가 불필요하게 강하게 결합된다.
   - projection 축소나 compatibility policy 정리가 어려워진다.

3. **legacy tuple surface를 canonical API와 동등한 1급 surface로 유지한다**
   - execution-first API와 compatibility wrapper의 위계가 흐려진다.
   - plain/routed duplication과 synthetic state ambiguity가 계속 남는다.

## Follow-Up
- concrete implementation handoff는 [0014 - TxExecution And Receipt Surface Cleanup Plan](../plans/0014-tx-execution-and-receipt-surface-cleanup-plan.md)이 소유한다.
- `documentedCompatibilityFamilies` closed enum화는 adjacent follow-up으로 남긴다.
- receipt projection compatibility policy가 external contract로 커지면 별도 doc note 또는 ADR addendum을 검토한다.

## References
- [ADR-0009: Blockchain Application Architecture](0009-blockchain-application-architecture.md)
- [ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams](0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md)
- [0011 - Codebase Quality Improvement Opportunity Catalog And Sequencing Plan](../plans/0011-codebase-quality-improvement-opportunity-catalog-plan.md)
- [0014 - TxExecution And Receipt Surface Cleanup Plan](../plans/0014-tx-execution-and-receipt-surface-cleanup-plan.md)
