# 0001 - v0.1.1 Foundation Features

## Status
Completed

## Created
2026-03-27

## Last Updated
2026-03-27

## Background
- `v0.1.1`의 출발선으로 `TxEnvelope`와 `NetworkId`는 이미 `core.application.transactions` 아래의 공용 모델로 정리되었다.
- 남은 `v0.1.1` 범위는 단일 파일 수정으로 끝나지 않는다. `ByteCodec`/`JsonCodec` 계약, opaque wrapper 파생 ergonomics, `TxRegistry` 기반 컴파일 타임 안전성, failure metadata, 문서/회귀 테스트가 함께 움직여야 한다.
- 현재 `JsonEncoder`/`JsonDecoder`는 wrapped-by-type-key 형태의 sum encoding을 이미 제공하지만, sealed trait와 enum의 label/discriminator 규칙이 재사용 가능한 공용 계약으로 잠기지 않아 호환성 부채가 커질 여지가 있다.
- 현재 `ByteCodec` 기본 primitive coverage에는 `Boolean` round-trip이 빠져 있고, `GroupId`, `NetworkId` 같은 단순 opaque wrapper는 반복적인 forwarding 코드를 직접 작성하고 있다.
- `TxRegistry[Txs]`는 존재하지만, 등록된 트랜잭션이 reducer/handler coverage를 모두 갖췄는지를 조립 경계에서 강제하지 않는다.
- `SigilarisFailure`는 현재 사람이 읽는 `msg`만 제공한다. 반면 `ClientFailureMessage`와 `ConflictMessage`는 별도 문자열 규칙을 통해 안정 키를 만들고 있어, 실제 failure source와 외부 매핑 계층 사이에 중복 계약이 생겨 있다.

## Goal
- `v0.1.1`에서 공용 transaction/codec/failure 계약을 명시적으로 잠그고, 문서와 테스트가 그 계약을 바로 검증하도록 만든다.
- downstream 모듈이 opaque wrapper와 공용 ADT를 더 적은 boilerplate로 정의할 수 있게 한다.
- 새 트랜잭션을 `TxRegistry`에 추가할 때 누락된 reducer coverage가 컴파일 단계에서 바로 드러나게 한다.

## Scope
- `Boolean` byte codec과 관련 primitive regression을 완성한다.
- 기존 wrapped-by-type-key JSON sum derivation 위에 sealed trait/enum 공용 label/discriminator 전략을 추출한다.
- representation 기반 opaque wrapper용 companion mix-in과 opaque product용 codec helper를 추가한다.
- `TxRegistry[Txs]`에 묶인 compile-time coverage check를 도입한다.
- `FailureCode`/`ErrorKey` 기반의 lightweight failure metadata foundation을 추가한다.
- 재사용 가능한 codec law helper를 패키징하고 대표 타입/스위트에 적용한다.
- `TxEnvelope`/`NetworkId`의 현재 통합 지점을 검증하고 공개 문서 예제를 동기화한다.

## Non-Goals
- byte-level sum encoding 또는 별도의 `DiscriminatorCodec` 도입
- HTTP 4xx/5xx 의미를 core failure 모델에 직접 반영하는 일
- `TxRegistry`와 무관한 전역 sealed subtype 스캔 기반 coverage 검사
- 결제 escrow, refund liability, manager release 같은 node-level commerce feature
- `v0.1.1` 범위를 넘어서는 대규모 패키지 재정렬

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0012: Signed Transaction Requirement
- ADR-0013: Application Package Realignment
- ADR-0014: v0.1.1 Foundation Contracts
- `docs/dev/v0.1.1-feature-summary.md`
- `docs/dev/codec-and-prefix-laws.md`
- `docs/application-package-index.md`

위 참조는 2026-03-27 기준 현재 저장소에 존재하는 문서만 연결한다.

