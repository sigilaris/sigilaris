# Group Module (ADR-0011)

[← Overview](README.md) | [한국어 →](../../ko/application/group.md)

---

## Overview

The Group module implements account group management for blockchain applications. Based on ADR-0011, it provides coordinator-based group administration with membership tracking and requires Accounts module for coordinator validation.

**Key Features:**
- **Coordinator Model**: Single coordinator manages group membership
- **Membership Tracking**: On-chain membership with memberCount integrity
- **Account Dependencies**: Validates coordinators exist in Accounts module
- **Disband Constraints**: Groups must be empty before disbandment

## Group Model

### Group Structure

Each group consists of:
- **GroupId**: UTF-8 string identifier (no format restrictions for operational flexibility)
- **Name**: Immutable group name set at creation
- **Coordinator**: Single Account with management privileges
- **Members**: Set of accounts belonging to the group
- **Nonce**: Sequential counter for replay protection
- **MemberCount**: Tracks current membership size for disband validation

**Key Design**: Coordinator is NOT automatically a member - must be explicitly added if needed.

## State Schema

### Group Metadata
```scala
Entry["groupData", GroupId, GroupData]
```

**GroupData** fields:
- `name: Utf8` - Group name (immutable after creation)
- `coordinator: Account` - Coordinator account
- `nonce: BigNat` - Transaction replay protection counter
- `memberCount: BigNat` - Current member count (for disband validation)
- `createdAt: Instant` - Group creation timestamp

### Group Membership
```scala
Entry["groupMember", (GroupId, Account), Unit]
```

Represents membership as existence-only (key exists = member).

**No reverse index**: Account → Groups not maintained. Operational queries scan with group prefix.

## Dependencies

### Required Tables (from Accounts Module)

```scala
type GroupNeeds = Entry["accountInfo", Utf8, AccountInfo] *: EmptyTuple
```

Group module depends on Accounts to validate coordinator existence when creating groups.

## Transactions

All group transactions include an **Envelope** with:
- `networkId: BigNat` - Chain/network identifier
- `createdAt: Instant` - Transaction creation timestamp
- `memo: Option[Utf8]` - Optional operational memo

All group management transactions (except CreateGroup) require:
- `groupNonce: BigNat` - Must match current group nonce for replay protection

### CreateGroup

Creates a new group with specified coordinator.

**Parameters:**
- `groupId: GroupId` - UTF-8 string identifier
- `name: Utf8` - Immutable group name
- `coordinator: Account` - Initial coordinator

**Signature:** Coordinator account

**Preconditions:**
- Group with `groupId` must not exist
- Coordinator must exist in accountInfo (validated via dependency)

**Postconditions:**
- Creates `group(groupId) = GroupData(name, coordinator, nonce=0, memberCount=0, ...)`
- Emits `GroupCreated(groupId, coordinator, name)` event

### DisbandGroup

Dissolves an existing group.

**Parameters:**
- `groupId: GroupId`
- `groupNonce: BigNat`

**Signature:** Coordinator

**Preconditions:**
- Group exists
- `groupNonce` matches `group(groupId).nonce`
- **Group must be empty**: `memberCount == 0`
  - All members must be removed via `RemoveAccounts` first

**Postconditions:**
- Removes `group(groupId)` state
- All `groupAccount(groupId, *)` entries already removed (guaranteed by memberCount constraint)
- Same `groupId` can be reused for new group (new nonce starts at 0)
- Emits `GroupDisbanded(groupId)` event

**Design Rationale:**
- MerkleTrie API lacks range deletion/iteration (planned for ADR-0009 Phase 8)
- Disbanding non-empty group would leave orphaned `groupAccount` entries
- Reusing `groupId` would resurrect old members (security issue)
- **Solution**: Require `memberCount == 0` before disband

### AddAccounts

Adds accounts to group membership.

**Parameters:**
- `groupId: GroupId`
- `accounts: Set[Account]`
- `groupNonce: BigNat`

