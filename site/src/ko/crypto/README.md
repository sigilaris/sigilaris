# Crypto (암호화)

[← 메인](../../README.md) | [English →](../../en/crypto/README.md)

---

[API](api.md)

---

## 개요

Sigilaris crypto 패키지는 블록체인 애플리케이션을 위한 고성능 암호화 기본 요소를 제공합니다. secp256k1 타원곡선 암호화(ECDSA)와 Keccak-256 해싱을 지원하며, JVM과 JavaScript 플랫폼 모두에서 일관된 API로 동작합니다.

**왜 crypto 패키지가 필요한가?** 블록체인 시스템에서는 트랜잭션 서명, 서명 검증, 공개키 복구 등의 암호화 연산이 핵심입니다. 이 패키지는 타입 안전하고 성능 최적화된 방식으로 이러한 연산을 제공합니다.

**주요 특징:**
- **Cross-platform**: JVM(BouncyCastle)과 JS(elliptic.js)에서 동일한 API
- **타입 안전**: Scala 3 타입 시스템 활용한 안전한 API
- **고성능**: 최소 할당, 객체 풀링, 캐싱을 통한 최적화
- **Low-S 정규화**: 서명 변조 방지를 위한 자동 Low-S 정규화
- **복구 가능 서명**: ECDSA 서명에서 공개키 복구 지원

## 빠른 시작 (30초)

```scala
import org.sigilaris.core.crypto.*
import scodec.bits.ByteVector

// 키 쌍 생성
val keyPair = CryptoOps.generate()

// 메시지 해싱
val message = "Hello, Blockchain!".getBytes
val hash = CryptoOps.keccak256(message)

// 서명 생성
val signResult = CryptoOps.sign(keyPair, hash)
val signature = signResult.toOption.get

// 공개키 복구
val recovered = CryptoOps.recover(signature, hash)
val publicKey = recovered.toOption.get

// 복구된 공개키가 원본과 동일한지 확인
assert(publicKey == keyPair.publicKey)
```

이게 전부입니다! crypto 패키지가 자동으로:
- 암호학적으로 안전한 난수 생성
- 결정론적 서명 생성 (RFC 6979)
- Low-S 정규화로 서명 변조 방지
- 효율적인 공개키 복구

## 문서

### 핵심 개념
- **[API 레퍼런스](api.md)**: CryptoOps, KeyPair, Signature, Hash trait 상세

### 주요 타입

#### CryptoOps
플랫폼별 암호화 연산 구현:
- `keccak256`: Keccak-256 해싱
- `generate`: 새로운 키 쌍 생성
- `fromPrivate`: 개인키로부터 키 쌍 유도
- `sign`: ECDSA 서명 생성
- `recover`: 서명으로부터 공개키 복구

#### KeyPair
secp256k1 키 쌍을 나타내는 데이터 타입:
- `privateKey: UInt256` - 32바이트 개인키
- `publicKey: PublicKey` - 64바이트 공개키 (x||y 좌표)

#### Signature
복구 가능한 ECDSA 서명:
- `v: Int` - 복구 파라미터 (27-30)
- `r: UInt256` - 서명 요소 r (32바이트)
- `s: UInt256` - 서명 요소 s (32바이트, Low-S 정규화됨)

#### Hash
타입 안전한 Keccak-256 해싱을 위한 타입 클래스:
```scala mdoc:reset:silent
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.Utf8

// UTF-8 문자열 해싱
val utf8Data = Utf8("hello")
val utf8Hash: Hash.Value[Utf8] = utf8Data.toHash

// 커스텀 타입 해싱
case class Order(from: Long, to: Long, amount: Long)
given Hash[Order] = Hash.build[Order]

val order = Order(1L, 2L, 100L)
val orderHash: Hash.Value[Order] = order.toHash
```

## 활용 사례

### 1. 트랜잭션 서명
```scala
import org.sigilaris.core.crypto.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*

case class Transaction(from: Long, to: Long, amount: Long, nonce: Long)

val keyPair = CryptoOps.generate()
val tx = Transaction(from = 1L, to = 2L, amount = 100L, nonce = 42L)

// 트랜잭션 인코딩 후 해싱
val txBytes = ByteEncoder[Transaction].encode(tx).toArray
val txHash = CryptoOps.keccak256(txBytes)

// 서명 생성
val signature = CryptoOps.sign(keyPair, txHash).toOption.get
```

