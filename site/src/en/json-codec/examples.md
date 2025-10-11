# Examples

[← JSON Codec](README.md) | [API](api.md) | [Examples](examples.md)

---

## Overview

This document demonstrates practical usage patterns for the JSON codec, including configuration options, custom encoders/decoders, and real-world scenarios.

## Basic Product Encoding

```scala mdoc:silent
import org.sigilaris.core.codec.json.*

case class Person(name: String, age: Int) derives JsonCodec

val person = Person("Alice", 30)
```

```scala mdoc
// Encode
val json = JsonEncoder[Person].encode(person)

// Decode
JsonDecoder[Person].decode(json)
```

## Nested Structures

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

## Coproduct (Sealed Trait) Encoding

Sealed traits use **wrapped-by-type-key** discriminator:

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

// Wrapped discriminator format
JsonEncoder[PaymentMethod].encode(payment1)
JsonEncoder[PaymentMethod].encode(payment2)
```

## Field Naming Policies

### Snake Case

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class UserProfile(firstName: String, lastName: String, emailAddress: String)
  derives JsonCodec

val profile = UserProfile("Alice", "Smith", "alice@example.com")
```

```scala mdoc
// Default: Identity
JsonEncoder[UserProfile].encode(profile)

// To use SnakeCase, create encoder with config
// (In practice, you'd configure globally or via given instances)
val snakeConfig = JsonConfig.default.copy(
  fieldNaming = FieldNamingPolicy.SnakeCase
)
// With snakeConfig: { "first_name": "Alice", "last_name": "Smith", ... }
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

## Optional Fields and Null Handling

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
// With this config, None fields would be omitted from output
```

### Treat Absent as Null

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class PartialData(name: String, age: Option[Int]) derives JsonCodec
```

```scala mdoc
// Missing "age" field
val json = JsonValue.obj("name" -> JsonValue.JString("Alice"))

val treatAbsentConfig = JsonConfig.default.copy(treatAbsentAsNull = true)
// With this config, missing "age" decodes as None
val decs = JsonDecoder.configured(treatAbsentConfig)
import decs.given
JsonDecoder[PartialData].decode(json)
```

## Big Number Formatting

### BigInt as String

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

// Default: writeBigIntAsString = true
JsonEncoder[Transaction].encode(tx)
// Encodes as: { ..., "amount": "123456789012345678901234567890", ... }
```

### BigDecimal as Number

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Price(currency: String, amount: BigDecimal) derives JsonCodec
```

```scala mdoc
val price = Price("USD", BigDecimal("29.99"))

// Default: writeBigDecimalAsString = true
JsonEncoder[Price].encode(price)
// To encode as JSON number: writeBigDecimalAsString = false
```

Decoders accept **both** string and number representations for robustness.

## Collections

### Lists

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Playlist(name: String, songs: List[String]) derives JsonCodec
```

```scala mdoc
val playlist = Playlist("Favorites", List("Song A", "Song B", "Song C"))
JsonEncoder[Playlist].encode(playlist)
```

### Vectors

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Tags(values: Vector[String]) derives JsonCodec
```

```scala mdoc
val tags = Tags(Vector("scala", "json", "codec"))
JsonEncoder[Tags].encode(tags)
```

### Maps with String Keys

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Config(settings: Map[String, String]) derives JsonCodec
```

```scala mdoc
val config = Config(Map("theme" -> "dark", "language" -> "en"))
JsonEncoder[Config].encode(config)
```

### Maps with Non-String Keys

Requires `JsonKeyCodec[K]` instance:

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

Map keys are encoded as JSON object field names (strings).

## Custom Encoders and Decoders

### Using contramap (Encoder)

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

### Using map and emap (Decoder)

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

### Bidirectional Custom Codec

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

## Custom Discriminator Mapping

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

// Default: SimpleName discriminator
JsonEncoder[Event].encode(event1)
JsonEncoder[Event].encode(event2)

// Custom mapping
val customConfig = JsonConfig.default.copy(
  discriminator = DiscriminatorConfig(
    TypeNameStrategy.Custom(Map(
      "UserCreated" -> "user.created",
      "UserDeleted" -> "user.deleted",
      "UserUpdated" -> "user.updated"
    ))
  )
)
// With customConfig: { "user.created": { "userId": "user-1", ... } }
```

## Working with JsonParser and JsonPrinter

### Parse JSON String

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

val jsonString = """{"name":"Alice","age":30}"""
```

```scala mdoc
// Parse to JsonValue
val parsed = CirceJsonOps.parse(jsonString)
```

### Print JsonValue to String

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

val json = JsonValue.obj(
  "name" -> JsonValue.JString("Bob"),
  "age" -> JsonValue.JNumber(25)
)
```

```scala mdoc
// Print to JSON string
CirceJsonOps.print(json)
```

### Full Roundtrip

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

// JsonValue → String (via Circe)
val jsonString = CirceJsonOps.print(jsonValue)

// String → JsonValue (via Circe)
val reparsed = CirceJsonOps.parse(jsonString)

// JsonValue → Domain
reparsed.flatMap(JsonDecoder[Product].decode(_))
```

## Validation with emap

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

## Complex Example: API Response

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

[← JSON Codec](README.md) | [API](api.md) | [Examples](examples.md)
