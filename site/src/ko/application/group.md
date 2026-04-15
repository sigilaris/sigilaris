# Group Module

Group module은 현재 `sigilaris-core`에 포함된 coordinator-managed membership
feature다. public surface는
`org.sigilaris.core.application.feature.group` 아래에 있다.

## 현재 도메인 표면

- `GroupId`는 non-empty UTF-8을 검증하는 identifier다.
- `GroupNonce`는 group mutation용 replay-protection counter다.
- `MemberCount`는 guarded add/subtract helper를 가진 typed counter다.
- `NonEmptyGroupAccounts`는 membership mutation transaction에서 쓰는 validated
  wrapper다.
- `GroupData`는 `name`, `coordinator`, `nonce`, `memberCount`, `createdAt`를
  저장한다.

## 현재 테이블

- `Entry["groups", GroupId, GroupData]`
- `Entry["groupAccounts", (GroupId, Account), Unit]`

`groups` table은 metadata를 보관하고, membership은 `(GroupId, Account)` key를
가지는 typed existence entry로 표현된다.

## 의존성 경계

Reducer는 named-account key verification을 위해 accounts provider에 의존한다.
이 의존성은 blueprint layer에서 명시적으로 wiring되며, group module이
독립적인 identity system인 척하지 않는다.

## 현재 트랜잭션

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

## 현재 Baseline

- Coordinator가 membership과 coordinator replacement를 관리한다.
- Coordinator는 자동으로 member로 취급되지 않는다.
- Membership mutation transaction은 생성 시점에 empty account set을 거부한다.
- Reducer는 실제 추가/삭제 수에 맞춰 `memberCount`를 동기화한다.

## 현재 제한 사항

- Group 해산은 empty group에서만 허용된다. Reducer는 orphaned membership entry를
  막기 위해 non-empty group 삭제를 의도적으로 거부한다.
- 현재 모듈은 reverse `Account -> Groups` index를 유지하지 않는다.

## 관련 페이지

- [애플리케이션 모듈](README.md)
- [Accounts Module](accounts.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
