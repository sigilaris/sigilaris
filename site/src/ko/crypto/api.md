# Crypto API 레퍼런스

[← Crypto 메인](README.md)

---

## CryptoOps

플랫폼별 암호화 연산 구현을 제공하는 메인 인터페이스입니다. JVM과 JavaScript 모두에서 동일한 API를 제공합니다.

### keccak256

```scala
def keccak256(input: Array[Byte]): Array[Byte]
```

입력 바이트의 Keccak-256 해시를 계산합니다.

**파라미터:**
- `input: Array[Byte]` - 해시할 메시지 바이트

**반환값:**
- `Array[Byte]` - 32바이트 해시 다이제스트

**특징:**
- JVM에서는 스레드 안전 (thread-local 풀 사용)
- JS에서는 안전 (단일 스레드)

**예제:**
```scala
val message = "Hello, World!".getBytes
val hash = CryptoOps.keccak256(message)
assert(hash.length == 32)
```

### generate

```scala
def generate(): KeyPair
```

새로운 무작위 secp256k1 키 쌍을 생성합니다.

**반환값:**
- `KeyPair` - 무작위 개인키를 가진 새로운 키 쌍

**특징:**
- 암호학적으로 안전한 난수 소스 사용
  - JVM: `SecureRandom`
  - JS: `crypto.getRandomValues`

**예제:**
```scala
val keyPair1 = CryptoOps.generate()
val keyPair2 = CryptoOps.generate()
assert(keyPair1.privateKey != keyPair2.privateKey)
```

### fromPrivate

```scala
def fromPrivate(privateKey: BigInt): KeyPair
```

기존 개인키로부터 키 쌍을 유도합니다.

**파라미터:**
- `privateKey: BigInt` - BigInt로 표현된 개인키, `[1, n-1]` 범위여야 함 (n은 곡선 차수)

**반환값:**
- `KeyPair` - 주어진 개인키와 유도된 공개키를 가진 키 쌍

**특징:**
- 개인키가 유효 범위 내에 있는지 검증
- 잘못된 입력 시 예외 발생

**예제:**
```scala
import org.sigilaris.core.datatype.UInt256

val privateKey = UInt256.fromHex("0123456789abcdef...").toOption.get
val keyPair = CryptoOps.fromPrivate(privateKey.toBigInt)
```

### sign

```scala
def sign(keyPair: KeyPair, transactionHash: Array[Byte]): Either[SigilarisFailure, Signature]
```

ECDSA로 메시지 해시에 서명합니다.

**파라미터:**
- `keyPair: KeyPair` - 서명에 사용할 키 쌍
- `transactionHash: Array[Byte]` - 서명할 32바이트 메시지 해시

**반환값:**
- `Right(Signature)` - 성공 시 서명
- `Left(SigilarisFailure)` - 서명 실패 시 에러

**특징:**
- 결정론적 k 생성 사용 (RFC 6979)
- Low-S 형태로 서명 정규화
- 복구 파라미터 포함 (v = 27 + recId)

**예제:**
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

서명과 메시지 해시로부터 공개키를 복구합니다.

**파라미터:**
- `signature: Signature` - 복구 파라미터를 포함한 ECDSA 서명
- `hashArray: Array[Byte]` - 서명된 32바이트 메시지 해시

**반환값:**
- `Right(PublicKey)` - 성공 시 복구된 공개키
- `Left(SigilarisFailure)` - 복구 실패 시 에러

**특징:**
- High-S와 Low-S 서명 모두 허용
- 복구 파라미터 (v)는 27, 28, 29, 또는 30이어야 함

**예제:**
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

secp256k1 키 쌍을 나타내는 데이터 타입입니다.

```scala
final case class KeyPair(privateKey: UInt256, publicKey: PublicKey)
```

**필드:**
- `privateKey: UInt256` - 32바이트 개인키, `[1, n-1]` 범위 (n은 secp256k1 곡선 차수)
- `publicKey: PublicKey` - 대응하는 64바이트 비압축 공개키 (x||y 좌표)

**예제:**
```scala
val keyPair = CryptoOps.generate()
println(s"Private: ${keyPair.privateKey}")
println(s"Public: ${keyPair.publicKey}")
```

**관련 함수:**
- `CryptoOps.generate()` - 키 쌍 생성
- `CryptoOps.fromPrivate(privateKey)` - 개인키로부터 유도

---

## Signature

복구 파라미터를 포함한 secp256k1용 ECDSA 서명입니다.

```scala
final case class Signature(v: Int, r: UInt256, s: UInt256)
```

**필드:**
- `v: Int` - 복구 파라미터, 일반적으로 27 또는 28 (27 + recId, recId는 0 또는 1)
- `r: UInt256` - 서명 요소 r, 32바이트
- `s: UInt256` - 서명 요소 s, 32바이트, Low-S 형태로 정규화됨 (s ≤ n/2)

