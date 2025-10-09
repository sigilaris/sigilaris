# Type Encoding Rules

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)

---

## Overview

This document specifies the exact encoding and decoding rules for each supported type in the Sigilaris byte codec. These rules serve as the technical specification for the codec implementation.

**Key Principles:**
- **Deterministic**: Same input always produces same output
- **Space-efficient**: Small values use fewer bytes
- **Reversible**: Decoding inverts encoding exactly (roundtrip property)
- **Type-safe**: Errors are caught at compile time when possible

## Primitive Types

### Unit

**Encoding Rule:**
```
Unit → empty byte sequence (ByteVector.empty)
```

**Decoding Rule:**
```
Consumes no bytes, returns Unit and original byte sequence as remainder
```

**Examples:**
```scala mdoc:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

val encoded1 = ByteEncoder[Unit].encode(())
// Result: ByteVector(empty)

val decoded1 = ByteDecoder[Unit].decode(ByteVector(0x01, 0x02))
// Result: Right(DecodeResult((), ByteVector(0x01, 0x02)))
```

**Use Case:**
Unit is useful as a marker or flag in product types where a field's presence is significant but it carries no data.

### Byte

**Encoding Rule:**
```
Byte → single byte
```

**Decoding Rule:**
```
Read 1 byte, return as Byte with remainder
```

**Examples:**
```scala mdoc:silent
val b: Byte = 0x42
val encoded1 = ByteEncoder[Byte].encode(b)
// Result: ByteVector(0x42)

val decoded1 = ByteDecoder[Byte].decode(encoded1)
// Result: Right(DecodeResult(0x42, ByteVector(empty)))
```

### Long

**Encoding Rule:**
```
Long → 8-byte big-endian representation
```

**Decoding Rule:**
```
Read 8 bytes, interpret as big-endian Long
```

**Examples:**
```scala mdoc:silent
val n: Long = 42L
val encoded1 = ByteEncoder[Long].encode(n)
// Result: ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a)

val decoded1 = ByteDecoder[Long].decode(encoded1)
// Result: Right(DecodeResult(42L, ByteVector(empty)))
```

**Note:** Long uses fixed 8-byte encoding for simplicity and consistency. For space-efficient integer encoding, use BigInt instead.

### Instant

**Encoding Rule:**
```
Instant → epoch milliseconds as Long (8 bytes)
```

**Decoding Rule:**
```
Read 8 bytes as Long, convert to Instant via Instant.ofEpochMilli
```

**Examples:**
```scala mdoc:silent
import java.time.Instant

val timestamp = Instant.parse("2024-01-01T00:00:00Z")
val encoded1 = ByteEncoder[Instant].encode(timestamp)
// Result: epoch milliseconds encoded as Long

val decoded1 = ByteDecoder[Instant].decode(encoded1)
// Result: Right(DecodeResult(timestamp, ByteVector(empty)))
```

## Numeric Types

### BigNat (Natural Numbers)

BigNat represents non-negative integers (0, 1, 2, ...) with variable-length encoding.

**Type Definition:**
```scala
type BigNat = BigInt :| Positive0  // non-negative BigInt
```

**Encoding Rules:**

The encoding uses three ranges for space efficiency:

1. **Single-byte range (0x00 ~ 0x80):** Values 0-128
   ```
   value n (0 ≤ n ≤ 128) → single byte 0xnn
   ```

2. **Short data range (0x81 ~ 0xf7):** Data length 1-119 bytes
   ```
   [0x80 + data_length][data_bytes]
   data_length: 1 to 119 (0xf7 - 0x80)
   ```

3. **Long data range (0xf8 ~ 0xff):** Data length 120+ bytes
   ```
   [0xf8 + (length_byte_count - 1)][length_bytes][data_bytes]
   length_byte_count: 1 to 8
   ```

**Encoding Examples:**

| Value | Encoded Bytes | Explanation |
|-------|---------------|-------------|
| 0 | `0x00` | Single byte |
| 1 | `0x01` | Single byte |
| 128 | `0x80` | Single byte (inclusive) |
| 129 | `0x81 81` | Length 1, data 0x81 |
| 255 | `0x81 ff` | Length 1, data 0xff |
| 256 | `0x82 01 00` | Length 2, data 0x0100 |
| 65535 | `0x82 ff ff` | Length 2, data 0xffff |
| 65536 | `0x83 01 00 00` | Length 3, data 0x010000 |

**Decoding Algorithm:**

```scala
def decodeBigNat(bytes: ByteVector): (BigNat, ByteVector) =
  val head = bytes.head & 0xff

  if head <= 0x80 then
    // Single byte: value is 0-128
    (BigInt(head), bytes.tail)

  else if head <= 0xf7 then
    // Short data: 1-119 byte data
    val dataLength = head - 0x80
    val (dataBytes, remainder) = bytes.tail.splitAt(dataLength)
    (BigInt(1, dataBytes.toArray), remainder)

  else
    // Long data: 120+ byte data
    val lengthByteCount = head - 0xf7
    val (lengthBytes, afterLength) = bytes.tail.splitAt(lengthByteCount)
    val dataLength = BigInt(1, lengthBytes.toArray).toLong
    val (dataBytes, remainder) = afterLength.splitAt(dataLength)
    (BigInt(1, dataBytes.toArray), remainder)
```

