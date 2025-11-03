# Assembly DSL

[← Main](../../README.md) | [한국어 →](../../ko/assembly/README.md)

---

[API](api.md)

---

## Overview

The Sigilaris assembly package provides a high-level DSL for constructing blockchain applications from modular blueprints. It offers convenient tools for mounting modules at deployment paths with compile-time validation and type-safe table access.

**Why do we need the assembly package?** Building modular blockchain applications requires composing independent modules, wiring dependencies, and ensuring type safety across module boundaries. This package provides ergonomic DSL methods that handle these concerns while enforcing compile-time validation.

**Key Features:**
- **High-level mounting DSL**: Simple methods for mounting blueprints at paths
- **Compile-time validation**: PrefixFreePath, UniqueNames, and schema validation
- **Type-safe table access**: Automatic evidence derivation for table lookups
- **Dependency injection**: Provider extraction and wiring
- **Entry interpolator**: String interpolation for type-safe Entry creation

## Quick Start (30 seconds)

```scala mdoc:reset:silent
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.state.*
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Define schema using entry interpolator (example from Accounts module)
val accountInfoEntry = entry"accountInfo"[Utf8, Utf8]  // Simplified for demo
val nameKeyEntry = entry"nameKey"[(Utf8, BigInt), Utf8]

// Create a blueprint (see Accounts/Group modules for full examples)
// Real blueprints would use proper domain types like AccountInfo, GroupData

// Mount at a single-segment path
// val module = mount("myapp" -> createBlueprint())

// Extract provider for dependent modules
// val provider = module.toTablesProvider
```

That's it! The assembly DSL automatically:
- Validates that paths and schemas are prefix-free
- Computes table prefixes from paths
- Derives evidence for schema requirements
- Enables cross-module dependencies

## Documentation

### Core Concepts
- **[API Reference](api.md)**: Detailed documentation for BlueprintDsl, EntrySyntax, and provider utilities

### Main Types

#### BlueprintDsl
High-level DSL for mounting blueprints at specific paths:
- `mount`: Mount a ModuleBlueprint at a single-segment path
- `mountAtPath`: Mount a ModuleBlueprint at a multi-segment path
- `mountComposed`: Mount a ComposedBlueprint at a single-segment path
- `mountComposedAtPath`: Mount a ComposedBlueprint at a multi-segment path

All methods automatically require evidence for:
- `PrefixFreePath`: Validates path + schema is prefix-free
- `SchemaMapper`: Instantiates tables from Entry specifications
- `NodeStore`: Provides MerkleTrie storage backend

#### EntrySyntax
String interpolator for creating Entry instances:
```scala mdoc:reset:silent
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Create entries with type-level names (from Accounts module)
val accountInfo = entry"accountInfo"[Utf8, Utf8]  // Simplified - use AccountInfo in production
val nameKey = entry"nameKey"[(Utf8, BigInt), Utf8]  // Simplified - use KeyInfo in production

// Compose into schema tuple
val schema = accountInfo *: nameKey *: EmptyTuple
```

The `entry` interpolator ensures table names are string literals (not expressions) and preserves type-level information for schema validation.

#### TablesProviderOps
Extension methods for extracting providers from mounted modules:
```scala mdoc:reset:silent
import cats.effect.IO
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.application.module.runtime.StateModule
import org.sigilaris.core.application.module.provider.TablesProvider

// Extract provider from module (placeholder)
def extractProvider[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, R](
  module: StateModule[F, Path, Owns, Needs, Txs, R]
): TablesProvider[F, Owns] = module.toTablesProvider
```

#### TablesAccessOps
Evidence derivation utilities for type-safe table access:
- `deriveLookup`: Derive Lookup evidence for table access
- `deriveRequires`: Derive Requires evidence for schema validation
- `providedTable`: Extension method for direct table access with automatic evidence

#### PrefixFreeValidator
Runtime validation for prefix-free table configurations:
- `validateSchema`: Compile-time validation
- `validateWithNames`: Runtime validation with debugging information

## Use Cases

