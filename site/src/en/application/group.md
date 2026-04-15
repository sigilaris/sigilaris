# Group Module

The group module is the current coordinator-managed membership feature that
ships in `sigilaris-core`. Its public surface lives under
`org.sigilaris.core.application.feature.group`.

## Current Domain Surface

- `GroupId` is a validated non-empty UTF-8 identifier.
- `GroupNonce` is the replay-protection counter for group mutations.
- `MemberCount` is a typed counter with guarded add/subtract helpers.
- `NonEmptyGroupAccounts` is the validated wrapper used by membership mutation
  transactions.
- `GroupData` stores `name`, `coordinator`, `nonce`, `memberCount`, and
  `createdAt`.

## Current Tables

- `Entry["groups", GroupId, GroupData]`
- `Entry["groupAccounts", (GroupId, Account), Unit]`

The groups table owns metadata, while membership is represented by typed
existence entries keyed by `(GroupId, Account)`.

## Dependency Boundary

The reducer depends on the accounts provider for named-account key verification.
That dependency is explicit in the blueprint layer; the group module does not
pretend to be a standalone identity system.

## Current Transactions

- `CreateGroup`
- `DisbandGroup`
- `AddAccounts`
- `RemoveAccounts`
- `ReplaceCoordinator`

```scala mdoc:compile-only
import java.time.Instant
import org.sigilaris.core.application.feature.accounts.domain.Account
import org.sigilaris.core.application.feature.group.domain.{
  GroupId,
  GroupNonce,
}
import org.sigilaris.core.application.feature.group.transactions.{
  AddAccounts,
  CreateGroup,
  DisbandGroup,
  RemoveAccounts,
  ReplaceCoordinator,
}
import org.sigilaris.core.application.transactions.{NetworkId, TxEnvelope}
import org.sigilaris.core.datatype.Utf8

val envelope = TxEnvelope(
  networkId = NetworkId.unsafeFromLong(1),
  createdAt = Instant.parse("2026-04-15T00:00:00Z"),
  memo = Some(Utf8("group example")),
)
val groupId = GroupId.unsafe(Utf8("ops"))
val alice = Account.Named(Utf8("alice"))
val bob = Account.Named(Utf8("bob"))

val create = CreateGroup(envelope, groupId, Utf8("Operations"), alice)
val add = AddAccounts.unsafe(
  envelope = envelope,
  groupId = groupId,
  accounts = Set(bob),
  groupNonce = GroupNonce.Zero.toBigNat,
)
val remove = RemoveAccounts.unsafe(
  envelope = envelope,
  groupId = groupId,
  accounts = Set(bob),
  groupNonce = GroupNonce.Zero.toBigNat,
)
val replace = ReplaceCoordinator(
  envelope = envelope,
  groupId = groupId,
  newCoordinator = bob,
  groupNonce = GroupNonce.Zero.toBigNat,
)
val disband = DisbandGroup(
  envelope = envelope,
  groupId = groupId,
  groupNonce = GroupNonce.Zero.toBigNat,
)
```

## Current Baseline

- Coordinators manage membership and coordinator replacement.
- Coordinator identity is explicit and is not automatically counted as a member.
- Membership mutation transactions reject empty account sets at construction
  time.
- Reducer logic keeps `memberCount` in sync with actual additions/removals.

## Current Limitations

- Disbanding requires an empty group. The reducer intentionally refuses to
  delete a non-empty group to avoid orphaned membership entries.
- The current module does not maintain a reverse `Account -> Groups` index.

## Related Pages

- [Application Module](README.md)
- [Accounts Module](accounts.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
