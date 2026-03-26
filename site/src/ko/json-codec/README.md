# JSON 코덱 (JSON Codec)

[← 메인](../../README.md) | [English →](../../en/json-codec/README.md)

[API](api.md) | [예제](examples.md)

---

## 개요

Sigilaris JSON 코덱은 Scala 데이터 구조를 JSON으로 인코딩/디코딩하는 경량의 라이브러리 독립적 추상화를 제공합니다. JSON은 해시값 계산과 관련된 크리티컬한 도메인이 아니라서(바이트 코덱과 달리), 외부 라이브러리를 어느 정도 의존해도 괜찮지만, 쉽게 외부 라이브러리를 바꿔치기할 수 있도록 하기 위해 최소한의 `JsonValue` ADT를 중간 표현으로 도입했습니다.

### 왜 커스텀 JSON 추상화인가?

- **라이브러리 독립성**: 도메인 로직을 한 번만 정의하고, 백엔드 라이브러리(Circe, Play JSON 등)를 도메인 코드 수정 없이 교체 가능
- **타입 안전 API**: Scala 3 + cats 생태계 기반, 펑터 연산(`contramap`, `map`, `emap`) 제공
- **설정 가능한 derivation**: case class와 sealed trait에 대해 자동 derivation, 커스터마이징 가능한 네이밍 및 discriminator 전략
- **최소한의 인터페이스**: 6개 케이스로 모든 JSON 타입을 커버하는 작은 ADT

### 주요 특징

- **라이브러리 독립적 코어**: `JsonValue` ADT로 인코딩/디코딩 후, 백엔드 라이브러리 포맷으로 변환
- **자동 Derivation**: Scala 3 mirror를 통해 case class와 sealed trait 자동 지원
- **유연한 설정**: 필드 네이밍 정책(snake_case, kebab-case, camelCase), null 처리, discriminator 전략
- **Wrapped Discriminator**: Coproduct는 `{ "TypeName": { ...fields... } }` 형태로 깔끔하게 타입 구분

## 빠른 시작 (30초)

```scala mdoc:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

case class User(name: String, age: Int) derives JsonCodec

val user = User("Alice", 30)

// JsonValue로 인코딩
val jsonValue = JsonEncoder[User].encode(user)

// JSON 문자열로 출력 (Circe 백엔드 사용)
val jsonString = CirceJsonOps.print(jsonValue)

// JSON 문자열에서 파싱
val parsed = CirceJsonOps.parse(jsonString)
```

```scala mdoc
// User로 디코딩
parsed.flatMap(JsonDecoder[User].decode(_))
```

이게 전부입니다! 코덱이 자동으로 인스턴스를 derivation하고, import를 변경하는 것만으로 JSON 백엔드를 교체할 수 있습니다.

## 문서

- **[API 레퍼런스](api.md)**: `JsonEncoder`, `JsonDecoder`, `JsonCodec`, `JsonConfig` 상세 설명
- **[예제](examples.md)**: 설정 옵션을 포함한 실전 사용법

## 포함된 내용

### 코어 ADT

`JsonValue` enum은 6개 케이스로 구성:
- `JNull`: JSON null
- `JBool(Boolean)`: JSON boolean
- `JNumber(BigDecimal)`: JSON number
- `JString(String)`: JSON string
- `JArray(Vector[JsonValue])`: JSON array
- `JObject(Map[String, JsonValue])`: JSON object

### 타입클래스

- `JsonEncoder[A]`: `A`를 `JsonValue`로 인코딩
- `JsonDecoder[A]`: `JsonValue`를 `A`로 디코딩
- `JsonCodec[A]`: 양방향 인코딩/디코딩
- `JsonKeyCodec[A]`: Map 키를 JSON 문자열로 인코딩/디코딩

### 백엔드 통합

- **Ops 인터페이스**: 문자열 ↔ `JsonValue` 변환을 위한 `JsonParser`와 `JsonPrinter`
- **Circe 백엔드**: Circe JSON 라이브러리를 위한 내장 어댑터
- **확장 가능**: 다른 라이브러리(Play JSON, uPickle 등)를 위한 ops 구현 가능

### 자동 Derivation

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Account(id: String, balance: BigDecimal) derives JsonCodec

sealed trait Status derives JsonCodec
case object Active extends Status
case object Inactive extends Status

