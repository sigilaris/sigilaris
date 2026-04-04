package org.sigilaris.core.application.feature.scheduling

import cats.syntax.either.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.feature.accounts.domain.{Account, KeyId20}
import org.sigilaris.core.application.feature.accounts.transactions.{AddKeyIds, CreateNamedAccount, RemoveAccount, RemoveKeyIds, UpdateAccount}
import org.sigilaris.core.application.feature.group.domain.GroupId
import org.sigilaris.core.application.feature.group.transactions.{AddAccounts, CreateGroup, DisbandGroup, RemoveAccounts, ReplaceCoordinator}
import org.sigilaris.core.application.scheduling.{ConflictFootprint, FootprintDerivationFailure, FootprintDeriver, SchedulingClassification, StateRef}
import org.sigilaris.core.application.support.encoding.tablePrefixRuntime
import org.sigilaris.core.application.transactions.{Signed, Tx}
import org.sigilaris.core.codec.byte.ByteCodec
import org.sigilaris.core.crypto.{CryptoOps, Hash, PublicKey, Recover}

object CurrentApplicationScheduling:
  type AccountsPath = "app" *: "accounts" *: EmptyTuple
  type GroupsPath   = "app" *: "groups" *: EmptyTuple

  val documentedCompatibilityFamilies: Vector[String] = Vector.empty[String]

  private val accountsPrefix: ByteVector =
    tablePrefixRuntime[AccountsPath]("accounts")
  private val nameKeyPrefix: ByteVector =
    tablePrefixRuntime[AccountsPath]("nameKey")
  private val groupsPrefix: ByteVector =
    tablePrefixRuntime[GroupsPath]("groups")
  private val groupAccountsPrefix: ByteVector =
    tablePrefixRuntime[GroupsPath]("groupAccounts")

  private def stateRefFor[K: ByteCodec](
      tablePrefix: ByteVector,
      key: K,
  ): StateRef =
    StateRef.tableKey(tablePrefix, ByteCodec[K].encode(key))

  private def accountRef(
      name: org.sigilaris.core.datatype.Utf8,
  ): StateRef =
    stateRefFor(accountsPrefix, name)

  private def nameKeyRef(
      name: org.sigilaris.core.datatype.Utf8,
      keyId: KeyId20,
  ): StateRef =
    stateRefFor(nameKeyPrefix, (name, keyId))

  private def groupRef(
      groupId: GroupId,
  ): StateRef =
    stateRefFor(groupsPrefix, groupId)

  private def groupAccountRef(
      groupId: GroupId,
      account: Account,
  ): StateRef =
    stateRefFor(groupAccountsPrefix, (groupId, account))

  private def deriveRecoveredKeyId(
      publicKey: PublicKey,
  ): KeyId20 =
    val hash = CryptoOps.keccak256(publicKey.toBytes.toArray)
    KeyId20.unsafeApply(ByteVector.view(hash).takeRight(20))

  private def recoverSignerKeyId[T <: Tx](
      signed: Signed[T],
  )(using
      hashT: Hash[T],
      recoverT: Recover[T],
  ): Either[FootprintDerivationFailure, KeyId20] =
    recoverT
      .fromHash(hashT(signed.value), signed.sig.sig)
      .map(deriveRecoveredKeyId)
      .left
      .map: error =>
        FootprintDerivationFailure(
          reason = "signatureRecoveryFailed",
          detail = Some(error.msg),
        )

  private def signerKeyReads[T <: Tx](
      signed: Signed[T],
  )(using
      Hash[T],
      Recover[T],
  ): Either[FootprintDerivationFailure, Set[StateRef]] =
    signed.sig.account match
      case named: Account.Named =>
        recoverSignerKeyId(signed).map: recoveredKeyId =>
          Set(nameKeyRef(named.name, recoveredKeyId))
      case Account.Unnamed(_) =>
        Set.empty[StateRef].asRight[FootprintDerivationFailure]

  private def deriveUpdateAccountLike[T <: Tx](
      signed: Signed[T],
      targetName: org.sigilaris.core.datatype.Utf8,
  )(using
      Hash[T],
      Recover[T],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    signerKeyReads(signed).map: signerReads =>
      val targetAccount = accountRef(targetName)
      ConflictFootprint(
        reads = signerReads + targetAccount,
        writes = Set(targetAccount),
      )

  private def deriveGroupOnlyFootprint[T <: Tx](
      signed: Signed[T],
      groupId: GroupId,
  )(using
      Hash[T],
      Recover[T],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    signerKeyReads(signed).map: signerReads =>
      val groupState = groupRef(groupId)
      ConflictFootprint(
        reads = signerReads + groupState,
        writes = Set(groupState),
      )

  private def deriveGroupMembershipFootprint[T <: Tx](
      signed: Signed[T],
      groupId: GroupId,
      accounts: Set[Account],
  )(using
      Hash[T],
      Recover[T],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    signerKeyReads(signed).map: signerReads =>
      val groupState = groupRef(groupId)
      val memberRefs = accounts.map(account => groupAccountRef(groupId, account))
      ConflictFootprint(
        reads = signerReads + groupState ++ memberRefs,
        writes = Set(groupState) ++ memberRefs,
      )

  def deriveCreateNamedAccount(
      signed: Signed[CreateNamedAccount],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    val tx = signed.value
    val accountState = accountRef(tx.name)
    val signerKey = nameKeyRef(tx.name, tx.initialKeyId)
    ConflictFootprint(
      reads = Set(accountState),
      writes = Set(accountState, signerKey),
    )
      .asRight[FootprintDerivationFailure]

  def deriveUpdateAccount(
      signed: Signed[UpdateAccount],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    deriveUpdateAccountLike(signed, signed.value.name)

  def deriveAddKeyIds(
      signed: Signed[AddKeyIds],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    signerKeyReads(signed).map: signerReads =>
      val tx = signed.value
      val accountState = accountRef(tx.name)
      val keyRefs = tx.keyIds.keySet.map(keyId => nameKeyRef(tx.name, keyId))
      ConflictFootprint(
        reads = signerReads + accountState ++ keyRefs,
        writes = Set(accountState) ++ keyRefs,
      )

  def deriveRemoveKeyIds(
      signed: Signed[RemoveKeyIds],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    signerKeyReads(signed).map: signerReads =>
      val tx = signed.value
      val accountState = accountRef(tx.name)
      val keyRefs = tx.keyIds.map(keyId => nameKeyRef(tx.name, keyId))
      ConflictFootprint(
        reads = signerReads + accountState ++ keyRefs,
        writes = Set(accountState) ++ keyRefs,
      )

  def deriveRemoveAccount(
      signed: Signed[RemoveAccount],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    // Current reducer behavior only removes the account row itself. If
    // RemoveAccount later grows nameKey cleanup or any prefix-scan sweep, this
    // derivation must be widened to explicit refs or the family must route to
    // compatibility until the transaction shape names those refs directly.
    deriveUpdateAccountLike(signed, signed.value.name)

  def deriveCreateGroup(
      signed: Signed[CreateGroup],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    deriveGroupOnlyFootprint(signed, signed.value.groupId)

  def deriveDisbandGroup(
      signed: Signed[DisbandGroup],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    deriveGroupOnlyFootprint(signed, signed.value.groupId)

  def deriveAddAccounts(
      signed: Signed[AddAccounts],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    deriveGroupMembershipFootprint(signed, signed.value.groupId, signed.value.accounts)

  def deriveRemoveAccounts(
      signed: Signed[RemoveAccounts],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    deriveGroupMembershipFootprint(signed, signed.value.groupId, signed.value.accounts)

  def deriveReplaceCoordinator(
      signed: Signed[ReplaceCoordinator],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    deriveGroupOnlyFootprint(signed, signed.value.groupId)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def deriveSigned(
      signed: Signed[? <: Tx],
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    signed.value match
      case _: CreateNamedAccount =>
        deriveCreateNamedAccount(signed.asInstanceOf[Signed[CreateNamedAccount]])
      case _: UpdateAccount =>
        deriveUpdateAccount(signed.asInstanceOf[Signed[UpdateAccount]])
      case _: AddKeyIds =>
        deriveAddKeyIds(signed.asInstanceOf[Signed[AddKeyIds]])
      case _: RemoveKeyIds =>
        deriveRemoveKeyIds(signed.asInstanceOf[Signed[RemoveKeyIds]])
      case _: RemoveAccount =>
        deriveRemoveAccount(signed.asInstanceOf[Signed[RemoveAccount]])
      case _: CreateGroup =>
        deriveCreateGroup(signed.asInstanceOf[Signed[CreateGroup]])
      case _: DisbandGroup =>
        deriveDisbandGroup(signed.asInstanceOf[Signed[DisbandGroup]])
      case _: AddAccounts =>
        deriveAddAccounts(signed.asInstanceOf[Signed[AddAccounts]])
      case _: RemoveAccounts =>
        deriveRemoveAccounts(signed.asInstanceOf[Signed[RemoveAccounts]])
      case _: ReplaceCoordinator =>
        deriveReplaceCoordinator(signed.asInstanceOf[Signed[ReplaceCoordinator]])
      case other =>
        FootprintDerivationFailure(
          reason = "unsupportedTxFamily",
          detail = Some(other.getClass.getName),
        )
          .asLeft[ConflictFootprint]

  def classify(
      signed: Signed[? <: Tx],
  ): SchedulingClassification =
    SchedulingClassification.fromDerivation(deriveSigned(signed))

  given createNamedAccountFootprintDeriver: FootprintDeriver[Signed[CreateNamedAccount]] =
    FootprintDeriver.instance(deriveCreateNamedAccount)
  given updateAccountFootprintDeriver: FootprintDeriver[Signed[UpdateAccount]] =
    FootprintDeriver.instance(deriveUpdateAccount)
  given addKeyIdsFootprintDeriver: FootprintDeriver[Signed[AddKeyIds]] =
    FootprintDeriver.instance(deriveAddKeyIds)
  given removeKeyIdsFootprintDeriver: FootprintDeriver[Signed[RemoveKeyIds]] =
    FootprintDeriver.instance(deriveRemoveKeyIds)
  given removeAccountFootprintDeriver: FootprintDeriver[Signed[RemoveAccount]] =
    FootprintDeriver.instance(deriveRemoveAccount)
  given createGroupFootprintDeriver: FootprintDeriver[Signed[CreateGroup]] =
    FootprintDeriver.instance(deriveCreateGroup)
  given disbandGroupFootprintDeriver: FootprintDeriver[Signed[DisbandGroup]] =
    FootprintDeriver.instance(deriveDisbandGroup)
  given addAccountsFootprintDeriver: FootprintDeriver[Signed[AddAccounts]] =
    FootprintDeriver.instance(deriveAddAccounts)
  given removeAccountsFootprintDeriver: FootprintDeriver[Signed[RemoveAccounts]] =
    FootprintDeriver.instance(deriveRemoveAccounts)
  given replaceCoordinatorFootprintDeriver: FootprintDeriver[Signed[ReplaceCoordinator]] =
    FootprintDeriver.instance(deriveReplaceCoordinator)
