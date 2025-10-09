# API 레퍼런스

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)

---

## 개요

이 문서는 세 가지 핵심 trait인 `ByteEncoder`, `ByteDecoder`, `ByteCodec`의 상세한 API 레퍼런스를 제공합니다. 타입별 인코딩 규칙은 [타입 규칙](types.md)을 참조하세요.

## ByteEncoder

`ByteEncoder[A]`는 타입 `A`의 값을 deterministic 바이트 시퀀스로 인코딩하는 contravariant 타입 클래스입니다.

### 핵심 메서드

#### encode
```scala
def encode(value: A): ByteVector
```

값을 deterministic 바이트 시퀀스로 인코딩합니다.

**예제:**
```scala mdoc:silent
import org.sigilaris.core.codec.byte.*

val encoder = ByteEncoder[Long]
```

```scala mdoc
encoder.encode(42L)
```

### 조합자 (Combinators)

#### contramap
```scala
def contramap[B](f: B => A): ByteEncoder[B]
```

인코딩 전에 함수를 적용하여 새로운 인코더를 생성합니다. 이는 contravariant functor 연산입니다.

**예제:**
```scala mdoc:silent
case class UserId(value: Long)

given ByteEncoder[UserId] = ByteEncoder[Long].contramap(_.value)
```

```scala mdoc
ByteEncoder[UserId].encode(UserId(100L))
```

**사용 사례:** 커스텀 타입을 인코딩 가능한 타입으로 변환합니다.

## ByteDecoder

`ByteDecoder[A]`는 바이트 시퀀스를 타입 `A`의 값으로 디코딩하는 covariant 타입 클래스입니다.

### 핵심 메서드

#### decode
```scala
def decode(bytes: ByteVector): Either[DecodeFailure, DecodeResult[A]]
```

바이트를 값으로 디코딩하여 실패 또는 나머지 바이트를 포함한 결과를 반환합니다.

**DecodeResult:**
```scala
case class DecodeResult[A](value: A, remainder: ByteVector)
```

**예제:**
```scala mdoc:silent
import scodec.bits.ByteVector

val decoder = ByteDecoder[Long]
val bytes = ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a)
```

```scala mdoc
decoder.decode(bytes)
```

### 조합자 (Combinators)

#### map
```scala
def map[B](f: A => B): ByteDecoder[B]
```

함수를 사용하여 디코딩된 값을 변환합니다. 이는 covariant functor 연산입니다.

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class UserId(value: Long)

given ByteDecoder[UserId] = ByteDecoder[Long].map(UserId(_))
```

```scala mdoc:silent
import scodec.bits.ByteVector

val bytes = ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64)
```

```scala mdoc
ByteDecoder[UserId].decode(bytes)
```

#### emap
```scala
def emap[B](f: A => Either[DecodeFailure, B]): ByteDecoder[B]
```

검증과 함께 디코딩된 값을 변환합니다. 비즈니스 규칙에 따라 디코딩이 실패할 수 있습니다.

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

case class PositiveInt(value: Int)

given ByteDecoder[PositiveInt] = ByteDecoder[Long].emap: n =>
  if n > 0 && n <= Int.MaxValue then
    PositiveInt(n.toInt).asRight
  else
    DecodeFailure(s"Value $n is not a positive Int").asLeft
```

```scala mdoc:silent
import scodec.bits.ByteVector

val validBytes = ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a)
val invalidBytes = ByteVector(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
```

```scala mdoc
ByteDecoder[PositiveInt].decode(validBytes)
ByteDecoder[PositiveInt].decode(invalidBytes).isLeft
```

#### flatMap
```scala
def flatMap[B](f: A => ByteDecoder[B]): ByteDecoder[B]
```

디코딩 작업을 체이닝합니다. 다음 디코더는 이전에 디코딩된 값에 따라 달라집니다.

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

// 길이가 디코더 동작을 결정하는 길이 접두사 데이터 디코딩
def decodeLengthPrefixed: ByteDecoder[String] =
  ByteDecoder[Long].flatMap: length =>
    new ByteDecoder[String]:
      def decode(bytes: ByteVector) =
        if bytes.size >= length then
          val (data, remainder) = bytes.splitAt(length)
          Right(DecodeResult(data.decodeUtf8.getOrElse(""), remainder))
        else
          Left(org.sigilaris.core.failure.DecodeFailure(s"Insufficient bytes: need $length, got ${bytes.size}"))
```

**사용 사례:** 구조가 이전에 디코딩된 값에 따라 달라지는 컨텍스트 의존적 디코딩.

## ByteCodec

`ByteCodec[A]`는 `ByteEncoder[A]`와 `ByteDecoder[A]`를 단일 타입 클래스로 결합합니다.

```scala
trait ByteCodec[A] extends ByteDecoder[A] with ByteEncoder[A]
```

### 사용법

타입에 인코더와 디코더 인스턴스가 모두 있으면 함께 summon할 수 있습니다:

```scala mdoc:silent
val codec = ByteCodec[Long]