**Signature:** Coordinator

**Preconditions:**
- Group exists
- `groupNonce` matches current nonce
- `accounts` must not be empty (rejected during validation)

**Postconditions:**
- Creates `(groupId, account)` entries for each account
- Already-member accounts: idempotent no-op (no state change)
- Increments `group.nonce`
- Increments `memberCount` by number of actually added accounts
- Emits `GroupMembersAdded(groupId, added=actuallyAdded)` event

### RemoveAccounts

Removes accounts from group membership.

**Parameters:**
- `groupId: GroupId`
- `accounts: Set[Account]`
- `groupNonce: BigNat`

**Signature:** Coordinator

**Preconditions:**
- Group exists
- `groupNonce` matches current nonce
- `accounts` must not be empty

**Postconditions:**
- Removes `(groupId, account)` entries
- Non-member accounts: idempotent no-op
- Increments `group.nonce`
- Decrements `memberCount` by number of actually removed accounts
- Emits `GroupMembersRemoved(groupId, removed=actuallyRemoved)` event

### ReplaceCoordinator

Changes group coordinator.

**Parameters:**
- `groupId: GroupId`
- `newCoordinator: Account`
- `groupNonce: BigNat`

**Signature:** Current coordinator

**Preconditions:**
- Group exists
- `groupNonce` matches current nonce

**Postconditions:**
- Updates `group(groupId).coordinator = newCoordinator`
- If `oldCoordinator == newCoordinator`: idempotent no-op (but nonce still increments)
- Increments `group.nonce`
- Emits `GroupCoordinatorReplaced(groupId, old, new)` event

## Blueprint Structure

```scala mdoc:compile-only
import cats.Monad
import cats.effect.IO
import java.time.Instant
import scala.Tuple.++
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.codec.byte.ByteCodec
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

// Domain types (from ADR-0010/0011)
type GroupId = Utf8
type Account = Utf8

case class AccountInfo(guardian: Option[Account], nonce: BigInt :| Positive0)
case class GroupData(
  name: Utf8,
  coordinator: Account,
  nonce: BigInt :| Positive0,
  memberCount: BigInt :| Positive0,
  createdAt: Instant
)

// Note: In production, provide ByteCodec instances for domain types
// given ByteCodec[AccountInfo] = ...
// given ByteCodec[GroupData] = ...

// Group owns its tables
type GroupOwns = (
  Entry["groupData", GroupId, GroupData],        // groupId → GroupData
  Entry["groupMember", (GroupId, Account), Unit], // (groupId, account) → membership
)

// Group needs Accounts tables
type GroupNeeds = Entry["accountInfo", Utf8, AccountInfo] *: EmptyTuple

// Transactions
trait CreateGroup extends Tx:
  type Reads = GroupNeeds   // Validate coordinator exists
  type Writes = GroupOwns    // Create group
  def groupId: GroupId
  def nameValue: Utf8
  def coordinator: Account

trait AddAccounts extends Tx:
  type Reads = GroupOwns ++ GroupNeeds  // Read group + validate accounts
  type Writes = GroupOwns                // Update members
  def groupId: GroupId
  def accounts: Set[Account]
  def groupNonce: BigInt :| Positive0

// Reducer with dependency injection
class GroupReducer[F[_]: Monad] 
  extends StateReducer0[F, GroupOwns, GroupNeeds]:
  
  def apply[T <: Tx](signedTx: Signed[T])(
    using
      Requires[signedTx.value.Reads, GroupOwns ++ GroupNeeds],
      Requires[signedTx.value.Writes, GroupOwns ++ GroupNeeds],
      Tables[F, GroupOwns],
      TablesProvider[F, GroupNeeds],  // Injected accountInfo
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = ???

// Blueprint (requires AccountsBP provider)
// val groupBP = new ModuleBlueprint[
//   IO, "group", GroupOwns, GroupNeeds, GroupTxs
// ](
//   owns = groupSchema,
//   reducer0 = new GroupReducer[IO],
//   txs = groupTxRegistry,
//   provider = accountsProvider  // Must be wired from mounted AccountsBP
// )
```

