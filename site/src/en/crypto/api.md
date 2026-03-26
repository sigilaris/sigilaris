# Crypto API Reference

[← Crypto Main](README.md)

---

## CryptoOps

Main interface providing platform-specific cryptographic operations. Provides the same API on both JVM and JavaScript.

### keccak256

```scala
def keccak256(input: Array[Byte]): Array[Byte]
```

Computes Keccak-256 hash of input bytes.

**Parameters:**
- `input: Array[Byte]` - message bytes to hash

**Returns:**
- `Array[Byte]` - 32-byte hash digest

**Features:**
- Thread-safe on JVM (uses thread-local pool)
- Safe on JS (single-threaded)

**Example:**
```scala
val message = "Hello, World!".getBytes
val hash = CryptoOps.keccak256(message)
assert(hash.length == 32)
```

### generate

```scala
def generate(): KeyPair
```

Generates a new random secp256k1 key pair.

**Returns:**
- `KeyPair` - fresh key pair with random private key

**Features:**
- Uses cryptographically secure random source
  - JVM: `SecureRandom`
  - JS: `crypto.getRandomValues`

**Example:**
```scala
val keyPair1 = CryptoOps.generate()
val keyPair2 = CryptoOps.generate()
assert(keyPair1.privateKey != keyPair2.privateKey)
```

### fromPrivate

```scala
def fromPrivate(privateKey: BigInt): KeyPair
```

Derives a key pair from an existing private key.

**Parameters:**
- `privateKey: BigInt` - private key as BigInt, must be in range `[1, n-1]` where n is curve order

**Returns:**
- `KeyPair` - key pair with the given private key and derived public key

**Features:**
- Validates that privateKey is within valid range
- Throws on invalid input

**Example:**
```scala
import org.sigilaris.core.datatype.UInt256

val privateKey = UInt256.fromHex("0123456789abcdef...").toOption.get
val keyPair = CryptoOps.fromPrivate(privateKey.toBigInt)
```

### sign

```scala
def sign(keyPair: KeyPair, transactionHash: Array[Byte]): Either[SigilarisFailure, Signature]
```

Signs a message hash with ECDSA.

**Parameters:**
- `keyPair: KeyPair` - key pair to sign with
- `transactionHash: Array[Byte]` - 32-byte message hash to sign

**Returns:**
- `Right(Signature)` - signature on success
- `Left(SigilarisFailure)` - error if signing fails

**Features:**
- Uses deterministic k-generation (RFC 6979)
- Normalizes signatures to Low-S form
- Includes recovery parameter (v = 27 + recId)

**Example:**
```scala
val keyPair = CryptoOps.generate()
val message = "Sign this!".getBytes
val hash = CryptoOps.keccak256(message)

CryptoOps.sign(keyPair, hash) match {
  case Right(signature) => println(s"Signed: $signature")
  case Left(error) => println(s"Error: $error")
}
```

### recover

```scala
def recover(signature: Signature, hashArray: Array[Byte]): Either[SigilarisFailure, PublicKey]
```

Recovers public key from signature and message hash.

**Parameters:**
- `signature: Signature` - ECDSA signature with recovery parameter
- `hashArray: Array[Byte]` - 32-byte message hash that was signed

**Returns:**
- `Right(PublicKey)` - recovered public key on success
- `Left(SigilarisFailure)` - error if recovery fails

**Features:**
- Accepts both High-S and Low-S signatures
- Recovery parameter (v) must be 27, 28, 29, or 30

**Example:**
```scala
val keyPair = CryptoOps.generate()
val hash = CryptoOps.keccak256("message".getBytes)
val signature = CryptoOps.sign(keyPair, hash).toOption.get

CryptoOps.recover(signature, hash) match {
  case Right(publicKey) =>
    assert(publicKey == keyPair.publicKey)
  case Left(error) =>
    println(s"Recovery failed: $error")
}
```

---

## KeyPair

Data type representing a secp256k1 key pair.

```scala
final case class KeyPair(privateKey: UInt256, publicKey: PublicKey)
```

**Fields:**
- `privateKey: UInt256` - 32-byte private key, in range `[1, n-1]` where n is secp256k1 curve order
- `publicKey: PublicKey` - corresponding 64-byte uncompressed public key (x||y coordinates)

**Example:**
```scala
val keyPair = CryptoOps.generate()
println(s"Private: ${keyPair.privateKey}")
println(s"Public: ${keyPair.publicKey}")
```

**Related Functions:**
- `CryptoOps.generate()` - generate key pair
- `CryptoOps.fromPrivate(privateKey)` - derive from private key

---

## Signature

ECDSA signature with recovery parameter for secp256k1.

