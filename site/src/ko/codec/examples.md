# 실전 예제

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)

---

## 개요

이 문서는 블록체인 애플리케이션에서 바이트 코덱을 사용하는 실제 예제를 제공하며, 일반적인 패턴과 사용 사례를 보여줍니다.

## 기본 사용법

### 간단한 데이터 인코딩

```scala mdoc:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

// 기본 타입 인코딩
val longValue = 42L
```

```scala mdoc
val longBytes = ByteEncoder[Long].encode(longValue)
val longDecoded = ByteDecoder[Long].decode(longBytes)
```

### 튜플

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
```

```scala mdoc
val pair = (100L, 200L)
val pairBytes = ByteEncoder[(Long, Long)].encode(pair)
val pairDecoded = ByteDecoder[(Long, Long)].decode(pairBytes)
```

## 블록체인 데이터 구조

### 트랜잭션

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import java.time.Instant

case class Address(id: Long)
case class Transaction(
  from: Address,
  to: Address,
  amount: Long,
  nonce: Long,
  timestamp: Instant
)
```

```scala mdoc
val tx = Transaction(
  from = Address(1L),
  to = Address(2L),
  amount = 1000L,
  nonce = 1L,
  timestamp = Instant.parse("2024-01-01T00:00:00Z")
)

// 인코딩
val txBytes = ByteEncoder[Transaction].encode(tx)

// 디코딩
val txDecoded = ByteDecoder[Transaction].decode(txBytes)
```

인코딩된 바이트는 (별도의 crypto 모듈에서) 해싱 함수로 전달되어 트랜잭션 ID를 계산할 수 있습니다.

### 블록 헤더

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import java.time.Instant

case class BlockHeader(
  height: Long,
  timestamp: Instant,
  previousHashBytes: Long,  // 이 예제에서는 단순화
  txCount: Long
)
```

```scala mdoc
val header = BlockHeader(
  height = 100L,
  timestamp = Instant.parse("2024-01-01T00:00:00Z"),
  previousHashBytes = 0x123abcL,
  txCount = 42L
)

val headerBytes = ByteEncoder[BlockHeader].encode(header)
val headerDecoded = ByteDecoder[BlockHeader].decode(headerBytes)
```

### 계정 상태

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class Account(
  address: Long,
  balance: BigInt,
  nonce: Long
)
```

```scala mdoc
val account = Account(
  address = 100L,
  balance = BigInt("1000000000000000000"),  // 1 ETH 상당
  nonce = 5L
)

val accountBytes = ByteEncoder[Account].encode(account)
val accountDecoded = ByteDecoder[Account].decode(accountBytes)
```

`BigInt`가 큰 잔액에 사용되어 효율적인 가변 길이 인코딩을 제공하는 것에 주목하세요.

## 커스텀 타입

### Opaque 타입

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

opaque type UserId = Long

object UserId:
  def apply(value: Long): UserId = value

  given ByteEncoder[UserId] = ByteEncoder[Long].contramap(identity)
  given ByteDecoder[UserId] = ByteDecoder[Long].map(identity)
```

```scala mdoc
val userId: UserId = UserId(12345L)
val userIdBytes = ByteEncoder[UserId].encode(userId)
val userIdDecoded = ByteDecoder[UserId].decode(userIdBytes)
```

### 검증이 있는 래퍼 타입

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

case class PositiveBalance(value: BigInt)

object PositiveBalance:
  given ByteEncoder[PositiveBalance] =
    ByteEncoder[BigInt].contramap(_.value)

  given ByteDecoder[PositiveBalance] =
    ByteDecoder[BigInt].emap: n =>
      if n >= 0 then PositiveBalance(n).asRight
      else DecodeFailure(s"Balance must be non-negative, got $n").asLeft
```

```scala mdoc
val validBalance = PositiveBalance(BigInt(100))
val validBytes = ByteEncoder[PositiveBalance].encode(validBalance)
ByteDecoder[PositiveBalance].decode(validBytes)

// 음수 잔액을 BigInt로 인코딩 (데모용)
val negativeBytes = ByteEncoder[BigInt].encode(BigInt(-50))
ByteDecoder[PositiveBalance].decode(negativeBytes).isLeft
```

### Sealed Trait (Sum 타입)

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*
import scodec.bits.ByteVector

