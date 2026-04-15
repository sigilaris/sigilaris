# Accounts Module

Accounts module은 현재 `sigilaris-core`에 포함된 named account 및
key-management feature다. public surface는
`org.sigilaris.core.application.feature.accounts` 아래에 있다.

## 현재 도메인 표면

- `Account`는 실제 domain enum이다.
  - `Account.Named(Utf8)`는 on-chain account state를 가지는 named account
  - `Account.Unnamed(KeyId20)`는 key identifier 기반의 lightweight account
- `KeyId20`은 문서 전용 `BigInt` alias가 아니라 public key에서 파생되는
  concrete 20-byte 값이다.
- `AccountNonce`는 `AccountInfo`에 저장되는 replay-protection counter다.
- `NonEmptyKeyIdDescriptions`, `NonEmptyKeyIds`는 key-management transaction에
  쓰이는 validated wrapper다.

## 현재 테이블

- `Entry["accounts", Utf8, AccountInfo]`
  - `AccountInfo(guardian: Option[Account], nonce: AccountNonce)`
- `Entry["nameKey", (Utf8, KeyId20), KeyInfo]`
  - `KeyInfo(addedAt, expiresAt, description)`

Named account state는 이 테이블들에 저장된다. Unnamed account는 key 기반
식별을 유지하며 같은 on-chain account record shape를 만들지 않는다.

## 현재 트랜잭션

- `CreateNamedAccount`
- `UpdateAccount`
- `AddKeyIds`
- `RemoveKeyIds`
- `RemoveAccount`

이 트랜잭션들은 모두 실제 `TxEnvelope`를 포함하며, state-changing surface는
typed nonce와 non-empty wrapper를 그대로 사용한다.

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

## 현재 Baseline

- Named account는 recovery-style operation을 위해 optional guardian을 가질 수
  있다.
- Key registration은 생성 시점부터 typed non-empty collection을 사용한다.
- Named account mutation의 signature verification과 key recovery는 reducer
  baseline에 포함돼 있다.
- 과거 wire compatibility를 위해 legacy empty key-set payload decoding은 남아
  있지만, 새로운 transaction construction은 이를 거부한다.

## 현재 제한 사항

- Unnamed account는 named account와 같은 recovery/metadata surface를 의도적으로
  제공하지 않는다.
- Account 제거는 외부 UTXO/account-balance policy까지 해결하지 않으며, 그
  경계는 이 feature module 밖에 남아 있다.

## 관련 페이지

- [애플리케이션 모듈](README.md)
- [Group Module](group.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
