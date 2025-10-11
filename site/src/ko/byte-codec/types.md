# 타입 인코딩 규칙

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)

---

## 개요

이 문서는 Sigilaris 바이트 코덱에서 지원하는 각 타입에 대한 정확한 인코딩 및 디코딩 규칙을 명시합니다. 이 규칙들은 코덱 구현을 위한 기술 명세입니다.

**핵심 원칙:**
- **Deterministic**: 동일한 입력은 항상 동일한 출력 생성
- **공간 효율적**: 작은 값은 더 적은 바이트 사용
- **가역적**: 디코딩은 인코딩을 정확히 반대로 수행 (roundtrip 속성)
- **타입 안전**: 가능한 경우 컴파일 타임에 에러 감지

## 기본 타입

### Unit

**인코딩 규칙:**
```
Unit → 빈 바이트 시퀀스 (ByteVector.empty)
```

**디코딩 규칙:**
```
바이트를 소비하지 않고, Unit과 원본 바이트 시퀀스를 remainder로 반환
```

**예제:**
```scala mdoc:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

val unitEncoded = ByteEncoder[Unit].encode(())
// 결과: ByteVector(empty)

val unitDecoded = ByteDecoder[Unit].decode(ByteVector(0x01, 0x02))
// 결과: Right(DecodeResult((), ByteVector(0x01, 0x02)))
```

**사용 사례:**
Unit은 필드의 존재 자체가 의미가 있지만 데이터를 포함하지 않는 product 타입에서 마커나 플래그로 유용합니다.

### Byte

**인코딩 규칙:**
```
Byte → 단일 바이트
```

**디코딩 규칙:**
```
1 바이트를 읽고, remainder와 함께 Byte로 반환
```

**예제:**
```scala mdoc:silent
val b: Byte = 0x42
val byteEncoded = ByteEncoder[Byte].encode(b)
// 결과: ByteVector(0x42)

val byteDecoded = ByteDecoder[Byte].decode(byteEncoded)
// 결과: Right(DecodeResult(0x42, ByteVector(empty)))
```

### Long

**인코딩 규칙:**
```
Long → 8-바이트 big-endian 표현
```

**디코딩 규칙:**
```
8 바이트를 읽고, big-endian Long으로 해석
```

**예제:**
```scala mdoc:silent
val n: Long = 42L
val longEncoded = ByteEncoder[Long].encode(n)
// 결과: ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a)

val longDecoded = ByteDecoder[Long].decode(longEncoded)
// 결과: Right(DecodeResult(42L, ByteVector(empty)))
```

**참고:** Long은 단순성과 일관성을 위해 고정 8-바이트 인코딩을 사용합니다. 공간 효율적인 정수 인코딩이 필요하면 대신 BigInt를 사용하세요.

### Instant

**인코딩 규칙:**
```
Instant → epoch milliseconds를 Long으로 (8 바이트)
```

**디코딩 규칙:**
```
8 바이트를 Long으로 읽고, Instant.ofEpochMilli를 통해 Instant로 변환
```

**예제:**
```scala mdoc:silent
import java.time.Instant

val timestamp = Instant.parse("2024-01-01T00:00:00Z")
val instantEncoded = ByteEncoder[Instant].encode(timestamp)
// 결과: epoch milliseconds가 Long으로 인코딩됨

val instantDecoded = ByteDecoder[Instant].decode(instantEncoded)
// 결과: Right(DecodeResult(timestamp, ByteVector(empty)))
```

## 숫자 타입

### BigNat (자연수)

BigNat은 가변 길이 인코딩을 사용하는 음이 아닌 정수 (0, 1, 2, ...)를 나타냅니다.

**타입 정의:**
```scala
type BigNat = BigInt :| Positive0  // 음이 아닌 BigInt
```

**인코딩 규칙:**

인코딩은 공간 효율성을 위해 세 가지 범위를 사용합니다:

1. **단일 바이트 범위 (0x00 ~ 0x80):** 값 0-128
   ```
   value n (0 ≤ n ≤ 128) → 단일 바이트 0xnn
   ```

2. **짧은 데이터 범위 (0x81 ~ 0xf7):** 데이터 길이 1-119 바이트
   ```
   [0x80 + data_length][data_bytes]
   data_length: 1부터 119까지 (0xf7 - 0x80)
   ```

3. **긴 데이터 범위 (0xf8 ~ 0xff):** 데이터 길이 120+ 바이트
   ```
   [0xf8 + (length_byte_count - 1)][length_bytes][data_bytes]
   length_byte_count: 1부터 8까지
   ```

**인코딩 예제:**