## Decisions To Lock Before Implementation
- Phase 0의 확정 결과는 최소한 세 곳에 남긴다: 이 plan의 `Decisions To Lock Before Implementation` 갱신, 장기 정책이면 ADR 링크 추가 또는 ADR 초안 생성, 대표 API 예시를 담은 scaladoc/fixture/test 추가.
- JSON sum/enum contract는 `wrapped-by-type-key`를 `v0.1.1`의 유일한 public representation으로 유지한다.
- canonical label은 Scala 3 Mirror가 노출하는 subtype label 그대로 사용한다. sealed trait와 enum은 동일한 label selection 경로를 공유하고, rename이 필요하면 `TypeNameStrategy.Custom`의 명시 매핑으로만 허용한다.
- `TypeNameStrategy.FullyQualified`는 실제 fully qualified label source가 생기기 전까지 별도 public wire format을 만들지 않는다. `v0.1.1`에서는 canonical label과 동일하게 동작하는 compatibility alias로 유지한다.
- opaque companion mix-in은 representation type별로 쪼개지지 않는 하나의 trait family로 설계한다. base trait는 `Eq`, `ByteEncoder`, `ByteDecoder`, `JsonEncoder`, `JsonDecoder`를 representation evidence만으로 forwarding하고, key-safe variant만 `JsonKeyCodec[Repr]`를 추가 요구한다.
- 대상 API 표면은 아래 수준으로 잠근다.

```scala
trait OpaqueValueCompanion[A, Repr]:
  protected def wrap(repr: Repr): A
  protected def unwrap(value: A): Repr

  extension (value: A) def repr: Repr = unwrap(value)

  given Eq[A] = new Eq[A]:
    def eqv(x: A, y: A): Boolean =
      Eq[Repr].eqv(unwrap(x), unwrap(y))

  given ByteEncoder[A] = ByteEncoder[Repr].contramap(unwrap)
  given ByteDecoder[A] = ByteDecoder[Repr].map(wrap)
  given JsonEncoder[A] = JsonEncoder[Repr].contramap(unwrap)
  given JsonDecoder[A] = JsonDecoder[Repr].map(wrap)

trait KeyLikeOpaqueValueCompanion[A, Repr]
    extends OpaqueValueCompanion[A, Repr]:
  given JsonKeyCodec[A] = JsonKeyCodec[Repr].imap(wrap, unwrap)
```

- 위 표면은 `opaque` 범위와 generic trait를 함께 다루기 위해 실제 구현에서 `inline` abstract method 대신 `wrap` / `unwrap` 조합으로 실현한다. public projection은 `repr` extension으로 유지한다.

- `NetworkId`와 `GroupId`는 같은 trait family로 커버한다. `KeyId20`처럼 fixed-size binary 검증이나 custom tag byte가 필요한 타입은 mix-in 대상에서 제외한다.
- `Show`/`Order` 같은 추가 typeclass는 이번 helper의 기본 표면에 넣지 않는다. 정말 필요한 타입만 별도 수동 인스턴스 또는 후속 확장으로 다룬다.
- `Boolean` byte codec의 canonical encoding은 `false -> 0x00`, `true -> 0x01`의 단일 바이트로 잠근다. decoder는 빈 입력과 `0x00/0x01` 이외의 첫 바이트를 모두 실패로 처리한다.
- transaction coverage proof는 `TxRegistry[Txs]` 경계에서 검증한다. 조립 모델과 무관한 “모든 `Tx` subtype 검사”는 이번 마일스톤에 넣지 않는다.
- transaction coverage proof는 Scala 3의 `erasedValue`/`summonInline` 기반 tuple recursion으로 구현하고, evidence는 예를 들어 `ReducerCoverage[T <: Tx]`처럼 transaction 단위로 모델링한다. 매크로나 runtime reflection은 사용하지 않는다.
- `ReducerCoverage[T]` evidence는 해당 transaction을 실제로 처리하는 feature blueprint/module 쪽이 제공한다. registry guard는 이 evidence를 소비만 하고, 자동 reflection으로 생성하지 않는다.
- failure metadata는 `SigilarisFailure`의 보조 계약으로 추가한다. `msg`는 유지하되, transport별 상태 코드는 core 밖에서 매핑한다.
- `FailureCode`는 core가 소유하는 stable, transport-neutral, machine-readable identifier이고, `ErrorKey`는 formatter/adapter가 사용하는 normalized external mapping key다. `ErrorKey`는 `FailureCode`와 feature context에서 투영되며, core failure가 곧바로 HTTP status를 뜻하지는 않는다.
- codec law helper packaging은 `internal-only support`로 잠근다. `v0.1.1`에서는 build/publish surface를 늘리지 않고 `modules/core/shared/src/test/scala` 아래 재사용 helper로 유지한다.

