# Application Module

[← Main](../../README.md) | [한국어 →](../../ko/application/README.md)

---

[API](api.md)

---

## Overview

The Sigilaris application module provides the core abstractions for building modular blockchain applications with compile-time schema validation, type-safe table access, and automatic dependency injection. It implements the blueprint-based architecture described in ADR-0009.

**Why do we need the application module?** Blockchain applications require modular state management where different features can be independently developed, tested, and composed. This module provides the type-level machinery to ensure correctness at compile time while enabling flexible composition patterns.

**Key Features:**
- **Two-phase architecture**: Path-independent blueprints → Path-bound runtime modules
- **Compile-time validation**: Schema requirements, uniqueness, prefix-free constraints
- **Type-safe dependencies**: Provider system for cross-module table access
- **Flexible composition**: Combine independent modules at deployment time
- **Zero-cost abstractions**: Type-level proofs compiled away at runtime

## Quick Start (30 seconds)

```scala mdoc:reset:silent
import cats.effect.IO
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.codec.byte.ByteCodec
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Define schema
type MySchema = Entry["accounts", Utf8, BigInt] *: EmptyTuple
val accountsEntry = entry"accounts"[Utf8, BigInt]
val schema = accountsEntry *: EmptyTuple

// Create reducer (placeholder)
class MyReducer[F[_]] extends StateReducer0[F, MySchema, EmptyTuple]:
  def apply[T <: Tx](signedTx: Signed[T])(using
    requiresReads: Requires[signedTx.value.Reads, MySchema],
    requiresWrites: Requires[signedTx.value.Writes, MySchema],
    ownsTables: Tables[F, MySchema],
    provider: TablesProvider[F, EmptyTuple],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = ???

// Create blueprint
// val blueprint = new ModuleBlueprint[IO, "myModule", (Entry["accounts", Utf8, BigInt],), EmptyTuple, EmptyTuple](
//   owns = schema,
//   reducer0 = new MyReducer[IO],
//   txs = TxRegistry.empty,
//   provider = TablesProvider.empty
// )
```

That's it! The application module automatically:
- Validates schema requirements at compile time
- Ensures table names are unique
- Provides type-safe table access in reducers
- Enables dependency injection via providers

## Architecture: Blueprint → Module

The application module follows a **two-phase design** inspired by ADR-0009:

### Phase 1: Blueprint (Path-Independent)

Blueprints are **deployment-agnostic specifications** that define:
- `Owns`: Tables this module creates and owns
- `Needs`: Tables this module requires from other modules
- `StateReducer0`: Transaction logic operating over `Owns ++ Needs`
- `TxRegistry`: Registered transaction types

**Key insight**: Blueprints don't know where they'll be deployed. This enables:
- **Reusability**: Same blueprint deployed at different paths
- **Testability**: Unit test without deployment concerns
- **Composability**: Combine blueprints before deployment

### Phase 2: Module (Path-Bound)

Mounting a blueprint to a specific `Path` creates a **StateModule**:
- Computes table prefixes: `encodePath(Path) ++ tableName`
- Instantiates `StateTable[F]` instances for each Entry
- Binds reducer with concrete table implementations
- Validates prefix-free property at mount time

**Example**:
```scala
val blueprint: ModuleBlueprint[IO, "accounts", ...] = ...

// Deploy to different paths
val mainAccounts = blueprint.mount[("app", "v1", "accounts")]
val testAccounts = blueprint.mount[("test", "accounts")]  // Isolated for testing
```

### Why Two Phases?

1. **Separation of Concerns**: Logic (blueprint) vs deployment (module)
2. **Type Safety**: Path becomes part of module type for prefix checking
3. **Flexibility**: Same blueprint, multiple deployments
4. **Zero Cost**: All path computation done at compile time

## Documentation

### Core Concepts
- **[API Reference](api.md)**: Detailed documentation for Blueprint, StateModule, and related types

### Main Types

#### Blueprint (Path-Independent)