| 값 | 인코딩된 바이트 | 설명 |
|-------|---------------|-------------|
| 0 | `0x00` | 단일 바이트 |
| 1 | `0x01` | 단일 바이트 |
| 128 | `0x80` | 단일 바이트 (포함) |
| 129 | `0x81 81` | 길이 1, 데이터 0x81 |
| 255 | `0x81 ff` | 길이 1, 데이터 0xff |
| 256 | `0x82 01 00` | 길이 2, 데이터 0x0100 |
| 65535 | `0x82 ff ff` | 길이 2, 데이터 0xffff |
| 65536 | `0x83 01 00 00` | 길이 3, 데이터 0x010000 |

**디코딩 알고리즘:**

```scala
def decodeBigNat(bytes: ByteVector): (BigNat, ByteVector) =
  val head = bytes.head & 0xff

  if head <= 0x80 then
    // 단일 바이트: 값은 0-128
    (BigInt(head), bytes.tail)

  else if head <= 0xf7 then
    // 짧은 데이터: 1-119 바이트 데이터
    val dataLength = head - 0x80
    val (dataBytes, remainder) = bytes.tail.splitAt(dataLength)
    (BigInt(1, dataBytes.toArray), remainder)

  else
    // 긴 데이터: 120+ 바이트 데이터
    val lengthByteCount = head - 0xf7
    val (lengthBytes, afterLength) = bytes.tail.splitAt(lengthByteCount)
    val dataLength = BigInt(1, lengthBytes.toArray).toLong
    val (dataBytes, remainder) = afterLength.splitAt(dataLength)
    (BigInt(1, dataBytes.toArray), remainder)
```

**Roundtrip 속성:**
```scala mdoc:silent
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

def testRoundtrip(n: BigInt :| Positive0): Boolean =
  val bignatEncoded = ByteEncoder[BigInt :| Positive0].encode(n)
  val bignatDecoded = ByteDecoder[BigInt :| Positive0].decode(bignatEncoded)
  bignatDecoded match
    case Right(DecodeResult(value, remainder)) =>
      value == n && remainder.isEmpty
    case Left(_) => false

// 이 모든 경우가 true를 반환해야 함:
// testRoundtrip(BigInt(0).refineUnsafe)
// testRoundtrip(BigInt(128).refineUnsafe)
// testRoundtrip(BigInt(255).refineUnsafe)
// testRoundtrip(BigInt(65536).refineUnsafe)
```

### BigInt (부호 있는 정수)

BigInt는 부호 정보와 함께 BigNat 인코딩을 확장합니다.

**인코딩 규칙:**

sign-magnitude 인코딩을 사용하여 부호 있는 BigInt를 BigNat로 변환:

```scala
n >= 0  →  encode (n * 2) as BigNat
n < 0   →  encode (n * (-2) + 1) as BigNat
```

**핵심 인사이트:**
- 짝수는 양수 값을 나타냄: `2n → n`
- 홀수는 음수 값을 나타냄: `2n+1 → -(n+1)`

**인코딩 예제:**

| 값 | 변환 | BigNat | 인코딩됨 |
|-------|----------------|--------|----------|
| -2 | (-2) * (-2) + 1 = 5 | 5 | `0x05` |
| -1 | (-1) * (-2) + 1 = 3 | 3 | `0x03` |
| 0 | 0 * 2 = 0 | 0 | `0x00` |
| 1 | 1 * 2 = 2 | 2 | `0x02` |
| 2 | 2 * 2 = 4 | 4 | `0x04` |
| 127 | 127 * 2 = 254 | 254 | `0x81 fe` |
| -128 | -128 * (-2) + 1 = 257 | 257 | `0x82 01 01` |

**디코딩 규칙:**

디코딩된 BigNat `x`가 주어졌을 때:
```scala
x % 2 == 0  →  x / 2        // 짝수: 양수
x % 2 == 1  →  (x - 1) / (-2)  // 홀수: 음수
```

**Roundtrip 검증:**

| 값 | 인코딩 | 디코딩 확인 |
|-------|--------|---------------|
| -2 | 5 | (5-1)/(-2) = -2 ✓ |
| -1 | 3 | (3-1)/(-2) = -1 ✓ |
| 0 | 0 | 0/2 = 0 ✓ |
| 1 | 2 | 2/2 = 1 ✓ |
| 2 | 4 | 4/2 = 2 ✓ |

**공간 효율성:**

작은 정수 (양수와 음수)가 효율적으로 인코딩됨:
- 값 -64부터 64까지: 단일 바이트
- 값 -16384부터 16383까지: 2 바이트

