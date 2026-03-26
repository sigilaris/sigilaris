# API 레퍼런스

[← JSON 코덱](README.md) | [API](api.md) | [예제](examples.md)

---

## 개요

이 문서는 JSON 코덱 타입클래스에 대한 상세한 API 레퍼런스를 제공합니다: `JsonEncoder`, `JsonDecoder`, `JsonCodec`, `JsonKeyCodec`, 그리고 설정 타입들.

## JsonValue

코어 JSON AST - 최소한의 라이브러리 독립적 JSON 값 표현.

### 케이스

```scala
enum JsonValue:
  case JNull
  case JBool(value: Boolean)
  case JNumber(value: BigDecimal)
  case JString(value: String)
  case JArray(values: Vector[JsonValue])
  case JObject(fields: Map[String, JsonValue])
```

### 생성자

```scala mdoc:silent
import org.sigilaris.core.codec.json.*
import JsonValue.*
```

```scala mdoc
// Object 생성자
obj("name" -> JString("Alice"), "age" -> JNumber(30))

// Array 생성자
arr(JNumber(1), JNumber(2), JNumber(3))

// Null 별칭
nullValue
```

## JsonEncoder

`JsonEncoder[A]`는 Scala 값을 `JsonValue`로 인코딩하는 contravariant 타입클래스입니다.

### 핵심 메서드

#### encode

```scala
def encode(value: A): JsonValue
```

값을 JSON AST로 인코딩합니다.

**예제:**
```scala mdoc:silent
val encoder = JsonEncoder[Int]
```

```scala mdoc
encoder.encode(42)
```

### 조합자 (Combinators)

#### contramap

```scala
def contramap[B](f: B => A): JsonEncoder[B]
```

인코딩하기 전에 함수를 적용하여 새로운 인코더를 생성합니다 (contravariant functor).

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class UserId(value: String)

given JsonEncoder[UserId] = JsonEncoder[String].contramap(_.value)
```

```scala mdoc
JsonEncoder[UserId].encode(UserId("user-123"))
```

**사용 사례:** 커스텀 타입을 인코딩 가능한 타입으로 변환.

## JsonDecoder

`JsonDecoder[A]`는 `JsonValue`를 Scala 값으로 디코딩하는 covariant 타입클래스입니다.

### 핵심 메서드

#### decode

```scala
def decode(json: JsonValue): Either[DecodeFailure, A]
```

JSON을 값으로 디코딩하여, 실패 또는 디코딩된 값을 반환합니다.

**예제:**
```scala mdoc:silent
import org.sigilaris.core.failure.DecodeFailure

val decoder = JsonDecoder[Int]
val json = JsonValue.JNumber(42)
```

```scala mdoc
decoder.decode(json)
```

### 조합자 (Combinators)

#### map

```scala
def map[B](f: A => B): JsonDecoder[B]
```

디코딩된 값을 변환합니다 (covariant functor).

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class UserId(value: String)

given JsonDecoder[UserId] = JsonDecoder[String].map(UserId(_))
```

```scala mdoc
val json = JsonValue.JString("user-123")
JsonDecoder[UserId].decode(json)
```

#### emap

```scala
def emap[B](f: A => Either[DecodeFailure, B]): JsonDecoder[B]
```

검증과 함께 디코딩된 값을 변환합니다.

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

case class PositiveInt(value: Int)

given JsonDecoder[PositiveInt] = JsonDecoder[Int].emap { n =>
  if n > 0 then PositiveInt(n).asRight
  else DecodeFailure(s"Value $n must be positive").asLeft
}
```

```scala mdoc
val validJson = JsonValue.JNumber(10)
val invalidJson = JsonValue.JNumber(-5)

JsonDecoder[PositiveInt].decode(validJson)
JsonDecoder[PositiveInt].decode(invalidJson).isLeft
```

**사용 사례:** 디코딩 중 비즈니스 규칙 검증 추가.

## JsonCodec

`JsonCodec[A]`는 `JsonEncoder[A]`와 `JsonDecoder[A]`를 모두 결합합니다.

```scala
trait JsonCodec[A] extends JsonEncoder[A] with JsonDecoder[A]
```

### 사용법

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Person(name: String, age: Int) derives JsonCodec

val person = Person("Alice", 30)
```