sealed trait TxType
case object Transfer extends TxType
case object Deploy extends TxType
case object Call extends TxType

object TxType:
  given ByteEncoder[TxType] = ByteEncoder[Byte].contramap:
    case Transfer => 0
    case Deploy => 1
    case Call => 2

  given ByteDecoder[TxType] = ByteDecoder[Byte].emap:
    case 0 => Transfer.asRight
    case 1 => Deploy.asRight
    case 2 => Call.asRight
    case n => DecodeFailure(s"Unknown transaction type: $n").asLeft
```

```scala mdoc
val txType: TxType = Transfer
val txTypeBytes = ByteEncoder[TxType].encode(txType)
val txTypeDecoded = ByteDecoder[TxType].decode(txTypeBytes)
```

## 에러 처리

### 디코딩 실패 처리

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

case class Transaction(from: Long, to: Long, amount: Long)

def processTransaction(bytes: ByteVector): Either[String, String] =
  ByteDecoder[Transaction].decode(bytes) match
    case Right(result) =>
      val tx = result.value
      Right(s"Processed: ${tx.from} -> ${tx.to}, amount ${tx.amount}")

    case Left(failure) =>
      Left(s"Failed to decode transaction: ${failure.msg}")
```

```scala mdoc
// 유효한 트랜잭션
val validTx = Transaction(1L, 2L, 100L)
val validBytes = ByteEncoder[Transaction].encode(validTx)
processTransaction(validBytes)

// 잘못된 바이트 (데이터 부족)
val invalidBytes = ByteVector(0x01, 0x02)
processTransaction(invalidBytes)
```

### 나머지를 사용한 부분 디코딩

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

// 단일 바이트 시퀀스에서 여러 트랜잭션 디코딩
def decodeMultiple(bytes: ByteVector): List[Transaction] =
  def go(bs: ByteVector, acc: List[Transaction]): List[Transaction] =
    if bs.isEmpty then acc.reverse
    else
      ByteDecoder[Transaction].decode(bs) match
        case Right(result) => go(result.remainder, result.value :: acc)
        case Left(_) => acc.reverse  // 에러 발생 시 중단

  go(bytes, Nil)

case class Transaction(from: Long, to: Long, amount: Long)
```

```scala mdoc
val tx1 = Transaction(1L, 2L, 100L)
val tx2 = Transaction(3L, 4L, 200L)
val concatenated = ByteEncoder[Transaction].encode(tx1) ++ ByteEncoder[Transaction].encode(tx2)

decodeMultiple(concatenated)
```

## 라운드트립 테스트

### Property-Based 테스트

hedgehog-munit을 사용한 라운드트립 테스트 예제:

```scala
import org.sigilaris.core.codec.byte.*
import hedgehog.*
import hedgehog.munit.HedgehogSuite

class TransactionCodecSuite extends HedgehogSuite:

  property("Transaction roundtrip"):
    for
      from <- Gen.long(Range.linear(0L, 1000L))
      to <- Gen.long(Range.linear(0L, 1000L))
      amount <- Gen.long(Range.linear(0L, 1000000L))
    yield
      val tx = Transaction(from, to, amount)
      val encoded = ByteEncoder[Transaction].encode(tx)
      val decoded = ByteDecoder[Transaction].decode(encoded)

      decoded ==== Right(DecodeResult(tx, ByteVector.empty))
```

### 간단한 단위 테스트

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

case class User(id: Long, balance: BigInt)

def testRoundtrip[A](value: A)(using enc: ByteEncoder[A], dec: ByteDecoder[A]): Boolean =
  val encoded = enc.encode(value)
  val decoded = dec.decode(encoded)
  decoded match
    case Right(result) => result.value == value && result.remainder.isEmpty
    case Left(_) => false
```

```scala mdoc
val user = User(1L, BigInt(1000))
testRoundtrip(user)

// Option 예제
val maybeUser = Option(User(1L, BigInt(100)))
testRoundtrip[Option[User]](maybeUser)

// 명시적 타입이 있는 Set 예제
val userSet = Set(User(1L, BigInt(100)), User(2L, BigInt(200)))
testRoundtrip[Set[User]](userSet)
```

## 고급 패턴

### 버전이 있는 인코딩

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