```scala
final case class Signature(v: Int, r: UInt256, s: UInt256)
```

**Fields:**
- `v: Int` - recovery parameter, typically 27 or 28 (27 + recId where recId is 0 or 1)
- `r: UInt256` - signature component r, 32 bytes
- `s: UInt256` - signature component s, 32 bytes, normalized to Low-S form (s ≤ n/2)

**Low-S Normalization:**
Signatures are automatically normalized to Low-S form to prevent signature malleability.

**Example:**
```scala
val signature = Signature(
  v = 27,
  r = UInt256.fromHex("...").toOption.get,
  s = UInt256.fromHex("...").toOption.get
)
```

**Related Functions:**
- `CryptoOps.sign(keyPair, hash)` - create signature
- `CryptoOps.recover(signature, hash)` - recover public key

---

## Hash

Type class for type-safe Keccak-256 hashing.

```scala
trait Hash[A]:
  def apply(a: A): Hash.Value[A]
  def contramap[B](f: B => A): Hash[B]
```

### Hash.Value

Opaque type wrapping `UInt256` to track the source type of the hash.

```scala
opaque type Value[A] = UInt256
```

**Type Parameter:**
- `A` - the type that was hashed to produce this value

### Creating Hash Instances

#### build

```scala
def build[A: ByteEncoder]: Hash[A]
```

Builds a `Hash` instance for any type with a `ByteEncoder`.

**Example:**
```scala
import org.sigilaris.core.codec.byte.ByteEncoder

case class User(id: Long, name: String)

given Hash[User] = Hash.build[User]

val user = User(1L, "Alice")
val userHash = user.toHash
```

### Extension Methods

```scala
extension [A](a: A) def toHash(using h: Hash[A]): Hash.Value[A]
extension [A](value: Hash.Value[A]) def toUInt256: UInt256
```

**Example:**
```scala
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.Utf8

val utf8 = Utf8("hello")
val hash: Hash.Value[Utf8] = utf8.toHash
val uint256: UInt256 = hash.toUInt256
```

### Default Instances

#### Utf8

```scala
given Hash[Utf8] = Hash.build
```

Default `Hash` instance for UTF-8 strings.

**Example:**
```scala
import org.sigilaris.core.datatype.Utf8

val text = Utf8("Hello, World!")
val hash = text.toHash
```

---

## PublicKey

Represents a secp256k1 public key. May have different implementations on different platforms but provides the same API.

### Types

PublicKey is implemented as a sealed trait with two possible forms:

1. **XY**: Byte-oriented representation (x, y coordinates)
2. **Point**: Elliptic curve point-oriented representation (JVM only, performance optimized)

### Representation

- **Byte format**: 64 bytes, x||y coordinates (32 bytes each), big-endian
- **Coordinates**: `x: UInt256`, `y: UInt256`

### Features

- Same point is treated as equal regardless of internal representation
- equals/hashCode based on 64-byte value
- Immutable data structure

**Example:**
```scala
val keyPair = CryptoOps.generate()
val publicKey = keyPair.publicKey

// Public key can be serialized to 64 bytes
val bytes = publicKey.toBytes
assert(bytes.length == 64)
```

---

## Error Handling

Cryptographic operations can fail and return `Either[SigilarisFailure, A]`.

### Common Errors

**Signature Creation:**
- Invalid private key
- Invalid hash length

**Public Key Recovery:**
- Invalid recovery parameter (v)
- Invalid signature values (r, s)
- Invalid hash

**Example:**
```scala
val result = CryptoOps.sign(keyPair, hash)
result match {
  case Right(signature) =>
    // Use signature
    println(s"Success: $signature")
  case Left(error) =>
    // Handle error
    println(s"Failed: ${error.message}")
}
```

---

## Usage Tips

### 1. Hash Reuse

Don't hash the same message multiple times:
```scala
// Bad
val hash1 = CryptoOps.keccak256(message)
val hash2 = CryptoOps.keccak256(message)

// Good
val hash = CryptoOps.keccak256(message)
// Reuse hash multiple times
```

### 2. Type-Safe Hashing

Use the `Hash` type class to preserve type information:
```scala
import org.sigilaris.core.crypto.Hash.ops.*

case class Document(content: String)
given Hash[Document] = Hash.build[Document]

val doc = Document("important")
val docHash: Hash.Value[Document] = doc.toHash
```

### 3. Error Handling

Always handle the failure case of `Either`:
```scala
for {
  signature <- CryptoOps.sign(keyPair, hash)
  publicKey <- CryptoOps.recover(signature, hash)
} yield {
  // Success path
  println(s"Recovered: $publicKey")
}
```

---

[← Crypto Main](README.md)