```scala mdoc
// 인코딩
val json = JsonEncoder[Person].encode(person)

// 디코딩
JsonDecoder[Person].decode(json)
```

### 자동 Derivation

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

// Product (case class)
case class Address(street: String, city: String) derives JsonCodec

// Coproduct (sealed trait)
sealed trait Status derives JsonCodec
case object Active extends Status
case object Inactive extends Status

// 중첩
case class User(name: String, address: Address, status: Status)
  derives JsonCodec
```

```scala mdoc
val user = User("Bob", Address("Main St", "NYC"), Active)
JsonEncoder[User].encode(user)
```

## JsonKeyCodec

`JsonKeyCodec[A]`는 Map 키(JSON object의 문자열)를 인코딩/디코딩합니다.

### 핵심 메서드

```scala
trait JsonKeyCodec[A]:
  def encode(value: A): String
  def decode(key: String): Either[DecodeFailure, A]
```

### 내장 인스턴스

- `String`: Identity 인코딩
- `Int`, `Long`, `BigInt`: 숫자 문자열 표현
- `UUID`: 표준 UUID 문자열 형식

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import java.util.UUID

val map = Map(
  UUID.fromString("550e8400-e29b-41d4-a716-446655440000") -> "value1",
  UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8") -> "value2"
)
```

```scala mdoc
val json = JsonEncoder[Map[UUID, String]].encode(map)
```

Map 키는 JSON object의 필드명(문자열)으로 인코딩됩니다.

## JsonConfig

인코딩/디코딩 동작을 제어하는 설정.

### 필드

```scala
case class JsonConfig(
  fieldNaming: FieldNamingPolicy,
  dropNullValues: Boolean,
  treatAbsentAsNull: Boolean,
  writeBigIntAsString: Boolean,
  writeBigDecimalAsString: Boolean,
  discriminator: DiscriminatorConfig
)
```

### 기본 설정

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
```

```scala mdoc
JsonConfig.default
```

### 필드 네이밍 정책

```scala
enum FieldNamingPolicy:
  case Identity      // 그대로 유지
  case SnakeCase     // firstName → first_name
  case KebabCase     // firstName → first-name
  case CamelCase     // FirstName → firstName
```

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class UserProfile(firstName: String, lastName: String)
  derives JsonCodec

val profile = UserProfile("Alice", "Smith")
```

```scala mdoc
// Identity (기본값)
JsonEncoder[UserProfile].encode(profile)

// Snake case
val snakeConfig = JsonConfig.default.copy(fieldNaming = FieldNamingPolicy.SnakeCase)
// 참고: Encoder는 전역 설정 사용; 프로덕션에서는 config를 명시적으로 전달
```

### Null 처리

#### dropNullValues

`true`이면 인코딩된 object에서 null 값을 생략합니다.

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class OptionalData(required: String, optional: Option[String])
  derives JsonCodec
```

```scala mdoc
val withNull = OptionalData("value", None)
JsonEncoder[OptionalData].encode(withNull)
// dropNullValues=true일 때, "optional" 필드는 생략됩니다
```

#### treatAbsentAsNull

`true`이면 누락된 필드는 `Option[A]`에 대해 `null`로 디코딩됩니다.

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class PartialData(name: String, age: Option[Int]) derives JsonCodec
```

```scala mdoc
// "age" 필드가 누락됨
val json = JsonValue.obj("name" -> JsonValue.JString("Alice"))

// treatAbsentAsNull=true일 때, age는 None으로 디코딩됩니다
JsonDecoder[PartialData].decode(json)
```

### 숫자 포맷팅

#### writeBigIntAsString / writeBigDecimalAsString

큰 숫자를 JSON 문자열 또는 숫자로 인코딩할지 제어합니다.

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Amounts(bigInt: BigInt, bigDecimal: BigDecimal)
  derives JsonCodec
```

```scala mdoc
val amounts = Amounts(BigInt("123456789012345678901234567890"), BigDecimal("123.456"))
JsonEncoder[Amounts].encode(amounts)

// writeBigIntAsString=true: { "bigInt": "123456789012345678901234567890", ... }
// 디코더는 문자열과 숫자 표현 모두 수용
```

### Discriminator 설정

Coproduct(sealed trait) 인코딩 전략을 제어합니다.

```scala
case class DiscriminatorConfig(
  typeNameStrategy: TypeNameStrategy
)

