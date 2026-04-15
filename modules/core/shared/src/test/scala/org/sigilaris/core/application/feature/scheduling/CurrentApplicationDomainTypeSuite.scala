package org.sigilaris.core.application.feature.scheduling

import java.time.Instant

import munit.FunSuite

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteDecoder.ops.*
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.codec.json.{JsonDecoder, JsonValue}
import org.sigilaris.core.application.feature.accounts.domain.{
  Account,
  AccountNonce,
  KeyId20,
  NonEmptyKeyIdDescriptions,
  NonEmptyKeyIds,
}
import org.sigilaris.core.application.feature.accounts.transactions.{
  AddKeyIds,
  RemoveKeyIds,
}
import org.sigilaris.core.application.feature.group.domain.{
  GroupId,
  GroupNonce,
  MemberCount,
  NonEmptyGroupAccounts,
}
import org.sigilaris.core.application.feature.group.transactions.{
  AddAccounts,
  RemoveAccounts,
}
import org.sigilaris.core.application.transactions.{NetworkId, TxEnvelope}
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.{BigNat, Utf8}

final class CurrentApplicationDomainTypeSuite extends FunSuite:
  private val envelope =
    TxEnvelope(
      networkId = NetworkId.unsafeFromLong(1),
      createdAt = Instant.parse("2026-04-15T00:00:00Z"),
      memo = None,
    )
  private val alice       = Account.Named(Utf8("alice"))
  private val bob         = Account.Named(Utf8("bob"))
  private val keyId       = KeyId20.fromPublicKey(CryptoOps.generate().publicKey)

  test("group and batch identifiers reject empty strings for new values"):
    assertEquals(GroupId(Utf8("")), Left("GroupId must be non-empty"))
    assertEquals(
      BatchIdempotencyKey.fromString(""),
      Left("BatchIdempotencyKey must be non-empty"),
    )

    val groupIdError =
      intercept[IllegalArgumentException]:
        GroupId.unsafe(Utf8(""))
    assertEquals(groupIdError.getMessage, "GroupId must be non-empty")

    val batchIdError =
      intercept[IllegalArgumentException]:
        BatchIdempotencyKey.unsafeFromString("")
    assertEquals(
      batchIdError.getMessage,
      "BatchIdempotencyKey must be non-empty",
    )

    val emptyUtf8Bytes = ByteEncoder[Utf8].encode(Utf8(""))
    assertEquals(
      ByteDecoder[GroupId].decode(emptyUtf8Bytes).map(_.value.toUtf8),
      Right(Utf8("")),
    )
    assertEquals(
      ByteDecoder[BatchIdempotencyKey]
        .decode(emptyUtf8Bytes)
        .map(_.value.toUtf8),
      Right(Utf8("")),
    )
    assertEquals(
      JsonDecoder[GroupId].decode(JsonValue.JString("")).map(_.toUtf8),
      Right(Utf8("")),
    )
    assertEquals(
      JsonDecoder[BatchIdempotencyKey]
        .decode(JsonValue.JString(""))
        .map(_.toUtf8),
      Right(Utf8("")),
    )
    assertEquals(
      JsonDecoder[Map[GroupId, Int]]
        .decode(JsonValue.obj("" -> JsonValue.JNumber(BigDecimal(1))))
        .map(_.map((decodedGroupId, value) => decodedGroupId.toUtf8.asString -> value)),
      Right(Map("" -> 1)),
    )

  test("typed counters advance without falling back to raw BigNat math"):
    assertEquals(AccountNonce.Zero.next, AccountNonce.unsafeFromLong(1))
    assertEquals(GroupNonce.Zero.next, GroupNonce.unsafeFromLong(1))
    assertEquals(
      MemberCount.Zero.add(2),
      MemberCount(BigNat.unsafeFromLong(2)),
    )

    val memberCount = MemberCount.Zero.add(3)
    assertEquals(memberCount.unsafeSubtract(1), MemberCount.Zero.add(2))
    assert(MemberCount.Zero.trySubtract(1).isLeft)
    assertEquals(
      MemberCount.Zero.trySubtract(-1),
      Left("MemberCount.subtract requires non-negative delta"),
    )
    assertEquals(
      MemberCount.Zero.tryAdd(-1),
      Left("MemberCount.add requires non-negative delta"),
    )

    val underflowError =
      intercept[IllegalArgumentException]:
        MemberCount.Zero.unsafeSubtract(1)
    assert(underflowError.getMessage.nonEmpty)

    val negativeAddError =
      intercept[IllegalArgumentException]:
        MemberCount.Zero.add(-1)
    assertEquals(
      negativeAddError.getMessage,
      "MemberCount.add requires non-negative delta",
    )

  test("non-empty transaction shapes reject empty collections"):
    val groupId = GroupId.unsafe(Utf8("ops"))

    assertEquals(
      AddKeyIds(
        envelope = envelope,
        name = Utf8("alice"),
        nonce = AccountNonce.Zero.toBigNat,
        keyIds = Map.empty,
        expiresAt = None,
      ),
      Left("keyIds must be non-empty"),
    )
    assertEquals(
      RemoveKeyIds(
        envelope = envelope,
        name = Utf8("alice"),
        nonce = AccountNonce.Zero.toBigNat,
        keyIds = Set.empty,
      ),
      Left("keyIds must be non-empty"),
    )
    assertEquals(
      AddAccounts(
        envelope = envelope,
        groupId = groupId,
        accounts = Set.empty,
        groupNonce = GroupNonce.Zero.toBigNat,
      ),
      Left("accounts must be non-empty"),
    )
    assertEquals(
      RemoveAccounts(
        envelope = envelope,
        groupId = groupId,
        accounts = Set.empty,
        groupNonce = GroupNonce.Zero.toBigNat,
      ),
      Left("accounts must be non-empty"),
    )

    val addKeyIdsError =
      intercept[IllegalArgumentException]:
        AddKeyIds.unsafe(
          envelope = envelope,
          name = Utf8("alice"),
          nonce = AccountNonce.Zero.toBigNat,
          keyIds = Map.empty,
          expiresAt = None,
        )
    assertEquals(addKeyIdsError.getMessage, "keyIds must be non-empty")

    val addAccountsError =
      intercept[IllegalArgumentException]:
        AddAccounts.unsafe(
          envelope = envelope,
          groupId = groupId,
          accounts = Set.empty,
          groupNonce = GroupNonce.Zero.toBigNat,
        )
    assertEquals(
      addAccountsError.getMessage,
      "accounts must be non-empty",
    )

    assertEquals(
      AddKeyIds.unsafe(
        envelope = envelope,
        name = Utf8("alice"),
        nonce = AccountNonce.Zero.toBigNat,
        keyIds = Map(keyId -> Utf8("alice-key")),
        expiresAt = None,
      ).keyIds.keySet,
      Set(keyId),
    )
    assertEquals(
      RemoveKeyIds.unsafe(
        envelope = envelope,
        name = Utf8("alice"),
        nonce = AccountNonce.Zero.toBigNat,
        keyIds = Set(keyId),
      ).keyIds.toSet,
      Set(keyId),
    )
    assertEquals(
      AddAccounts.unsafe(
        envelope = envelope,
        groupId = groupId,
        accounts = Set(alice, bob),
        groupNonce = GroupNonce.Zero.toBigNat,
      ).accounts.toSet,
      Set(alice, bob),
    )
    assertEquals(
      RemoveAccounts.unsafe(
        envelope = envelope,
        groupId = groupId,
        accounts = Set(bob),
        groupNonce = GroupNonce.Zero.toBigNat,
      ).accounts.toSet,
      Set(bob),
    )

  test(
    "validated opaque byte codecs round-trip while preserving legacy wire compatibility",
  ):
    val accountNonce = AccountNonce.unsafeFromLong(2)
    val groupNonce   = GroupNonce.unsafeFromLong(3)
    val memberCount  = MemberCount.Zero.add(4)
    val keyDescriptions =
      NonEmptyKeyIdDescriptions.unsafe(Map(keyId -> Utf8("alice-key")))
    val keyIds = NonEmptyKeyIds.unsafe(Set(keyId))
    val accounts = NonEmptyGroupAccounts.unsafe(Set(alice, bob))

    assertEquals(accountNonce.toBytes.to[AccountNonce], Right(accountNonce))
    assertEquals(groupNonce.toBytes.to[GroupNonce], Right(groupNonce))
    assertEquals(memberCount.toBytes.to[MemberCount], Right(memberCount))
    assertEquals(
      keyDescriptions.toBytes.to[NonEmptyKeyIdDescriptions],
      Right(keyDescriptions),
    )
    assertEquals(keyIds.toBytes.to[NonEmptyKeyIds], Right(keyIds))
    assertEquals(accounts.toBytes.to[NonEmptyGroupAccounts], Right(accounts))

    val emptyKeyIdDescriptionsBytes =
      ByteEncoder[Map[KeyId20, Utf8]].encode(Map.empty)
    val emptyKeyIdsBytes =
      ByteEncoder[Set[KeyId20]].encode(Set.empty)
    val emptyAccountsBytes =
      ByteEncoder[Set[Account]].encode(Set.empty)

    assertEquals(
      ByteDecoder[NonEmptyKeyIdDescriptions]
        .decode(emptyKeyIdDescriptionsBytes)
        .map(_.value.toMap),
      Right(Map.empty),
    )
    assertEquals(
      ByteDecoder[NonEmptyKeyIds]
        .decode(emptyKeyIdsBytes)
        .map(_.value.toSet),
      Right(Set.empty),
    )
    assertEquals(
      ByteDecoder[NonEmptyGroupAccounts]
        .decode(emptyAccountsBytes)
        .map(_.value.toSet),
      Right(Set.empty),
    )

  test("legacy empty transaction payloads remain decodable"):
    val groupId = GroupId.unsafe(Utf8("ops"))

    val legacyAddKeyIdsBytes =
      envelope.toBytes ++
        Utf8("alice").toBytes ++
        AccountNonce.Zero.toBytes ++
        ByteEncoder[Map[KeyId20, Utf8]].encode(Map.empty) ++
        ByteEncoder[Option[Instant]].encode(None: Option[Instant])
    val legacyAddAccountsBytes =
      envelope.toBytes ++
        groupId.toBytes ++
        ByteEncoder[Set[Account]].encode(Set.empty) ++
        GroupNonce.Zero.toBytes

    assertEquals(
      legacyAddKeyIdsBytes.to[AddKeyIds].map(_.keyIds.toMap),
      Right(Map.empty),
    )
    assertEquals(
      legacyAddAccountsBytes.to[AddAccounts].map(_.accounts.toSet),
      Right(Set.empty),
    )