### Building a Simple Application

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.state.*
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Real-world example: See Accounts module (ADR-0010)
// Accounts module has no dependencies (Needs = EmptyTuple)
type AccountsOwns = (
  Entry["accountInfo", Utf8, Utf8],     // Simplified - use AccountInfo in production
  Entry["nameKey", (Utf8, BigInt), Utf8], // Simplified - use KeyInfo in production
)

// Define placeholder blueprint
def accountsBP(): ModuleBlueprint[IO, "accounts", AccountsOwns, EmptyTuple, EmptyTuple] = ???

// Mount module at path
// val accountsModule = mount("accounts" -> accountsBP())
```

### Wiring Dependencies

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.*
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Real-world pattern: Accounts provides tables to Group (ADR-0010/0011)
type AccountsOwns = Entry["accountInfo", Utf8, Utf8] *: EmptyTuple
type GroupNeeds = Entry["accountInfo", Utf8, Utf8] *: EmptyTuple

// Accounts module (no dependencies)
def accountsBP(): ModuleBlueprint[IO, "accounts", AccountsOwns, EmptyTuple, EmptyTuple] = ???
// val accountsModule = mount("accounts" -> accountsBP())
// val accountsProvider = accountsModule.toTablesProvider

// Group module depends on Accounts
def groupBP(provider: TablesProvider[IO, GroupNeeds]): ModuleBlueprint[IO, "group", EmptyTuple, GroupNeeds, EmptyTuple] = ???
// val groupModule = mount("group" -> groupBP(accountsProvider))
```

### Composing Blueprints

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.application.module.blueprint.{Blueprint, ModuleBlueprint}

// Compose two independent modules (placeholder)
def blueprint1(): ModuleBlueprint[IO, "mod1", EmptyTuple, EmptyTuple, EmptyTuple] = ???
def blueprint2(): ModuleBlueprint[IO, "mod2", EmptyTuple, EmptyTuple, EmptyTuple] = ???

// val composed = Blueprint.composeBlueprint[IO, "app"](blueprint1(), blueprint2())
// val composedModule = mountComposed("app" -> composed)
```

## Type Conventions

### Path Types
Paths are represented as tuple types of string literals:
- Single segment: `"accounts"` becomes `("accounts",)` at the type level
- Multiple segments: `("app", "v1", "accounts")`

### Schema Types
Schemas are tuples of Entry types:
```scala mdoc:reset:silent
import org.sigilaris.core.application.state.Entry

// Real example from Accounts module (simplified types)
type AccountsSchema = (
  Entry["accountInfo", String, String],    // In production: AccountInfo
  Entry["nameKey", (String, Int), String], // In production: (Utf8, KeyId20), KeyInfo
)
```

### Evidence Types
The assembly DSL automatically derives:
- `UniqueNames[Schema]`: Table names are unique within schema
- `PrefixFreePath[Path, Schema]`: Path + schema produces prefix-free byte prefixes
- `SchemaMapper[F, Path, Schema]`: Can instantiate tables from Entry specs

## Design Principles

**Type Safety**: All validation happens at compile-time. Invalid configurations (duplicate names, non-prefix-free schemas) are rejected by the compiler.

**Ergonomics**: DSL methods provide concise syntax for common operations. Import-based feature activation keeps the API clean.

**Composability**: Methods compose naturally. Provider extraction enables dependency injection patterns.

**Zero Cost**: Type-level abstractions compile to efficient runtime code. No reflection or runtime validation overhead.

## Next Steps

- **[API Reference](api.md)**: Detailed API documentation
- **[Application Module](../application/README.md)**: Underlying module system
- **[ADR-0009](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)**: Architecture decision record

## Limitations

- Entry interpolator requires string literals (not expressions)
- Evidence derivation may require explicit type annotations for complex schemas
- Composed blueprints require ModuleRoutedTx transactions

## References

- [Typelevel Documentation](https://typelevel.org/)
- [Cats Effect](https://typelevel.org/cats-effect/)
- [ADR-0009: Blockchain Application Architecture](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)

---

© 2025 Sigilaris. All rights reserved.