## Phase 0 Deliverables
- 이 plan의 정책 placeholder를 실제 선택된 계약으로 갱신한다.
- 장기 유지할 결정이 생기면 관련 ADR 링크를 추가하거나 새 ADR 초안을 만든다.
- opaque mix-in과 failure metadata에 대한 대표 API 예시를 코드 또는 테스트 fixture로 남긴다.
- codec law helper의 packaging 결과를 `publishable module` 또는 `internal-only support` 중 하나로 명시한다.

## Change Areas

### Code
- `modules/core/shared/src/main/scala/org/sigilaris/core/codec/byte`
- `modules/core/shared/src/main/scala/org/sigilaris/core/codec/json`
- `modules/core/shared/src/main/scala/org/sigilaris/core/failure`
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/transactions`
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/domain`
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/accounts/module`
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/feature/group/module`
- 필요하면 `build.sbt`와 신규 testkit 모듈 디렉터리

### Tests
- primitive byte codec property suite
- JSON derivation/configured discriminator regression suite
- `application/support/compiletime` 아래의 registry coverage negative test
- failure metadata/message suite
- codec law helper self-test와 기존 codec/property suite retrofit

### Docs
- `docs/dev/v0.1.1-feature-summary.md`
- `docs/application-package-index.md`
- `TxEnvelope`/`NetworkId` 공개 예제 또는 release-facing guide

## Implementation Phases

### Phase 0: Policy And Contract Lock
- sealed trait/enum label 규칙, opaque mix-in 표면, failure metadata 이름, codec law helper packaging 방식을 먼저 확정하고, 그 결과를 `Phase 0 Deliverables` 형식으로 저장소에 남긴다.
- 우선 migration할 단순 wrapper 후보를 고른다. 기본 후보는 `GroupId`와 `NetworkId`이며, 특수 byte layout이 있는 타입은 제외한다.
- `TxRegistry` coverage API와 실패 시 기대하는 compile-time error surface를 문서화하고, tuple recursion 기반 구현 전략을 잠근다.

### Phase 1: Codec Contract Consolidation
- `ByteEncoder[Boolean]`과 `ByteDecoder[Boolean]`을 추가하고 primitive round-trip/property coverage를 채운다.
- `JsonConfig.discriminator`를 출발점으로 sealed trait/enum label 계산을 공용 abstraction으로 정리한다.
- canonical label 규칙을 문서와 regression fixture에 함께 반영한다.

### Phase 2: Opaque Derivation Foundations
- representation 기반 `Eq`, `ByteCodec`, `JsonCodec`, 선택적 `JsonKeyCodec`를 제공하는 reusable companion mix-in을 추가한다.
- small opaque product wrapper가 수동 forwarding 없이 `ByteCodec`를 얻을 수 있는 helper entry point를 추가한다.
- `GroupId`, `NetworkId` 같은 단순 wrapper에 우선 적용하고, 특수 제약이 있는 codec은 수동 구현을 유지한다.

### Phase 3: Registry And Failure Safety
- `TxRegistry[Txs]`에 연결된 compile-time coverage proof를 추가하고 대표 blueprint/test 조합에 연결한다.
- `ReducerCoverage[T]` given은 해당 transaction reducer를 소유한 feature blueprint/module이 제공하고, `accounts`/`group` 대표 조합에 registry guard를 실제로 연결한다.
- `FailureCode`/`ErrorKey`를 `SigilarisFailure` 주변에 도입하되, 기존 `msg` 기반 API를 깨지 않게 유지한다.
- `ClientFailureMessage`와 `ConflictMessage`가 가능한 범위에서 새 metadata를 source of truth로 사용하도록 정리한다.

### Phase 4: Verification And Docs
- Phase 0에서 publishable testkit을 선택했으면 모듈/build wiring과 smoke consumer를 추가하고, internal-only support를 선택했으면 build 변경 없이 기존 회귀 스위트에 helper를 적용한다.
- legacy `TxEnvelope` import 경로가 남아 있지 않은지 점검하고 `NetworkId` 사용 예제를 문서에 반영한다.
- JVM/JS 대상의 핵심 회귀 스위트를 돌려 `v0.1.1` feature summary와 release wording을 동기화한다.

