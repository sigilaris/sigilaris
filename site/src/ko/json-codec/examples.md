# 예제

[← JSON 코덱](README.md) | [API](api.md) | [예제](examples.md)

---

## 개요

이 문서는 설정 옵션, 커스텀 인코더/디코더, 실제 시나리오를 포함한 JSON 코덱의 실전 사용 패턴을 보여줍니다.

## 기본 Product 인코딩

```scala mdoc:silent
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

## 중첩 구조

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Address(street: String, city: String, zipCode: String)
  derives JsonCodec

case class Company(name: String, address: Address)
  derives JsonCodec

case class Employee(
  id: String,
  name: String,
  company: Company,
  salary: BigDecimal
) derives JsonCodec
```

```scala mdoc
val employee = Employee(
  id = "emp-001",
  name = "Bob Smith",
  company = Company("Acme Inc", Address("123 Main St", "NYC", "10001")),
  salary = BigDecimal("75000.00")
)

JsonEncoder[Employee].encode(employee)
```

## Coproduct (Sealed Trait) 인코딩

Sealed trait는 **wrapped-by-type-key** discriminator를 사용합니다:

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

sealed trait PaymentMethod derives JsonCodec
case class CreditCard(number: String, expiry: String) extends PaymentMethod
case class BankTransfer(accountNumber: String, routingNumber: String)
  extends PaymentMethod
case object Cash extends PaymentMethod
```

```scala mdoc
val payment1: PaymentMethod = CreditCard("1234-5678", "12/25")
val payment2: PaymentMethod = Cash

// Wrapped discriminator 포맷
JsonEncoder[PaymentMethod].encode(payment1)
JsonEncoder[PaymentMethod].encode(payment2)
```

## 필드 네이밍 정책

### Snake Case

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class UserProfile(firstName: String, lastName: String, emailAddress: String)
  derives JsonCodec

val profile = UserProfile("Alice", "Smith", "alice@example.com")
```

```scala mdoc
// 기본: Identity
JsonEncoder[UserProfile].encode(profile)

// SnakeCase를 사용하려면 config로 encoder 생성
// (실전에서는 전역으로 설정하거나 given 인스턴스로 제공)
val snakeConfig = JsonConfig.default.copy(
  fieldNaming = FieldNamingPolicy.SnakeCase
)
// snakeConfig 사용 시: { "first_name": "Alice", "last_name": "Smith", ... }
```

### Kebab Case

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

val kebabConfig = JsonConfig.default.copy(
  fieldNaming = FieldNamingPolicy.KebabCase
)
// firstName → first-name
```

### Camel Case

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class DataClass(FirstName: String, LastName: String)
  derives JsonCodec

val camelConfig = JsonConfig.default.copy(
  fieldNaming = FieldNamingPolicy.CamelCase
)
// FirstName → firstName
```

## Optional 필드와 Null 처리

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class UserData(
  name: String,
  email: Option[String],
  phone: Option[String]
) derives JsonCodec
```

```scala mdoc
val user1 = UserData("Alice", Some("alice@example.com"), None)
val user2 = UserData("Bob", None, Some("555-1234"))

JsonEncoder[UserData].encode(user1)
JsonEncoder[UserData].encode(user2)
```

### Drop Null Values

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

val dropNullConfig = JsonConfig.default.copy(dropNullValues = true)
// 이 설정으로 None 필드는 출력에서 생략됩니다
```

### Treat Absent as Null

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class PartialData(name: String, age: Option[Int]) derives JsonCodec
```

```scala mdoc
// "age" 필드가 누락됨
val json = JsonValue.obj("name" -> JsonValue.JString("Alice"))

val treatAbsentConfig = JsonConfig.default.copy(treatAbsentAsNull = true)
// 이 설정으로 누락된 "age"는 None으로 디코딩됩니다
val decs = JsonDecoder.configured(treatAbsentConfig)
import decs.given
JsonDecoder[PartialData].decode(json)
```

## 큰 숫자 포맷팅

### BigInt를 문자열로

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Transaction(
  id: String,
  amount: BigInt,
  nonce: BigInt
) derives JsonCodec
```

