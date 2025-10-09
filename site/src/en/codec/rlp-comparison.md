# Comparison with Ethereum RLP

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)

---

## Overview

This document compares the Sigilaris byte codec with Ethereum's Recursive Length Prefix (RLP) encoding, explaining design similarities, differences, and the rationale behind design choices.

## What is RLP?

RLP (Recursive Length Prefix) is Ethereum's primary encoding method for serializing data structures. It's used for transactions, blocks, state trees, and network messages.

**Key characteristics:**
- Deterministic: same input always produces same output
- Variable-length encoding for space efficiency
- Supports byte arrays and lists (recursive structures)
- No explicit type information (type is determined by context)

## Similarities

Both Sigilaris codec and RLP share fundamental design goals:

### 1. Deterministic Encoding
Both ensure that the same data always produces the same byte sequence, which is essential for blockchain consensus.

### 2. Variable-Length Encoding
Small values use fewer bytes:
- **Sigilaris**: `0` → `0x00`, `128` → `0x80`, `129` → `0x81 81`
- **RLP**: `0` → `0x00`, `127` → `0x7f`, `128` → `0x81 80`

### 3. Length Prefixes
Both use prefix bytes to indicate data length for larger values.

### 4. Space Efficiency
Both optimize for common cases (small integers, short strings).

## Key Differences

### 1. Single-Byte Range

| Codec | Single-Byte Range | Values |
|-------|-------------------|--------|
| **Sigilaris** | `0x00` - `0x80` | 0-128 (inclusive) |
| **RLP** | `0x00` - `0x7f` | 0-127 (inclusive) |

**Rationale:** Sigilaris includes 128 in the single-byte range to optimize encoding of powers of 2.

**Examples:**
```
Value 128:
  Sigilaris: 0x80          (1 byte)
  RLP:       0x81 0x80     (2 bytes)

Value 127:
  Sigilaris: 0x7f          (1 byte)
  RLP:       0x7f          (1 byte)
```

### 2. Prefix Byte Calculation

#### Short Data (1-119 bytes)

| Codec | Prefix Range | Formula |
|-------|--------------|---------|
| **Sigilaris** | `0x81` - `0xf7` | `0x80 + data_length` |
| **RLP (strings)** | `0x80` - `0xb7` | `0x80 + data_length` |

**Sigilaris range:** `0x81` - `0xf7` = 119 possible values (1-119 byte data)
**RLP range:** `0x80` - `0xb7` = 56 possible values (1-55 byte data)

**Example (10-byte data):**
```
Sigilaris: [0x8a][10 bytes data]    (prefix = 0x80 + 10)
RLP:       [0x8a][10 bytes data]    (prefix = 0x80 + 10)
```

#### Long Data (120+ bytes)

| Codec | Prefix Range | Formula |
|-------|--------------|---------|
| **Sigilaris** | `0xf8` - `0xff` | `0xf8 + (length_bytes - 1)` |
| **RLP (strings)** | `0xb8` - `0xbf` | `0xb7 + length_bytes` |

**Example (200-byte data):**
```
Sigilaris:
  200 requires 1 byte to encode (0xc8)
  Prefix: 0xf8 (0xf8 + 0)
  Format: [0xf8][0xc8][200 bytes data]

RLP:
  200 requires 1 byte to encode (0xc8)
  Prefix: 0xb8 (0xb7 + 1)
  Format: [0xb8][0xc8][200 bytes data]
```

### 3. Type System

| Feature | Sigilaris | RLP |
|---------|-----------|-----|
| **Type safety** | Scala 3 type system | Untyped (context-dependent) |
| **Encoding** | Automatic derivation via `Mirror.ProductOf` | Manual serialization |
| **Validation** | Compile-time + runtime (via `emap`) | Runtime only |
| **Error handling** | `Either[DecodeFailure, A]` | Byte arrays or exceptions |

**Example:**
```scala
// Sigilaris: Type-safe
case class Transaction(from: Long, to: Long, amount: Long)
val tx = Transaction(1L, 2L, 100L)
val bytes = ByteEncoder[Transaction].encode(tx)
val decoded = ByteDecoder[Transaction].decode(bytes).map(_.value)

// RLP: Manual
val rlpTx = RLP.encodeList(
  RLP.encodeLong(1),
  RLP.encodeLong(2),
  RLP.encodeLong(100)
)
// Must manually extract and validate each field when decoding
```

### 4. Collection Handling

#### Lists

| Codec | Encoding |
|-------|----------|
| **Sigilaris** | `[size:BigNat][elem1][elem2]...` |
| **RLP** | `[length_prefix][elem1][elem2]...` (length is total byte size, not count) |

**Key difference:** RLP encodes the *total byte length* of all elements, while Sigilaris encodes the *element count*.

**Example:**
```
List(1L, 2L, 3L):

Sigilaris:
  [0x03]                          // 3 elements
  [8 bytes for 1L]
  [8 bytes for 2L]
  [8 bytes for 3L]

RLP:
  [prefix indicating 24 bytes follow]  // total size
  [encoded 1]
  [encoded 2]
  [encoded 3]
```

#### Sets and Maps

| Codec | Encoding | Order |
|-------|----------|-------|
| **Sigilaris Set** | Sorted by encoded bytes | Deterministic lexicographic |
| **RLP** | N/A (no native support) | - |
| **Sigilaris Map** | `Set[(K, V)]` | Deterministic lexicographic |
| **RLP** | N/A (no native support) | - |