## Product 타입

### 튜플

튜플은 순서대로 연결된 필드로 인코딩됩니다:

```
(A, B) → [A 인코딩됨][B 인코딩됨]
(A, B, C) → [A 인코딩됨][B 인코딩됨][C 인코딩됨]
```

**인코딩:**
```scala mdoc:silent
val tuple = (42L, 100L)
val tupleEncoded = ByteEncoder[(Long, Long)].encode(tuple)
// 결과: [42L 인코딩됨][100L 인코딩됨]
```

**디코딩:**
필드는 순차적으로 디코딩되어 왼쪽에서 오른쪽으로 바이트를 소비합니다.

### Case Class

Case class는 `Mirror.ProductOf`를 통한 자동 derivation을 사용합니다:

```
case class User(id: Long, balance: Long)
→ [id 인코딩됨][balance 인코딩됨]
```

**인코딩:**
```scala mdoc:silent
case class User(id: Long, balance: Long)

val user = User(1L, 100L)
val userEncoded = ByteEncoder[User].encode(user)
// 결과: [id 인코딩됨][balance 인코딩됨]
```

**필드 순서:**
필드는 case class 정의에 나타나는 순서대로 인코딩됩니다.

## 컬렉션 타입

### List

리스트는 순서를 보존하며 크기 접두사와 함께 인코딩됩니다:

```
List(a1, a2, ..., an) → [size:BigNat][a1][a2]...[an]
```

**인코딩:**
```scala mdoc:silent
val list = List(1, 2, 3).map(BigInt(_))
val listEncoded = ByteEncoder[List[BigInt]].encode(list)
// 결과: [0x03][0x02][0x04][0x06]
//         size=3, then 1→2, 2→4, 3→6
```

**디코딩:**
1. 크기를 BigNat로 디코딩
2. 정확히 `size`개의 요소 디코딩
3. 리스트와 remainder 반환

**빈 리스트:**
```
List() → [0x00]  // size = 0
```

### Option

Option은 0개 또는 1개 요소의 리스트로 인코딩됩니다:

```
None → [0x00]  // size = 0
Some(x) → [0x01][x 인코딩됨]  // size = 1, 요소 x
```

**인코딩:**
```scala mdoc:silent
val some: Option[Long] = Some(42L)
val someEncoded = ByteEncoder[Option[Long]].encode(some)
// 결과: [0x01][42L이 8 바이트로 인코딩됨]

val none: Option[Long] = None
val noneEncoded = ByteEncoder[Option[Long]].encode(none)
// 결과: [0x00]
```

**왜 이것이 작동하는가:**
컨텍스트 (타입)가 `Option[Long]`과 `Long`을 구분합니다. 바이트 `0x00`의 의미:
- `BigNat` 컨텍스트에서: 자연수 0
- `Option[A]` 컨텍스트에서: None (0개 요소)

### Set

Set은 deterministic 순서로 인코딩됩니다:

```
Set(a1, a2, ..., an) → [size:BigNat][sorted_a1][sorted_a2]...[sorted_an]
```

**Deterministic 정렬:**
1. 각 요소를 바이트로 인코딩
2. 인코딩된 바이트를 lexicographic으로 정렬
3. 크기 접두사와 함께 연결

**인코딩:**
```scala mdoc:silent
val set = Set(3, 1, 2).map(BigInt(_))
val setEncoded = ByteEncoder[Set[BigInt]].encode(set)
// 요소는 다음과 같이 인코딩됨: 3→0x06, 1→0x02, 2→0x04
// 정렬됨: 0x02, 0x04, 0x06
// 결과: [0x03][0x02][0x04][0x06]
```

**정렬하는 이유:**
Scala에서 Set 반복 순서는 정의되지 않습니다. 인코딩된 바이트를 정렬하면 동일한 Set이 항상 동일한 바이트 시퀀스를 생성하며, 이는 블록체인 해싱과 서명에 중요합니다.

**Lexicographic 순서:**
바이트는 왼쪽에서 오른쪽으로 비교됩니다:
- `0x01` < `0x02` < `0x03` < ... < `0xff`
- `0x01 0x00` < `0x01 0x01`
- `0x01 0xff` < `0x02 0x00`

### Map

Map은 deterministic으로 정렬된 튜플 집합으로 인코딩됩니다:

```
Map(k1 → v1, k2 → v2) → Set((k1, v1), (k2, v2))
→ [size:BigNat][sorted_tuple1][sorted_tuple2]...
```

