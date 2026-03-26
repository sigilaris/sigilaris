# Application Module API Reference

[← Overview](README.md) | [한국어 →](../../ko/application/api.md)

---

## Overview

This page provides detailed API reference for the application module system. For complete examples and usage patterns, see the [Overview](README.md).

## Core Abstractions

### Blueprint (Path-Independent)

**ModuleBlueprint**

Single-module specification without deployment path.

```scala
class ModuleBlueprint[
  F[_],
  MName <: String,      // Module name (type-level)
  Owns <: Tuple,        // Tables this module owns
  Needs <: Tuple,       // Tables this module requires
  Txs <: Tuple          // Transaction types
](
  owns: Owns,                           // Runtime Entry tuple
  reducer0: StateReducer0[F, Owns, Needs],
  txs: TxRegistry[Txs],
  provider: TablesProvider[F, Needs]    // Injected dependencies
)
```

**Example: AccountsBP (ADR-0010)**

```scala
// Schema definition
type AccountsOwns = (
  Entry["accountInfo", Utf8, AccountInfo],  // name → account metadata
  Entry["nameKey", (Utf8, KeyId20), KeyInfo], // (name, keyId) → key info
)

type AccountsNeeds = EmptyTuple  // No external dependencies

// Transactions
trait CreateNamedAccount extends Tx:
  type Reads = EmptyTuple
  type Writes = AccountsOwns
  def nameValue: Utf8
  def initialKeyId: KeyId20
  def guardian: Option[Account]

trait AddKeyIds extends Tx:
  type Reads = AccountsOwns
  type Writes = AccountsOwns
  def nameValue: Utf8
  def keyIds: Map[KeyId20, Utf8]  // keyId → description

// Reducer
class AccountsReducer[F[_]: Monad] 
  extends StateReducer0[F, AccountsOwns, AccountsNeeds]:
  
  def apply[T <: Tx](signedTx: Signed[T])(using
    Requires[signedTx.value.Reads, AccountsOwns],
    Requires[signedTx.value.Writes, AccountsOwns],
    Tables[F, AccountsOwns],
    TablesProvider[F, AccountsNeeds],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = ???

// Blueprint construction
val accountsBP = new ModuleBlueprint[
  IO, "accounts", AccountsOwns, AccountsNeeds, AccountsTxs
](
  owns = accountsSchema,
  reducer0 = new AccountsReducer[IO],
  txs = accountsTxRegistry,
  provider = TablesProvider.empty
)
```

**Key properties:**
- Path-independent: Can mount at any location
- No dependencies: `Needs = EmptyTuple`
- Type-safe: Transactions statically declare table requirements

**ComposedBlueprint**

Multiple modules combined with routing.

```scala
class ComposedBlueprint[
  F[_],
  MName <: String,
  Owns <: Tuple,
  Needs <: Tuple,
  Txs <: Tuple
](
  owns: Owns,
  reducer0: RoutedStateReducer0[F, Owns, Needs],  // Routes by moduleId
  txs: TxRegistry[Txs],
  provider: TablesProvider[F, Needs],
  routeHeads: List[String]                         // Module names for routing
)
```

**Example: Dependent Module (GroupBP, ADR-0011)**

```scala
// Group owns its tables
type GroupOwns = (
  Entry["groupData", GroupId, GroupData],         // groupId → metadata
  Entry["groupMember", (GroupId, Account), Unit], // (groupId, account) → membership
)

// Group needs Accounts tables
type GroupNeeds = (
  Entry["accountInfo", Utf8, AccountInfo],  // From AccountsBP
)

// Transactions
trait CreateGroup extends Tx:
  type Reads = GroupNeeds     // Check coordinator exists
  type Writes = GroupOwns      // Create group
  def groupId: GroupId
  def coordinator: Account     // Must exist in accountInfo

trait AddAccounts extends Tx:
  type Reads = GroupOwns ++ GroupNeeds  // Read group + validate accounts
  type Writes = GroupOwns                // Write members
  def groupId: GroupId
  def accounts: Set[Account]

// Reducer with dependency injection
class GroupReducer[F[_]: Monad] 
  extends StateReducer0[F, GroupOwns, GroupNeeds]:
  
  def apply[T <: Tx](signedTx: Signed[T])(using
    Requires[signedTx.value.Reads, GroupOwns ++ GroupNeeds],
    Requires[signedTx.value.Writes, GroupOwns ++ GroupNeeds],
    ownsTables: Tables[F, GroupOwns],
    provider: TablesProvider[F, GroupNeeds],  // Injected accountInfo
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = 
    signedTx.value match
      case tx: CreateGroup =>
        // Access ownsTables for groupData
        // Access provider.tables for accountInfo validation
        ???

// Blueprint with explicit dependency
val groupBP = new ModuleBlueprint[
  IO, "group", GroupOwns, GroupNeeds, GroupTxs
](
  owns = groupSchema,
  reducer0 = new GroupReducer[IO],
  txs = groupTxRegistry,
  provider = ???  // Must be provided by caller (e.g., from mounted AccountsBP)
)
```

