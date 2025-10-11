# 데이터 타입

[← 메인](../../README.md) | [English →](../../en/datatype/README.md)

---

## 개요

Sigilaris datatype 모듈은 블록체인 기본 자료형을 위한 타입 안전한 불투명 타입(opaque type)을 제공하며, 내장 코덱 지원을 포함합니다. 이러한 타입들은 런타임 오버헤드 없이 컴파일 타임에 정확성을 보장합니다.

### 왜 특화된 데이터 타입이 필요한가?

블록체인 애플리케이션에서:
- **고정 크기 정수**: 해시 값, 주소는 256비트 부호 없는 정수가 필요
- **자연수**: 토큰 양, 카운터는 음수가 아니어야 함
- **UTF-8 문자열**: 계정 이름, 메타데이터는 길이 접두사 직렬화가 필요
- **타입 안전성**: 호환되지 않는 숫자 타입 혼용을 컴파일 타임에 방지

### 주요 특징

- **불투명 타입**: 컴파일 타임 안전성을 갖춘 제로 비용 추상화
- **코덱 통합**: 자동 바이트 및 JSON 인코딩/디코딩
- **타입화된 실패**: `Either`를 통한 ADT 기반 에러 처리
- **검증된 생성**: 적절한 유효성 검사를 갖춘 안전한 생성자

## 빠른 시작 (30초)

```scala mdoc
import org.sigilaris.core.datatype.*
import scodec.bits.ByteVector

// 256비트 부호 없는 정수
val hash = UInt256.fromHex("cafe").toOption.get

// 음이 아닌 임의 정밀도 정수
val amount = BigNat.unsafeFromLong(1000L)

// 길이 접두사 UTF-8 문자열
val label = Utf8("account-1")
```

이게 전부입니다! 이러한 타입들은 바이트 및 JSON 코덱과 원활하게 작동합니다.

## 데이터 타입

### BigNat - 음이 아닌 임의 정밀도 정수

임의 정밀도를 가진 자연수(≥ 0):

```scala mdoc:reset
import org.sigilaris.core.datatype.*

val n1 = BigNat.unsafeFromLong(42L)
val n2 = BigNat.unsafeFromLong(10L)

// 안전한 산술 연산
val sum = BigNat.add(n1, n2)  // 52
val product = BigNat.multiply(n1, n2)  // 420

// 뺄셈은 Either를 반환
val diff = BigNat.tryToSubtract(n1, n2)  // Right(32)
val invalid = BigNat.tryToSubtract(n2, n1)  // Left("...")
```

**주요 특징:**
- 산술 연산이 음이 아닌 값 유지
- 가변 길이 바이트 인코딩
- JSON 문자열/숫자 지원

### UInt256 - 256비트 부호 없는 정수

빅엔디안 표현의 고정 크기 256비트 부호 없는 정수:

```scala mdoc:reset
import org.sigilaris.core.datatype.*
import scodec.bits.ByteVector

// 16진수 문자열로부터
val u1 = UInt256.fromHex("ff").toOption.get
val u2 = UInt256.fromHex("0x123abc").toOption.get

// BigInt로부터
val u3 = UInt256.fromBigIntUnsigned(BigInt(42)).toOption.get

// 변환
val bigInt: BigInt = u1.toBigIntUnsigned
val hex: String = u1.toHexLower  // 소문자 16진수, 0x 접두사 없음
```

**주요 특징:**
- 고정 32바이트 표현
- 자동 왼쪽 패딩
- 16진수 문자열 지원 (`0x` 접두사 있거나 없거나)
- 고정 크기 바이트 코덱

### Utf8 - 길이 접두사 UTF-8 문자열

길이 접두사 바이트 인코딩을 가진 UTF-8 문자열:

```scala mdoc:reset
import org.sigilaris.core.datatype.*

val text = Utf8("Hello, 世界!")

// 문자열 변환
val str: String = text.asString

// Map 키로 사용 가능
val map = Map(Utf8("key1") -> 42, Utf8("key2") -> 100)
```

**주요 특징:**
- 길이 접두사 바이트 인코딩: `[크기: BigNat][UTF-8 바이트]`
- JSON 문자열 인코딩
- JSON 키 코덱 지원
- 디코드 시 UTF-8 유효성 검사

## 코덱 통합

모든 타입은 내장 바이트 및 JSON 코덱을 가집니다:

```scala mdoc:reset
import org.sigilaris.core.datatype.*
import org.sigilaris.core.codec.byte.{ByteEncoder, ByteDecoder}
import org.sigilaris.core.codec.json.{JsonEncoder, JsonDecoder}
import scodec.bits.ByteVector

val num = BigNat.unsafeFromLong(42L)

// 바이트 인코딩
val bytes = ByteEncoder[BigNat].encode(num)
val decoded = ByteDecoder[BigNat].decode(bytes)

// JSON 인코딩
val json = JsonEncoder[BigNat].encode(num)
val fromJson = JsonDecoder[BigNat].decode(json)
```

## 타입 안전성 예제

### 유효하지 않은 값 방지

```scala mdoc:reset
import org.sigilaris.core.datatype.*
import scodec.bits.ByteVector

// BigNat: 음이 아닌 값의 컴파일 타임 보장
val valid = BigNat.fromBigInt(BigInt(100))  // Right(BigNat(100))
val invalid = BigNat.fromBigInt(BigInt(-1))  // Left("Constraint failed...")

// UInt256: 검증 오류에 대한 타입화된 실패
val overflow = UInt256.fromBigIntUnsigned(BigInt(2).pow(256))
// Left(UInt256Overflow("..."))

val tooLong = UInt256.fromBytesBE(ByteVector.fill(33)(0xff.toByte))
// Left(UInt256TooLong(33, 32))
```

### 안전한 산술 연산

```scala mdoc:reset
import org.sigilaris.core.datatype.*

val a = BigNat.unsafeFromLong(10L)
val b = BigNat.unsafeFromLong(3L)

// 항상 성공하는 연산
BigNat.add(a, b)       // 13
BigNat.multiply(a, b)  // 30
BigNat.divide(a, b)    // 3

// 실패할 수 있는 연산
BigNat.tryToSubtract(a, b)  // Right(7)
BigNat.tryToSubtract(b, a)  // Left("Constraint failed...")
```

## 설계 철학

### 불투명 타입
- 런타임 오버헤드 없음
- 컴파일 타임 타입 안전성
- 호환되지 않는 타입 혼용 방지

### 검증된 생성
- 안전한 생성자는 `Either` 반환
- 알려진 유효 데이터를 위한 unsafe 생성자
- ADT를 통한 명확한 실패 타입

### 코덱 우선 설계
- 모든 타입이 바이트/JSON 코덱 보유
- 결정적 직렬화
- 왕복 속성 테스트됨

## 다음 단계

1. **BigNat**: 임의 정밀도 자연수
2. **UInt256**: 해시/주소를 위한 고정 크기 부호 없는 정수
3. **Utf8**: 메타데이터를 위한 길이 접두사 문자열

---

[← 메인](../../README.md) | [English →](../../en/datatype/README.md)
