# Byte Codec

[← Main](../../README.md) | [한국어 →](../../ko/byte-codec/README.md)

---

[API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)

---

## Overview

The Sigilaris byte codec provides deterministic binary encoding and decoding for blockchain applications. When signing transactions or computing block hashes, data must first be converted to a deterministic byte sequence—the same data must always produce the same bytes to ensure hash and signature correctness.

### Why Deterministic Encoding?

In blockchain systems:
- **Transaction Signing**: Users sign the byte representation of transactions
- **Block Hashing**: Block headers are hashed to create block IDs
- **Merkle Trees**: Data structures require consistent byte ordering
- **Consensus**: All nodes must agree on byte representations

Any non-deterministic encoding (e.g., random collection ordering) breaks consensus.

### Key Features

- **Variable-Length Encoding**: Space-efficient encoding similar to RLP but with distinct design choices
- **Deterministic Collections**: Sets and Maps are lexicographically sorted after encoding
- **Type-Safe API**: Built on Scala 3 + cats ecosystem with contravariant/covariant functors
- **Automatic Derivation**: Case classes and sealed traits via `Mirror.ProductOf`

## Quick Start (30 seconds)

```scala mdoc:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

case class Transaction(from: Long, to: Long, amount: Long)

val tx = Transaction(from = 1L, to = 2L, amount = 100L)
```

```scala mdoc
val encoded: ByteVector = ByteEncoder[Transaction].encode(tx)
val decoded = ByteDecoder[Transaction].decode(encoded)
```

That's it! The codec automatically derives instances for case classes.

## Documentation

- **[API Reference](api.md)**: `ByteEncoder`, `ByteDecoder`, `ByteCodec` details
- **[Type Rules](types.md)**: Encoding/decoding rules for `BigNat`, `BigInt`, collections
- **[Practical Examples](examples.md)**: Blockchain data structures
- **[RLP Comparison](rlp-comparison.md)**: Differences from Ethereum RLP

## What's Included

### Basic Types
- `Unit`, `Byte`, `Long`, `Instant`: Direct encoding
- `BigInt`: Sign-aware variable-length encoding

### Collections
- `List[A]`: Size prefix + ordered elements
- `Option[A]`: Encoded as `List[A]` (0 or 1 element)
- `Set[A]`: Lexicographically sorted after encoding
- `Map[K, V]`: Treated as `Set[(K, V)]` for determinism

### Automatic Derivation
```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*

case class Block(height: Long, txCount: Long)

// Instances automatically derived
val block = Block(height = 1L, txCount = 10L)
```

```scala mdoc
ByteEncoder[Block].encode(block)
```

## Design Philosophy

### Separation of Concerns
This codec library handles **byte encoding only**. Hashing and signing are separate modules:
- Codec: `Data → ByteVector`
- Crypto: `ByteVector → Hash/Signature` (future module)

### Determinism Guarantee
- Collections are sorted by encoded byte representation
- Same input always produces same output
- Independent of platform, JVM version, or execution order

### Performance
- Small integers (0-128) use single bytes
- Variable-length encoding minimizes space
- Tail-recursive implementations for stack safety

## Example: Transaction Encoding

```scala mdoc:reset:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

case class Address(id: Long)
case class Transaction(
  from: Address,
  to: Address,
  amount: Long,
  nonce: Long
)
```

```scala mdoc
val tx = Transaction(
  from = Address(100L),
  to = Address(200L),
  amount = 5000L,
  nonce = 42L
)

val bytes = ByteEncoder[Transaction].encode(tx)
val roundtrip = ByteDecoder[Transaction].decode(bytes)
```

The encoded bytes can now be hashed or signed (using future crypto modules).

## Next Steps

1. **[API Reference](api.md)**: Learn about `contramap`, `emap`, `flatMap` combinators
2. **[Type Rules](types.md)**: Understand `BigNat`/`BigInt` variable-length encoding
3. **[Examples](examples.md)**: See complete blockchain data structures
4. **[RLP Comparison](rlp-comparison.md)**: Compare with Ethereum's RLP encoding

## Limitations and Scope

- **No Hashing/Signing**: This module only handles byte encoding
- **No Compression**: Data is not compressed (use separate compression library if needed)
- **Not RLP Compatible**: Similar design but different byte layout

## Performance Characteristics

- **Encoding**: O(n) where n is the size of the data structure
- **Decoding**: O(n) with early exit on errors
- **Space**: Variable-length encoding minimizes bytes for small values
- **Collections**: Sorting overhead is O(k log k) where k is collection size

---

[← Main](../../README.md) | [한국어 →](../../ko/byte-codec/README.md)
