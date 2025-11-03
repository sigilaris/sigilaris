# Accounts Module (ADR-0010)

[← Overview](README.md) | [한국어 →](../../ko/application/accounts.md)

---

## Overview

The Accounts module implements a blockchain account model with human-readable names and key management. Based on ADR-0010, it provides Named Accounts with guardian-based key recovery and Unnamed Accounts for lightweight usage.

**Key Features:**
- **Named Accounts**: UTF-8 names with multiple public keys
- **Unnamed Accounts**: KeyId20-based accounts without on-chain state
- **Guardian Recovery**: Service operator assists with key recovery via off-chain KYC
- **Nonce Protection**: Prevents replay attacks on state-changing transactions

## Account Types

### Named Account

Identified by a UTF-8 string name, supports multiple public keys, and enables key recovery through guardians.

**Properties:**
- Name is fully public on-chain (visible in block explorers)
- One guardian per account (optional)
- Nonce for replay protection
- Multiple keys can be registered with metadata

**Use Cases:**
- Service accounts requiring key rotation
- Accounts needing recovery mechanisms
- Human-readable addresses for UX

### Unnamed Account

Identified only by KeyId20 (20-byte public key hash), without on-chain account state.

**Properties:**
- No on-chain account metadata
- Balance tracked in UTXO model only
- Cannot recover if private key is lost
- Lightweight for one-time or temporary usage

**Use Cases:**
- One-time payment addresses
- Temporary testing accounts
- Privacy-focused lightweight accounts

## Public Key Identifier (KeyId20)

Uses Ethereum's address generation scheme:
- Compute Keccak256 hash of 64-byte public key
- Take last 20 bytes as KeyId20
- Collision risk is acceptable (proven in Ethereum)

**Example:**
```
PublicKey: 04 <32-byte X> <32-byte Y>  (64 bytes uncompressed)
Hash:      Keccak256(64 bytes)         (32 bytes)
KeyId20:   Last 20 bytes of hash       (20 bytes)
```

## State Schema

### Account Information
```scala
Entry["accountInfo", Name, AccountInfo]
```

**AccountInfo** fields:
- `guardian: Option[Account]` - Single guardian (may be None)
- `nonce: BigNat` - Sequential number for state-changing transactions (increments by exactly +1)

### Key Registration (Named Accounts Only)
```scala
Entry["nameKey", (Name, KeyId20), KeyInfo]
```

**KeyInfo** fields:
- `addedAt: Instant` - Timestamp when key was added
- `expiresAt: Option[Instant]` - Optional expiration time
- `description: Utf8` - Human-readable description for the key

### Balance (UTXO Model)
Managed separately in UTXO module:
```scala
Entry["utxo", (AccountId, TokenDefId, TokenId, UTXOHash), Unit]
```

`AccountId` is `Name` for Named Accounts, `KeyId20` for Unnamed Accounts.

## Transactions

All account transactions include an **Envelope** with common fields:
- `networkId: BigNat` - Chain/network identifier
- `createdAt: Instant` - Transaction creation timestamp
- `memo: Option[Utf8]` - Optional memo for audit/operational purposes

Signatures are verified over the hash of the entire payload including the envelope.

### CreateNamedAccount

Creates a new Named Account with initial key and optional guardian.

**Parameters:**
- `name: Name` - Account name (UTF-8 string)
- `initialKeyId: KeyId20` - Initial public key identifier
- `guardian: Option[Account]` - Optional single guardian

**Signature:** Private key corresponding to `initialKeyId`

**Preconditions:**
- `name` must not exist in account state
- `name` must be valid UTF-8

**Postconditions:**
- Creates `name → AccountInfo(guardian, nonce=0)`
- Creates `(name, initialKeyId) → KeyInfo(now, None, "")`

### UpdateAccount

Updates account information (change guardian).

**Parameters:**
- `name: Name`
- `nonce: BigNat`
- `newGuardian: Option[Account]`

**Signature:** One of the registered keys for this account, or the guardian

**Preconditions:**
- Account exists
- `nonce` matches current account nonce

**Postconditions:**
- Updates `AccountInfo.guardian` (set/change/remove)
- Increments `AccountInfo.nonce`

### AddKeyIds

Registers new public keys to a Named Account.

**Parameters:**
- `name: Name`
- `nonce: BigNat`
- `keyIds: Map[KeyId20, Utf8]` - Key identifiers and descriptions
- `expiresAt: Option[Instant]` - Optional expiration applied to all new keys

**Signature:** One of the registered keys for this account, or the guardian

**Preconditions:**
- Account exists
- `nonce` matches current account nonce
- Each `keyId` must not be already registered for this account

**Postconditions:**
- Creates `(name, keyId) → KeyInfo(addedAt=now, expiresAt, description)` for each key
- Increments `AccountInfo.nonce`

