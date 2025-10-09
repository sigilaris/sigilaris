# API Reference

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)

---

## Overview

This document provides detailed API reference for the three core traits: `ByteEncoder`, `ByteDecoder`, and `ByteCodec`. For type-specific encoding rules, see [Type Rules](types.md).

## ByteEncoder

`ByteEncoder[A]` is a contravariant type class for encoding values of type `A` into deterministic byte sequences.

### Core Methods

#### encode
```scala
def encode(value: A): ByteVector
```

Encodes a value to a deterministic byte sequence.

**Example:**
```scala mdoc:silent
import org.sigilaris.core.codec.byte.*

val encoder = ByteEncoder[Long]
```

```scala mdoc
encoder.encode(42L)
```

### Combinators

#### contramap
```scala
def contramap[B](f: B => A): ByteEncoder[B]
```

Creates a new encoder by applying a function before encoding. This is the contravariant functor operation.

**Example:**
```scala mdoc:silent
case class UserId(value: Long)

given ByteEncoder[UserId] = ByteEncoder[Long].contramap(_.value)
```

```scala mdoc
ByteEncoder[UserId].encode(UserId(100L))
```

**Use Case:** Transform custom types to encodable types.

## ByteDecoder

`ByteDecoder[A]` is a covariant type class for decoding byte sequences into values of type `A`.

### Core Methods

#### decode
```scala
def decode(bytes: ByteVector): Either[DecodeFailure, DecodeResult[A]]
```

Decodes bytes to a value, returning either a failure or a result with remainder.

**DecodeResult:**
```scala
case class DecodeResult[A](value: A, remainder: ByteVector)
```

**Example:**
```scala mdoc:silent
import scodec.bits.ByteVector

val decoder = ByteDecoder[Long]
val bytes = ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a)
```

```scala mdoc
decoder.decode(bytes)
```

### Combinators

#### map
```scala
def map[B](f: A => B): ByteDecoder[B]
```

Transforms the decoded value using a function. This is the covariant functor operation.

**Example:**
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

Transforms the decoded value with validation. Allows decoding to fail based on business rules.

**Example:**
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

Chains decoding operations. The next decoder depends on the previously decoded value.

**Example:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

// Decode length-prefixed data where length determines decoder behavior
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

**Use Case:** Context-dependent decoding where the structure depends on previously decoded values.

## ByteCodec

`ByteCodec[A]` combines both `ByteEncoder[A]` and `ByteDecoder[A]` into a single type class.

```scala
trait ByteCodec[A] extends ByteDecoder[A] with ByteEncoder[A]
```

### Usage

When a type has both encoder and decoder instances, you can summon them together:

```scala mdoc:silent
val codec = ByteCodec[Long]

val encoded = codec.encode(42L)
val decoded = codec.decode(encoded)
```

```scala mdoc
encoded
decoded
```

### Automatic Derivation

`ByteCodec` provides automatic derivation for product types (case classes, tuples):

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class Transaction(from: Long, to: Long, amount: Long)

// Instances automatically derived
val tx = Transaction(1L, 2L, 100L)
```

```scala mdoc
val encoded = ByteEncoder[Transaction].encode(tx)
val decoded = ByteDecoder[Transaction].decode(encoded)
```

## Given Instances

The companion objects provide given instances for common types. See [Type Rules](types.md) for detailed encoding specifications.

### Primitive Types
- `Unit`: Empty byte sequence
- `Byte`: Single byte
- `Long`: 8-byte big-endian
- `Instant`: Epoch milliseconds as Long

### Numeric Types
- `BigInt`: Sign-aware variable-length encoding
- `BigNat`: Natural numbers with variable-length encoding

### Collections
- `List[A]`: Size prefix + ordered elements
- `Option[A]`: Encoded as zero or one-element list
- `Set[A]`: Lexicographically sorted after encoding
- `Map[K, V]`: Treated as `Set[(K, V)]`

### Product Types
- `Tuple2[A, B]`, `Tuple3[A, B, C]`, etc.: Automatic derivation
- Case classes: Automatic derivation via `Mirror.ProductOf`

## Extracting Values from DecodeResult

`DecodeResult[A]` contains both the decoded value and the remainder bytes. To extract just the value:

**Example:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector
```

```scala mdoc
val result = ByteDecoder[Long].decode(ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a))
result.map(_.value)  // Extract just the value
```

## Error Handling

### DecodeFailure

Decoding failures are represented by `DecodeFailure`:

```scala
case class DecodeFailure(message: String)
```

Common failure scenarios:
- **Insufficient bytes**: Not enough data to decode required type
- **Invalid format**: Data doesn't match expected structure
- **Validation failure**: Value decoded successfully but failed validation (from `emap`)

**Example:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector
```

```scala mdoc
// Insufficient bytes for Long (needs 8 bytes)
ByteDecoder[Long].decode(ByteVector(0x01, 0x02))

// Empty bytes
ByteDecoder[Long].decode(ByteVector.empty)
```

## Best Practices

### 1. Use contramap for Encoders
Transform your custom types to standard types:
```scala
case class Timestamp(millis: Long)
given ByteEncoder[Timestamp] = ByteEncoder[Long].contramap(_.millis)
```

### 2. Use emap for Validation
Add validation logic during decoding:
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

case class PositiveLong(value: Long)

given ByteDecoder[PositiveLong] = ByteDecoder[Long].emap: n =>
  if n > 0 then PositiveLong(n).asRight
  else DecodeFailure(s"Value must be positive, got $n").asLeft
```

### 3. Leverage Automatic Derivation
Let the compiler derive instances for case classes:
```scala
case class Account(id: Long, balance: BigInt)
// ByteEncoder[Account] and ByteDecoder[Account] automatically available
```

### 4. Chain Decoders with flatMap
For complex decoding logic:
```scala
ByteDecoder[Long].flatMap: discriminator =>
  discriminator match
    case 1 => ByteDecoder[TypeA]
    case 2 => ByteDecoder[TypeB]
    case _ => ByteDecoder.fail(s"Unknown type: $discriminator")
```

## Performance Notes

- **Encoding**: O(n) where n is the size of data structure
- **Decoding**: O(n) with early exit on failures
- **Product derivation**: Compile-time, no runtime overhead
- **Collection sorting**: O(k log k) for Sets/Maps where k is collection size

## See Also

- [Type Rules](types.md): Detailed encoding specifications for each type
- [Examples](examples.md): Real-world usage patterns
- [RLP Comparison](rlp-comparison.md): Differences from Ethereum RLP

---

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)
