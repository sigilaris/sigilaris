# Assembly DSL API Reference

[← Overview](README.md) | [한국어 →](../../ko/assembly/api.md)

---

## BlueprintDsl

High-level DSL for mounting blueprints at specific paths.

### Signature

```scala
object BlueprintDsl:
  def mount[F[_], Name <: String & Singleton, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    binding: (Name, ModuleBlueprint[F, MName, Owns, Needs, Txs])
  )(using ...): StateModule[F, (Name,), Owns, Needs, Txs, StateReducer[F, (Name,), Owns, Needs]]
  
  def mountAtPath[F[_], Path <: Tuple, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    binding: (Path, ModuleBlueprint[F, MName, Owns, Needs, Txs])
  )(using ...): StateModule[F, Path, Owns, Needs, Txs, StateReducer[F, Path, Owns, Needs]]
  
  def mountComposed[F[_], Name <: String & Singleton, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    binding: (Name, ComposedBlueprint[F, MName, Owns, Needs, Txs])
  )(using ...): StateModule[F, (Name,), Owns, Needs, Txs, RoutedStateReducer[F, (Name,), Owns, Needs]]
  
  def mountComposedAtPath[F[_], Path <: Tuple, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    binding: (Path, ComposedBlueprint[F, MName, Owns, Needs, Txs])
  )(using ...): StateModule[F, Path, Owns, Needs, Txs, RoutedStateReducer[F, Path, Owns, Needs]]
```

### Behavior

**mount**: Mounts a ModuleBlueprint at a single-segment path. The path name is extracted from the tuple and becomes the first segment of the module's deployment path.

**mountAtPath**: Mounts a ModuleBlueprint at an arbitrary multi-segment path. Useful for nested module hierarchies.

**mountComposed**: Mounts a ComposedBlueprint (created via `Blueprint.composeBlueprint`) at a single-segment path. The resulting module uses a RoutedStateReducer that routes transactions based on `moduleId.path`.

**mountComposedAtPath**: Mounts a ComposedBlueprint at a multi-segment path.

All methods automatically require evidence:
- `Monad[F]`: Effect type must be a monad
- `PrefixFreePath[Path, Owns]`: Path + schema produces prefix-free byte prefixes
- `NodeStore[F]`: MerkleTrie storage backend
- `SchemaMapper[F, Path, Owns]`: Can instantiate tables from schema

### Examples

```scala mdoc:reset:silent
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.application.module.blueprint.ModuleBlueprint
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given

// Mount at single segment
def simpleBlueprint(): ModuleBlueprint[IO, "simple", EmptyTuple, EmptyTuple, EmptyTuple] = ???
// val module1 = mount("accounts" -> simpleBlueprint())

// Mount at multi-segment path
// val module2 = mountAtPath(("app", "v1", "accounts") -> simpleBlueprint())
```

---

## EntrySyntax

String interpolator for creating Entry instances with type-level names.

### Signature

```scala
object EntrySyntax:
  extension (inline sc: StringContext)
    transparent inline def entry[K, V]: Entry[?, K, V]
```

### Behavior

Creates an `Entry[Name, K, V]` where `Name` is extracted from the string literal at compile time. The macro ensures:
- The interpolated string is a literal (not an expression)
- The name is extracted and preserved at the type level
- Key and value types are specified via type parameters

### Examples

```scala mdoc:reset:silent
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Create entries with type-level names
val accounts = entry"accounts"[Utf8, Utf8]
val balances = entry"balances"[Utf8, BigInt]

// Type is Entry["accounts", Utf8, Utf8]
val accountsType: Entry["accounts", Utf8, Utf8] = accounts

// Compose into schema
val schema = accounts *: balances *: EmptyTuple
```

```scala mdoc:compile-only
import org.sigilaris.core.assembly.EntrySyntax.*

// Compile error: not a string literal
// val name = "users"
// val entry = entry"$name"[String, Int]
```

---

## TablesProviderOps

Extension methods for extracting TablesProvider from mounted modules.

### Signature

```scala
object TablesProviderOps:
  extension [F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, R](
    module: StateModule[F, Path, Owns, Needs, Txs, R]
  )
    def toTablesProvider: TablesProvider[F, Owns]
```

### Behavior

Extracts a `TablesProvider[F, Owns]` from a mounted StateModule. The provider exposes the module's owned tables to dependent modules. This is the primary mechanism for dependency injection between modules.

### Examples

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.application.module.blueprint.ModuleBlueprint
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Module A provides tables
type ModuleASchema = Entry["accounts", Utf8, Utf8] *: EmptyTuple
def moduleABlueprint(): ModuleBlueprint[IO, "moduleA", ModuleASchema, EmptyTuple, EmptyTuple] = ???
// val moduleA = mount("moduleA" -> moduleABlueprint())
// val provider = moduleA.toTablesProvider