```scala mdoc
val tx = Transaction(
  "tx-001",
  BigInt("123456789012345678901234567890"),
  BigInt("42")
)

// 기본: writeBigIntAsString = true
JsonEncoder[Transaction].encode(tx)
// 인코딩 결과: { ..., "amount": "123456789012345678901234567890", ... }
```

### BigDecimal을 숫자로

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Price(currency: String, amount: BigDecimal) derives JsonCodec
```

```scala mdoc
val price = Price("USD", BigDecimal("29.99"))

// 기본: writeBigDecimalAsString = true
JsonEncoder[Price].encode(price)
// JSON 숫자로 인코딩하려면: writeBigDecimalAsString = false
```

디코더는 견고성을 위해 문자열과 숫자 표현을 **모두** 수용합니다.

## 컬렉션

### List

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Playlist(name: String, songs: List[String]) derives JsonCodec
```

```scala mdoc
val playlist = Playlist("Favorites", List("Song A", "Song B", "Song C"))
JsonEncoder[Playlist].encode(playlist)
```

### Vector

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Tags(values: Vector[String]) derives JsonCodec
```

```scala mdoc
val tags = Tags(Vector("scala", "json", "codec"))
JsonEncoder[Tags].encode(tags)
```

### String 키를 가진 Map

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Config(settings: Map[String, String]) derives JsonCodec
```

```scala mdoc
val config = Config(Map("theme" -> "dark", "language" -> "en"))
JsonEncoder[Config].encode(config)
```

### String이 아닌 키를 가진 Map

`JsonKeyCodec[K]` 인스턴스가 필요합니다:

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import java.util.UUID

case class UserPermissions(permissions: Map[UUID, String])
  derives JsonCodec
```

```scala mdoc
val permissions = UserPermissions(Map(
  UUID.fromString("550e8400-e29b-41d4-a716-446655440000") -> "admin",
  UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8") -> "user"
))

JsonEncoder[UserPermissions].encode(permissions)
```

Map 키는 JSON object의 필드명(문자열)으로 인코딩됩니다.

## 커스텀 Encoder와 Decoder

### contramap 사용 (Encoder)

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

case class CustomDate(value: LocalDate)

given JsonEncoder[CustomDate] =
  JsonEncoder[String].contramap { cd =>
    cd.value.format(DateTimeFormatter.ISO_LOCAL_DATE)
  }
```

```scala mdoc
JsonEncoder[CustomDate].encode(CustomDate(LocalDate.of(2025, 1, 15)))
```

### map과 emap 사용 (Decoder)

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.Try

case class CustomDate(value: LocalDate)

given JsonDecoder[CustomDate] =
  JsonDecoder[String].emap { s =>
    Try(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE))
      .toEither
      .bimap(
        ex => DecodeFailure(s"Invalid date format: ${ex.getMessage}"),
        CustomDate(_)
      )
  }
```

```scala mdoc
val validDate = JsonValue.JString("2025-01-15")
val invalidDate = JsonValue.JString("not-a-date")

JsonDecoder[CustomDate].decode(validDate)
JsonDecoder[CustomDate].decode(invalidDate).isLeft
```

### 양방향 커스텀 Codec

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.Try

case class CustomDate(value: LocalDate)

object CustomDate:
  given JsonEncoder[CustomDate] with
    def encode(cd: CustomDate): JsonValue =
      JsonValue.JString(cd.value.format(DateTimeFormatter.ISO_LOCAL_DATE))

  given JsonDecoder[CustomDate] = new JsonDecoder[CustomDate]:
    def decode(json: JsonValue): Either[DecodeFailure, CustomDate] =
      json match
        case JsonValue.JString(s) =>
          Try(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE))
            .toEither
            .bimap(
              ex => DecodeFailure(s"Invalid date: ${ex.getMessage}"),
              CustomDate(_)
            )
        case _ =>
          DecodeFailure(s"Expected JString for CustomDate, got $json").asLeft

  given JsonCodec[CustomDate] = JsonCodec(summon[JsonEncoder[CustomDate]], summon[JsonDecoder[CustomDate]])
```

```scala mdoc
val date = CustomDate(LocalDate.of(2025, 1, 15))
val encoded = JsonEncoder[CustomDate].encode(date)
JsonDecoder[CustomDate].decode(encoded)
```

