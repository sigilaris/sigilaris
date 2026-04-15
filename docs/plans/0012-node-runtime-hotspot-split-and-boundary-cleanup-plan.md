# 0012 - Node Runtime Hotspot Split And Boundary Cleanup Plan

## Status
Completed

## Created
2026-04-15

## Last Updated
2026-04-15

## Background
- `0011`은 `Wave 2`를 "Hotspot Split And Seam Cleanup" tranche로 분리했고, 이 tranche가 지나가면 이후 typed parsing, side-effect isolation, property gate rollout이 더 작은 file/module 단위에서 가능해진다고 정리했다.
- current hotspot은 이미 문서화돼 있다.
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala`: `1492` lines
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala`: `1752` lines
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`: `1453` lines
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala`: `935` lines
- 이 file들은 단순히 길기만 한 것이 아니라, transport-neutral model, runtime integration, auth parsing, HTTP adapter, config/bootstrap assembly, topic-specific translation이 한 곳에 섞여 있다.
- 이 상태에서 refined type, typed config parser, ADT promotion, property-based test를 바로 밀어 넣으면 한 batch가 "semantic change + file split + integration repair"를 동시에 떠안게 된다.
- 따라서 이 plan의 목적은 semantic 변경을 최소화한 split-only tranche를 먼저 완료해, 이후 tranche의 patch surface와 regression radius를 줄이는 것이다.

## Goal
- `Wave 2`의 `W2-B1` ~ `W2-B6`를 실제 구현 가능한 batch로 정리한다.
- shared-vs-runtime seam, pure-vs-effectful seam, model-vs-adapter seam을 file/package 수준에서 먼저 드러낸다.
- 이후 `Wave 3`, `Wave 4`, `Wave 5` typed rollout이 split된 surface 위에서 진행되게 만든다.

## Scope
- `modules/node-common`의 gossip hotspot split
- `modules/node-jvm`의 transport/auth/bootstrap hotspot split
- semantic regression 없이 file/module/package seam을 정리하는 작업
- split 이후 regression parity gate와 import/reference update

## Non-Goals
- refined type / typed parser rollout
- wire contract shape 변경
- config value semantics 변경
- consensus policy semantics 변경
- 새로운 public API 의미 변경

## Related ADRs And Docs
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0025: Shared Node Abstraction And Cross-Runtime Module Boundary
- `docs/plans/0010-node-common-extraction-and-cross-runtime-contract-plan.md`
- `docs/plans/0011-codebase-quality-improvement-opportunity-catalog-plan.md`

## Decisions To Lock Before Implementation
- 이 plan은 split-only tranche다. semantic/wire/config policy 변경은 원칙적으로 다루지 않는다.
- shared contract ownership은 ADR-0025를 그대로 따른다. `org.sigilaris.node` shared contract와 `org.sigilaris.node.jvm` runtime-specific adapter 경계를 뒤집지 않는다.
- `GossipTransportAuth.scala` split은 pure crypto core vs HTTP parsing surface를 분리하는 것이지, transport credential semantics를 다시 정의하는 작업이 아니다.
- `HotStuffRuntimeBootstrap.scala` split은 parsed config / assembly plan / resource wiring seam을 만드는 것이지, bootstrap readiness 정책을 바꾸는 작업이 아니다.
- exit gate는 property test 추가가 아니라 regression parity다. split 뒤에도 existing suite와 public/shared contract는 같은 의미를 유지해야 한다.

## Landed Split Shape And Handoff
- `Model.scala`는 rejection, identifier, cursor/artifact, session/control protocol file family로 분리됐다.
  - `GossipRejections.scala`
  - `GossipIdentifiers.scala`
  - `GossipCursorModel.scala`
  - `GossipSessionProtocol.scala`
- `TxGossipRuntime.scala`는 runtime support/shared ops/control ops/polling ops seam으로 분리됐다.
  - `TxGossipRuntimeSupport.scala`
  - `TxGossipRuntimeSharedOps.scala`
  - `TxGossipRuntimeControlOps.scala`
  - `TxGossipRuntimePollingOps.scala`
- `TxGossipArmeriaAdapter.scala`는 transport wire, binary codec, adapter surface seam으로 분리됐다.
  - `TxGossipWire.scala`
  - `BinaryEventStreamCodec.scala`
  - `TxGossipArmeriaAdapter.scala`
