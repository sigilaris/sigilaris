# API Reference

[← JSON Codec](README.md) | [API](api.md) | [Examples](examples.md)

---

## Overview

This document provides detailed API reference for the JSON codec type classes: `JsonEncoder`, `JsonDecoder`, `JsonCodec`, `JsonKeyCodec`, and configuration types.

## JsonValue

The core JSON AST - a minimal, library-agnostic representation of JSON values.

### Cases

```scala
enum JsonValue:
  case JNull
  case JBool(value: Boolean)
  case JNumber(value: BigDecimal)
  case JString(value: String)
  case JArray(values: Vector[JsonValue])
  case JObject(fields: Map[String, JsonValue])
```

### Constructors

```scala mdoc:silent
import org.sigilaris.core.codec.json.*
import JsonValue.*
```

```scala mdoc
// Object constructor
obj("name" -> JString("Alice"), "age" -> JNumber(30))

// Array constructor
arr(JNumber(1), JNumber(2), JNumber(3))

// Null alias
nullValue
```

## JsonEncoder

`JsonEncoder[A]` is a contravariant type class for encoding Scala values to `JsonValue`.

### Core Methods

#### encode

```scala
def encode(value: A): JsonValue
```

Encodes a value to JSON AST.

**Example:**
```scala mdoc:silent
val encoder = JsonEncoder[Int]
```

```scala mdoc
encoder.encode(42)
```

### Combinators

#### contramap

```scala
def contramap[B](f: B => A): JsonEncoder[B]
```

Creates a new encoder by applying a function before encoding (contravariant functor).

**Example:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class UserId(value: String)

given JsonEncoder[UserId] = JsonEncoder[String].contramap(_.value)
```

```scala mdoc
JsonEncoder[UserId].encode(UserId("user-123"))
```

**Use Case:** Transform custom types to encodable types.

## JsonDecoder

`JsonDecoder[A]` is a covariant type class for decoding `JsonValue` to Scala values.

### Core Methods

#### decode

```scala
def decode(json: JsonValue): Either[DecodeFailure, A]
```

Decodes JSON to a value, returning either a failure or the decoded value.

**Example:**
```scala mdoc:silent
import org.sigilaris.core.failure.DecodeFailure

val decoder = JsonDecoder[Int]
val json = JsonValue.JNumber(42)
```

```scala mdoc
decoder.decode(json)
```

### Combinators

#### map

```scala
def map[B](f: A => B): JsonDecoder[B]
```

Transforms the decoded value (covariant functor).

**Example:**
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

Transforms the decoded value with validation.

**Example:**
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

**Use Case:** Add business rule validation during decoding.

## JsonCodec

`JsonCodec[A]` combines both `JsonEncoder[A]` and `JsonDecoder[A]`.

```scala
trait JsonCodec[A] extends JsonEncoder[A] with JsonDecoder[A]
```

### Usage

```scala mdoc:reset:silent
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

### Automatic Derivation

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

// Products (case classes)
case class Address(street: String, city: String) derives JsonCodec

// Coproducts (sealed traits)
sealed trait Status derives JsonCodec
case object Active extends Status
case object Inactive extends Status

// Nested
case class User(name: String, address: Address, status: Status)
  derives JsonCodec
```

```scala mdoc
val user = User("Bob", Address("Main St", "NYC"), Active)
JsonEncoder[User].encode(user)
```

## JsonKeyCodec

`JsonKeyCodec[A]` handles encoding/decoding of map keys (strings in JSON objects).

### Core Methods

```scala
trait JsonKeyCodec[A]:
  def encode(value: A): String
  def decode(key: String): Either[DecodeFailure, A]
```

### Built-in Instances

- `String`: Identity encoding
- `Int`, `Long`, `BigInt`: Numeric string representation
- `UUID`: Standard UUID string format

**Example:**
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

Map keys are encoded as JSON object field names (strings).

## JsonConfig

Configuration controlling encode/decode behavior.

### Fields

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

### Default Configuration

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
```

```scala mdoc
JsonConfig.default
```

### Field Naming Policies

```scala
enum FieldNamingPolicy:
  case Identity      // Keep as-is
  case SnakeCase     // firstName → first_name
  case KebabCase     // firstName → first-name
  case CamelCase     // FirstName → firstName
```

**Example:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class UserProfile(firstName: String, lastName: String)
  derives JsonCodec

val profile = UserProfile("Alice", "Smith")
```

```scala mdoc
// Identity (default)
JsonEncoder[UserProfile].encode(profile)

// Snake case
val snakeConfig = JsonConfig.default.copy(fieldNaming = FieldNamingPolicy.SnakeCase)
// Note: Encoder uses global config; pass config explicitly in production
```

### Null Handling

#### dropNullValues

If `true`, null values are omitted from encoded objects.

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class OptionalData(required: String, optional: Option[String])
  derives JsonCodec
```