case class VersionedTransaction(version: Byte, data: Transaction)
case class Transaction(from: Long, to: Long, amount: Long)

object VersionedTransaction:
  given ByteEncoder[VersionedTransaction] = new ByteEncoder[VersionedTransaction]:
    def encode(vt: VersionedTransaction) =
      ByteEncoder[Byte].encode(vt.version) ++ ByteEncoder[Transaction].encode(vt.data)

  given ByteDecoder[VersionedTransaction] =
    ByteDecoder[Byte].flatMap: version =>
      if version == 1 then
        ByteDecoder[Transaction].map(data => VersionedTransaction(version, data))
      else
        new ByteDecoder[VersionedTransaction]:
          def decode(bytes: scodec.bits.ByteVector) =
            Left(DecodeFailure(s"Unsupported version: $version"))
```

```scala mdoc
val vTx = VersionedTransaction(1, Transaction(1L, 2L, 100L))
val vTxBytes = ByteEncoder[VersionedTransaction].encode(vTx)
val vTxDecoded = ByteDecoder[VersionedTransaction].decode(vTxBytes)
```

### 서로 다른 타입의 컬렉션

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class Block(
  height: Long,
  txHashes: Set[Long],
  metadata: Map[Long, Long]  // key: metadata 타입 ID, value: metadata 값
)
```

```scala mdoc
val block = Block(
  height = 100L,
  txHashes = Set(0xabcL, 0xdefL, 0x123L),
  metadata = Map(1L -> 1672531200L, 2L -> 21000L)  // 1: timestamp, 2: gasUsed
)

val blockBytes = ByteEncoder[Block].encode(block)
val blockDecoded = ByteDecoder[Block].decode(blockBytes)
```

**참고:** `Set`과 `Map`은 인코딩 중에 자동으로 결정론적으로 정렬됩니다.

### 중첩된 구조

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class Address(id: Long)
case class Transaction(from: Address, to: Address, amount: Long)
case class Block(height: Long, txCount: Long, lastTxAmount: Long)
```

```scala mdoc
// 단순화된 예제 - 실제로는 Set이나 커스텀 컬렉션 사용
val block = Block(
  height = 100L,
  txCount = 2L,
  lastTxAmount = 200L
)

val blockBytes = ByteEncoder[Block].encode(block)
val blockDecoded = ByteDecoder[Block].decode(blockBytes)
```

**참고**: `List[A]` encoder는 존재하지만 decoder는 아직 구현되지 않았습니다. 컬렉션에는 `Set[A]`, `Option[A]`, `Map[K, V]`를 사용하세요.

## 통합 예제

### 완전한 트랜잭션 파이프라인

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector
import java.time.Instant

case class Transaction(
  from: Long,
  to: Long,
  amount: Long,
  nonce: Long,
  timestamp: Instant
)

// 단계 1: 트랜잭션 생성
val tx = Transaction(
  from = 100L,
  to = 200L,
  amount = 5000L,
  nonce = 1L,
  timestamp = Instant.now()
)

// 단계 2: 바이트로 인코딩
val txBytes = ByteEncoder[Transaction].encode(tx)
```

```scala mdoc
txBytes

// 단계 3: 이 바이트는 다음과 같이 처리됨:
// - 트랜잭션 ID 생성을 위한 해싱 (별도 crypto 모듈 사용)
// - 개인키를 사용한 서명 (별도 crypto 모듈 사용)
// - 네트워크로 브로드캐스트 (별도 네트워크 모듈 사용)

// 단계 4: 수신 노드가 디코딩
val received = ByteDecoder[Transaction].decode(txBytes)
received.map(_.value)
```

## 모범 사례 요약

1. **Case Class 사용**: product 타입에 대한 자동 derivation 활용
2. **Decoder에서 검증**: `emap`을 사용하여 검증 로직 추가
3. **라운드트립 테스트**: `decode(encode(x)) == x` 확인
4. **에러 처리**: 디코딩 결과에 대해 `Either` 패턴 매칭 사용
5. **관심사 분리**: 코덱을 해싱/서명/네트워킹과 분리 유지
6. **결정론적 컬렉션**: `Set`과 `Map` 자동 정렬 신뢰
7. **큰 숫자에는 BigInt 사용**: 가변 길이 인코딩의 이점 활용

---

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)