### 2. 서명 검증
```scala
// 서명으로부터 공개키 복구
val recoveredPubKey = CryptoOps.recover(signature, txHash).toOption.get

// 복구된 공개키가 원본 공개키와 일치하는지 확인
val isValid = recoveredPubKey == keyPair.publicKey
```

### 3. 타입 안전한 해싱
```scala
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.Utf8

// UTF-8 문자열 해싱
val data = Utf8("important data")
val dataHash = data.toHash

// 해시 값은 원본 타입 정보를 유지
val hashValue: Hash.Value[Utf8] = dataHash
```

## 플랫폼별 구현

### JVM (BouncyCastle)
- **secp256k1**: BouncyCastle의 EC 구현 사용
- **Keccak-256**: ThreadLocal 객체 풀링으로 최적화
- **성능**: 최소 할당, 캐싱을 통한 고성능
- **스레드 안전**: ThreadLocal 풀로 안전한 동시성 지원

### JavaScript (elliptic.js)
- **secp256k1**: elliptic.js 라이브러리 사용
- **Keccak-256**: js-sha3 라이브러리 사용
- **일관성**: JVM과 동일한 API 및 동작 보장
- **단일 스레드**: JavaScript 환경에 최적화

## 보안 고려사항

### Low-S 정규화
모든 서명은 자동으로 Low-S 형태로 정규화됩니다 (s ≤ n/2):
- 서명 변조(signature malleability) 방지
- 동일한 메시지에 대해 고유한 서명 보장
- Ethereum 등 주요 블록체인과 호환

### 상수 시간 비교
비밀 데이터 비교 시 상수 시간 알고리즘 사용:
- 타이밍 공격 방지
- 개인키, HMAC 등 민감 데이터 보호

### 메모리 위생
비밀 데이터 사용 후 메모리 제로화:
- ThreadLocal 버퍼 재사용 전 제로화
- 비밀 데이터 누출 방지

## 성능 특성

### JMH 벤치마크 결과
최근 최적화 결과 (Phase 5):
- **fromPrivate**: ~16,000 ops/s (+2.2% 개선)
- **sign**: 최소 할당으로 높은 처리량
- **recover**: ECPoint 뷰 캐싱으로 최적화

### 메모리 사용
- **객체 풀링**: ThreadLocal Keccak 인스턴스 재사용
- **최소 할당**: 핫패스에서 불필요한 할당 제거
- **캐싱**: PublicKey의 ECPoint 뷰 캐싱

### 확장성
- **스레드 안전**: JVM에서 ThreadLocal 풀로 안전한 병렬 처리
- **회귀 방지**: CI 벤치마크로 성능 회귀 감시 (±2% 이내)

## 타입 규약

### 바이트 표현
- **개인키**: 32바이트, big-endian
- **공개키**: 64바이트, x||y 좌표 (각 32바이트), big-endian
- **해시**: 32바이트, Keccak-256 결과

### 서명 형식
- **v**: 복구 파라미터 (27, 28, 29, 또는 30)
- **r**: 32바이트 UInt256
- **s**: 32바이트 UInt256 (Low-S 정규화됨, s ≤ n/2)

## 다음 단계

- [API 레퍼런스](api.md)에서 상세한 API 문서 읽기
- ADR 문서에서 설계 결정 및 최적화 과정 확인:
  - `docs/adr/0005-cryptoops-security-and-consistency.md` - 보안 및 일관성
  - `docs/adr/0007-cryptoops-publickey-sealed-trait-ecpoint-view.md` - PublicKey 최적화
- `benchmarks/` 디렉토리에서 성능 벤치마크 실행하기

## 제한사항

- **secp256k1 전용**: 현재 secp256k1 곡선만 지원
- **Keccak-256 전용**: SHA-256 등 다른 해시 함수는 별도 모듈 필요
- **서명 스키마**: ECDSA만 지원 (EdDSA 등은 미지원)

## 참고자료

- [secp256k1 곡선 사양](https://www.secg.org/sec2-v2.pdf)
- [RFC 6979: 결정론적 ECDSA](https://tools.ietf.org/html/rfc6979)
- [Keccak SHA-3](https://keccak.team/keccak.html)
- [BouncyCastle 라이브러리](https://www.bouncycastle.org/)
- [elliptic.js 라이브러리](https://github.com/indutny/elliptic)

---

[← 메인](../../README.md) | [English →](../../en/crypto/README.md)
