# Crypto

[← Main](../../README.md) | [한국어 →](../../ko/crypto/README.md)

---

[API](api.md)

---

## Overview

The Sigilaris crypto package provides high-performance cryptographic primitives for blockchain applications. It supports secp256k1 elliptic curve cryptography (ECDSA) and Keccak-256 hashing with a consistent API across both JVM and JavaScript platforms.

**Why do we need the crypto package?** In blockchain systems, cryptographic operations like transaction signing, signature verification, and public key recovery are essential. This package provides these operations in a type-safe and performance-optimized manner.

**Key Features:**
- **Cross-platform**: Unified API for JVM (BouncyCastle) and JS (elliptic.js)
- **Type-safe**: Leverages Scala 3 type system for safe APIs
- **High-performance**: Optimized with minimal allocation, object pooling, and caching
- **Low-S normalization**: Automatic Low-S normalization to prevent signature malleability
- **Recoverable signatures**: Support for public key recovery from ECDSA signatures

## Quick Start (30 seconds)

```scala
import org.sigilaris.core.crypto.*
import scodec.bits.ByteVector

// Generate a key pair
val keyPair = CryptoOps.generate()

// Hash a message
val message = "Hello, Blockchain!".getBytes
val hash = CryptoOps.keccak256(message)

// Create a signature
val signResult = CryptoOps.sign(keyPair, hash)
val signature = signResult.toOption.get

// Recover public key
val recovered = CryptoOps.recover(signature, hash)
val publicKey = recovered.toOption.get

// Verify the recovered public key matches the original
assert(publicKey == keyPair.publicKey)
```

That's it! The crypto package automatically:
- Generates cryptographically secure random numbers
- Creates deterministic signatures (RFC 6979)
- Prevents signature malleability with Low-S normalization
- Efficiently recovers public keys

## Documentation

### Core Concepts
- **[API Reference](api.md)**: Detailed documentation for CryptoOps, KeyPair, Signature, and Hash traits

### Main Types

#### CryptoOps
Platform-specific cryptographic operations implementation:
- `keccak256`: Keccak-256 hashing
- `generate`: Generate a new key pair
- `fromPrivate`: Derive key pair from private key
- `sign`: Create ECDSA signature
- `recover`: Recover public key from signature

#### KeyPair
Data type representing a secp256k1 key pair:
- `privateKey: UInt256` - 32-byte private key
- `publicKey: PublicKey` - 64-byte public key (x||y coordinates)

#### Signature
Recoverable ECDSA signature:
- `v: Int` - Recovery parameter (27-30)
- `r: UInt256` - Signature component r (32 bytes)
- `s: UInt256` - Signature component s (32 bytes, Low-S normalized)

#### Hash
Type class for type-safe Keccak-256 hashing:
```scala mdoc:reset:silent
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.Utf8

// Hash a UTF-8 string
val utf8Data = Utf8("hello")
val utf8Hash: Hash.Value[Utf8] = utf8Data.toHash

// Hash custom types
case class Order(from: Long, to: Long, amount: Long)
given Hash[Order] = Hash.build[Order]

val order = Order(1L, 2L, 100L)
val orderHash: Hash.Value[Order] = order.toHash
```

## Use Cases

### 1. Transaction Signing
```scala
import org.sigilaris.core.crypto.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*

case class Transaction(from: Long, to: Long, amount: Long, nonce: Long)

val keyPair = CryptoOps.generate()
val tx = Transaction(from = 1L, to = 2L, amount = 100L, nonce = 42L)

// Encode and hash the transaction
val txBytes = ByteEncoder[Transaction].encode(tx).toArray
val txHash = CryptoOps.keccak256(txBytes)

// Create signature
val signature = CryptoOps.sign(keyPair, txHash).toOption.get
```

### 2. Signature Verification
```scala
// Recover public key from signature
val recoveredPubKey = CryptoOps.recover(signature, txHash).toOption.get

// Verify the recovered public key matches the original
val isValid = recoveredPubKey == keyPair.publicKey
```

### 3. Type-Safe Hashing
```scala
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.Utf8

// Hash a UTF-8 string
val data = Utf8("important data")
val dataHash = data.toHash

// Hash value preserves the source type information
val hashValue: Hash.Value[Utf8] = dataHash
```

## Platform-Specific Implementations

### JVM (BouncyCastle)
- **secp256k1**: Uses BouncyCastle's EC implementation
- **Keccak-256**: Optimized with ThreadLocal object pooling
- **Performance**: High throughput with minimal allocation and caching
- **Thread-safe**: Safe concurrency with ThreadLocal pools

### JavaScript (elliptic.js)
- **secp256k1**: Uses elliptic.js library
- **Keccak-256**: Uses js-sha3 library
- **Consistency**: Same API and behavior as JVM
- **Single-threaded**: Optimized for JavaScript environments

## Security Considerations

### Low-S Normalization
All signatures are automatically normalized to Low-S form (s ≤ n/2):
- Prevents signature malleability
- Ensures unique signatures for the same message
- Compatible with major blockchains like Ethereum

### Constant-Time Comparison
Uses constant-time algorithms for sensitive data comparison:
- Prevents timing attacks
- Protects private keys, HMAC, and other sensitive data

### Memory Hygiene
Zeroizes memory after using secret data:
- Zeroizes ThreadLocal buffers before reuse
- Prevents secret data leakage

## Performance Characteristics

### JMH Benchmark Results
Recent optimization results (Phase 5):
- **fromPrivate**: ~16,000 ops/s (+2.2% improvement)
- **sign**: High throughput with minimal allocation
- **recover**: Optimized with ECPoint view caching

### Memory Usage
- **Object pooling**: Reuses ThreadLocal Keccak instances
- **Minimal allocation**: Eliminates unnecessary allocation in hot paths
- **Caching**: Caches ECPoint views in PublicKey

### Scalability
- **Thread-safe**: Safe parallel processing with ThreadLocal pools on JVM
- **Regression prevention**: Monitors performance regression with CI benchmarks (within ±2%)

## Type Conventions

### Byte Representation
- **Private key**: 32 bytes, big-endian
- **Public key**: 64 bytes, x||y coordinates (32 bytes each), big-endian
- **Hash**: 32 bytes, Keccak-256 result

### Signature Format
- **v**: Recovery parameter (27, 28, 29, or 30)
- **r**: 32-byte UInt256
- **s**: 32-byte UInt256 (Low-S normalized, s ≤ n/2)

## Next Steps

- Read detailed API documentation in [API Reference](api.md)
- Check design decisions and optimization process in ADR documents:
  - `docs/adr/0005-cryptoops-security-and-consistency.md` - Security and consistency
  - `docs/adr/0007-cryptoops-publickey-sealed-trait-ecpoint-view.md` - PublicKey optimization
- Run performance benchmarks in the `benchmarks/` directory

## Limitations

- **secp256k1 only**: Currently supports only the secp256k1 curve
- **Keccak-256 only**: Other hash functions like SHA-256 require separate modules
- **Signature scheme**: Supports only ECDSA (EdDSA and others are not supported)

## References

- [secp256k1 curve specification](https://www.secg.org/sec2-v2.pdf)
- [RFC 6979: Deterministic ECDSA](https://tools.ietf.org/html/rfc6979)
- [Keccak SHA-3](https://keccak.team/keccak.html)
- [BouncyCastle library](https://www.bouncycastle.org/)
- [elliptic.js library](https://github.com/indutny/elliptic)

---

[← Main](../../README.md) | [한국어 →](../../ko/crypto/README.md)