**ModuleBlueprint**: Single-module specification with owned and needed tables
```scala
class ModuleBlueprint[F[_], MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
  owns: Owns,                           // Runtime tuple of Entry instances
  reducer0: StateReducer0[F, Owns, Needs],
  txs: TxRegistry[Txs],
  provider: TablesProvider[F, Needs]    // Injected external tables
)
```

**ComposedBlueprint**: Multiple modules combined with routing
```scala
class ComposedBlueprint[F[_], MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
  owns: Owns,
  reducer0: RoutedStateReducer0[F, Owns, Needs],  // Routes by moduleId
  txs: TxRegistry[Txs],
  provider: TablesProvider[F, Needs],
  routeHeads: List[String]                         // Module names for routing
)
```

#### StateModule (Path-Bound)

Runtime module with instantiated tables at a specific path:
```scala
class StateModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, R](
  tables: Tables[F, Owns],               // Instantiated StateTable instances
  reducer: R,                             // StateReducer or RoutedStateReducer
  txs: TxRegistry[Txs],
  tablesProvider: TablesProvider[F, Needs]
)
```

#### State Types

**StoreF**: Effectful state monad with error handling and state threading

```scala
// StoreF stacks three effects:
type Eff[F[_]] = EitherT[F, SigilarisFailure, *]       // Error channel
type StoreF[F[_]] = StateT[Eff[F], StoreState, *]     // State threading

// Full expansion:
type StoreF[F[_]] = StateT[
  EitherT[F, SigilarisFailure, *],  // Can fail with SigilarisFailure
  StoreState,                        // Threads StoreState (trie + log)
  *                                   // Result type parameter
]

// Usage in reducers:
def apply[T <: Tx](...): StoreF[F][(T#Result, List[T#Event])]
// Returns: StateT that threads StoreState and can fail with SigilarisFailure
```

**Why this stack?**
- `F[_]`: Underlying effect (IO, Task, etc.)
- `EitherT`: Short-circuit on validation/execution errors
- `StateT`: Thread MerkleTrie state + access log through operations
- Result: Composable, type-safe state transitions

**StoreState**: Combines MerkleTrie state with access logging (ADR-0009 Phase 8)
```scala
case class StoreState(
  trieState: MerkleTrieState,        // Actual key-value trie
  accessLog: AccessLog               // Tracks reads/writes for conflict detection
)
```

**AccessLog**: Records table-level operations for parallel execution analysis
```scala
case class AccessLog(
  reads: Map[ByteVector, Set[ByteVector]],   // tablePrefix → keys read
  writes: Map[ByteVector, Set[ByteVector]]   // tablePrefix → keys written
):
  def conflictsWith(other: AccessLog): Boolean  // Detects W∩W or R∩W conflicts
  def readCount: Int                            // Total unique keys read
  def writeCount: Int                           // Total unique keys written
```

**Key property**: Prefix-free table prefixes ensure no false positives in conflict detection.

#### Transaction Model

**Tx**: Base trait for all transactions
```scala
trait Tx:
  type Reads <: Tuple    // Required tables for reads
  type Writes <: Tuple   // Required tables for writes
  type Result            // Transaction result type
  type Event             // Event log type
```

**ModuleRoutedTx**: Transactions with module-relative routing
```scala
trait ModuleRoutedTx:
  def moduleId: ModuleId  // Always module-relative: MName *: SubPath
```

#### Dependency System

**TablesProvider**: Supplies external tables to dependent modules
```scala
trait TablesProvider[F[_], Schema <: Tuple]:
  def tables: Tables[F, Schema]
  def narrow[Subset <: Tuple](using TablesProjection[F, Subset, Schema]): TablesProvider[F, Subset]
```

**Requires**: Compile-time proof that transaction needs are satisfied
```scala
trait Requires[Needs <: Tuple, Schema <: Tuple]
```

## Use Cases

### Real-World Example: Accounts Module (ADR-0010)

The Accounts module from ADR-0010 demonstrates a complete blueprint implementation:

```scala mdoc:compile-only
import cats.Monad
import cats.effect.IO
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Accounts module schema (simplified from ADR-0010)
type AccountsOwns = (
  Entry["accountInfo", Utf8, BigInt],     // name → AccountInfo
  Entry["nameKey", (Utf8, BigInt), BigInt], // (name, keyId) → KeyInfo
)

// Accounts module has no dependencies
type AccountsNeeds = EmptyTuple

// Transaction types for accounts
trait CreateNamedAccount extends Tx:
  type Reads = EmptyTuple
  type Writes = AccountsOwns
  def nameValue: Utf8
  def initialKeyId: BigInt

class AccountsReducer[F[_]: Monad] extends StateReducer0[F, AccountsOwns, AccountsNeeds]:
  def apply[T <: Tx](signedTx: Signed[T])(using
    requiresReads: Requires[signedTx.value.Reads, AccountsOwns],
    requiresWrites: Requires[signedTx.value.Writes, AccountsOwns],
    tables: Tables[F, AccountsOwns],
    provider: TablesProvider[F, AccountsNeeds],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = ???

// AccountsBP can be deployed anywhere
// val accountsBP = new ModuleBlueprint[IO, "accounts", AccountsOwns, AccountsNeeds, ...]
```

### Module Dependencies: Group Module (ADR-0011)

The Group module depends on Accounts for coordinator validation:

```scala mdoc:compile-only
import cats.Monad
import cats.effect.IO
import scala.Tuple.++
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Group owns its tables
type GroupOwns = (
  Entry["groupData", Utf8, BigInt],           // groupId → GroupData
  Entry["groupMember", (Utf8, Utf8), Unit],   // (groupId, accountName) → Unit
)

// Group needs Accounts tables for coordinator validation
type GroupNeeds = Entry["accountInfo", Utf8, BigInt] *: EmptyTuple  // From AccountsBP

// Group transactions operate over GroupOwns ++ GroupNeeds
trait CreateGroup extends Tx:
  type Reads = GroupNeeds    // Check coordinator exists
  type Writes = GroupOwns     // Create group
  def groupId: Utf8
  def coordinator: Utf8  // Must exist in accountInfo

class GroupReducer[F[_]: Monad] extends StateReducer0[F, GroupOwns, GroupNeeds]:
  def apply[T <: Tx](signedTx: Signed[T])(using
    requiresReads: Requires[signedTx.value.Reads, GroupOwns ++ GroupNeeds],
    requiresWrites: Requires[signedTx.value.Writes, GroupOwns ++ GroupNeeds],
    ownsTables: Tables[F, GroupOwns],
    provider: TablesProvider[F, GroupNeeds],  // Injected accountInfo table
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = ???

// GroupBP explicitly declares dependency on Accounts
// val groupBP = new ModuleBlueprint[IO, "group", GroupOwns, GroupNeeds, ...]
```

**Key insight**: Group reducer can access both:
- `ownsTables`: Its own groupData/groupMember tables
- `provider.tables`: Accounts' accountInfo table (injected)

### Deployment Patterns

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.application.module.blueprint.ModuleBlueprint

// Assume we have the blueprints
def accountsBP(): ModuleBlueprint[IO, "accounts", EmptyTuple, EmptyTuple, EmptyTuple] = ???
def groupBP(): ModuleBlueprint[IO, "group", EmptyTuple, EmptyTuple, EmptyTuple] = ???

// Pattern 1: Shared Accounts (both modules use same instance)
// val accountsModule = accountsBP().mount[("app", "shared")]
// val groupModule = groupBP().mount[("app", "group")]  
//   // with accountsModule.tables as provider

// Pattern 2: Sandboxed Accounts (group has isolated accounts)
// val groupAccounts = accountsBP().mount[("app", "group", "accounts")]
// val groupModule = groupBP().mount[("app", "group")]
//   // with groupAccounts.tables as provider
```

### Building a Simple Module

```scala mdoc:compile-only
import cats.Monad
import cats.effect.IO
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.codec.byte.ByteCodec
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Define domain type (use BigInt directly for simplicity)
// case class Account(balance: BigInt)

// Define schema
type MySchema = Entry["accounts", Utf8, BigInt] *: EmptyTuple
val accountsEntry = entry"accounts"[Utf8, BigInt]
val schema = accountsEntry *: EmptyTuple