### RemoveKeyIds

Removes public keys from a Named Account.

**Parameters:**
- `name: Name`
- `nonce: BigNat`
- `keyIds: Set[KeyId20]`

**Signature:** One of the registered keys for this account, or the guardian

**Preconditions:**
- Account exists
- `nonce` matches current account nonce
- Each `keyId` must be registered for this account

**Postconditions:**
- Removes `(name, keyId)` key information entries
- Increments `AccountInfo.nonce`
- Can remove the last key (balance in UTXO managed separately)

### RemoveAccount

Deletes a Named Account.

**Parameters:**
- `name: Name`
- `nonce: BigNat`

**Signature:** One of the registered keys for this account, or the guardian

**Preconditions:**
- Account exists
- `nonce` matches current account nonce
- Balance check NOT enforced at module boundary (ensured by operational policy)

**Postconditions:**
- Removes account from state
- Removes all `(name, *)` key information
- Increments `AccountInfo.nonce` (before deletion)
- UTXO balance unaffected (becomes inaccessible)

## Guardian System

**Guardian Role:**
- Service operator acts as guardian
- Assists with key recovery via off-chain KYC
- Can add/remove keys on behalf of account owner
- Centralized trust model for private blockchain

**Recovery Process:**
1. User loses private key
2. User contacts service operator (guardian)
3. Off-chain KYC verification
4. Guardian signs `AddKeyIds` transaction with new key
5. User regains access with new private key

**Security Considerations:**
- Guardian has full control over account keys
- Requires trust in service operator
- Appropriate for private/consortium blockchains
- Not suitable for trustless/public blockchains

## Blueprint Structure

```scala mdoc:compile-only
import cats.Monad
import cats.effect.IO
import java.time.Instant
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

// Domain types (from ADR-0010)
type KeyId20 = BigInt  // 20-byte public key hash
type Account = Utf8    // Named or Unnamed account identifier

case class AccountInfo(
  guardian: Option[Account],
  nonce: BigInt :| Positive0
)

case class KeyInfo(
  addedAt: Instant,
  expiresAt: Option[Instant],
  description: Utf8
)

// Note: In production, provide ByteCodec instances for domain types
// given ByteCodec[AccountInfo] = ...
// given ByteCodec[KeyInfo] = ...

// Schema
type AccountsOwns = (
  Entry["accountInfo", Utf8, AccountInfo],       // name → AccountInfo
  Entry["nameKey", (Utf8, KeyId20), KeyInfo],    // (name, keyId) → KeyInfo
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

// Blueprint (path-independent)
// val accountsBP = new ModuleBlueprint[
//   IO, "accounts", AccountsOwns, AccountsNeeds, AccountsTxs
// ](...)
```

**Key Properties:**
- `Needs = EmptyTuple`: No external dependencies
- Path-independent: Can deploy anywhere
- Reusable: Same blueprint for multiple instances

## Deployment Examples

### Standalone Deployment
```scala
val accountsModule = accountsBP.mount[("app", "accounts")]
```

### Shared by Multiple Modules
```scala
// Single accounts instance shared
val accounts = accountsBP.mount[("app", "shared", "accounts")]

// Group and Token both reference accounts
val groupModule = groupBP
  .withProvider(TablesProvider.fromModule(accounts))
  .mount[("app", "group")]

val tokenModule = tokenBP
  .withProvider(TablesProvider.fromModule(accounts))
  .mount[("app", "token")]
```

### Sandboxed per Module
```scala
// Each module has isolated accounts
val groupAccounts = accountsBP.mount[("app", "group", "accounts")]
val tokenAccounts = accountsBP.mount[("app", "token", "accounts")]

val groupModule = groupBP
  .withProvider(TablesProvider.fromModule(groupAccounts))
  .mount[("app", "group")]

val tokenModule = tokenBP
  .withProvider(TablesProvider.fromModule(tokenAccounts))
  .mount[("app", "token")]
```

## Design Decisions

**Named vs Unnamed:**
- Named: User-friendly, recoverable, requires on-chain state
- Unnamed: Lightweight, no recovery, minimal on-chain footprint

**Single Guardian:**
- Simpler operational model
- Easier to reason about responsibility
- Sufficient for private blockchain environments

**Nonce-based Replay Protection:**
- Simple sequential counter
- Prevents transaction replay
- Must increment by exactly +1 for state-changing transactions

**KeyId20 (Ethereum-compatible):**
- Proven collision resistance
- Interoperability with Ethereum tooling
- 20 bytes is sufficient for security

## References

- [ADR-0010: Blockchain Account Model and Key Management](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0010-blockchain-account-model-and-key-management.md)
- [ADR-0009: Blockchain Application Architecture](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)
- [Application Overview](README.md)

---

© 2025 Sigilaris. All rights reserved.