**인코딩:**
```scala mdoc:silent
val map = Map(1L -> 10L, 2L -> 20L)
val mapEncoded = ByteEncoder[Map[Long, Long]].encode(map)
// 각 엔트리 (1L, 10L)은 튜플로 인코딩됨
// 튜플은 인코딩된 바이트로 정렬됨
// 결과: [size][정렬된 엔트리들]
```

**튜플 인코딩:**
각 `(K, V)` 쌍은 product로 인코딩됨: `[K 인코딩됨][V 인코딩됨]`

**Deterministic 순서:**
Set과 마찬가지로, Map 엔트리는 인코딩된 튜플 바이트로 정렬되어 Map 반복 순서와 관계없이 일관된 인코딩을 보장합니다.

## 커스텀 타입

### contramap 사용 (Encoder)

기존 타입으로 변환하여 커스텀 타입에 대한 encoder 생성:

```scala mdoc:silent
case class UserId(value: Long)

given ByteEncoder[UserId] = ByteEncoder[Long].contramap(_.value)
```

### map/emap 사용 (Decoder)

디코딩된 값을 변환하여 decoder 생성:

```scala mdoc:silent
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

// 간단한 변환
given ByteDecoder[UserId] = ByteDecoder[Long].map(UserId(_))

// 검증과 함께
case class PositiveInt(value: Int)

given ByteDecoder[PositiveInt] = ByteDecoder[Long].emap: n =>
  if n > 0 && n <= Int.MaxValue then
    PositiveInt(n.toInt).asRight
  else
    DecodeFailure(s"Value $n is not a positive Int").asLeft
```

## 에러 케이스

### 디코딩 실패

일반적인 에러 시나리오:

**바이트 부족:**
```scala mdoc:silent
val incomplete = ByteVector(0x01)  // 길이 1이라고 주장하지만 데이터 없음
val result = ByteDecoder[BigInt :| Positive0].decode(incomplete)
// 결과: Left(DecodeFailure("Insufficient bytes..."))
```

**BigNat에 대한 빈 바이트:**
```scala mdoc:silent
val empty = ByteVector.empty
val result2 = ByteDecoder[BigInt :| Positive0].decode(empty)
// 결과: Left(DecodeFailure("Empty bytes"))
```

**검증 실패:**
```scala mdoc:silent
// emap에서 커스텀 검증
val negative = ByteVector(0x03)  // BigInt로 -1 인코딩
// PositiveInt로 디코딩하면 검증 실패
```

## 성능 특성

### 공간 복잡도

| 타입 | 공간 | 참고 |
|------|-------|-------|
| Unit | 0 바이트 | 데이터 없음 |
| Byte | 1 바이트 | 고정 |
| Long | 8 바이트 | 고정 |
| Instant | 8 바이트 | 고정 |
| BigInt 0-64 | 1 바이트 | 단일 바이트 범위 |
| BigInt 65-128 | 1 바이트 | 단일 바이트 범위 |
| BigInt 129-32767 | 3 바이트 | 짧은 데이터 범위 |
| `List[A]` n개 요소 | 1+ + n*sizeof(A) | 크기 접두사 + 요소들 |
| `Set[A]` n개 요소 | 1+ + n*sizeof(A) | 크기 접두사 + 정렬됨 |
| `Map[K,V]` n개 엔트리 | 1+ + n*(sizeof(K)+sizeof(V)) | 크기 + 정렬된 튜플 |

### 시간 복잡도

| 작업 | 복잡도 | 참고 |
|-----------|------------|-------|
| 기본 타입 인코딩 | O(1) | 상수 시간 |
| BigNat 인코딩 | O(log n) | 값 크기에 비례 |
| `List[A]` 인코딩 | O(n) | 리스트 크기에 선형 |
| `Set[A]` 인코딩 | O(n log n) | 정렬 때문 |
| `Map[K,V]` 인코딩 | O(n log n) | 정렬 때문 |
| 기본 타입 디코딩 | O(1) | 상수 시간 |
| BigNat 디코딩 | O(log n) | 가변 바이트 읽기 |
| `List[A]` 디코딩 | O(n) | 리스트 크기에 선형 |
| `Set[A]` 디코딩 | O(n) | 정렬 불필요 |
| `Map[K,V]` 디코딩 | O(n) | 정렬 불필요 |

## Roundtrip 속성

모든 지원되는 타입에 대해 다음이 성립해야 합니다:

```scala
encode(decode(encode(value))) == encode(value)
decode(encode(value)) == Right(DecodeResult(value, ByteVector.empty))
```

이 속성은 hedgehog-munit을 사용한 property-based 테스트를 통해 검증됩니다.

---

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)