// Create reducer
class AccountsReducer[F[_]: Monad] extends StateReducer0[F, MySchema, EmptyTuple]:
  def apply[T <: Tx](signedTx: Signed[T])(using
    requiresReads: Requires[signedTx.value.Reads, MySchema],
    requiresWrites: Requires[signedTx.value.Writes, MySchema],
    ownsTables: Tables[F, MySchema],
    provider: TablesProvider[F, EmptyTuple],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = ???

// Create blueprint
// val blueprint = new ModuleBlueprint[IO, "accounts", MySchema, EmptyTuple, EmptyTuple](
//   owns = schema,
//   reducer0 = new AccountsReducer[IO],
//   txs = TxRegistry.empty,
//   provider = TablesProvider.empty
// )
```

### Module Dependencies

```scala mdoc:compile-only
import cats.Monad
import cats.effect.IO
import scala.Tuple.++
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// Module A owns accounts
type SchemaA = Entry["accounts", Utf8, BigInt] *: EmptyTuple

// Module B depends on A's accounts
type OwnsB = Entry["balances", Utf8, BigInt] *: EmptyTuple
type NeedsB = SchemaA  // Depends on accounts from module A

class ModuleBReducer[F[_]: Monad] extends StateReducer0[F, OwnsB, NeedsB]:
  def apply[T <: Tx](signedTx: Signed[T])(using
    requiresReads: Requires[signedTx.value.Reads, OwnsB ++ NeedsB],
    requiresWrites: Requires[signedTx.value.Writes, OwnsB ++ NeedsB],
    ownsTables: Tables[F, OwnsB],
    provider: TablesProvider[F, NeedsB],  // Injected accounts table
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = 
    // Can access both ownsTables (balances) and provider.tables (accounts)
    ???
```

### Composing Modules

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.application.module.blueprint.{Blueprint, ModuleBlueprint}

// Two independent modules (placeholders)
def accountsBP(): ModuleBlueprint[IO, "accounts", EmptyTuple, EmptyTuple, EmptyTuple] = ???
def balancesBP(): ModuleBlueprint[IO, "balances", EmptyTuple, EmptyTuple, EmptyTuple] = ???

// Compose into single blueprint with routing
// val composed = Blueprint.composeBlueprint[IO, "app"](
//   accountsBP(),
//   balancesBP()
// )

// Transactions must use ModuleRoutedTx for routing
```

## Type Conventions

### Schema Types
Schemas are tuples of Entry types:
```scala mdoc:reset:silent
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

type MySchema = (
  Entry["accounts", Utf8, BigInt],
  Entry["balances", Utf8, BigInt],
)
```

### Owns vs Needs
- **Owns**: Tables this module creates and manages
- **Needs**: Tables this module reads from other modules  
- Combined schema `Owns ++ Needs` is what transactions operate over

### Path Types
Paths are tuples of string literals representing deployment location:
- Single segment: `("accounts",)`
- Multiple segments: `("app", "v1", "accounts")`

## Design Principles

**Blueprint Phase**: Modules don't know their deployment path. This enables reusability and testing independence.

**Runtime Phase**: Mounting binds a blueprint to a path, computing table prefixes and instantiating StateTable instances.

**Type Safety**: All schema requirements are validated at compile time. Missing tables, duplicate names, or non-prefix-free schemas are rejected by the compiler.

**Dependency Injection**: TablesProvider enables clean separation between module definition and dependency wiring.

## Next Steps

- **[API Reference](api.md)**: Detailed API documentation
- **[Assembly DSL](../assembly/README.md)**: High-level mounting utilities
- **[ADR-0009](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)**: Architecture decision record

## Limitations

- Composed blueprints require ModuleRoutedTx transactions
- Provider narrowing requires explicit TablesProjection evidence for complex cases
- Schema instantiation requires SchemaMapper derivation

## References

- [Typelevel Documentation](https://typelevel.org/)
- [Cats Effect](https://typelevel.org/cats-effect/)
- [ADR-0009: Blockchain Application Architecture](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)
- [ADR-0010: Blockchain Account Model and Key Management](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0010-blockchain-account-model-and-key-management.md)

---

© 2025 Sigilaris. All rights reserved.