- `GossipTransportAuth.scala`는 pure crypto core vs HTTP parsing/rejection mapping seam으로 분리됐다.
  - `GossipTransportAuthCore.scala`
  - `GossipTransportAuth.scala`
- `HotStuffBootstrapArmeriaAdapter.scala`는 inbound adapter vs outbound HTTP bootstrap transport seam으로 분리됐다.
  - `HotStuffBootstrapWire.scala`
  - `HotStuffBootstrapHttpTransport.scala`
  - `HotStuffBootstrapArmeriaAdapter.scala`
- `HotStuffRuntimeBootstrap.scala`는 parsed config vs runtime/resource wiring seam으로 분리됐다.
  - `HotStuffBootstrapConfig.scala`
  - `HotStuffRuntimeBootstrap.scala`
- regression parity는 아래 existing suite로 확인했다.
  - `nodeCommonJVM/testOnly org.sigilaris.node.gossip.tx.TxControlInterpreterSuite org.sigilaris.node.gossip.tx.TxLoopbackSuite`
  - `nodeJvm/testOnly org.sigilaris.node.jvm.transport.armeria.gossip.TxGossipArmeriaAdapterSuite org.sigilaris.node.jvm.transport.armeria.gossip.HotStuffBootstrapArmeriaAdapterSuite`
  - `nodeJvm/testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffRuntimeBootstrapSuite`
- 다음 tranche handoff는 아래 seam을 기준으로 진행한다.
  - `Wave 3`: `GossipIdentifiers.scala`, `GossipCursorModel.scala`, `GossipSessionProtocol.scala`, `TxGossipRuntimeSupport.scala`
  - `Wave 4`: `TxGossipWire.scala`, `BinaryEventStreamCodec.scala`, `HotStuffBootstrapWire.scala`, `HotStuffBootstrapConfig.scala`

## Change Areas

### Code
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/GossipTransportAuth.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapArmeriaAdapter.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala`

### Tests
- `modules/node-common/shared/src/test/scala`
- `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/transport/armeria/gossip`
- `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
- split 뒤 regression parity를 보장할 existing suite 정리

### Docs
- 본 plan 문서
- `docs/plans/0011-codebase-quality-improvement-opportunity-catalog-plan.md`
- 필요 시 ADR-0025 Follow-Up / References

## Implementation Phases

### Phase 0: Ownership And Split Contract Lock
- `W2-B1` ~ `W2-B6` batch별 primary owner surface를 고정한다.
- 각 hotspot file에서 아래 seam을 어떤 file/package 경계로 드러낼지 먼저 적는다.
  - concept model vs parser/helper
  - pure interpreter vs integration/runtime orchestration
  - transport DTO/codec vs endpoint/server adapter
  - crypto core vs HTTP parsing/rejection mapping
  - parsed config model vs assembly/resource wiring
- split-only tranche에서 건드리지 않을 semantic surface를 명시한다.
  - wire field name
  - rejection family meaning
  - bootstrap readiness meaning
  - shared-vs-runtime ownership

### Phase 1: `node-common` Hotspot Split
- `Model.scala`를 최소한 아래 concept family로 분해한다.
  - canonical rejection / diagnostic model
  - identifier / cursor / token / subscription model
  - control batch / negotiation / session-open model
  - topic/runtime-neutral helper
- `TxGossipRuntime.scala`를 최소한 아래 seam으로 분리한다.
  - pure runtime state transition / interpreter core
  - control interpretation
  - batching / replay / dedup integration layer
- split 이후 import/reference churn을 정리하되 public semantic surface는 유지한다.

### Phase 2: `node-jvm` Transport / Bootstrap Split
- `TxGossipArmeriaAdapter.scala`를 최소한 아래 seam으로 분리한다.
  - wire DTO / codec
  - binary frame codec
  - Tapir/Armeria server adapter
  - runtime translation
- `GossipTransportAuth.scala`를 아래 seam으로 분리한다.
  - pure crypto core
  - HTTP header parsing / rejection mapping
- `HotStuffBootstrapArmeriaAdapter.scala`를 inbound server adapter와 outbound client adapter로 분리한다.
- `HotStuffRuntimeBootstrap.scala`를 parsed config model, assembly plan, resource wiring seam으로 분리한다.

