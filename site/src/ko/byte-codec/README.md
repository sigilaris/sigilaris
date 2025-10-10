# 바이트 코덱 (Byte Codec)

[← 메인](../../README.md) | [English →](../../en/byte-codec/README.md)

---

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)

---

## 개요

Sigilaris 바이트 코덱은 블록체인 애플리케이션을 위해 설계된 deterministic 바이트 인코딩/디코딩 라이브러리입니다. Scala 데이터 구조를 트랜잭션 서명, 블록 해싱, 머클 트리 생성에 적합한 바이트열로 타입 안전하고 조합 가능하게 인코딩합니다.

**왜 deterministic인가?** 블록체인 시스템에서는 같은 데이터가 항상 같은 바이트열로 인코딩되어야 합니다. 이를 통해 서로 다른 노드와 플랫폼에서도 암호화 해시와 서명이 일관되게 유지됩니다.

**주요 특징:**
- **타입 안전 API**: Scala 3의 타입 시스템과 cats 타입클래스 활용
- **자동 derivation**: Product 타입(case class)은 자동으로 인코딩
- **Deterministic 컬렉션**: Set과 Map 요소는 인코딩된 바이트 기준으로 정렬
- **가변 길이 인코딩**: 공간 효율적인 표현 (RLP와 유사하지만 구별됨)
- **조합 가능**: `contramap`, `map`, `emap`을 사용하여 커스텀 인코더 생성 용이

## 빠른 시작 (30초)

```scala mdoc:silent
import org.sigilaris.core.codec.byte.*
import scodec.bits.ByteVector

// 간단한 인코딩과 디코딩
val value: Long = 42L
val bytes = ByteEncoder[Long].encode(value)
val decoded = ByteDecoder[Long].decode(bytes)
// 결과: Right(DecodeResult(42, ByteVector(empty)))

// 튜플도 자동으로 동작
val pair = (1L, 2L)
val pairBytes = ByteEncoder[(Long, Long)].encode(pair)
val decodedPair = ByteDecoder[(Long, Long)].decode(pairBytes)
// 결과: Right(DecodeResult((1,2), ByteVector(empty)))
```

이게 전부입니다! 코덱이 자동으로:
- 값을 deterministic하게 인코딩
- BigInt에 대해 공간 효율적인 가변 길이 인코딩 사용
- 타입 안전한 인코딩과 디코딩 제공
- Product 타입에 대한 자동 derivation 지원

## 문서

### 핵심 개념
- **[API 레퍼런스](api.md)**: ByteEncoder, ByteDecoder, ByteCodec trait와 메서드
- **[타입별 규칙](types.md)**: 타입별 상세 인코딩/디코딩 명세
- **[실전 예제](examples.md)**: 실제 블록체인 데이터 구조
- **[RLP 비교](rlp-comparison.md)**: Ethereum RLP와의 차이점

### 활용 사례
1. **트랜잭션 서명**: 개인키로 서명하기 전에 트랜잭션 데이터 인코딩
2. **블록 해싱**: 블록 헤더의 deterministic 바이트 표현 생성
3. **머클 트리**: 머클 증명 검증을 위한 일관된 해시 생성
4. **네트워크 프로토콜**: P2P 통신을 위한 메시지 직렬화

### 이 라이브러리가 하지 않는 것
- **암호화 해싱**: 별도 암호화 모듈 사용 (SHA-256, Keccak 등)
- **전자서명**: 별도 서명 모듈 사용 (ECDSA, EdDSA 등)
- **네트워크 전송**: 별도 네트워킹 레이어 사용 (TCP, HTTP 등)

이 라이브러리는 바이트 인코딩/디코딩에만 집중합니다. 완전한 블록체인 기능을 위해서는 다른 모듈과 조합하여 사용하세요.

## 타입 지원

코덱은 다음 타입에 대한 인스턴스를 제공합니다:

**기본 타입:**
- `Unit`, `Byte`, `Long`, `java.time.Instant`

**숫자 타입:**
- `BigInt` (효율적인 인코딩을 지원하는 부호 있는 정수)
- `BigNat` (자연수, 내부 사용)

**컬렉션:**
- `List[A]` (순서 유지)
- `Option[A]` (0개 또는 1개 요소 리스트로 인코딩)
- `Set[A]` (deterministic: 인코딩된 바이트 기준 정렬)
- `Map[K, V]` (deterministic: 정렬된 튜플 집합으로 인코딩)

**Product 타입:**
- `Tuple2`, `Tuple3`, ... (자동 derivation)
- Case class (자동 derivation via `Mirror.ProductOf`)

**커스텀 타입:**
- `contramap`, `map`, `emap`을 사용하여 인스턴스 생성

## 에러 처리

디코딩은 `Either[String, (A, ByteVector)]`를 반환합니다:
- **Left(error)**: 에러 메시지와 함께 디코딩 실패
- **Right((value, remainder))**: `value` 디코딩 성공, `remainder`는 사용되지 않은 바이트

```scala mdoc:silent
// 예제: 불충분한 데이터 디코딩
val incomplete = ByteVector(0x01)
val result = ByteDecoder[Long].decode(incomplete)
// 결과: Left("Insufficient bytes for BigNat data: needed 1, got 0")
```

## 성능 특성

- **공간 효율적**: 작은 정수(0-128)는 단일 바이트로 인코딩
- **시간 복잡도**: 인코딩과 디코딩 모두 O(n), 여기서 n은 데이터 크기
- **Deterministic 정렬**: Set/Map 정렬은 O(n log n), 여기서 n은 요소 개수
- **스택 안전**: 큰 컬렉션에 대해 cats-effect의 스택 안전 재귀 사용

## 다음 단계

- [API 레퍼런스](api.md)에서 상세한 trait 문서 읽기
- [타입별 규칙](types.md)에서 인코딩 명세 이해하기
- [실전 예제](examples.md)에서 실제 사용 패턴 확인하기
- Ethereum에 익숙하다면 [RLP 비교](rlp-comparison.md) 참고하기

---

[← 메인](../../README.md) | [English →](../../en/byte-codec/README.md)
