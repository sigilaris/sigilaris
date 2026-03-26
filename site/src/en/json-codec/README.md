# JSON Codec

[← Main](../../README.md) | [한국어 →](../../ko/json-codec/README.md)

[API](api.md) | [Examples](examples.md)

---

## Overview

The Sigilaris JSON codec provides a lightweight, library-agnostic abstraction for encoding and decoding Scala data structures to JSON. While hash computation is not a critical concern for JSON (unlike the byte codec), this module is designed to allow easy switching between JSON libraries by introducing a minimal `JsonValue` ADT as an intermediate representation.

### Why Custom JSON Abstraction?

- **Library Independence**: Define domain logic once, swap backend libraries (Circe, Play JSON, etc.) without touching domain code
- **Type-Safe API**: Built on Scala 3 + cats ecosystem with functor operations (`contramap`, `map`, `emap`)
- **Configurable Derivation**: Automatic derivation for case classes and sealed traits with customizable naming and discriminator strategies
- **Minimal Surface Area**: Small ADT with six cases covering all JSON types

### Key Features

- **Library-Agnostic Core**: Encode/decode to `JsonValue` ADT, then convert to/from backend library formats
- **Automatic Derivation**: Case classes and sealed traits via Scala 3 mirrors
- **Flexible Configuration**: Field naming policies (snake_case, kebab-case, camelCase), null handling, and discriminator strategies
- **Wrapped Discriminator**: Coproducts encoded as `{ "TypeName": { ...fields... } }` for clean type discrimination

## Quick Start (30 seconds)

```scala mdoc:silent
import org.sigilaris.core.codec.json.*
import org.sigilaris.core.codec.json.backend.circe.CirceJsonOps

case class User(name: String, age: Int) derives JsonCodec

val user = User("Alice", 30)

// Encode to JsonValue
val jsonValue = JsonEncoder[User].encode(user)

// Print to JSON string (via Circe backend)
val jsonString = CirceJsonOps.print(jsonValue)

// Parse from JSON string
val parsed = CirceJsonOps.parse(jsonString)
```

```scala mdoc
// Decode back to User
parsed.flatMap(JsonDecoder[User].decode(_))
```

That's it! The codec automatically derives instances and you can switch JSON backends by changing imports.

## Documentation

- **[API Reference](api.md)**: `JsonEncoder`, `JsonDecoder`, `JsonCodec`, `JsonConfig` details
- **[Examples](examples.md)**: Practical usage with configuration options

## What's Included

### Core ADT

`JsonValue` enum with six cases:
- `JNull`: JSON null
- `JBool(Boolean)`: JSON boolean
- `JNumber(BigDecimal)`: JSON number
- `JString(String)`: JSON string
- `JArray(Vector[JsonValue])`: JSON array
- `JObject(Map[String, JsonValue])`: JSON object

### Type Classes

- `JsonEncoder[A]`: Encode `A` to `JsonValue`
- `JsonDecoder[A]`: Decode `JsonValue` to `A`
- `JsonCodec[A]`: Bidirectional encoding/decoding
- `JsonKeyCodec[A]`: Encode/decode map keys as JSON strings

### Backend Integration

- **Ops Interfaces**: `JsonParser` and `JsonPrinter` for string ↔ `JsonValue` conversion
- **Circe Backend**: Built-in adapter for Circe JSON library
- **Extensible**: Implement ops for other libraries (Play JSON, uPickle, etc.)

### Automatic Derivation

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

## Configuration

`JsonConfig` controls encoding/decoding behavior:

```scala mdoc:reset:silent
import org.sigilaris.core.codec.json.*

val config = JsonConfig(
  fieldNaming = FieldNamingPolicy.SnakeCase,      // firstName → first_name
  dropNullValues = true,                          // omit null fields
  treatAbsentAsNull = true,                       // missing → null for Option
  writeBigIntAsString = true,                     // BigInt → "123"
  writeBigDecimalAsString = false,                // BigDecimal → 123.45
  discriminator = DiscriminatorConfig(
    TypeNameStrategy.SimpleName                   // { "TypeName": {...} }
  )
)
```

### Field Naming Policies

- `Identity`: Keep as-is
- `SnakeCase`: `firstName` → `first_name`
- `KebabCase`: `firstName` → `first-name`
- `CamelCase`: `FirstName` → `firstName`

### Discriminator Strategy

Coproducts (sealed traits) use **wrapped-by-type-key** encoding:

```scala
sealed trait Color
case object Red extends Color
case object Blue extends Color

// Encoded as: { "Red": {} } or { "Blue": {} }
```

Type name strategies:
- `SimpleName`: Use case class/object name
- `FullyQualified`: Use full package path (currently falls back to simple)
- `Custom(Map[String, String])`: Provide custom mapping

## Design Philosophy

### Separation of Concerns

Unlike the byte codec (which is critical for deterministic hashing), JSON encoding:
- Does **not** need to be deterministic for hash computation
- Allows flexible use of external libraries
- Focuses on API usability and library independence

The architecture ensures:
- **Domain → JsonValue**: Type-safe, library-agnostic encoding
- **JsonValue ↔ Backend**: Minimal conversion layer (e.g., Circe)
- **Easy Library Swapping**: Change backend without touching domain code

### Minimal Dependencies

The core `JsonValue` ADT has zero dependencies on external JSON libraries. Backend adapters are separate modules, making it trivial to:
- Add support for new libraries
- Remove unused backends
- Test encoding logic without library overhead

## Example: API Response Encoding

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

## Next Steps

1. **[API Reference](api.md)**: Learn about `contramap`, `emap`, and combinators
2. **[Examples](examples.md)**: See configuration options and advanced patterns

## Limitations and Scope

- **Not Deterministic for Hashing**: Use byte codec for blockchain/cryptographic hashing
- **No Schema Validation**: Decoders validate structure but not business rules (use `emap` for validation)
- **Backend Required for Strings**: `JsonValue` alone doesn't parse/print JSON strings (use `JsonParser`/`JsonPrinter`)

## Performance Characteristics

- **Encoding**: O(n) where n is the size of the data structure
- **Decoding**: O(n) with early exit on errors
- **Product/Coproduct Derivation**: Compile-time, no runtime overhead
- **Backend Conversion**: Minimal overhead for AST transformation

---

[← Main](../../README.md) | [한국어 →](../../ko/json-codec/README.md)

[API](api.md) | [Examples](examples.md)