case class UserAccount(
  username: String,
  account: Account,
  status: Status
) derives JsonCodec
```

```scala mdoc
val ua = UserAccount("alice", Account("acc-1", BigDecimal(100)), Active)
JsonEncoder[UserAccount].encode(ua)
```

## 설정 (Configuration)

`JsonConfig`로 인코딩/디코딩 동작 제어:

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

val config = JsonConfig(
  fieldNaming = FieldNamingPolicy.SnakeCase,      // firstName → first_name
  dropNullValues = true,                          // null 필드 생략
  treatAbsentAsNull = true,                       // 결측 → null (Option용)
  writeBigIntAsString = true,                     // BigInt → "123"
  writeBigDecimalAsString = false,                // BigDecimal → 123.45
  discriminator = DiscriminatorConfig(
    TypeNameStrategy.SimpleName                   // { "TypeName": {...} }
  )
)
```

### 필드 네이밍 정책

- `Identity`: 그대로 유지
- `SnakeCase`: `firstName` → `first_name`
- `KebabCase`: `firstName` → `first-name`
- `CamelCase`: `FirstName` → `firstName`

### Discriminator 전략

Coproduct(sealed trait)는 **wrapped-by-type-key** 인코딩 사용:

```scala
sealed trait Color
case object Red extends Color
case object Blue extends Color

// 인코딩 결과: { "Red": {} } 또는 { "Blue": {} }
```

타입명 전략:
- `SimpleName`: case class/object 이름 사용
- `FullyQualified`: 전체 패키지 경로 사용 (현재는 simple로 폴백)
- `Custom(Map[String, String])`: 커스텀 매핑 제공

## 설계 철학

### 관심사의 분리

바이트 코덱(deterministic 해싱에 크리티컬)과 달리 JSON 인코딩은:
- 해시 계산을 위한 **deterministic 보장이 불필요**
- 외부 라이브러리의 유연한 사용 가능
- API 사용성과 라이브러리 독립성에 집중

아키텍처는 다음을 보장합니다:
- **Domain → JsonValue**: 타입 안전, 라이브러리 독립적 인코딩
- **JsonValue ↔ Backend**: 최소한의 변환 레이어 (예: Circe)
- **쉬운 라이브러리 교체**: 도메인 코드 수정 없이 백엔드 변경

### 최소 의존성

코어 `JsonValue` ADT는 외부 JSON 라이브러리에 대한 의존성이 전혀 없습니다. 백엔드 어댑터는 별도 모듈이므로 다음이 쉽습니다:
- 새로운 라이브러리 지원 추가
- 사용하지 않는 백엔드 제거
- 라이브러리 오버헤드 없이 인코딩 로직 테스트

## 예제: API 응답 인코딩

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import java.time.Instant

case class Product(id: String, name: String, price: BigDecimal)
  derives JsonCodec

case class ApiResponse(
  data: Product,
  timestamp: Instant,
  status: String
) derives JsonCodec

val response = ApiResponse(
  data = Product("p-1", "Widget", BigDecimal("29.99")),
  timestamp = Instant.parse("2025-01-15T10:30:00Z"),
  status = "success"
)
```

```scala mdoc
val json = JsonEncoder[ApiResponse].encode(response)
```

## 다음 단계

1. **[API 레퍼런스](api.md)**: `contramap`, `emap`, 조합자 사용법 학습
2. **[예제](examples.md)**: 설정 옵션과 고급 패턴 확인

## 한계 및 범위

- **해싱용 Deterministic 보장 없음**: 블록체인/암호화 해싱에는 바이트 코덱 사용
- **스키마 검증 없음**: 디코더는 구조만 검증, 비즈니스 규칙은 `emap`으로 검증 필요
- **문자열 파싱/출력에는 백엔드 필요**: `JsonValue` 단독으로는 JSON 문자열 파싱/출력 불가 (`JsonParser`/`JsonPrinter` 사용)

## 성능 특성

- **인코딩**: O(n), n은 데이터 구조 크기
- **디코딩**: O(n), 에러 발생 시 조기 종료
- **Product/Coproduct Derivation**: 컴파일 타임, 런타임 오버헤드 없음
- **백엔드 변환**: AST 변환의 최소한의 오버헤드

---

[← 메인](../../README.md) | [English →](../../en/json-codec/README.md)

[API](api.md) | [예제](examples.md)
