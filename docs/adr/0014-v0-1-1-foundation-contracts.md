# ADR-0014: v0.1.1 Foundation Contracts

## Status
Accepted

## Context
- `v0.1.1` 범위는 transaction envelope 이동만으로 끝나지 않는다. codec, opaque wrapper ergonomics, failure metadata, registry safety가 함께 잠기지 않으면 downstream contract drift가 생긴다.
- 현재 JSON sum derivation은 wrapped-by-type-key를 지원하지만 sealed trait와 enum이 공유하는 canonical label rule이 공용 abstraction으로 고정돼 있지 않다.
- `GroupId`, `NetworkId` 같은 단순 opaque wrapper는 representation codec forwarding을 반복 작성하고 있다.
- codec regression helper를 별도 publishable artifact로 만들면 빌드/publish 복잡도가 커지지만, 아무 재사용 helper 없이 두면 법칙(property/law) 스위트가 계속 중복된다.

## Decision
1. JSON coproduct contract는 `wrapped-by-type-key` 하나만 public representation으로 유지한다.
   - canonical subtype label은 Scala 3 Mirror label을 그대로 사용한다.
   - sealed trait와 enum은 동일한 label resolution path를 공유한다.
   - rename이 필요하면 `TypeNameStrategy.Custom`의 명시 매핑으로만 허용한다.
   - `TypeNameStrategy.FullyQualified`는 실제 FQ label source가 생길 때까지 canonical label과 동일하게 동작하는 compatibility alias로 유지한다.

2. 단순 opaque wrapper는 representation-driven companion trait family로 정리한다.
   - `OpaqueValueCompanion[A, Repr]`는 `Eq`, `ByteEncoder`, `ByteDecoder`, `JsonEncoder`, `JsonDecoder`를 representation evidence만으로 forwarding한다.
   - `KeyLikeOpaqueValueCompanion[A, Repr]`만 `JsonKeyCodec[Repr]`를 추가 요구하고 `JsonKeyCodec[A]`를 제공한다.
   - `GroupId`, `NetworkId`는 이 helper family의 1차 적용 대상이다.
   - fixed-size binary validation이나 custom tag byte가 필요한 타입은 opt-in 하지 않는다.

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

실제 구현은 generic trait 밖에서 `opaque` companion의 representation projection을 안정적으로 재사용하기 위해 `inline` abstract method 대신 `wrap` / `unwrap` pair를 사용한다. public API는 그대로 `repr` extension을 통해 노출한다.

3. primitive byte contract에서 `Boolean`은 단일 바이트 canonical form으로 잠근다.
   - `false -> 0x00`
   - `true -> 0x01`
   - decoder는 빈 입력과 나머지 값을 모두 실패로 처리한다.

4. transaction coverage proof는 `TxRegistry[Txs]` 경계에서만 강제한다.
   - evidence shape는 `ReducerCoverage[T <: Tx]`.
   - 구현은 `erasedValue`/`summonInline` 기반 tuple recursion을 사용한다.
   - registry guard는 proof를 소비만 하고, reflection이나 subtype scanning은 도입하지 않는다.

5. failure metadata는 core-owned `FailureCode`와 adapter-facing `ErrorKey`로 분리한다.
   - `SigilarisFailure.msg`는 유지한다.
   - `FailureCode`는 stable, transport-neutral, machine-readable identifier다.
   - `ErrorKey`는 formatter가 `FailureCode`와 feature context에서 투영한 normalized external key다.

```scala
trait SigilarisFailure:
  def msg: String
  def code: FailureCode

object ClientFailureMessage:
  def invalidRequest(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    ErrorKey.client(Kind.InvalidRequest, domain, reason, code).render(message, detail)
```

6. codec law helper는 `v0.1.1`에서 publishable module로 분리하지 않는다.
   - `modules/core/shared/src/test/scala` 아래 internal-only test support로 유지한다.
   - release artifact surface와 publish configuration은 늘리지 않는다.

## Consequences
- public JSON examples, fixtures, and docs must use canonical Mirror labels unless an explicit custom mapping is shown.
- 단순 opaque wrapper는 공통 helper로 줄이고, 특수 layout 타입만 수동 codec을 유지한다.
- failure formatter는 문자열 규칙 자체가 아니라 `FailureCode`를 source of truth로 삼게 된다.
- law helper는 빠르게 재사용할 수 있지만, 외부 소비용 artifact는 후속 마일스톤으로 미룬다.

## Follow-Up
- fully qualified subtype labels가 실제로 필요해지면 별도 label source와 migration story를 ADR로 다시 잠근다.
- internal-only codec law support가 반복적으로 외부 모듈에서 필요해지면 testkit module 승격을 재평가한다.