## Test Plan
- `Boolean`, `Option`, `List`, `Set`, representative opaque wrapper에 대해 byte round-trip/property test를 추가하거나 갱신한다.
- sum/enum JSON label 전략에 대해 success fixture와 unknown label failure 케이스를 모두 고정한다.
- 등록된 트랜잭션에 reducer coverage evidence가 없을 때 컴파일이 실패하는 negative compile-time test를 추가한다.
- migrated failure에 대해 `FailureCode`/`ErrorKey`와 기존 문자열 포맷이 함께 기대값을 만족하는지 검증한다.
- `AccountsBlueprintTest`, `GroupBlueprintTest`, integration suite에서 `TxEnvelope`/`NetworkId` 계약이 유지되는지 회귀 확인한다.
- 새 testkit 모듈을 도입하면 sibling module 또는 smoke test로 실제 소비 가능성을 확인한다.

## Risks And Mitigations
- discriminator label 규칙 변경이 외부 JSON 호환성을 깨뜨릴 수 있다. 기본 규칙을 먼저 고정하고 fixture/golden test로 보호한다.
- generic opaque derivation이 특수 codec을 잘못 대체할 수 있다. mix-in은 opt-in으로 두고, byte layout이 다른 타입은 수동 구현을 유지한다.
- compile-time coverage proof가 에러 메시지를 난해하게 만들 수 있다. `TxRegistry` 경계에 한정하고 evidence pretty-print 보강을 함께 검토한다.
- failure metadata와 기존 formatter 문자열이 이중 관리될 수 있다. `FailureCode`를 source of truth로 두고 formatter regression test를 유지한다.
- reusable law helper를 publishable artifact로 빼면 build/publish 복잡도가 올라간다. 초기 표면을 최소화하고 Phase 0에서 packaging을 잠근다.

## Acceptance Criteria
1. Phase 0에서 잠근 정책은 이 plan에 반영되고, 장기 정책은 ADR 링크 또는 ADR 초안으로 연결되며, 대표 API 예시가 저장소에 남는다.
2. `Boolean` byte codec, canonical JSON sum/enum label 규칙, 관련 문서/테스트가 `v0.1.1` 공용 계약으로 함께 정리된다.
3. `GroupId`와 `NetworkId`는 shared opaque helper 또는 동등한 단일 helper surface를 사용하도록 정리되고, companion object에서 수동 codec forwarding body를 반복하지 않는다.
4. `TxRegistry[Txs]`에 등록된 트랜잭션에 coverage proof가 없으면 전용 regression suite에서 컴파일이 실패한다.
5. `SigilarisFailure`는 transport 의미를 강제하지 않는 stable `FailureCode`를 제공하고, `ErrorKey` 투영 규칙이 formatter/adapters에서 일관되게 사용된다.
6. codec law helper와 `TxEnvelope`/`NetworkId` release-facing 문서가 함께 정리되어 회귀 테스트와 설명 문서가 같은 계약을 가리킨다.

## Phase Checklists

### Phase 0: Policy And Contract Lock
- [x] 이 plan에 잠긴 정책을 실제 선택안으로 갱신
- [x] 장기 유지할 결정의 ADR 링크 추가 또는 ADR 초안 생성
- [x] opaque mix-in / failure metadata 대표 API 예시 추가
- [x] codec law helper packaging 경로 확정

### Phase 1: Codec Contract Consolidation
- [x] `Boolean` byte codec 구현 완료
- [x] sealed trait/enum label abstraction 정리 완료
- [x] JSON 관련 regression fixture 및 문서 동기화 완료

### Phase 2: Opaque Derivation Foundations
- [x] shared opaque companion helper 추가 완료
- [x] `GroupId`, `NetworkId` 적용 완료
- [x] 특수 codec 유지 대상 수동 구현 정리 완료

### Phase 3: Registry And Failure Safety
- [x] `ReducerCoverage` evidence 및 `TxRegistry` guard 추가 완료
- [x] `accounts`/`group` blueprint wiring 반영 완료
- [x] `FailureCode` / `ErrorKey` foundation 추가 완료

### Phase 4: Verification And Docs
- [x] 선택된 packaging 경로에 맞는 codec law helper 적용 완료
- [x] `TxEnvelope` / `NetworkId` 공개 문서 및 예제 갱신 완료
- [x] 핵심 JVM/JS 회귀 검증 완료 또는 미실행 사유 기록
- [x] 후속 과제 정리 완료

## Follow-Ups
- byte-level sum encoding과 scodec-inspired `DiscriminatorCodec` 설계
- `(K_i, V_i)` pair 기반 schema case class 표현 탐색
- transport adapter와 더 넓은 failure taxonomy 정리
- `TxRegistry`를 넘어서는 sealed subtype coverage가 정말 필요한지 재평가