enum TypeNameStrategy:
  case SimpleName                        // case class/object 이름 사용
  case FullyQualified                    // 전체 패키지 경로 사용
  case Custom(mapping: Map[String, String])  // 커스텀 매핑
```

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

sealed trait Event derives JsonCodec
case class UserCreated(userId: String) extends Event
case class UserDeleted(userId: String) extends Event
```

```scala mdoc
val event: Event = UserCreated("user-1")
JsonEncoder[Event].encode(event)
// 인코딩 결과: { "UserCreated": { "userId": "user-1" } }
```

**커스텀 타입명:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

val customConfig = JsonConfig.default.copy(
  discriminator = DiscriminatorConfig(
    TypeNameStrategy.Custom(Map(
      "UserCreated" -> "user.created",
      "UserDeleted" -> "user.deleted"
    ))
  )
)
// 인코딩 결과: { "user.created": { "userId": "user-1" } }
```

## JsonParser와 JsonPrinter

문자열 ↔ `JsonValue` 변환을 위한 백엔드 독립적 인터페이스.

```scala
trait JsonParser[BackendJson]:
  def parse(input: String): Either[ParseFailure, JsonValue]

trait JsonPrinter[BackendJson]:
  def print(json: JsonValue): String
```

### Circe 백엔드

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

val jsonString = """{"name":"Alice","age":30}"""
```

```scala mdoc
// 문자열 → JsonValue 파싱
val parsed = CirceJsonOps.parse(jsonString)

// JsonValue → 문자열 출력
parsed.map(CirceJsonOps.print)
```

## 에러 처리

### DecodeFailure

디코딩 실패는 `DecodeFailure`로 표현됩니다:

```scala
case class DecodeFailure(message: String) extends SigilarisFailure
```

일반적인 실패 시나리오:
- **타입 불일치**: 예상된 타입이 JSON 구조와 일치하지 않음
- **필드 누락**: 필수 필드가 JSON object에 없음
- **검증 실패**: 값은 디코딩되었지만 `emap` 검증 실패
- **알 수 없는 서브타입**: Coproduct discriminator가 알려진 타입과 일치하지 않음

**예제:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
```

```scala mdoc
// 타입 불일치
JsonDecoder[Int].decode(JsonValue.JString("not a number"))

// 필드 누락
case class Required(name: String) derives JsonCodec
JsonDecoder[Required].decode(JsonValue.obj())
```

## 모범 사례

### 1. Encoder에 contramap 사용

```scala
case class Timestamp(millis: Long)
given JsonEncoder[Timestamp] = JsonEncoder[Long].contramap(_.millis)
```

### 2. 검증에 emap 사용

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

case class Email(value: String)

given JsonDecoder[Email] = JsonDecoder[String].emap { s =>
  if s.contains("@") then Email(s).asRight
  else DecodeFailure("Invalid email format").asLeft
}
```

### 3. 자동 Derivation 활용

```scala
case class Account(id: String, balance: BigDecimal) derives JsonCodec
// 인스턴스 자동으로 사용 가능
```

### 4. 커스텀 설정

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

val apiConfig = JsonConfig.default.copy(
  fieldNaming = FieldNamingPolicy.SnakeCase,
  dropNullValues = true,
  writeBigDecimalAsString = false
)

// 디코딩 시 설정 명시적으로 사용
case class ApiData(userName: String, accountBalance: BigDecimal)
  derives JsonCodec

val json = JsonValue.obj(
  "user_name" -> JsonValue.JString("alice"),
  "account_balance" -> JsonValue.JNumber(100.50)
)

// 구성된 givens로 config 전달
// val decs = JsonDecoder.configured(apiConfig); import decs.given; summon[JsonDecoder[ApiData]].decode(json)
```

## 성능 특성

- **인코딩**: O(n), n은 데이터 구조 크기
- **디코딩**: O(n), 실패 시 조기 종료
- **Derivation**: 컴파일 타임, 런타임 오버헤드 없음
- **백엔드 변환**: AST 변환의 최소한의 오버헤드

## 참고

- [예제](examples.md): 실전 사용 패턴

---

[← JSON 코덱](README.md) | [API](api.md) | [예제](examples.md)