**Key Properties:**
- `Needs = GroupNeeds`: Depends on Accounts module
- Reducer accesses both `ownsTables` and `provider.tables`
- Path-independent: Can deploy anywhere

## Deployment Patterns

### Shared Accounts
```scala
// Mount Accounts first
val accountsModule = accountsBP.mount[("app", "accounts")]

// Create provider from accounts
val accountsProvider = TablesProvider.fromModule(accountsModule)

// Wire into GroupBP
val groupBPWired = new ModuleBlueprint[...](
  ...,
  provider = accountsProvider
)

// Mount Group
val groupModule = groupBPWired.mount[("app", "group")]
```

### Sandboxed Accounts
```scala
// Each module gets isolated accounts
val groupAccounts = accountsBP.mount[("app", "group", "accounts")]
val groupProvider = TablesProvider.fromModule(groupAccounts)

val groupModule = groupBP
  .withProvider(groupProvider)
  .mount[("app", "group")]
```

## MemberCount Integrity

The `memberCount` field ensures disband safety:

**Invariant**: `memberCount == actual number of groupMember entries`

**Enforcement:**
- `CreateGroup`: Sets `memberCount = 0`
- `AddAccounts`: Increments by actually-added count (idempotent for existing members)
- `RemoveAccounts`: Decrements by actually-removed count (idempotent for non-members)
- `DisbandGroup`: Only allowed when `memberCount == 0`

**Why?** MerkleTrie lacks range deletion. Without memberCount constraint:
1. Disband non-empty group → orphaned `groupMember` entries
2. Recreate same groupId → old members resurrected (security bug)

**Solution**: Force empty group before disband ensures complete cleanup.

## Use Cases

### Permission Groups
```scala
// Define issuer group for token minting
CreateGroup(groupId = "token-issuers", coordinator = serviceAccount)
AddAccounts(groupId = "token-issuers", accounts = Set(alice, bob))

// Check if account can mint
if (isMember("token-issuers", alice)) {
  // Allow mint operation
}
```

### Role-Based Access
```scala
// Admin group
CreateGroup(groupId = "admins", coordinator = rootAccount)
AddAccounts(groupId = "admins", accounts = Set(admin1, admin2))

// Operator group
CreateGroup(groupId = "operators", coordinator = admin1)
AddAccounts(groupId = "operators", accounts = Set(op1, op2, op3))
```

### Dynamic Membership
```scala
// Add new member
AddAccounts(groupId = "reviewers", accounts = Set(newReviewer))

// Remove departed member
RemoveAccounts(groupId = "reviewers", accounts = Set(formerReviewer))

// Transfer coordinator role
ReplaceCoordinator(
  groupId = "reviewers",
  newCoordinator = seniorReviewer
)
```

## Design Decisions

**Single Coordinator:**
- Simpler operational model
- Clear responsibility structure
- Sufficient for private blockchain environments
- Future: Multi-sig/voting can be layered on top

**Coordinator Not Auto-Member:**
- Separates management role from membership
- Flexibility: coordinator may only manage, not participate
- Explicit: must add coordinator as member if needed

**No Reverse Index:**
- Operational queries scan with group prefix
- Reduces write overhead (no dual maintenance)
- Sufficient for expected query patterns

**Empty-Group Disband:**
- Ensures complete cleanup without range deletion API
- Prevents security bugs from orphaned entries
- Trade-off: requires RemoveAccounts before DisbandGroup

## References

- [ADR-0011: Blockchain Account Group Management](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0011-blockchain-account-group-management.md)
- [ADR-0010: Blockchain Account Model](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0010-blockchain-account-model-and-key-management.md)
- [ADR-0009: Blockchain Application Architecture](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)
- [Accounts Module](accounts.md)
- [Application Overview](README.md)

---

© 2025 Sigilaris. All rights reserved.