**Key insight**: GroupBP declares `Needs = GroupNeeds` at type level, requiring caller to inject AccountsBP's tables via TablesProvider.

### StateModule (Path-Bound)

Runtime module with instantiated tables:
- Contains Tables\[F, Owns\] with computed prefixes
- Bound to specific deployment Path
- Can extract TablesProvider for dependencies

### State Management

**StoreF\[F\]**: Effectful state monad
- Combines EitherT for errors and StateT for state
- Returns `StoreF[F][(Result, List[Event])]`

**StoreState**: Combines MerkleTrie state with AccessLog
- Tracks reads/writes for conflict detection
- Enables parallel transaction execution analysis

### Transaction Model

**Tx**: Base transaction trait
- Declares Reads/Writes schema requirements
- Defines Result and Event types
- Type-safe at compile time

**ModuleRoutedTx**: Transactions with routing
- Contains module-relative ModuleId
- Required for composed blueprints
- Routes to appropriate sub-reducer

### Dependency System

**TablesProvider**: Dependency injection for cross-module tables

```scala
trait TablesProvider[F[_], Schema <: Tuple]:
  def tables: Tables[F, Schema]
  def narrow[Subset <: Tuple](
    using TablesProjection[F, Subset, Schema]
  ): TablesProvider[F, Subset]

object TablesProvider:
  def empty[F[_]]: TablesProvider[F, EmptyTuple]
  
  // Create from mounted module
  def fromModule[F[_], Path <: Tuple, Owns <: Tuple, ...]()
    (module: StateModule[F, Path, Owns, ...])
    : TablesProvider[F, Owns]
```

**Deployment Pattern: Wiring Dependencies**

```scala
// Step 1: Mount AccountsBP (no dependencies)
val accountsModule = accountsBP.mount[("app", "accounts")]

// Step 2: Create provider from accounts
val accountsProvider: TablesProvider[IO, AccountsOwns] = 
  TablesProvider.fromModule(accountsModule)

// Step 3: Wire into GroupBP
val groupBPWired = new ModuleBlueprint[
  IO, "group", GroupOwns, GroupNeeds, GroupTxs
](
  owns = groupSchema,
  reducer0 = new GroupReducer[IO],
  txs = groupTxRegistry,
  provider = accountsProvider  // Inject accounts tables
)

// Step 4: Mount GroupBP
val groupModule = groupBPWired.mount[("app", "group")]
```

**Narrowing**: Select subset of provided tables

```scala
// Provider supplies multiple tables
type FullSchema = (
  Entry["accounts", Utf8, BigInt],
  Entry["balances", Utf8, BigInt],
  Entry["metadata", Utf8, String],
)

val fullProvider: TablesProvider[IO, FullSchema] = ???

// Module only needs accounts and balances
type PartialNeeds = (
  Entry["accounts", Utf8, BigInt],
  Entry["balances", Utf8, BigInt],
)

val narrowedProvider: TablesProvider[IO, PartialNeeds] = 
  fullProvider.narrow[PartialNeeds]
```

**Key properties:**
- Type-safe: Schema mismatch caught at compile time
- Reusable: Single mounted module can provide to multiple dependents
- Composable: Narrow to exactly what each module needs

**Requires**: Compile-time proof
- Validates transaction needs ⊆ schema
- Prevents missing table access
- Zero runtime overhead

---

[← Overview](README.md) | [한국어 →](../../ko/application/api.md)
