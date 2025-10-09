# Practical Examples

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)

---

## Overview

This document provides real-world examples of using the byte codec in blockchain applications, demonstrating common patterns and use cases.

## Basic Usage

### Simple Data Encoding

```scala mdoc:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

// Encoding primitives
val longValue = 42L
```

```scala mdoc
val longBytes = ByteEncoder[Long].encode(longValue)
val longDecoded = ByteDecoder[Long].decode(longBytes)
```

### Tuples

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
```

```scala mdoc
val pair = (100L, 200L)
val pairBytes = ByteEncoder[(Long, Long)].encode(pair)
val pairDecoded = ByteDecoder[(Long, Long)].decode(pairBytes)
```

## Blockchain Data Structures

### Transaction

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

// Encoding
val txBytes = ByteEncoder[Transaction].encode(tx)

// Decoding
val txDecoded = ByteDecoder[Transaction].decode(txBytes)
```

The encoded bytes can be passed to a hashing function (in a separate crypto module) to compute the transaction ID.

### Block Header

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import java.time.Instant

case class BlockHeader(
  height: Long,
  timestamp: Instant,
  previousHashBytes: Long,  // Simplified for this example
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

### Account State

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
  balance = BigInt("1000000000000000000"),  // 1 ETH equivalent
  nonce = 5L
)

val accountBytes = ByteEncoder[Account].encode(account)
val accountDecoded = ByteDecoder[Account].decode(accountBytes)
```

Note how `BigInt` is used for large balances, providing efficient variable-length encoding.

## Custom Types

### Opaque Types

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

### Wrapper Types with Validation

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

// Negative balance encoding as BigInt (for demonstration)
val negativeBytes = ByteEncoder[BigInt].encode(BigInt(-50))
ByteDecoder[PositiveBalance].decode(negativeBytes).isLeft
```

### Sealed Trait (Sum Types)

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

## Error Handling

### Handling Decode Failures

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
// Valid transaction
val validTx = Transaction(1L, 2L, 100L)
val validBytes = ByteEncoder[Transaction].encode(validTx)
processTransaction(validBytes)

// Invalid bytes (insufficient data)
val invalidBytes = ByteVector(0x01, 0x02)
processTransaction(invalidBytes)
```

### Partial Decoding with Remainder

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

// Decode multiple transactions from a single byte sequence
def decodeMultiple(bytes: ByteVector): List[Transaction] =
  def go(bs: ByteVector, acc: List[Transaction]): List[Transaction] =
    if bs.isEmpty then acc.reverse
    else
      ByteDecoder[Transaction].decode(bs) match
        case Right(result) => go(result.remainder, result.value :: acc)
        case Left(_) => acc.reverse  // Stop on error

  go(bytes, Nil)

case class Transaction(from: Long, to: Long, amount: Long)
```

```scala mdoc
val tx1 = Transaction(1L, 2L, 100L)
val tx2 = Transaction(3L, 4L, 200L)
val concatenated = ByteEncoder[Transaction].encode(tx1) ++ ByteEncoder[Transaction].encode(tx2)

decodeMultiple(concatenated)
```

## Roundtrip Testing

### Property-Based Testing

Example using hedgehog-munit for roundtrip testing:

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

### Simple Unit Tests

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

// Option example
val maybeUser = Option(User(1L, BigInt(100)))
testRoundtrip[Option[User]](maybeUser)

// Set example with explicit type
val userSet = Set(User(1L, BigInt(100)), User(2L, BigInt(200)))
testRoundtrip[Set[User]](userSet)
```

## Advanced Patterns

### Versioned Encoding

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

### Collection of Different Types

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class Block(
  height: Long,
  txHashes: Set[Long],
  metadata: Map[Long, Long]  // key: metadata type ID, value: metadata value
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

Note: `Set` and `Map` are automatically sorted deterministically during encoding.

### Nested Structures

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class Address(id: Long)
case class Transaction(from: Address, to: Address, amount: Long)
case class Block(height: Long, txCount: Long, lastTxAmount: Long)
```

```scala mdoc
// Simplified example - in practice, use Set or custom collection
val block = Block(
  height = 100L,
  txCount = 2L,
  lastTxAmount = 200L
)

val blockBytes = ByteEncoder[Block].encode(block)
val blockDecoded = ByteDecoder[Block].decode(blockBytes)
```

**Note**: `List[A]` encoder exists but decoder is not yet implemented. Use `Set[A]`, `Option[A]`, or `Map[K, V]` for collections.

## Integration Example

### Complete Transaction Pipeline

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

// Step 1: Create transaction
val tx = Transaction(
  from = 100L,
  to = 200L,
  amount = 5000L,
  nonce = 1L,
  timestamp = Instant.now()
)

// Step 2: Encode to bytes
val txBytes = ByteEncoder[Transaction].encode(tx)
```

```scala mdoc
txBytes

// Step 3: These bytes would then be:
// - Hashed to create transaction ID (using separate crypto module)
// - Signed using private key (using separate crypto module)
// - Broadcast to network (using separate network module)

// Step 4: Receiving node decodes
val received = ByteDecoder[Transaction].decode(txBytes)
received.map(_.value)
```

## Best Practices Summary

1. **Use Case Classes**: Leverage automatic derivation for product types
2. **Validate in Decoders**: Use `emap` to add validation logic
3. **Test Roundtrips**: Ensure `decode(encode(x)) == x`
4. **Handle Errors**: Pattern match on `Either` for decode results
5. **Separate Concerns**: Keep codec separate from hashing/signing/networking
6. **Deterministic Collections**: Trust `Set` and `Map` automatic sorting
7. **Use BigInt for Large Numbers**: Benefit from variable-length encoding

---

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)