**Roundtrip Property:**
```scala mdoc:silent
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

def testRoundtrip(n: BigInt :| Positive0): Boolean =
  val encoded1 = ByteEncoder[BigInt :| Positive0].encode(n)
  val decoded1 = ByteDecoder[BigInt :| Positive0].decode(encoded1)
  decoded1 match
    case Right(DecodeResult(value, remainder)) =>
      value == n && remainder.isEmpty
    case Left(_) => false

// All these should return true:
// testRoundtrip(BigInt(0).refineUnsafe)
// testRoundtrip(BigInt(128).refineUnsafe)
// testRoundtrip(BigInt(255).refineUnsafe)
// testRoundtrip(BigInt(65536).refineUnsafe)
```

### BigInt (Signed Integers)

BigInt extends BigNat encoding with sign information.

**Encoding Rules:**

Convert signed BigInt to BigNat using sign-magnitude encoding:

```scala
n >= 0  →  encode (n * 2) as BigNat
n < 0   →  encode (n * (-2) + 1) as BigNat
```

**Key Insight:**
- Even numbers represent positive values: `2n → n`
- Odd numbers represent negative values: `2n+1 → -(n+1)`

**Encoding Examples:**

| Value | Transformation | BigNat | Encoded |
|-------|----------------|--------|---------|
| -2 | (-2) * (-2) + 1 = 5 | 5 | `0x05` |
| -1 | (-1) * (-2) + 1 = 3 | 3 | `0x03` |
| 0 | 0 * 2 = 0 | 0 | `0x00` |
| 1 | 1 * 2 = 2 | 2 | `0x02` |
| 2 | 2 * 2 = 4 | 4 | `0x04` |
| 127 | 127 * 2 = 254 | 254 | `0x81 fe` |
| -128 | -128 * (-2) + 1 = 257 | 257 | `0x82 01 01` |

**Decoding Rules:**

Given decoded BigNat `x`:
```scala
x % 2 == 0  →  x / 2        // even: positive
x % 2 == 1  →  (x - 1) / (-2)  // odd: negative
```

**Roundtrip Verification:**

| Value | Encode | Decode Check |
|-------|--------|--------------|
| -2 | 5 | (5-1)/(-2) = -2 ✓ |
| -1 | 3 | (3-1)/(-2) = -1 ✓ |
| 0 | 0 | 0/2 = 0 ✓ |
| 1 | 2 | 2/2 = 1 ✓ |
| 2 | 4 | 4/2 = 2 ✓ |

**Space Efficiency:**

Small integers (positive and negative) encode efficiently:
- Values -64 to 64: single byte
- Values -16384 to 16383: two bytes

## Product Types

### Tuples

Tuples are encoded as concatenated fields in order:

```
(A, B) → [A encoded][B encoded]
(A, B, C) → [A encoded][B encoded][C encoded]
```

**Encoding:**
```scala mdoc:silent
val tuple = (42L, 100L)
val encoded1 = ByteEncoder[(Long, Long)].encode(tuple)
// Result: [42L encoded][100L encoded]
```

**Decoding:**
Fields are decoded sequentially, consuming bytes from left to right.

### Case Classes

Case classes use automatic derivation via `Mirror.ProductOf`:

```
case class User(id: Long, balance: Long)
→ [id encoded][balance encoded]
```

**Encoding:**
```scala mdoc:silent
case class User(id: Long, balance: Long)

val user = User(1L, 100L)
val encoded1 = ByteEncoder[User].encode(user)
// Result: [id encoded][balance encoded]
```

**Field Order:**
Fields are encoded in the order they appear in the case class definition.

## Collection Types

### List[A]

Lists preserve order and encode with size prefix:

```
List(a1, a2, ..., an) → [size:BigNat][a1][a2]...[an]
```

**Encoding:**
```scala mdoc:silent
val list = List(1, 2, 3).map(BigInt(_))
val encoded1 = ByteEncoder[List[BigInt]].encode(list)
// Result: [0x03][0x02][0x04][0x06]
//         size=3, then 1→2, 2→4, 3→6
```

**Decoding:**
1. Decode size as BigNat
2. Decode exactly `size` elements
3. Return list and remainder

**Empty List:**
```
List() → [0x00]  // size = 0
```

### Option[A]

Option is encoded as zero or one-element list:

```
None → [0x00]  // size = 0
Some(x) → [0x01][x encoded]  // size = 1, element x
```

**Encoding:**
```scala mdoc:silent
val some: Option[Long] = Some(42L)
val encoded1 = ByteEncoder[Option[Long]].encode(some)
// Result: [0x01][42L encoded as 8 bytes]

val none: Option[Long] = None
val encoded2 = ByteEncoder[Option[Long]].encode(none)
// Result: [0x00]
```

**Why This Works:**
The context (type) distinguishes `Option[Long]` from `Long`. The byte `0x00` means:
- In `BigNat` context: natural number 0
- In `Option[A]` context: None (zero elements)