// Module B depends on A's tables
// def moduleBBlueprint(p: TablesProvider[IO, ModuleASchema]): ModuleBlueprint[IO, "moduleB", EmptyTuple, ModuleASchema, EmptyTuple] = ???
// val moduleB = mount("moduleB" -> moduleBBlueprint(provider))
```

---

## TablesAccessOps

Evidence derivation utilities for type-safe table access.

### Signature

```scala
object TablesAccessOps:
  def deriveLookup[Schema <: Tuple, Name <: String, K, V]: Lookup[Schema, Name, K, V]
  
  def deriveRequires[Needs <: Tuple, Schema <: Tuple]: Requires[Needs, Schema]
  
  extension [F[_], Schema <: Tuple](provider: TablesProvider[F, Schema])
    def providedTable[Name <: String, K, V](using Lookup[Schema, Name, K, V]): StateTable[F] { type Name = Name; type K = K; type V = V }
```

### Behavior

**deriveLookup**: Derives `Lookup[Schema, Name, K, V]` evidence, which proves that the schema contains an entry with the specified name and types. Used internally by the module system.

**deriveRequires**: Derives `Requires[Needs, Schema]` evidence, which proves that all entries in `Needs` are present in `Schema`. Used for validating transaction requirements.

**providedTable**: Extension method for accessing a table from a provider with automatic evidence derivation. Returns a `StateTable[F]` with refined types.

### Examples

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.TablesAccessOps.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

type MySchema = (
  Entry["accounts", Utf8, Utf8],
  Entry["balances", Utf8, BigInt],
)

// Derive lookup evidence
// val lookup = deriveLookup[MySchema, "accounts", String, String]

// Access table with extension method
def accessTable(provider: TablesProvider[IO, MySchema]): Unit =
  // val accountsTable = provider.providedTable["accounts", String, String]
  ???
```

---

## PrefixFreeValidator

Runtime validation for prefix-free table configurations.

### Signature

```scala
object PrefixFreeValidator:
  sealed trait ValidationResult
  case object Valid extends ValidationResult
  case class PrefixCollision(prefix1: ByteVector, prefix2: ByteVector) extends ValidationResult
  case class IdenticalPrefixes(prefix: ByteVector, count: Int) extends ValidationResult
  
  def validateSchema[Path <: Tuple, Schema <: Tuple]: ValidationResult
  
  def validateWithNames(prefixes: List[(String, ByteVector)]): ValidationResult
```

### Behavior

**validateSchema**: Validates at compile-time that the given Path + Schema combination produces prefix-free byte prefixes. Returns `Valid` if all prefixes are distinct and prefix-free.

**validateWithNames**: Runtime validation with debugging information. Takes a list of (name, prefix) pairs and checks for collisions. Useful for testing and debugging schema configurations.

**ValidationResult**: ADT representing validation outcomes:
- `Valid`: All prefixes are prefix-free
- `PrefixCollision`: One prefix is a prefix of another
- `IdenticalPrefixes`: Multiple tables have the same prefix

### Examples

```scala mdoc:compile-only
import scodec.bits.ByteVector
import org.sigilaris.core.assembly.PrefixFreeValidator
import org.sigilaris.core.application.support.encoding.tablePrefix
import org.sigilaris.core.application.state.Entry

type MySchema = (
  Entry["accounts", String, String],
  Entry["balances", String, BigInt],
)

// Runtime validation with names
val prefixes = List(
  ("accounts", ByteVector(0x01)),
  ("balances", ByteVector(0x02))
)
val result = PrefixFreeValidator.validateWithNames(prefixes)

result match
  case PrefixFreeValidator.Valid => println("Schema is prefix-free")
  case PrefixFreeValidator.PrefixCollision(p1, p2) => println(s"Collision: $p1 is prefix of $p2")
  case PrefixFreeValidator.IdenticalPrefixes(p, n) => println(s"Duplicate prefix: $p appears $n times")
```

---

## Helper Functions

### tablePrefix

```scala
inline def tablePrefix[Path <: Tuple, Name <: String]: ByteVector
```

Computes the byte-level prefix for a table given its Path and Name. Uses length-prefix encoding for each path segment.

### encodePath

```scala
inline def encodePath[Path <: Tuple]: ByteVector
```

Encodes a tuple of path segments into a ByteVector using length-prefix encoding.

### encodeSegment

```scala
inline def encodeSegment[S <: String]: ByteVector
```

Encodes a single path segment (string literal) with length prefix and null terminator.

---

[← Overview](README.md) | [한국어 →](../../ko/assembly/api.md)
