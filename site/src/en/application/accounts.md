# Accounts Module

The accounts module is the current named-account and key-management feature that
ships in `sigilaris-core`. Its public surface lives under
`org.sigilaris.core.application.feature.accounts`.

## Current Domain Surface

- `Account` is a real domain enum:
  - `Account.Named(Utf8)` for named accounts with on-chain account state
  - `Account.Unnamed(KeyId20)` for lightweight key-identified accounts
- `KeyId20` is a concrete 20-byte value derived from the public key, not a
  documentation-only `BigInt` alias.
- `AccountNonce` is the replay-protection counter stored in `AccountInfo`.
- `NonEmptyKeyIdDescriptions` and `NonEmptyKeyIds` are validated wrappers used
  by key-management transactions.

## Current Tables

- `Entry["accounts", Utf8, AccountInfo]`
  - `AccountInfo(guardian: Option[Account], nonce: AccountNonce)`
- `Entry["nameKey", (Utf8, KeyId20), KeyInfo]`
  - `KeyInfo(addedAt, expiresAt, description)`

Named-account state lives in these tables. Unnamed accounts remain
key-identified and do not allocate the same on-chain account record shape.

## Current Transactions

- `CreateNamedAccount`
- `UpdateAccount`
- `AddKeyIds`
- `RemoveKeyIds`
- `RemoveAccount`

These transactions all carry a real `TxEnvelope`, and mutating operations use
typed nonce and non-empty wrappers at the public surface.

```scala mdoc:compile-only
import java.time.Instant
import org.sigilaris.core.application.feature.accounts.domain.{
  Account,
  AccountNonce,
  KeyId20,
}
import org.sigilaris.core.application.feature.accounts.transactions.{
  AddKeyIds,
  CreateNamedAccount,
  RemoveKeyIds,
  UpdateAccount,
}
import org.sigilaris.core.application.transactions.{NetworkId, TxEnvelope}
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.Utf8

val keyPair = CryptoOps.generate()
val keyId = KeyId20.fromPublicKey(keyPair.publicKey)
val envelope = TxEnvelope(
  networkId = NetworkId.unsafeFromLong(1),
  createdAt = Instant.parse("2026-04-15T00:00:00Z"),
  memo = Some(Utf8("accounts example")),
)

val create = CreateNamedAccount(
  envelope = envelope,
  name = Utf8("alice"),
  initialKeyId = keyId,
  guardian = None,
)
val update = UpdateAccount(
  envelope = envelope,
  name = Utf8("alice"),
  nonce = AccountNonce.Zero.toBigNat,
  newGuardian = Some(Account.Named(Utf8("guardian"))),
)
val addKey = AddKeyIds.unsafe(
  envelope = envelope,
  name = Utf8("alice"),
  nonce = AccountNonce.Zero.toBigNat,
  keyIds = Map(keyId -> Utf8("alice-main")),
  expiresAt = None,
)
val removeKey = RemoveKeyIds.unsafe(
  envelope = envelope,
  name = Utf8("alice"),
  nonce = AccountNonce.Zero.toBigNat,
  keyIds = Set(keyId),
)
```

## Current Baseline

- Named accounts can carry an optional guardian for recovery-style operations.
- Key registration uses typed non-empty collections at construction time.
- Signature verification and key recovery are part of the reducer baseline for
  named-account mutations.
- Backward-compatible wire decoding still tolerates historical empty key-set
  payloads, but new transaction construction rejects them.

## Current Limitations

- Unnamed accounts intentionally do not expose the same recovery and metadata
  surface as named accounts.
- Removing an account does not solve external UTXO/account-balance policy by
  itself; that boundary stays outside this feature module.

## Related Pages

- [Application Module](README.md)
- [Group Module](group.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