### Set[A]

Sets encode with deterministic ordering:

```
Set(a1, a2, ..., an) → [size:BigNat][sorted_a1][sorted_a2]...[sorted_an]
```

**Deterministic Sorting:**
1. Encode each element to bytes
2. Sort encoded bytes lexicographically
3. Concatenate with size prefix

**Encoding:**
```scala mdoc:silent
val set = Set(3, 1, 2).map(BigInt(_))
val encoded1 = ByteEncoder[Set[BigInt]].encode(set)
// Elements encode as: 3→0x06, 1→0x02, 2→0x04
// Sorted: 0x02, 0x04, 0x06
// Result: [0x03][0x02][0x04][0x06]
```

**Why Sorting:**
Set iteration order is undefined in Scala. Sorting encoded bytes ensures the same Set always produces the same byte sequence, which is critical for blockchain hashing and signing.

**Lexicographic Order:**
Bytes are compared left to right:
- `0x01` < `0x02` < `0x03` < ... < `0xff`
- `0x01 0x00` < `0x01 0x01`
- `0x01 0xff` < `0x02 0x00`

### Map[K, V]

Maps are encoded as deterministically sorted sets of tuples:

```
Map(k1 → v1, k2 → v2) → Set((k1, v1), (k2, v2))
→ [size:BigNat][sorted_tuple1][sorted_tuple2]...
```

**Encoding:**
```scala mdoc:silent
val map = Map(1L -> 10L, 2L -> 20L)
val encoded1 = ByteEncoder[Map[Long, Long]].encode(map)
// Each entry (1L, 10L) is encoded as tuple
// Tuples are sorted by their encoded bytes
// Result: [size][sorted entries]
```

**Tuple Encoding:**
Each `(K, V)` pair is encoded as product: `[K encoded][V encoded]`

**Deterministic Ordering:**
Like Set, Map entries are sorted by their encoded tuple bytes, ensuring consistent encoding regardless of Map iteration order.

## Custom Types

### Using contramap (Encoder)

Create encoder for custom type by transforming to existing type:

```scala mdoc:silent
case class UserId(value: Long)

given ByteEncoder[UserId] = ByteEncoder[Long].contramap(_.value)
```

### Using map/emap (Decoder)

Create decoder by transforming decoded value:

```scala mdoc:silent
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

// Simple transformation
given ByteDecoder[UserId] = ByteDecoder[Long].map(UserId(_))

// With validation
case class PositiveInt(value: Int)

given ByteDecoder[PositiveInt] = ByteDecoder[Long].emap: n =>
  if n > 0 && n <= Int.MaxValue then
    PositiveInt(n.toInt).asRight
  else
    DecodeFailure(s"Value $n is not a positive Int").asLeft
```

## Error Cases

### Decoding Failures

Common error scenarios:

**Insufficient bytes:**
```scala mdoc:silent
val incomplete = ByteVector(0x01)  // claims length 1, but no data
val result = ByteDecoder[BigInt :| Positive0].decode(incomplete)
// Result: Left(DecodeFailure("Insufficient bytes..."))
```

**Empty bytes for BigNat:**
```scala mdoc:silent
val empty = ByteVector.empty
val result2 = ByteDecoder[BigInt :| Positive0].decode(empty)
// Result: Left(DecodeFailure("Empty bytes"))
```

**Validation failures:**
```scala mdoc:silent
// Custom validation in emap
val negative = ByteVector(0x03)  // encodes -1 as BigInt
// If decoded as PositiveInt, validation fails
```

## Performance Characteristics

### Space Complexity

| Type | Space | Notes |
|------|-------|-------|
| Unit | 0 bytes | No data |
| Byte | 1 byte | Fixed |
| Long | 8 bytes | Fixed |
| Instant | 8 bytes | Fixed |
| BigInt 0-64 | 1 byte | Single byte range |
| BigInt 65-128 | 1 byte | Single byte range |
| BigInt 129-32767 | 3 bytes | Short data range |
| List[A] n elements | 1+ + n*sizeof(A) | Size prefix + elements |
| Set[A] n elements | 1+ + n*sizeof(A) | Size prefix + sorted |
| Map[K,V] n entries | 1+ + n*(sizeof(K)+sizeof(V)) | Size + sorted tuples |

### Time Complexity

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Encode primitive | O(1) | Constant time |
| Encode BigNat | O(log n) | Proportional to value size |
| Encode List[A] | O(n) | Linear in list size |
| Encode Set[A] | O(n log n) | Due to sorting |
| Encode Map[K,V] | O(n log n) | Due to sorting |
| Decode primitive | O(1) | Constant time |
| Decode BigNat | O(log n) | Read variable bytes |
| Decode List[A] | O(n) | Linear in list size |
| Decode Set[A] | O(n) | No sorting needed |
| Decode Map[K,V] | O(n) | No sorting needed |

## Roundtrip Property

For all supported types, the following must hold:

```scala
encode(decode(encode(value))) == encode(value)
decode(encode(value)) == Right(DecodeResult(value, ByteVector.empty))
```

This property is verified via property-based tests using hedgehog-munit.

---

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)