## 커스텀 Discriminator 매핑

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

sealed trait Event derives JsonCodec
case class UserCreated(userId: String, username: String) extends Event
case class UserDeleted(userId: String) extends Event
case class UserUpdated(userId: String, fields: Map[String, String]) extends Event
```

```scala mdoc
val event1: Event = UserCreated("user-1", "alice")
val event2: Event = UserDeleted("user-2")

// 기본: SimpleName discriminator
JsonEncoder[Event].encode(event1)
JsonEncoder[Event].encode(event2)

// 커스텀 매핑
val customConfig = JsonConfig.default.copy(
  discriminator = DiscriminatorConfig(
    TypeNameStrategy.Custom(Map(
      "UserCreated" -> "user.created",
      "UserDeleted" -> "user.deleted",
      "UserUpdated" -> "user.updated"
    ))
  )
)
// customConfig 사용 시: { "user.created": { "userId": "user-1", ... } }
```

## JsonParser와 JsonPrinter 사용하기

### JSON 문자열 파싱

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

val jsonString = """{"name":"Alice","age":30}"""
```

```scala mdoc
// JsonValue로 파싱
val parsed = CirceJsonOps.parse(jsonString)
```

### JsonValue를 문자열로 출력

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

val json = JsonValue.obj(
  "name" -> JsonValue.JString("Bob"),
  "age" -> JsonValue.JNumber(25)
)
```

```scala mdoc
// JSON 문자열로 출력
CirceJsonOps.print(json)
```

### 전체 라운드트립

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

case class Product(id: String, name: String, price: BigDecimal)
  derives JsonCodec

val product = Product("p-1", "Widget", BigDecimal("19.99"))
```

```scala mdoc
// Domain → JsonValue
val jsonValue = JsonEncoder[Product].encode(product)

// JsonValue → String (Circe 경유)
val jsonString = CirceJsonOps.print(jsonValue)

// String → JsonValue (Circe 경유)
val reparsed = CirceJsonOps.parse(jsonString)

// JsonValue → Domain
reparsed.flatMap(JsonDecoder[Product].decode(_))
```

## emap을 이용한 검증

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*

case class Email(value: String)

object Email:
  given JsonEncoder[Email] with
    def encode(email: Email): JsonValue =
      JsonValue.JString(email.value)

  given JsonDecoder[Email] = new JsonDecoder[Email]:
    def decode(json: JsonValue): Either[DecodeFailure, Email] =
      json match
        case JsonValue.JString(s) =>
          if s.contains("@") && s.contains(".") then
            Email(s).asRight
          else
            DecodeFailure(s"Invalid email format: $s").asLeft
        case _ =>
          DecodeFailure(s"Expected string for Email, got $json").asLeft

  given JsonCodec[Email] = JsonCodec(summon[JsonEncoder[Email]], summon[JsonDecoder[Email]])
```

```scala mdoc
val validEmail = JsonValue.JString("alice@example.com")
val invalidEmail = JsonValue.JString("not-an-email")

JsonDecoder[Email].decode(validEmail)
JsonDecoder[Email].decode(invalidEmail).isLeft
```

## 복잡한 예제: API 응답

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import java.time.Instant

sealed trait ApiStatus derives JsonCodec
case object Success extends ApiStatus
case object Error extends ApiStatus

case class Metadata(
  requestId: String,
  timestamp: Instant,
  status: ApiStatus
) derives JsonCodec

case class UserInfo(
  id: String,
  username: String,
  email: String
) derives JsonCodec

case class ApiResponse(
  data: Option[UserInfo],
  metadata: Metadata,
  errors: List[String]
) derives JsonCodec

val response = ApiResponse(
  data = Some(UserInfo("u-1", "alice", "alice@example.com")),
  metadata = Metadata(
    requestId = "req-123",
    timestamp = Instant.parse("2025-01-15T10:30:00Z"),
    status = Success
  ),
  errors = List()
)
```

```scala mdoc
JsonEncoder[ApiResponse].encode(response)
```

---

[← JSON 코덱](README.md) | [API](api.md) | [예제](examples.md)