```scala mdoc
val withNull = OptionalData("value", None)
JsonEncoder[OptionalData].encode(withNull)
// With dropNullValues=true, "optional" field would be omitted
```

#### treatAbsentAsNull

If `true`, missing fields decode as `null` for `Option[A]`.

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class PartialData(name: String, age: Option[Int]) derives JsonCodec
```

```scala mdoc
// Missing "age" field
val json = JsonValue.obj("name" -> JsonValue.JString("Alice"))

// With treatAbsentAsNull=true, age decodes as None
JsonDecoder[PartialData].decode(json)
```

### Number Formatting

#### writeBigIntAsString / writeBigDecimalAsString

Controls whether big numbers are encoded as JSON strings or numbers.

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

case class Amounts(bigInt: BigInt, bigDecimal: BigDecimal)
  derives JsonCodec
```

```scala mdoc
val amounts = Amounts(BigInt("123456789012345678901234567890"), BigDecimal("123.456"))
JsonEncoder[Amounts].encode(amounts)

// With writeBigIntAsString=true: { "bigInt": "123456789012345678901234567890", ... }
// Decoders accept both string and number representations
```

### Discriminator Configuration

Controls coproduct (sealed trait) encoding strategy.

```scala
case class DiscriminatorConfig(
  typeNameStrategy: TypeNameStrategy
)

enum TypeNameStrategy:
  case SimpleName                        // Use case class/object name
  case FullyQualified                    // Use full package path
  case Custom(mapping: Map[String, String])  // Custom mapping
```

**Example:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

sealed trait Event derives JsonCodec
case class UserCreated(userId: String) extends Event
case class UserDeleted(userId: String) extends Event
```

```scala mdoc
val event: Event = UserCreated("user-1")
JsonEncoder[Event].encode(event)
// Encoded as: { "UserCreated": { "userId": "user-1" } }
```

**Custom Type Names:**
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
// Would encode as: { "user.created": { "userId": "user-1" } }
```

## JsonParser and JsonPrinter

Backend-agnostic interfaces for string ↔ `JsonValue` conversion.

```scala
trait JsonParser[BackendJson]:
  def parse(input: String): Either[ParseFailure, JsonValue]

trait JsonPrinter[BackendJson]:
  def print(json: JsonValue): String
```

### Circe Backend

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

val jsonString = """{"name":"Alice","age":30}"""
```

```scala mdoc
// Parse string → JsonValue
val parsed = CirceJsonOps.parse(jsonString)

// Print JsonValue → string
parsed.map(CirceJsonOps.print)
```

## Error Handling

### DecodeFailure

Decoding failures are represented by `DecodeFailure`:

```scala
case class DecodeFailure(message: String) extends SigilarisFailure
```

Common failure scenarios:
- **Type Mismatch**: Expected type doesn't match JSON structure
- **Missing Field**: Required field absent in JSON object
- **Validation Failure**: Value decoded but failed `emap` validation
- **Unknown Subtype**: Coproduct discriminator doesn't match known types

**Example:**
```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*
```

```scala mdoc
// Type mismatch
JsonDecoder[Int].decode(JsonValue.JString("not a number"))

// Missing field
case class Required(name: String) derives JsonCodec
JsonDecoder[Required].decode(JsonValue.obj())
```

## Best Practices

### 1. Use contramap for Encoders

```scala
case class Timestamp(millis: Long)
given JsonEncoder[Timestamp] = JsonEncoder[Long].contramap(_.millis)
```

### 2. Use emap for Validation

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

### 3. Leverage Automatic Derivation

```scala
case class Account(id: String, balance: BigDecimal) derives JsonCodec
// Instances automatically available
```

### 4. Custom Configurations

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

val apiConfig = JsonConfig.default.copy(
  fieldNaming = FieldNamingPolicy.SnakeCase,
  dropNullValues = true,
  writeBigDecimalAsString = false
)

// Use config explicitly when decoding
case class ApiData(userName: String, accountBalance: BigDecimal)
  derives JsonCodec

val json = JsonValue.obj(
  "user_name" -> JsonValue.JString("alice"),
  "account_balance" -> JsonValue.JNumber(100.50)
)

// Pass config via configured givens
// val decs = JsonDecoder.configured(apiConfig); import decs.given; summon[JsonDecoder[ApiData]].decode(json)
```

## Performance Notes

- **Encoding**: O(n) where n is the size of data structure
- **Decoding**: O(n) with early exit on failures
- **Derivation**: Compile-time, no runtime overhead
- **Backend Conversion**: Minimal AST transformation overhead

## See Also

- [Examples](examples.md): Practical usage patterns

---

[← JSON Codec](README.md) | [API](api.md) | [Examples](examples.md)
