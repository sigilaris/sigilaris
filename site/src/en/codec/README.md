# Byte Codec

[← Main](../../README.md) | [한국어 →](../../ko/codec/README.md)

---

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)

---

## Overview

Sigilaris Byte Codec is a deterministic byte encoding/decoding library designed for blockchain applications. It provides type-safe, composable encoding of Scala data structures into byte sequences suitable for transaction signing, block hashing, and merkle tree construction.

**Why deterministic?** In blockchain systems, the same data must always encode to the same byte sequence. This ensures that cryptographic hashes and signatures remain consistent across different nodes and platforms.

**Key Features:**
- **Type-safe API**: Leverages Scala 3's type system and cats typeclasses
- **Automatic derivation**: Product types (case classes) encode automatically
- **Deterministic collections**: Set and Map elements are sorted by encoded bytes
- **Variable-length encoding**: Space-efficient representation (similar to RLP but distinct)
- **Composable**: Easily create custom encoders using `contramap`, `map`, `emap`

## Quick Start (30 seconds)

```scala mdoc:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

// Simple encoding and decoding
val value: Long = 42L
val bytes = ByteEncoder[Long].encode(value)
val decoded = ByteDecoder[Long].decode(bytes)
// Result: Right(DecodeResult(42, ByteVector(empty)))

// Works with tuples automatically
val pair = (1L, 2L)
val pairBytes = ByteEncoder[(Long, Long)].encode(pair)
val decodedPair = ByteDecoder[(Long, Long)].decode(pairBytes)
// Result: Right(DecodeResult((1,2), ByteVector(empty)))
```

That's it! The codec automatically:
- Encodes values deterministically
- Uses space-efficient variable-length encoding for BigInt
- Provides type-safe encoding and decoding
- Supports automatic derivation for product types

## Documentation

### Core Concepts
- **[API Reference](api.md)**: ByteEncoder, ByteDecoder, ByteCodec traits and methods
- **[Type Rules](types.md)**: Detailed encoding/decoding specifications per type
- **[Examples](examples.md)**: Real-world blockchain data structures
- **[RLP Comparison](rlp-comparison.md)**: How this codec differs from Ethereum RLP

### Use Cases
1. **Transaction signing**: Encode transaction data before signing with private key
2. **Block hashing**: Create deterministic byte representation for block headers
3. **Merkle trees**: Generate consistent hashes for merkle proof verification
4. **Network protocols**: Serialize messages for peer-to-peer communication

### What This Library Does NOT Do
- **Cryptographic hashing**: Use separate crypto modules (SHA-256, Keccak, etc.)
- **Digital signatures**: Use signature modules (ECDSA, EdDSA, etc.)
- **Network transport**: Use separate networking layer (TCP, HTTP, etc.)

This library focuses solely on byte encoding/decoding. Combine it with other modules for complete blockchain functionality.

## Type Support

The codec provides instances for:

**Primitive types:**
- `Unit`, `Byte`, `Long`, `java.time.Instant`

**Numeric types:**
- `BigInt` (signed integers with efficient encoding)
- `BigNat` (natural numbers, internal use)

**Collections:**
- `List[A]` (preserves order)
- `Option[A]` (encoded as zero or one-element list)
- `Set[A]` (deterministic: sorted by encoded bytes)
- `Map[K, V]` (deterministic: encoded as sorted set of tuples)

**Product types:**
- `Tuple2`, `Tuple3`, ... (automatic derivation)
- Case classes (automatic derivation via `Mirror.ProductOf`)

**Custom types:**
- Create instances using `contramap`, `map`, `emap`

## Error Handling

Decoding returns `Either[String, (A, ByteVector)]`:
- **Left(error)**: Decoding failed with error message
- **Right((value, remainder))**: Successfully decoded `value`, `remainder` contains unused bytes

```scala mdoc:silent
// Example: decoding insufficient data
val incomplete = ByteVector(0x01)
val result = ByteDecoder[Long].decode(incomplete)
// Result: Left("Insufficient bytes for BigNat data: needed 1, got 0")
```

## Performance Characteristics

- **Space-efficient**: Small integers (0-128) encoded as single byte
- **Time complexity**: O(n) encoding and decoding where n is data size
- **Deterministic sorting**: Set/Map sorting is O(n log n) where n is element count
- **Stack-safe**: Uses cats-effect stack-safe recursion for large collections

## Next Steps

- Read [API Reference](api.md) for detailed trait documentation
- Check [Type Rules](types.md) to understand encoding specifications
- See [Examples](examples.md) for real-world usage patterns
- Compare with [RLP](rlp-comparison.md) if you're familiar with Ethereum

---

[← Main](../../README.md) | [한국어 →](../../ko/codec/README.md)