val encoded = codec.encode(42L)
val decoded = codec.decode(encoded)
```

```scala mdoc
encoded
decoded
```

### 자동 Derivation

`ByteCodec`는 product 타입(case class, tuple)에 대한 자동 derivation을 제공합니다:

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class Transaction(from: Long, to: Long, amount: Long)

// 인스턴스가 자동으로 derive됨
val tx = Transaction(1L, 2L, 100L)
```

```scala mdoc
val encoded = ByteEncoder[Transaction].encode(tx)
val decoded = ByteDecoder[Transaction].decode(encoded)
```

## Given 인스턴스

companion object는 일반적인 타입에 대한 given 인스턴스를 제공합니다. 자세한 인코딩 명세는 [타입 규칙](types.md)을 참조하세요.

### 기본 타입
- `Unit`: 빈 바이트 시퀀스
- `Byte`: 단일 바이트
- `Long`: 8바이트 big-endian
- `Instant`: Long으로 epoch milliseconds

### 숫자 타입
- `BigInt`: 부호를 고려한 가변 길이 인코딩
- `BigNat`: 가변 길이 인코딩을 사용하는 자연수

### 컬렉션
- `List[A]`: 크기 접두사 + 순서가 있는 요소들 (encoder만 존재)
- `Option[A]`: 0개 또는 1개 요소 리스트로 인코딩
- `Set[A]`: 인코딩 후 lexicographic 정렬
- `Map[K, V]`: `Set[(K, V)]`로 처리

### Product 타입
- `Tuple2[A, B]`, `Tuple3[A, B, C]` 등: 자동 derivation
- Case class: `Mirror.ProductOf`를 통한 자동 derivation

## DecodeResult에서 값 추출

`DecodeResult[A]`는 디코딩된 값과 나머지 바이트를 모두 포함합니다. 값만 추출하려면:

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector
```

```scala mdoc
val result = ByteDecoder[Long].decode(ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a))
result.map(_.value)  // 값만 추출
```

## 에러 처리

### DecodeFailure

디코딩 실패는 `DecodeFailure`로 표현됩니다:

```scala
case class DecodeFailure(msg: String)
```

일반적인 실패 시나리오:
- **바이트 부족**: 필요한 타입을 디코딩하기에 데이터가 충분하지 않음
- **잘못된 형식**: 데이터가 예상한 구조와 일치하지 않음
- **검증 실패**: 값은 성공적으로 디코딩되었지만 검증 실패 (`emap`에서)

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector
```

```scala mdoc
// Long에 대한 바이트 부족 (8바이트 필요)
ByteDecoder[Long].decode(ByteVector(0x01, 0x02))

// 빈 바이트
ByteDecoder[Long].decode(ByteVector.empty)
```

## 모범 사례

### 1. Encoder에는 contramap 사용
커스텀 타입을 표준 타입으로 변환:
```scala
case class Timestamp(millis: Long)
given ByteEncoder[Timestamp] = ByteEncoder[Long].contramap(_.millis)
```

### 2. 검증에는 emap 사용
디코딩 중 검증 로직 추가:
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

case class PositiveLong(value: Long)

given ByteDecoder[PositiveLong] = ByteDecoder[Long].emap: n =>
  if n > 0 then PositiveLong(n).asRight
  else DecodeFailure(s"Value must be positive, got $n").asLeft
```

### 3. 자동 Derivation 활용
컴파일러가 case class에 대한 인스턴스를 derive하도록:
```scala
case class Account(id: Long, balance: BigInt)
// ByteEncoder[Account]와 ByteDecoder[Account]가 자동으로 사용 가능
```

### 4. flatMap으로 Decoder 체이닝
복잡한 디코딩 로직:
```scala
ByteDecoder[Long].flatMap: discriminator =>
  discriminator match
    case 1 => ByteDecoder[TypeA]
    case 2 => ByteDecoder[TypeB]
    case _ => ByteDecoder.fail(s"Unknown type: $discriminator")
```

## 성능 참고사항

- **인코딩**: O(n), n은 데이터 구조 크기
- **디코딩**: O(n), 실패 시 조기 종료
- **Product derivation**: 컴파일 타임, 런타임 오버헤드 없음
- **컬렉션 정렬**: O(k log k), k는 Set/Map의 컬렉션 크기

## 참고

- [타입 규칙](types.md): 각 타입에 대한 자세한 인코딩 명세
- [예제](examples.md): 실전 사용 패턴
- [RLP 비교](rlp-comparison.md): Ethereum RLP와의 차이점

---

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)
