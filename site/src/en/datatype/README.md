# Data Types

[← Main](../../README.md) | [한국어 →](../../ko/datatype/README.md)

---

## Overview

The Sigilaris datatype module provides type-safe, opaque types for common blockchain primitives with built-in codec support. These types ensure correctness at compile time while maintaining zero-cost abstractions at runtime.

### Why Specialized Data Types?

In blockchain applications:
- **Fixed-Size Integers**: Hash values, addresses require 256-bit unsigned integers
- **Natural Numbers**: Token amounts, counters must be non-negative
- **UTF-8 Strings**: Account names, metadata need length-prefixed serialization
- **Type Safety**: Prevent mixing incompatible numeric types at compile time

### Key Features

- **Opaque Types**: Zero-cost abstractions with compile-time safety
- **Codec Integration**: Automatic byte and JSON encoding/decoding
- **Typed Failures**: ADT-based error handling via `Either`
- **Validated Construction**: Safe constructors with proper validation

## Quick Start (30 seconds)

```scala mdoc
import org.sigilaris.core.datatype.*
import scodec.bits.ByteVector

// 256-bit unsigned integer
val hash = UInt256.fromHex("cafe").toOption.get

// Non-negative arbitrary-precision integer
val amount = BigNat.unsafeFromLong(1000L)

// Length-prefixed UTF-8 string
val label = Utf8("account-1")
```

That's it! These types work seamlessly with byte and JSON codecs.

## Data Types

### BigNat - Non-Negative Arbitrary-Precision Integer

Natural number (≥ 0) with arbitrary precision:

```scala mdoc:reset
import org.sigilaris.core.datatype.*

val n1 = BigNat.unsafeFromLong(42L)
val n2 = BigNat.unsafeFromLong(10L)

// Safe arithmetic operations
val sum = BigNat.add(n1, n2)  // 52
val product = BigNat.multiply(n1, n2)  // 420

// Subtraction returns Either
val diff = BigNat.tryToSubtract(n1, n2)  // Right(32)
val invalid = BigNat.tryToSubtract(n2, n1)  // Left("...")
```

**Key Features:**
- Arithmetic operations preserve non-negativity
- Variable-length byte encoding
- JSON string/number support

### UInt256 - 256-bit Unsigned Integer

Fixed-size 256-bit unsigned integer with big-endian representation:

```scala mdoc:reset
import org.sigilaris.core.datatype.*
import scodec.bits.ByteVector

// From hex string
val u1 = UInt256.fromHex("ff").toOption.get
val u2 = UInt256.fromHex("0x123abc").toOption.get

// From BigInt
val u3 = UInt256.fromBigIntUnsigned(BigInt(42)).toOption.get

// Conversions
val bigInt: BigInt = u1.toBigIntUnsigned
val hex: String = u1.toHexLower  // lowercase hex, no 0x prefix
```

**Key Features:**
- Fixed 32-byte representation
- Automatic left-padding
- Hex string support (with/without `0x` prefix)
- Fixed-size byte codec

### Utf8 - Length-Prefixed UTF-8 String

UTF-8 string with length-prefixed byte encoding:

```scala mdoc:reset
import org.sigilaris.core.datatype.*

val text = Utf8("Hello, 世界!")

// String conversion
val str: String = text.asString

// Works as Map keys
val map = Map(Utf8("key1") -> 42, Utf8("key2") -> 100)
```

**Key Features:**
- Length-prefixed byte encoding: `[size: BigNat][UTF-8 bytes]`
- JSON string encoding
- JSON key codec support
- UTF-8 validation on decode

## Codec Integration

All types have built-in byte and JSON codecs:

```scala mdoc:reset
import org.sigilaris.core.datatype.*
import org.sigilaris.core.codec.byte.{ByteEncoder, ByteDecoder}
import org.sigilaris.core.codec.json.{JsonEncoder, JsonDecoder}
import scodec.bits.ByteVector

val num = BigNat.unsafeFromLong(42L)

// Byte encoding
val bytes = ByteEncoder[BigNat].encode(num)
val decoded = ByteDecoder[BigNat].decode(bytes)

// JSON encoding
val json = JsonEncoder[BigNat].encode(num)
val fromJson = JsonDecoder[BigNat].decode(json)
```

## Type Safety Examples

### Preventing Invalid Values

```scala mdoc:reset
import org.sigilaris.core.datatype.*
import scodec.bits.ByteVector

// BigNat: compile-time guarantee of non-negativity
val valid = BigNat.fromBigInt(BigInt(100))  // Right(BigNat(100))
val invalid = BigNat.fromBigInt(BigInt(-1))  // Left("Constraint failed...")

// UInt256: typed failures for validation errors
val overflow = UInt256.fromBigIntUnsigned(BigInt(2).pow(256))
// Left(UInt256Overflow("..."))

val tooLong = UInt256.fromBytesBE(ByteVector.fill(33)(0xff.toByte))
// Left(UInt256TooLong(33, 32))
```

### Safe Arithmetic

```scala mdoc:reset
import org.sigilaris.core.datatype.*

val a = BigNat.unsafeFromLong(10L)
val b = BigNat.unsafeFromLong(3L)

// Operations that always succeed
BigNat.add(a, b)       // 13
BigNat.multiply(a, b)  // 30
BigNat.divide(a, b)    // 3

// Operation that can fail
BigNat.tryToSubtract(a, b)  // Right(7)
BigNat.tryToSubtract(b, a)  // Left("Constraint failed...")
```

## Design Philosophy

### Opaque Types
- Zero runtime overhead
- Compile-time type safety
- Prevents mixing incompatible types

### Validated Construction
- Safe constructors return `Either`
- Unsafe constructors for known-valid data
- Clear failure types via ADTs

### Codec-First Design
- All types have byte/JSON codecs
- Deterministic serialization
- Roundtrip property tested

## Next Steps

1. **BigNat**: Arbitrary-precision natural numbers
2. **UInt256**: Fixed-size unsigned integers for hashes/addresses
3. **Utf8**: Length-prefixed strings for metadata

---

[← Main](../../README.md) | [한국어 →](../../ko/datatype/README.md)