### Phase 3: Regression Parity And Handoff
- split 뒤 impacted suite를 실행해 semantic regression이 없는지 확인한다.
- `0011`의 `Wave 2` / batch handoff와 실제 landed split 방향이 일치하는지 문서에 반영한다.
- typed parsing/config rollout follow-up이 새 seam 위에서 어디로 들어갈지 짧게 handoff note를 남긴다.

## Test Plan
- Phase 0 Success: 각 hotspot file마다 split boundary와 non-goal semantic surface가 문서상으로 고정된다.
- Phase 1 Success: `Model.scala`, `TxGossipRuntime.scala`가 smaller file family로 분리되고 existing node-common suite가 green이다.
- Phase 1 Failure: split 과정에서 shared contract ownership이 `node-jvm` 쪽으로 새거나, public parser/contract drift가 생기지 않는지 검증한다.
- Phase 2 Success: transport/auth/bootstrap hotspot이 split되고 existing node-jvm transport/bootstrap suite가 green이다.
- Phase 2 Failure: wire field / rejection meaning / bootstrap readiness meaning이 의도치 않게 바뀌지 않는지 regression으로 검증한다.
- Phase 3 Success: split-only tranche 뒤 follow-up typed rollout이 더 작은 owner surface를 갖는지 검토한다.

## Risks And Mitigations
- split-only tranche가 semantic refactor와 섞이면 범위가 다시 커진다. semantic change는 다음 wave로 미루고 regression parity를 gate로 둔다.
- `Model.scala`와 `TxGossipRuntime.scala`는 shared contract와 runtime helper가 섞여 있어 잘못 쪼개면 ownership이 흐려질 수 있다. ADR-0025 boundary를 먼저 lock하고 이동한다.
- `GossipTransportAuth.scala` split이 auth hardening 작업으로 번질 수 있다. 이번 plan에서는 pure-vs-HTTP seam만 만든다.
- `HotStuffRuntimeBootstrap.scala` split이 config parser semantics 변경으로 번질 수 있다. parser scaffold/typed rollout은 `Wave 4` companion plan으로 넘긴다.
- package/file rename churn이 크다. batch 단위로 나누고 impacted regression suite를 batch마다 확인한다.

## Acceptance Criteria
1. `Model.scala`, `TxGossipRuntime.scala`, `TxGossipArmeriaAdapter.scala`, `GossipTransportAuth.scala`, `HotStuffBootstrapArmeriaAdapter.scala`, `HotStuffRuntimeBootstrap.scala`가 smaller owner surface로 분리된다.
2. split 뒤 shared-vs-runtime boundary는 ADR-0025와 일치하고, semantic/wire/config meaning drift 없이 existing regression suite가 통과한다.
3. 이후 typed parsing/config rollout/property-test tranche가 새 seam 위에서 smaller batch로 진행 가능한 상태가 된다.

## Checklist

### Phase 0: Ownership And Split Contract Lock
- [x] `W2-B1` ~ `W2-B6` batch별 owner surface와 non-goal semantic surface를 문서화한다.
- [x] hotspot file별 split seam과 target file family를 적는다.

### Phase 1: `node-common` Hotspot Split
- [x] `Model.scala` concept split landed
- [x] `TxGossipRuntime.scala` interpreter/integration split landed
- [x] node-common regression suite green

### Phase 2: `node-jvm` Transport / Bootstrap Split
- [x] `TxGossipArmeriaAdapter.scala` split landed
- [x] `GossipTransportAuth.scala` split landed
- [x] `HotStuffBootstrapArmeriaAdapter.scala` split landed
- [x] `HotStuffRuntimeBootstrap.scala` split landed
- [x] impacted node-jvm transport/bootstrap suite green

### Phase 3: Regression Parity And Handoff
- [x] split-only tranche regression parity 확인
- [x] `0011`와 handoff 문서를 실제 landed seam 기준으로 갱신한다.
- [x] 다음 tranche(`Wave 3` / `Wave 4`)로 넘길 owner surface를 정리한다.

## Follow-Ups
- `Wave 3` shared contract typing rollout companion plan
- `Wave 4` transport/config boundary rollout companion plan
- ADR-0025 reference update if shared-vs-runtime ownership 설명이 더 필요해질 경우