**Sigilaris determinism:**
```scala
Set(3, 1, 2) always encodes as:
  [0x03][0x02][0x04][0x06]  // size=3, then sorted: 1→0x02, 2→0x04, 3→0x06
```

RLP has no built-in concept of Sets or Maps—applications must handle ordering manually.

### 5. Signed Integers

| Codec | Signed Integers |
|-------|-----------------|
| **Sigilaris** | Built-in: `BigInt` with sign-magnitude encoding |
| **RLP** | No native support (use two's complement or custom encoding) |

**Sigilaris sign encoding:**
```
n >= 0  →  encode(n * 2)           // even numbers = positive
n < 0   →  encode(n * (-2) + 1)    // odd numbers = negative

Examples:
  -2 → 5 → 0x05
  -1 → 3 → 0x03
   0 → 0 → 0x00
   1 → 2 → 0x02
   2 → 4 → 0x04
```

RLP requires applications to implement their own sign encoding.

## Design Rationale

### Why Not Use RLP Directly?

1. **Type Safety**: Sigilaris provides compile-time guarantees via Scala's type system
2. **Functional Programming**: Leverages cats ecosystem (contravariant/covariant functors)
3. **Deterministic Collections**: Built-in sorting for Sets and Maps
4. **Signed Integers**: Native support for negative numbers
5. **Automatic Derivation**: Case classes encode/decode automatically
6. **Better Error Handling**: `Either` instead of exceptions

### Trade-offs

| Aspect | Sigilaris | RLP |
|--------|-----------|-----|
| **Type safety** | ✓ Strong | ✗ Weak |
| **Ergonomics** | ✓ High (automatic derivation) | ✗ Manual |
| **Compatibility** | ✗ Not RLP-compatible | ✓ Ethereum standard |
| **Collection determinism** | ✓ Built-in | ✗ Manual |
| **Overhead** | ~ Comparable | ~ Comparable |

## Encoding Comparison Examples

### Example 1: Small Integer

```
Value: 42

Sigilaris BigInt:
  42 * 2 = 84
  84 → 0x54
  Result: [0x54] (1 byte)

RLP (as big-endian bytes):
  42 → 0x2a
  Result: [0x2a] (1 byte)
```

### Example 2: String "hello"

```
Sigilaris:
  UTF-8: 0x68 0x65 0x6c 0x6c 0x6f (5 bytes)
  Length: 5 encoded as BigNat → 0x05
  Result: [0x05][0x68 0x65 0x6c 0x6c 0x6f] (6 bytes)

RLP:
  UTF-8: 0x68 0x65 0x6c 0x6c 0x6f (5 bytes)
  Prefix: 0x80 + 5 = 0x85
  Result: [0x85][0x68 0x65 0x6c 0x6c 0x6f] (6 bytes)
```

### Example 3: List of Integers

```
List(1, 2, 3) where each element is encoded as BigInt

Sigilaris:
  Element count: 3 → 0x03
  1 → 0x02, 2 → 0x04, 3 → 0x06
  Result: [0x03][0x02][0x04][0x06] (4 bytes)

RLP:
  Total payload: 3 bytes
  Prefix: 0x80 + 3 = 0xc3
  1 → 0x01, 2 → 0x02, 3 → 0x03
  Result: [0xc3][0x01][0x02][0x03] (4 bytes)
```

### Example 4: Case Class

```scala
case class User(id: Long, balance: Long)
val user = User(1L, 100L)

Sigilaris:
  id: 1L → 8 bytes (0x00...0x01)
  balance: 100L → 8 bytes (0x00...0x64)
  Result: [16 bytes total]

RLP:
  Manually encode each field:
  RLP.encodeList(
    RLP.encodeLong(1),
    RLP.encodeLong(100)
  )
  Result: [prefix][encoded fields]
```

## When to Use Each

### Use Sigilaris Codec When:
- Building application-specific blockchains
- Type safety and functional programming are priorities
- You need deterministic collection encoding
- Automatic derivation simplifies development
- Scala 3 + cats ecosystem is your stack

### Use RLP When:
- Interoperating with Ethereum
- Following EVM standards
- Compatibility with existing Ethereum tools
- Working in dynamic-typed languages

## Interoperability

**Important:** Sigilaris codec and RLP are **not compatible**. Data encoded with one cannot be decoded by the other.

If you need to communicate with Ethereum nodes or smart contracts, you must use RLP. Sigilaris codec is designed for independent blockchain applications where you control both encoding and decoding.

## Performance Comparison

Both codecs have comparable performance characteristics:

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Encode primitive | O(1) | Similar |
| Encode list | O(n) | Similar |
| Encode set | O(n log n) | Sigilaris sorting overhead |
| Decode | O(n) | Similar |
| Space efficiency | ~ Equal | Both optimize small values |

Sigilaris may have slight overhead for Sets/Maps due to sorting, but this is negligible compared to the determinism guarantee.

## Summary

| Feature | Sigilaris | RLP |
|---------|-----------|-----|
| Determinism | ✓ | ✓ |
| Variable-length | ✓ | ✓ |
| Type safety | ✓ Strong | ✗ Weak |
| Collections | Set/Map deterministic | Manual |
| Signed integers | ✓ Built-in | ✗ Manual |
| Auto derivation | ✓ | ✗ |
| Ethereum compatible | ✗ | ✓ |

Sigilaris codec is inspired by RLP but designed for type-safe, functional blockchain development in Scala, with built-in support for deterministic collections and signed integers.

---

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)