**Low-S 정규화:**
서명은 자동으로 Low-S 형태로 정규화되어 서명 변조(signature malleability)를 방지합니다.

**예제:**
```scala
val signature = Signature(
  v = 27,
  r = UInt256.fromHex("...").toOption.get,
  s = UInt256.fromHex("...").toOption.get
)
```

**관련 함수:**
- `CryptoOps.sign(keyPair, hash)` - 서명 생성
- `CryptoOps.recover(signature, hash)` - 공개키 복구

---

## Hash

타입 안전한 Keccak-256 해싱을 위한 타입 클래스입니다.

```scala
trait Hash[A]:
  def apply(a: A): Hash.Value[A]
  def contramap[B](f: B => A): Hash[B]
```

### Hash.Value

해시의 소스 타입을 추적하는 `UInt256`을 감싸는 opaque 타입입니다.

```scala
opaque type Value[A] = UInt256
```

**타입 파라미터:**
- `A` - 이 해시를 생성하는 데 해시된 타입

### Hash 인스턴스 생성

#### build

```scala
def build[A: ByteEncoder]: Hash[A]
```

`ByteEncoder`를 가진 모든 타입에 대한 `Hash` 인스턴스를 생성합니다.

**예제:**
```scala
import org.sigilaris.core.codec.byte.ByteEncoder

case class User(id: Long, name: String)

given Hash[User] = Hash.build[User]

val user = User(1L, "Alice")
val userHash = user.toHash
```

### Extension 메서드

```scala
extension [A](a: A) def toHash(using h: Hash[A]): Hash.Value[A]
extension [A](value: Hash.Value[A]) def toUInt256: UInt256
```

**예제:**
```scala
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.Utf8

val utf8 = Utf8("hello")
val hash: Hash.Value[Utf8] = utf8.toHash
val uint256: UInt256 = hash.toUInt256
```

### 기본 인스턴스

#### Utf8

```scala
given Hash[Utf8] = Hash.build
```

UTF-8 문자열에 대한 기본 `Hash` 인스턴스입니다.

**예제:**
```scala
import org.sigilaris.core.datatype.Utf8

val text = Utf8("Hello, World!")
val hash = text.toHash
```

---

## PublicKey

secp256k1 공개키를 나타냅니다. 플랫폼별로 다른 구현을 가질 수 있지만 동일한 API를 제공합니다.

### 타입

공개키는 sealed trait로 구현되어 있으며, 두 가지 형태를 가질 수 있습니다:

1. **XY**: 바이트 중심 표현 (x, y 좌표)
2. **Point**: 타원곡선 점 중심 표현 (JVM 전용, 성능 최적화)

### 표현

- **바이트 형식**: 64바이트, x||y 좌표 (각 32바이트), big-endian
- **좌표**: `x: UInt256`, `y: UInt256`

### 특징

- 내부 표현에 관계없이 동일한 점은 같은 것으로 취급됨
- equals/hashCode는 64바이트 값 기준
- 불변 데이터 구조

**예제:**
```scala
val keyPair = CryptoOps.generate()
val publicKey = keyPair.publicKey

// 공개키는 64바이트로 직렬화 가능
val bytes = publicKey.toBytes
assert(bytes.length == 64)
```

---

## 에러 처리

암호화 연산은 실패할 수 있으며, `Either[SigilarisFailure, A]`를 반환합니다.

### 일반적인 에러

**서명 생성:**
- 잘못된 개인키
- 잘못된 해시 길이

**공개키 복구:**
- 잘못된 복구 파라미터 (v)
- 잘못된 서명 값 (r, s)
- 잘못된 해시

**예제:**
```scala
val result = CryptoOps.sign(keyPair, hash)
result match {
  case Right(signature) =>
    // 서명 사용
    println(s"Success: $signature")
  case Left(error) =>
    // 에러 처리
    println(s"Failed: ${error.message}")
}
```

---

## 사용 팁

### 1. 해시 재사용

같은 메시지를 여러 번 해시하지 마세요:
```scala
// 나쁜 예
val hash1 = CryptoOps.keccak256(message)
val hash2 = CryptoOps.keccak256(message)

// 좋은 예
val hash = CryptoOps.keccak256(message)
// hash를 여러 번 재사용
```

### 2. 타입 안전한 해싱

`Hash` 타입 클래스를 사용하여 타입 정보를 보존하세요:
```scala
import org.sigilaris.core.crypto.Hash.ops.*

case class Document(content: String)
given Hash[Document] = Hash.build[Document]

val doc = Document("important")
val docHash: Hash.Value[Document] = doc.toHash
```

### 3. 에러 처리

항상 `Either`의 실패 케이스를 처리하세요:
```scala
for {
  signature <- CryptoOps.sign(keyPair, hash)
  publicKey <- CryptoOps.recover(signature, hash)
} yield {
  // 성공 경로
  println(s"Recovered: $publicKey")
}
```

---

[← Crypto 메인](README.md)
