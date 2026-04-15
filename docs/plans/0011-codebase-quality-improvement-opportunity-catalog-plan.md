# 0011 - Codebase Quality Improvement Opportunity Catalog And Sequencing Plan

## Status
Draft

## Created
2026-04-14

## Last Updated
2026-04-15

## Background
- `sigilaris`는 이미 opaque type, `iron`, `Hedgehog`, compile-time schema/routing 같은 강한 타입 도구를 일부 도입했다.
- 반면 적용 밀도는 균일하지 않다. `core` 일부는 refined/value-object style이지만, `node-common`과 `node-jvm`으로 갈수록 raw `String`/`Int`/`Long`, `require`, `unsafe*`, 예외 기반 경계, large mixed-responsibility file이 늘어난다.
- 조사 중 line count hotspot도 분명했다.
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala`: `1752` lines
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala`: `1492` lines
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/GossipIntegration.scala`: `1506` lines
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`: `1453` lines
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala`: `935` lines
  - 이 hotspot들은 아래 `B`/`C` section의 file split 후보와 다시 cross-reference한다.
- 테스트도 편차가 있다. `core` codec/datatype 쪽에는 property/fuzz test가 있지만 `node-common`과 `node-jvm` gossip/hotstuff/transport 쪽은 거의 전부 example-based suite다.
- 이번 문서는 quality improvement catalog를 유지하되, 이제 dependency와 leverage를 기준으로 실제 구현 순서까지 함께 기록한다.
  - duplication 제거
  - intention clarification
  - side-effect isolation
  - Parse, don't validate
  - Principle of Least Power
  - total function화
  - `iron` refined type 적용 확대
  - property-based test 확대

## Goal
- `modules/core`, `modules/node-common`, `modules/node-jvm` 전반의 개선 기회를 실제 구현 순서가 보이는 catalog로 남긴다.
- 각 항목을 실제 file/type/function과 연결하고, 어떤 항목이 다른 항목의 enabler인지 드러낸다.
- 특히 raw primitive, partial function, mixed-responsibility file, side-effect leakage, missing property tests를 드러내고 wave 단위 실행 순서를 정한다.

## Scope
- `sigilaris` 코드베이스만 조사한다.
- main code와 test code를 함께 보고 개선 기회를 문서화한다.
- 즉시 구현은 하지 않지만, 모든 항목이 들어갈 우선순위 wave는 이 문서에서 정한다.

## Non-Goals
- effort estimate
- 즉시 ADR 수정
- 모든 항목의 상세 issue/PR 단위 분해

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0010: Blockchain Account Model And Key Management
- ADR-0011: Blockchain Account Group Management
- ADR-0012: Signed Transaction Requirement
- ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams
- ADR-0025: Shared Node Abstraction And Cross-Runtime Module Boundary
- `docs/plans/0010-node-common-extraction-and-cross-runtime-contract-plan.md`
- [Parse, don't validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/)
- [Principle of Least Power](https://www.lihaoyi.com/post/StrategicScalaStylePrincipleofLeastPower.html)
- [Iron overview](https://iltotore.github.io/iron/docs/overview.html)

## Decisions To Lock Before Implementation
- 이 문서는 raw opportunity catalog와 prioritized execution sequence를 함께 유지한다. 비슷해 보여도 seam이 다르면 catalog에서는 분리하고, 실행 순서에서만 묶는다.
- 구현 시에는 가능한 한 "raw primitive를 받아서 나중에 검사"하는 방식보다 "parse 단계에서 typed value로 승격"하는 방향을 기본값으로 둔다.
- production path에서 `require`, `throw`, `unsafe*`가 남아야 한다면 boundary/test/constants 용도인지 명확히 분리한다.
- pure model/state transition과 effectful adapter/bootstrap/config parsing은 더 강하게 분리하는 방향을 기본값으로 둔다.
- parser/codec/canonicalization/scheduler를 건드리는 후속 작업은 example test만이 아니라 property test를 함께 추가하는 것을 기본 gate로 둔다.

## Catalog Reading Notes
- 아래 opportunity row는 raw inventory이고, 실제 구현 순서는 별도 `Prioritized Implementation Sequence` section에서 정의한다.
- "무엇을 바꾸면 좋은가"와 "무엇이 선행되면 다른 항목이 쉬워지는가"를 구분하기 위해, cross-cutting dependency note를 별도 표로 둔다.
- `D. Property-Based Test Gaps`의 `risk tier`는 우선순위가 아니라 영향 범위를 빠르게 스캔하기 위한 분류다.
  - `wire / interoperability`: wire compatibility, parser/codec contract, client-server interop 위험
  - `consensus safety`: consensus/runtime safety와 직접 연결되는 위험
  - `core correctness`: scheduler/state/law 위반 시 논리 오류가 누적될 수 있는 위험
  - `config ergonomics`: config alias, parsing UX, 운영 설정 drift 위험
  - `error contract ergonomics`: failure rendering/parsing contract drift와 diagnostic compatibility 위험
  - `coverage hygiene`: 특정 영역에 property/fuzz coverage 자체가 없는 상태

### Cross-Cutting Dependency Notes

| enabling change | unblocks / amplifies |
| --- | --- |
| shared fixed-width byte ID helper (`KeyId20` 일반화) | `AccountsTypes.scala` byte-backed ID 정리, `ProposalTxId` 도입, hash-like ID family 통일 |
| `node-common` `Model.scala` concept split | gossip refined type 확장, parser/wire codec 분리, `CanonicalRejection`/session/cursor/control surface 정리 |
| transport adapter split in `TxGossipArmeriaAdapter.scala` / `HotStuffBootstrapArmeriaAdapter.scala` | wire DTO typed promotion, codec property test, runtime-specific adapter와 shared contract 경계 정리 |
| structured failure surface normalization | `ClientFailureMessage` / `ConflictMessage` / reducer-local wrapper 통합, `reason/detail/msg` string family 정리, failure render/parse round-trip property test 도입 |
| typed config parser layer for `StaticPeerTopologyConfig.scala` / `StaticPeerTransportAuthConfig.scala` / `StaticPeerBootstrapHttpTransportConfig.scala` | config alias equivalence, missing-key rejection, unknown-peer rejection property test를 generator-friendly input model 위에서 검증 가능하게 함 |
| shared property-test generator/law harness for gossip/hotstuff/config | parser round-trip, negotiation law, validator set law, config alias equivalence 검증의 중복 감소 |
| `node-common` shared contract curation aligned with ADR-0025 | shared `org.sigilaris.node` contract와 runtime-specific `node-jvm` adapter/file split을 같은 방향으로 묶어 정리 가능 |

## Prioritized Implementation Sequence
- 순서는 `dependency leverage -> hotspot simplification -> typed boundary rollout -> runtime hardening -> domain semantic cleanup` 기준으로 잡는다.
- 앞 wave가 뒤 wave의 helper, file seam, test harness를 제공해야 한다.
- property test gap은 별도 마지막 단계로 미루지 않고, 해당 surface를 정리하는 wave의 exit gate로 묶는다.
- `Wave 1`과 `Wave 2`는 strict serial이 아니다. `Wave 1a` helper/scaffold 추출과 `Wave 2` seam cleanup은 병렬 가능하고, cross-module normalization은 split 이후 더 작은 file seam에서 마무리하는 편을 권장한다.
- 모든 catalog 항목은 정확히 하나의 wave에 배정한다. wave bullet이 여러 row를 대표할 때는 대표 surface를 문장 안에 explicit하게 남긴다.

### Mapping Rules And Completeness Check
- catalog row는 중복 배정 없이 정확히 하나의 wave에 속해야 한다.
- ambiguous하기 쉬운 항목은 wave 본문에서 직접 이름을 적어 배정 사실을 고정한다.
  - `SessionSubscription` `NonEmpty*` strengthening은 `Wave 3`
  - `documentedCompatibilityFamilies` closed enum화는 `Wave 6`
  - `Bag.scala` unsafe boundary isolation은 dependency가 아니라 priority-tail cleanup으로 `Wave 6`
- `Phase 3` handoff에서는 이 배정을 유지한 채 wave 내부 batch만 더 잘게 나눈다.

### Wave 1. Shared Foundations
- 왜 먼저인가: 가장 많은 후속 작업이 shared helper, total constructor, failure/config/test scaffolding에 의존한다.
- 권장 내부 순서:
  - `Wave 1a. Low-conflict scaffolding`: `BigNat` / `UInt256` totalization, fixed-width byte ID helper 일반화, typed config parser scaffold, shared property-test generator/law harness bootstrap
  - `Wave 1b. Cross-module normalization`: failure/diagnostic normalization과 failure render/parse property test. 이 부분은 가능하면 `Wave 2` split 이후 더 작은 file seam 위에서 마무리한다.
- 포함 항목:
  - `modules/core/shared/src/main/scala/org/sigilaris/core/failure/{FailureCode,SigilarisFailure,ClientFailureMessage,ConflictMessage,FailureMessageFormat}.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/scheduling/FootprintDeriver.scala`, `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala`, `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/{Artifacts,Policy,SnapshotSync}.scala`의 failure/diagnostic normalization
  - `modules/core/shared/src/main/scala/org/sigilaris/core/datatype/{BigNat,UInt256}.scala` totalization과 unsafe surface 축소
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/domain/AccountsTypes.scala`의 shared fixed-width byte ID helper 일반화
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/{StaticPeerTopologyConfig,StaticPeerTransportAuthConfig,StaticPeerBootstrapHttpTransportConfig}.scala`에 공통 typed config parser layer scaffold 추출
  - `modules/core/shared/src/test/scala`, `modules/node-common/shared/src/test/scala`, `modules/node-jvm/src/test/scala`에서 재사용할 shared property-test generator/law harness bootstrap
  - `modules/core/shared/src/main/scala/org/sigilaris/core/failure/*` render/parse property test는 structured failure surface가 이 wave에서 정리되면 같이 추가
- 이 wave가 열어주는 것:
  - 이후의 domain newtype, config parser, failure DSL, wire/consensus property test가 공통 helper 위에서 돌아간다.

### Wave 2. Hotspot Split And Seam Cleanup
- 왜 두 번째인가: 이 wave가 지나가면 코드베이스가 가장 먼저 "살아난다". 큰 file을 쪼개고 seam을 세우면 이후 patch 범위가 급격히 줄어든다.
- 병렬성 메모: `Wave 1a`와는 병렬 가능하다. 반대로 `Wave 1b`처럼 cross-module normalization 성격이 강한 작업은 이 wave가 만든 seam 위에서 마무리하는 편이 낫다.
- 포함 항목:
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` concept split
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala` pure interpreter core vs integration surface split
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala` split
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapArmeriaAdapter.scala` inbound/outbound adapter split
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/GossipTransportAuth.scala` pure crypto core vs HTTP parsing surface split
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala` parsed config / assembly plan / resource wiring split
- 이 wave가 열어주는 것:
  - 이후 typed parsing과 effect isolation이 작은 file/module 단위에서 진행된다.
  - ADR-0025와 맞물린 shared-vs-runtime boundary 정리가 먼저 끝난다.
- exit gate:
  - split-only wave인 만큼 새로운 property test보다 regression parity가 핵심이다.
  - 기존 suite가 split 후에도 동일하게 통과하고, public/shared contract drift 없이 file/module boundary만 달라졌음을 확인한다.

### Wave 3. Shared Contract Typing Rollout
- 왜 세 번째인가: `node-common` shared contract가 typed parsing으로 안정돼야 transport와 hotstuff가 stringly-typed 상태를 벗어날 수 있다.
- 포함 항목:
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala`의 `StableArtifactId`, `TopicWindowKey`, `CursorToken`, `CursorToken.issue`
  - 같은 file의 `PeerIdentity`, `ChainId`, `GossipTopic`, `ControlIdempotencyKey`, `SessionSubscription` `NonEmpty*` strengthening, `GossipFieldValidation`
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxRuntimeState.scala`의 `TxRuntimePolicy`
  - `modules/node-common/shared/src/test/scala` property/fuzz suite 추가
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` parse/render closure property와 session negotiation law property
- 이 wave가 열어주는 것:
  - transport DTO decode가 바로 typed value를 만들 수 있게 된다.
  - hotstuff 쪽 shared abstraction과 validator/height/view typing도 같은 패턴으로 가져갈 수 있다.

### Wave 4. Transport And Config Boundary Rollout
- 왜 네 번째인가: shared contract가 안정된 뒤에야 wire DTO와 config surface를 ADT/typed parser로 밀어올릴 수 있다.
- 포함 항목:
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/{StaticPeerTopologyConfig,StaticPeerTransportAuthConfig,StaticPeerBootstrapHttpTransportConfig}.scala`, `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala`에 typed config parser layer 실제 적용
  - `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/StaticPeerTransportAuth.scala`의 parse/completeness validation 분리
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/StaticPeerBootstrapHttpTransportConfig.scala`의 `peerBaseUris` URI typing
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`의 `ControlOpWire` / `ControlRequestWire` / `EventRequestWire` ADT화
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapArmeriaAdapter.scala`의 typed request decode
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`, `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala`의 `RejectionWire` duplication 제거
  - `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/gossip`, `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/transport/armeria/gossip` property suite
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`의 `BinaryEventStreamCodec` round-trip/rejection property
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/{StaticPeerTopologyConfig,StaticPeerTransportAuthConfig,StaticPeerBootstrapHttpTransportConfig}.scala` config alias/missing-key/unknown-peer property
- 이 wave가 열어주는 것:
  - node-jvm transport가 더 이상 raw string/sparse optional shape에 매달리지 않게 된다.
  - hotstuff/bootstrap runtime이 typed config와 typed wire boundary 위에 올라가게 된다.

### Wave 5. Consensus And Runtime Hardening
- 왜 다섯 번째인가: shared contract와 transport/config boundary가 정리된 뒤에 consensus runtime invariant를 type으로 끌어올리는 것이 가장 안전하다.
- 포함 항목:
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Policy.scala`의 `HotStuffRequestPolicy`, `HotStuffDeploymentTarget`, `HotStuffHeight.+`, `HotStuffView.+`, `HotStuffWindow.apply`, `EquivocationKey.apply`
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/PacemakerRuntime.scala`의 `HotStuffPacemakerPolicy`
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Policy.scala`의 `ValidatorId`, `HotStuffHeight`, `HotStuffView` 공통 abstraction
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Artifacts.scala`의 `ProposalTxId`
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/SnapshotSync.scala` typed failure 유지와 common algebra + adapter split
  - `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff` property suite
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Artifacts.scala`의 `ValidatorSet` / `ProposalTxSet` law
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/PacemakerRuntime.scala` generative model test
- 이 wave가 열어주는 것:
  - consensus safety 관련 invariant가 runtime `require`/`throw`에서 type/property gate로 이동한다.

### Wave 6. Core Domain Semantics And Remaining Boundary Cleanup
- 왜 마지막인가: core application semantic cleanup은 앞선 primitive/helper/failure/runtime boundary가 정리된 뒤에 가장 깔끔하게 끝낼 수 있다. 다만 `Bag.scala`는 strict dependency가 아니라 leverage 기준으로 뒤에 둔 low-conflict tail cleanup이다.
- 포함 항목:
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/domain/GroupTypes.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationBatchRuntime.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/domain/AccountsTypes.scala`의 `GroupId`, batch idempotency key, nonce/member-count domain typing
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/transactions/GroupTransactions.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/transactions/AccountsTransactions.scala`의 `NonEmpty*` transaction shape
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/domain/AccountsBranding.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/domain/GroupBranding.scala` generic branding helper
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/security/SignatureVerifier.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationScheduling.scala` key-id derivation single source of truth
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/module/AccountsBlueprint.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/module/GroupsBlueprint.scala` reducer support utility, tuple-key helper, nonce/member-count helper
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/execution/{StateModuleExecutor,TxExecution}.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationBatchRuntime.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationScheduling.scala`의 execution/receipt/compatibility surface cleanup과 `documentedCompatibilityFamilies` closed enum화
  - `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage/swaydb/Bag.scala` unsafe boundary isolation. 독립 항목이지만 hotspot/contract work 이후에 밀어 넣기 쉬운 priority-tail cleanup으로 취급한다.
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationScheduling.scala` footprint upper-bound property
  - `modules/core/shared/src/main/scala/org/sigilaris/core/application/state/AccessLog.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/scheduling/ConflictFootprint.scala` law property
- 이 wave가 닫히면:
  - catalog의 core semantic / effect isolation / property gap이 모두 정리되고, 남는 것은 implementation detail polish 수준이 된다.

## Change Areas

### Code
- 이번 단계에서는 code를 바꾸지 않고 catalog와 sequencing 문서만 작성한다.

### Tests
- 이번 단계에서는 test를 추가하지 않는다.
- 다만 아래 catalog에서 property-based test gap을 추적하고, sequencing section에서 wave별 gate로 연결한다.

### Docs
- 본 문서

## Opportunity Catalog

### A. Parse, Don’t Validate / Refined Type / Total Function

#### A1. `require` / `unsafe*` / `throw`를 typed constructor로 밀어올릴 후보

| surface | focus | opportunity |
| --- | --- | --- |
| `modules/core/shared/src/main/scala/org/sigilaris/core/failure/FailureCode.scala` | `parse`, `iron`, `total` | `FailureCode(value: String)`가 `require`에 의존한다. opaque refined type + safe parser로 바꿔 invalid code를 constructor 밖으로 밀어내고, `ErrorKey`도 "만들고 나서 검증" 대신 typed parts에서 조합하게 만들 수 있다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/datatype/BigNat.scala` | `total`, `iron` | `unsafeFromBigInt`가 generic `Exception`을 던지고 `divide`는 0 나눗셈에 partial이다. `NonZeroBigNat` 또는 refined divisor를 도입하면 division을 total하게 만들 수 있다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/datatype/UInt256.scala` | `parse`, `total`, `iron` | `unsafeFromBytesBE`, `unsafeFromBigIntUnsigned`는 production surface를 넓힌다. unsafe API를 test/constants namespace로 좁히고, fixed-width bytes 자체를 refined type로 승격할 수 있다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `parse`, `iron`, `total` | `StableArtifactId`, `TopicWindowKey`, `CursorToken`은 "non-empty bytes" invariant를 runtime `Either`/`throw`로 다룬다. refined `ByteVector` wrapper로 더 이른 단계에서 막을 수 있다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `total`, `iron` | `CursorToken.issue`는 version range를 `throw`로 검사한다. `0..255` refined byte version type으로 바꾸면 partial constructor가 사라진다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxRuntimeState.scala` | `parse`, `iron` | `TxRuntimePolicy`는 `require`로 positive/non-negative 범위를 막는다. config load 단계에서 refined policy 값으로 parse하고 runtime에는 이미 validated policy만 넘기게 만들 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Policy.scala` | `parse`, `iron` | `HotStuffRequestPolicy`, `HotStuffDeploymentTarget`는 `require` 기반이다. positive `Int`, positive `Duration` value object를 도입하면 policy object 자체가 parse result가 된다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/PacemakerRuntime.scala` | `parse`, `iron`, `total` | `HotStuffPacemakerPolicy`는 여러 numeric/duration invariant를 `require`에 둔다. `NonNegativeDuration`, `PositiveInt`, bounded jitter/backoff types로 승격하면 policy validation이 분산되지 않는다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Policy.scala` | `total` | `HotStuffHeight.+(delta: Long)`와 `HotStuffView.+(delta: Long)`가 negative delta에서 throw 한다. delta를 refined non-negative type으로 받게 하면 addition이 total해진다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Policy.scala` | `total`, `parse` | `HotStuffWindow.apply(..., height: Long, view: Long)`와 `EquivocationKey.apply(..., height: Long, view: Long)`는 unsafe constructor다. safe constructor만 남기고 raw numeric overload를 지우는 쪽이 더 명확하다. |

#### A2. raw `String` / `Int` / `Long`를 domain type으로 끌어올릴 후보

| surface | focus | opportunity |
| --- | --- | --- |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/domain/AccountsTypes.scala` | `iron`, `dedup` | `KeyId20`만 `FixedLength[20]`를 쓰고 비슷한 byte-backed ID들은 제각각이다. fixed-width byte ID helper를 일반화해서 `KeyId20`, tx id, hash-like ID가 공통 패턴을 재사용하게 만들 수 있다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `parse`, `iron`, `dedup` | `PeerIdentity`, `ChainId`, `GossipTopic`, `ControlIdempotencyKey`가 동일한 구조의 `parse`/`unsafe`/`Eq`를 반복한다. lower-ascii token / UUIDv4 typed newtype을 공통화하면 duplication과 typo surface를 동시에 줄일 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Policy.scala` | `parse`, `iron`, `dedup` | `ValidatorId`, `HotStuffHeight`, `HotStuffView`도 같은 패턴을 반복한다. gossip 쪽 token/uuid/newtype machinery와 shared abstraction으로 합칠 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Artifacts.scala` | `parse`, `least power` | `ProposalTxSet`는 실제로 fixed-width `UInt256` tx id를 기대하지만 type은 `StableArtifactId`다. `ProposalTxId` 같은 더 약한 전용 타입으로 wire-compat invariant를 type level에 올릴 수 있다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/domain/GroupTypes.scala` | `parse`, `intention` | `GroupId = Utf8`는 comment상 "no format constraints"다. stable key/identifier로 쓸 것이라면 `Utf8Key` 또는 별도 refined identifier 정책을 도입하는 편이 의도를 더 잘 드러낸다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationBatchRuntime.scala` | `parse`, `iron` | `CurrentApplicationBatch.idempotencyKey: String`과 `receiptsByIdempotencyKey: Map[String, ...]`는 stringly-typed이다. dedicated batch idempotency key type이 필요하다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/domain/AccountsTypes.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/domain/GroupTypes.scala` | `intention`, `least power` | `nonce: BigNat`, `memberCount: BigNat`는 너무 일반적이다. `AccountNonce`, `GroupNonce`, `MemberCount` 같은 domain type으로 intention을 분리할 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala` | `parse`, `least power` | `ControlOpWire`, `ControlRequestWire`, `EventRequestWire`가 raw `kind: String` + optional sparse field 조합이다. operation/request별 sealed ADT로 바꾸면 invalid shape를 parse 단계에서 제거할 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapArmeriaAdapter.scala` | `parse`, `least power` | `ProposalPageRequestWire.height: String` 같은 wire field는 domain type보다 약하다. wire DTO decode 시점에 `HotStuffHeight`, `BlockId`, `StateRoot` 같은 typed value로 승격하는 쪽이 낫다. |

#### A3. collection shape를 `NonEmpty*` 쪽으로 강화할 후보

| surface | focus | opportunity |
| --- | --- | --- |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `least power`, `total` | `SessionSubscription`이 empty set를 runtime에서 막는다. `NonEmptySet[ChainTopic]` 같은 표현으로 illegal state를 만들 수 없게 하는 편이 더 약한 power를 준다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/transactions/GroupTransactions.scala` | `parse`, `least power` | `AddAccounts.accounts`와 `RemoveAccounts.accounts`는 reducer에서 empty 여부를 검사한다. transaction model 자체를 `NonEmptySet[Account]`로 바꿔야 한다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/transactions/AccountsTransactions.scala` | `parse`, `least power` | `AddKeyIds.keyIds`와 `RemoveKeyIds.keyIds`도 empty collection이 허용된다. 이 역시 `NonEmptyMap` / `NonEmptySet`로 transaction shape를 더 약하게 만들 수 있다. |

### B. Duplication Removal / Intention Clarification

| surface | focus | opportunity |
| --- | --- | --- |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/domain/AccountsBranding.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/domain/GroupBranding.scala` | `dedup`, `intention` | 동일한 branded wrapper 패턴이 모듈별로 반복된다. generic module branding helper로 올리면 boilerplate가 줄고 pattern이 선명해진다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/module/AccountsBlueprint.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/module/GroupsBlueprint.scala` | `dedup`, `intention` | `invalidRequest`/`notFound`/`conflict` helper, nonce mismatch 패턴, auth flow가 크게 겹친다. reducer support utility를 두고 각 reducer는 domain rule만 남기는 편이 낫다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/security/SignatureVerifier.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationScheduling.scala` | `dedup` | recovered public key에서 `KeyId20`를 만드는 로직이 중복된다. single source of truth로 모아야 footprint derivation과 실제 verification이 drift하지 않는다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/module/AccountsBlueprint.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/module/GroupsBlueprint.scala` | `dedup` | tuple key `ByteEncoder`/`ByteDecoder`를 모듈별로 손으로 쓴다. common tuple-key helper 또는 schema DSL이 있으면 중복이 줄어든다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `dedup`, `intention` | `GossipFieldValidation` 안의 lower-ascii token과 topic regex가 사실상 같다. semantic distinction이 필요 없다면 하나로 합치고, 필요하다면 타입 수준으로 분리하는 편이 낫다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/execution/StateModuleExecutor.scala` | `dedup`, `intention` | plain/routed API가 거의 같은 로직을 두 벌로 갖고 있다. 작은 executor algebra 하나로 합치면 surface area와 유지 비용이 줄어든다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/execution/TxExecution.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/execution/StateModuleExecutor.scala` | `intention`, `side-effect` | `nextState`와 `observedState`를 함께 두고 back-compat path가 `observedState`를 반환한다. witness log와 continuation state의 의미를 다시 섞고 있으므로, 이후에는 `TxExecution`만 노출하고 compatibility path를 줄이는 편이 더 명확하다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationBatchRuntime.scala` | `intention`, `least power` | receipt에 `TxExecution[?, ?]`를 저장하면 consumer가 실제로 필요한 surface가 무엇인지 흐려진다. 공개 receipt용 projection ADT와 internal witness를 분리할 여지가 크다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/failure/ClientFailureMessage.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/failure/ConflictMessage.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/failure/FailureMessageFormat.scala` | `dedup`, `intention` | `ClientFailureMessage.invalidRequestWithCode` / `forbiddenWithCode` / `notFoundWithCode`, `ConflictMessage.formatWithCode`, 그리고 `AccountsBlueprint` / `GroupsBlueprint`의 reducer-local `invalidRequest` / `notFound` / `conflict` wrapper가 같은 조립 패턴을 반복한다. structured failure renderer DSL로 합치면 domain reducer 코드가 더 읽히기 쉬워진다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/failure/SigilarisFailure.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/scheduling/FootprintDeriver.scala`, `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala`, `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/{Artifacts,Policy,SnapshotSync}.scala` | `intention`, `least power` | `SigilarisFailure.msg`, `FootprintDerivationFailure(reason, detail)`, `CanonicalRejection.*(reason, detail)`, `HotStuffValidationFailure(reason, detail)`, `HotStuffPolicyViolation(reason, detail)`, `SnapshotSyncFailure(reason, detail)`가 비슷한 diagnostic shape를 각자 string tuple로 들고 있다. closed reason ADT 또는 typed diagnostic payload가 있어야 string drift를 줄일 수 있다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationScheduling.scala` | `intention` | `documentedCompatibilityFamilies: Vector[String]`는 documentation string drift 위험이 있다. compatibility family marker 또는 closed enum을 두는 편이 더 명시적이다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `intention` | session id, cursor, control op, handshake policy, negotiation, channel message가 한 file에 몰려 있다. concept별 file split이 필요하다. Background hotspot 참고. shared `org.sigilaris.node` contract를 더 작게 curate 하려는 ADR-0025 방향과도 직접 연결된다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala` | `intention`, `least power` | runtime state transition, control interpretation, batching, replay, dedup가 한 file에 몰려 있다. pure interpreter core와 effectful integration surface를 쪼갤 여지가 크다. Background hotspot 참고. shared `org.sigilaris.node` contract와 runtime-specific interpreter seam을 더 분명히 하려는 ADR-0025 방향과도 연결된다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala` | `dedup`, `intention` | wire DTO, Circe codec, binary frame codec, Tapir endpoint, auth choreography, runtime translation이 한 file에 섞여 있다. model/codec/translator/server adapter로 분리해야 읽힌다. Background hotspot 참고. runtime-specific adapter를 `node-jvm`에 남기고 shared contract를 더 좁히려는 ADR-0025 경계와도 맞물린다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapArmeriaAdapter.scala` | `dedup`, `intention` | server endpoint, wire DTO, bootstrap translation, Java `HttpClient` outbound logic가 한 file에 섞여 있다. inbound server adapter와 outbound client adapter를 분리할 수 있다. runtime-specific bootstrap adapter ownership을 더 선명히 하는 ADR-0025 boundary cleanup과 연결된다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`, `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `dedup`, `intention` | `RejectionWire`가 `CanonicalRejection`을 raw fields로 다시 풀어쓴다. wire codec을 ADT 쪽으로 붙이면 duplicate mapping layer를 줄일 수 있다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/module/AccountsBlueprint.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/module/GroupsBlueprint.scala` | `dedup` | `BigNat.unsafeFromBigInt(... + 1)` 같은 nonce increment/update가 반복된다. domain-level `nextNonce`, `incrementMemberCount`, `decrementMemberCount` helper가 있으면 intent가 코드로 드러난다. |

### C. Side-Effect Isolation / Boundary Cleanup

| surface | focus | opportunity |
| --- | --- | --- |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage/swaydb/Bag.scala` | `side-effect isolation`, `total` | SwayDB bridge가 `unsafeRunSync`, `unsafeToFuture`, global runtime에 의존한다. unsafe boundary를 더 좁은 adapter seam으로 밀고 테스트 가능한 contract를 분리하는 편이 낫다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/SnapshotSync.scala` | `side-effect isolation`, `typed failure` | typed storage failure를 `IllegalStateException`으로 재포장하는 코드가 있다. repository/runtime 사이에서 typed error channel을 유지해야 한다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/SnapshotSync.scala` | `side-effect isolation`, `dedup` | `SnapshotMetadataStore.fromKeyValueStore`와 `SnapshotNodeStore.fromKeyValueStore`는 in-memory/store-backed branch가 유사한데 실패 surface가 다르다. common algebra + separate adapter가 더 적합하다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/StaticPeerTopologyConfig.scala`, `StaticPeerTransportAuthConfig.scala`, `StaticPeerBootstrapHttpTransportConfig.scala`, `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala` | `side-effect isolation`, `parse`, `dedup` | Typesafe Config parsing helper가 파일마다 반복되고 alias policy도 흩어져 있다. typed config parser layer를 따로 두고 runtime bootstrap은 parsed config만 받게 만들 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/StaticPeerBootstrapHttpTransportConfig.scala` | `parse`, `least power` | `peerBaseUris: Map[PeerIdentity, String]`는 raw string URI를 그대로 들고 있다. parsed `URI` 또는 dedicated endpoint type으로 바꾸는 게 맞다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/StaticPeerTransportAuth.scala` | `parse`, `side-effect isolation` | `configure(topology, peerSecrets: Map[String, String])`가 parsing과 completeness validation을 한 곳에서 한다. parse된 peer/secret pair와 topology consistency check를 분리할 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/GossipTransportAuth.scala` | `side-effect isolation`, `intention` | `issueTransportProof` / `issueBootstrapCapability`, `deriveKey`, `mac`, `constantTimeEquals`는 pure crypto core에 가깝고, `authenticateRequest` / `verifyBootstrapCapability`, `parseAuthenticatedPeer`, `parseMacHeader`는 HTTP header parsing + rejection mapping에 가깝다. 현재는 한 object에 섞여 있으므로 pure crypto core와 HTTP adapter를 분리하면 테스트 단위가 좋아진다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala` | `side-effect isolation`, `intention` | huge bootstrap file이 config load, validator parsing, transport wiring, runtime assembly를 함께 가진다. parsed config model, assembly plan, effectful resource wiring을 분리하는 게 필요하다. Background hotspot 참고. |

### D. Property-Based Test Gaps

| surface | focus | risk tier | opportunity |
| --- | --- | --- | --- |
| `modules/node-common/shared/src/test/scala` | `property test` | `coverage hygiene` | 현재 `node-common`에는 property/fuzz suite가 없다. gossip ID/parser/negotiation/cursor/control contract 전반이 example-based test에만 의존한다. |
| `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff` | `property test` | `consensus safety` | HotStuff 쪽도 property suite가 없다. validator set, tx-set canonicalization, pacemaker timeout laws, bootstrap parsing은 generative test에 잘 맞는다. |
| `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/gossip`, `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/transport/armeria/gossip` | `property test` | `wire / interoperability` | transport/auth/adapter tests도 example 위주다. binary frame codec, rejection rendering, session auth binding은 round-trip/property test가 필요하다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `property test`, `parse` | `wire / interoperability` | `DirectionalSessionId`, `PeerCorrelationId`, `PeerIdentity`, `ChainId`, `GossipTopic`, `ControlIdempotencyKey`는 parse/render closure와 invalid-input rejection property를 가질 수 있다. |
| `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Model.scala` | `property test` | `wire / interoperability` | `SessionNegotiation.resolveProposal` / `acknowledge` / `validateAck`는 negotiation bound law, monotonicity, round-trip 성질을 property로 검증할 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala` | `property test` | `wire / interoperability` | `BinaryEventStreamCodec`는 encode/decode round-trip, trailing-byte rejection, oversize frame rejection, version/kind mismatch property를 generative하게 검증해야 한다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/StaticPeerTopologyConfig.scala`, `StaticPeerTransportAuthConfig.scala`, `StaticPeerBootstrapHttpTransportConfig.scala` | `property test`, `parse` | `config ergonomics` | alias key(`kebab-case` vs `camelCase`) equivalence, missing-key rejection, unknown-peer rejection을 config generator로 검증할 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Artifacts.scala` | `property test` | `consensus safety` | `ValidatorSet`은 uniqueness, order preservation, hash stability property를 가지며 `ProposalTxSet`은 canonicalize idempotence, dedup, sort order law를 property로 검증할 수 있다. |
| `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/PacemakerRuntime.scala` | `property test` | `consensus safety` | timeout backoff monotonicity, bootstrap-held 상태에서 emit 금지, duplicate/stale 처리 성질은 generative model test가 더 적합하다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/scheduling/CurrentApplicationScheduling.scala` | `property test`, `intention` | `core correctness` | declared footprint가 actual footprint를 항상 upper-bound 하는지 transaction generator 기반 property로 확인할 수 있다. 지금은 example suite만 있다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/application/state/AccessLog.scala`, `modules/core/shared/src/main/scala/org/sigilaris/core/application/scheduling/ConflictFootprint.scala` | `property test` | `core correctness` | `combine` associativity/identity, `conflictsWith` symmetry, `fromAccessLog` prefix invariant property를 law 형태로 넣을 수 있다. |
| `modules/core/shared/src/main/scala/org/sigilaris/core/failure/*` | `property test`, `parse` | `error contract ergonomics` | 현재 failure/message render surface는 stringly-typed 조합이라 example test 이상으로 일반화하기 어렵다. `structured failure surface normalization` 이후 render/parse round-trip과 invalid key fallback property를 추가한다. |

## Test Plan
- 이 단계의 success 조건은 code 변경이 아니라 catalog completeness와 sequencing clarity 다.
- 최소한 `core`, `node-common`, `node-jvm` 각각에서 typed parsing, duplication, side-effect isolation, property-test gap이 모두 한 번 이상 기록되어야 한다.
- `unsafe`, `require`, raw primitive, large mixed-responsibility file, missing property suite가 모두 catalog에 반영되어야 한다.
- 후속 구현 단계에서는 각 항목에 맞는 property test 또는 regression test를 추가 gate로 삼는다.

## Risks And Mitigations
- 항목 수가 많아 중복처럼 보일 수 있다. seam이 다른 항목은 catalog에서 분리해 두고, sequencing에서만 같은 wave로 묶는다.
- wave가 커지면 다시 거대한 refactor가 될 수 있다. 각 wave 안에서도 helper extraction -> type rollout -> test gate 순서로 더 잘게 나눈다.
- `Wave 1`이 과도하게 넓어질 수 있다. 그래서 `Wave 1a` scaffolding과 `Wave 1b` cross-module normalization을 구분하고, 후자는 가능하면 `Wave 2` split 이후에 마무리한다.
- `Wave 1`과 `Wave 2`를 strict serial로 오해할 수 있다. 문서상으로는 순서를 두되, 실제 실행은 `Wave 1a || Wave 2 -> Wave 1b` 형태의 부분 병렬을 허용한다.
- 구현이 아닌 catalog 단계라 표현이 너무 추상적으로 흐를 수 있다. 가능한 한 file/type 단위로 anchor를 남긴다.
- 구조적 항목과 소규모 cleanup 항목이 섞여 산만해질 수 있다. category를 `typed parsing`, `duplication`, `side effects`, `property test`로 분리해 읽기 비용을 낮춘다.

## Acceptance Criteria
1. `plan 0011` 문서가 repository에 추가되고, quality improvement opportunity catalog를 실제 code surface와 연결해 기록한다.
2. 문서가 duplication, intention clarification, side-effect isolation, parse-don't-validate, least power, total function, `iron`, property-based test를 모두 포함한다.
3. 문서가 모든 catalog 항목을 wave 단위 우선순위로 재배치하고, 앞 wave가 뒤 wave를 어떻게 여는지 설명한다.
4. 문서가 모든 catalog 항목이 정확히 하나의 wave에 배정되었음을 검증 가능하게 남긴다.

## Checklist

### Phase 0: Repository Survey
- [x] 기존 plan/ADR 형식을 확인한다.
- [x] `core`, `node-common`, `node-jvm` main/test code를 조사한다.
- [x] `unsafe`, `require`, raw primitive, hotspot file, property-test 분포를 확인한다.

### Phase 1: Catalog Authoring
- [x] typed parsing / refined type / total function 후보를 정리한다.
- [x] duplication / intention clarification 후보를 정리한다.
- [x] side-effect isolation 후보를 정리한다.
- [x] property-based test gap을 정리한다.
- [x] cross-cutting dependency note와 property-test risk tier를 정리한다.

### Phase 2: Sequencing
- [x] catalog 항목을 dependency/leverage 기준 wave로 재배치한다.
- [x] 각 wave가 여는 후속 작업을 명시한다.
- [x] property test gap을 각 wave의 exit gate에 연결한다.
- [x] catalog↔wave completeness check와 ambiguous 항목 배정을 문서에 명시한다.

### Phase 3: Follow-Up Handoff
- [ ] 각 wave를 smaller implementation batches로 분해한다.
- [ ] 구조적 항목 중 ADR 승격이 필요한 것을 선별한다.
- [ ] 실제 구현 plan 또는 issue breakdown으로 옮긴다.

## Follow-Ups
- `Wave 2`와 `Wave 5`는 범위가 넓어서 별도 implementation plan으로 다시 쪼갤 가능성이 높다.
- `TxExecution` / `StateModuleExecutor` witness-vs-continuation surface는 `Wave 6` 안에서도 별도 설계 메모가 필요할 수 있다.
- refined type 도입은 `Wave 1` helper를 기준으로 domain/application/node 쪽으로 확장하는 단계적 plan으로 가져간다.
