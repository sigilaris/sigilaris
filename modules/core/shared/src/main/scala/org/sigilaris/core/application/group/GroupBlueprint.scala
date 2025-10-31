package org.sigilaris.core
package application
package group

import java.time.Instant

import cats.Monad
import cats.syntax.eq.*
import scala.Tuple.++

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.byte.ByteEncoder.ops.*
import crypto.{Hash, Recover}
import datatype.{BigNat, Utf8}
import failure.{TrieFailure, CryptoFailure}
import application.accounts.{Account, AccountInfo, KeyId20, KeyInfo}
import application.security.SignatureVerifier
import support.TablesAccessOps.*

/** Groups module schema.
  *
  * Tables:
  *   - groups: groupId -> GroupData (name, coordinator, nonce, createdAt)
  *   - groupAccounts: (groupId, account) -> Unit (membership indicator)
  */
object GroupsSchema:
  // Need ByteCodec for tuple keys (GroupId, Account)
  given tupleByteEncoder: ByteEncoder[(GroupId, Account)] = (t: (GroupId, Account)) =>
    t._1.toUtf8.toBytes ++ t._2.toBytes

  given tupleByteDecoder: ByteDecoder[(GroupId, Account)] = bytes =>
    for
      groupIdResult <- ByteDecoder[Utf8].decode(bytes)
      accountResult <- ByteDecoder[Account].decode(groupIdResult.remainder)
    yield codec.byte.DecodeResult((GroupId(groupIdResult.value), accountResult.value), accountResult.remainder)

  type GroupsSchema =
    Entry["groups", GroupId, GroupData] *:
      Entry["groupAccounts", (GroupId, Account), Unit] *:
      EmptyTuple

  val groupsEntry = new Entry["groups", GroupId, GroupData]("groups")
  val groupAccountsEntry = new Entry["groupAccounts", (GroupId, Account), Unit]("groupAccounts")

  val groupsEntries: GroupsSchema = groupsEntry *: groupAccountsEntry *: EmptyTuple

/** Groups state reducer (path-agnostic).
  *
  * Implements transaction logic for group management:
  *   - CreateGroup
  *   - DisbandGroup
  *   - AddAccounts
  *   - RemoveAccounts
  *   - ReplaceCoordinator
  *
  * Signature verification follows ADR-0012 protocol:
  * All group management transactions require coordinator signature.
  * Named account verification requires access to AccountsBP's nameKey table.
  *
  * DEPENDENCY: This reducer depends on AccountsSchema for Named account key verification.
  * The Needs type parameter includes the accounts tables.
  */
@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Overloading"))
class GroupsReducer[F[_]: Monad] extends StateReducer0[F, GroupsSchema.GroupsSchema, GroupsReducer.GroupsNeeds]:
  import GroupsSchema.*

  private inline def resultOf[A](value: A): GroupsResult[A] = GroupsResult(value)
  private inline def eventOf[A](value: A): GroupsEvent[A] = GroupsEvent(value)

  private def verifySigner[T <: Tx](tx: T, sig: AccountSignature, envelopeTimestamp: Instant, context: Option[String])(using
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
      hashT: Hash[T],
      recoverT: Recover[T],
  ): StoreF[F][KeyId20] =
    val nameKeyTable = provider.providedTable["nameKey", (Utf8, KeyId20), KeyInfo]

    SignatureVerifier.verifySignature[F, T](
      Signed(sig, tx),
      envelopeTimestamp,
      context,
    ) { (name, keyId) =>
      nameKeyTable.get(nameKeyTable.brand((name, keyId)))
    }

  /** Verify coordinator authorization for group management operations.
    *
    * Checks that the signer is the group's coordinator.
    *
    * @param groupId the group being managed
    * @param accountSig the signature claiming authorization
    * @param ownsTables the owned tables for group lookups
    * @return Either authorization failure or Unit on success
    */
  private def verifyCoordinatorSignature(
      groupId: GroupId,
      accountSig: AccountSignature,
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][Unit] =
    val (groupsTable *: _ *: EmptyTuple) = ownsTables

    for
      maybeData <- groupsTable.get(groupsTable.brand(groupId))
      _ <- maybeData match
        case Some(groupData) if groupData.coordinator === accountSig.account =>
          StoreF.pure[F, Unit](())
        case Some(groupData) =>
          StoreF.raise[F, Unit](
            CryptoFailure(
              s"Unauthorized: ${accountSig.account} is not the coordinator of group ${groupId.toUtf8.asString}"
            )
          )
        case None =>
          StoreF.raise[F, Unit](
            TrieFailure(s"Group ${groupId.toUtf8.asString} not found during authorization check")
          )
    yield ()

  def apply[T <: Tx](signedTx: Signed[T])(using
      requiresReads: Requires[signedTx.value.Reads, GroupsSchema.GroupsSchema ++ GroupsReducer.GroupsNeeds],
      requiresWrites: Requires[signedTx.value.Writes, GroupsSchema.GroupsSchema ++ GroupsReducer.GroupsNeeds],
      ownsTables: Tables[F, GroupsSchema.GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
    val tx = signedTx.value

    // Verify signature BEFORE processing any transaction logic
    val result = tx match
      case tx: CreateGroup =>
        verifyAndHandleCreateGroup(tx, signedTx.sig)
      case tx: DisbandGroup =>
        verifyAndHandleDisbandGroup(tx, signedTx.sig)
      case tx: AddAccounts =>
        verifyAndHandleAddAccounts(tx, signedTx.sig)
      case tx: RemoveAccounts =>
        verifyAndHandleRemoveAccounts(tx, signedTx.sig)
      case tx: ReplaceCoordinator =>
        verifyAndHandleReplaceCoordinator(tx, signedTx.sig)
      case _ =>
        StoreF.raise[F, (signedTx.value.Result, List[signedTx.value.Event])](
          TrieFailure(s"Unknown transaction type: ${tx.getClass.getName}")
        )

    result.asInstanceOf[StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])]]

  private def verifyAndHandleCreateGroup(tx: CreateGroup, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySigner(tx, sig, tx.envelope.createdAt, Some("CreateGroup"))
      result <- handleCreateGroup(tx, sig)
    yield result

  private def handleCreateGroup(tx: CreateGroup, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (groupsTable *: _ *: EmptyTuple) = ownsTables

    // Verify signer is the coordinator
    if sig.account =!= tx.coordinator then
      StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupCreated]])](
        CryptoFailure(
          s"Unauthorized: signer ${sig.account} does not match coordinator ${tx.coordinator}"
        )
      )
    else
      for
        maybeExisting <- groupsTable.get(groupsTable.brand(tx.groupId))
        result <- maybeExisting match
          case Some(_) =>
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupCreated]])](
              TrieFailure(s"Group ${tx.groupId.toUtf8.asString} already exists")
            )
          case None =>
            val groupData = GroupData(
              name = tx.name,
              coordinator = tx.coordinator,
              nonce = BigNat.Zero,
              memberCount = BigNat.Zero,
              createdAt = tx.envelope.createdAt,
            )
            for
              _ <- groupsTable.put(groupsTable.brand(tx.groupId), groupData)
            yield (resultOf(()), List(eventOf(GroupCreated(tx.groupId, tx.coordinator, tx.name))))
      yield result

  private def verifyAndHandleDisbandGroup(tx: DisbandGroup, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySigner(tx, sig, tx.envelope.createdAt, Some("DisbandGroup"))
      _ <- verifyCoordinatorSignature(tx.groupId, sig, ownsTables)
      result <- handleDisbandGroup(tx)
    yield result

  private def handleDisbandGroup(tx: DisbandGroup)(using
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (groupsTable *: _groupAccountsTable *: EmptyTuple) = ownsTables

    for
      maybeData <- groupsTable.get(groupsTable.brand(tx.groupId))
      result <- maybeData match
        case Some(groupData) =>
          if groupData.nonce =!= tx.groupNonce then
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupDisbanded]])](
              TrieFailure(s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}")
            )
          else if groupData.memberCount =!= BigNat.Zero then
            // SAFETY INVARIANT: Only empty groups can be disbanded.
            // This prevents orphaned membership entries in the groupAccounts table.
            // Without this check, disbanding a group with members would leave
            // (groupId, account) entries behind, which could resurrect if the
            // group is recreated with the same ID.
            //
            // To disband a group with members, the coordinator must first
            // remove all members via RemoveAccounts transactions.
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupDisbanded]])](
              TrieFailure(s"Cannot disband group ${tx.groupId.toUtf8.asString} with ${groupData.memberCount} members. Remove all members first.")
            )
          else
            for
              _ <- groupsTable.remove(groupsTable.brand(tx.groupId))
            yield (resultOf(()), List(eventOf(GroupDisbanded(tx.groupId))))
        case None =>
          StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupDisbanded]])](
            TrieFailure(s"Group ${tx.groupId.toUtf8.asString} not found")
          )
    yield result

  private def verifyAndHandleAddAccounts(tx: AddAccounts, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySigner(tx, sig, tx.envelope.createdAt, Some("AddAccounts"))
      _ <- verifyCoordinatorSignature(tx.groupId, sig, ownsTables)
      result <- handleAddAccounts(tx)
    yield result

  private def handleAddAccounts(tx: AddAccounts)(using
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (groupsTable *: groupAccountsTable *: EmptyTuple) = ownsTables

    if tx.accounts.isEmpty then
      StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersAdded]])](
        TrieFailure("AddAccounts requires non-empty accounts set")
      )
    else
      for
        maybeData <- groupsTable.get(groupsTable.brand(tx.groupId))
        result <- maybeData match
          case Some(groupData) if groupData.nonce === tx.groupNonce =>
            val addAccountsEffect = tx.accounts.foldLeft(StoreF.pure[F, Set[Account]](Set.empty[Account])) {
              case (accEffect, account) =>
                for
                  alreadyAdded <- accEffect
                  maybeExisting <- groupAccountsTable.get(groupAccountsTable.brand((tx.groupId, account)))
                  updated <- maybeExisting match
                    case Some(_) =>
                      StoreF.pure[F, Set[Account]](alreadyAdded)
                    case None =>
                      for
                        _ <- groupAccountsTable.put(groupAccountsTable.brand((tx.groupId, account)), ())
                      yield alreadyAdded + account
                yield updated
            }

            for
              actuallyAdded <- addAccountsEffect
              newData = GroupData(
                name = groupData.name,
                coordinator = groupData.coordinator,
                nonce = BigNat.unsafeFromBigInt(groupData.nonce.toBigInt + 1),
                memberCount = BigNat.unsafeFromBigInt(groupData.memberCount.toBigInt + actuallyAdded.size),
                createdAt = groupData.createdAt,
              )
              _ <- groupsTable.put(groupsTable.brand(tx.groupId), newData)
            yield (resultOf(()), List(eventOf(GroupMembersAdded(tx.groupId, actuallyAdded))))

          case Some(groupData) =>
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersAdded]])](
              TrieFailure(s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}")
            )
          case None =>
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersAdded]])](
              TrieFailure(s"Group ${tx.groupId.toUtf8.asString} not found")
            )
      yield result

  private def verifyAndHandleRemoveAccounts(tx: RemoveAccounts, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySigner(tx, sig, tx.envelope.createdAt, Some("RemoveAccounts"))
      _ <- verifyCoordinatorSignature(tx.groupId, sig, ownsTables)
      result <- handleRemoveAccounts(tx)
    yield result

  private def handleRemoveAccounts(tx: RemoveAccounts)(using
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (groupsTable *: groupAccountsTable *: EmptyTuple) = ownsTables

    if tx.accounts.isEmpty then
      StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersRemoved]])](
        TrieFailure("RemoveAccounts requires non-empty accounts set")
      )
    else
      for
        maybeData <- groupsTable.get(groupsTable.brand(tx.groupId))
        result <- maybeData match
          case Some(groupData) if groupData.nonce === tx.groupNonce =>
            val removeAccountsEffect = tx.accounts.foldLeft(StoreF.pure[F, Set[Account]](Set.empty[Account])) {
              case (accEffect, account) =>
                for
                  alreadyRemoved <- accEffect
                  wasRemoved <- groupAccountsTable.remove(groupAccountsTable.brand((tx.groupId, account)))
                yield if wasRemoved then alreadyRemoved + account else alreadyRemoved
            }

            for
              actuallyRemoved <- removeAccountsEffect
              newData = GroupData(
                name = groupData.name,
                coordinator = groupData.coordinator,
                nonce = BigNat.unsafeFromBigInt(groupData.nonce.toBigInt + 1),
                memberCount = BigNat.unsafeFromBigInt(groupData.memberCount.toBigInt - actuallyRemoved.size),
                createdAt = groupData.createdAt,
              )
              _ <- groupsTable.put(groupsTable.brand(tx.groupId), newData)
            yield (resultOf(()), List(eventOf(GroupMembersRemoved(tx.groupId, actuallyRemoved))))

          case Some(groupData) =>
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersRemoved]])](
              TrieFailure(s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}")
            )
          case None =>
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersRemoved]])](
              TrieFailure(s"Group ${tx.groupId.toUtf8.asString} not found")
            )
      yield result

  private def verifyAndHandleReplaceCoordinator(tx: ReplaceCoordinator, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySigner(tx, sig, tx.envelope.createdAt, Some("ReplaceCoordinator"))
      _ <- verifyCoordinatorSignature(tx.groupId, sig, ownsTables)
      result <- handleReplaceCoordinator(tx)
    yield result

  private def handleReplaceCoordinator(tx: ReplaceCoordinator)(using
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (groupsTable *: _ *: EmptyTuple) = ownsTables

    for
      maybeData <- groupsTable.get(groupsTable.brand(tx.groupId))
      result <- maybeData match
        case Some(groupData) if groupData.nonce === tx.groupNonce =>
          val oldCoordinator = groupData.coordinator
          val newData = GroupData(
            name = groupData.name,
            coordinator = tx.newCoordinator,
            nonce = BigNat.unsafeFromBigInt(groupData.nonce.toBigInt + 1),
            memberCount = groupData.memberCount,
            createdAt = groupData.createdAt,
          )
          for
            _ <- groupsTable.put(groupsTable.brand(tx.groupId), newData)
          yield (resultOf(()), List(eventOf(GroupCoordinatorReplaced(tx.groupId, oldCoordinator, tx.newCoordinator))))

        case Some(groupData) =>
          StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupCoordinatorReplaced]])](
            TrieFailure(s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}")
          )
        case None =>
          StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupCoordinatorReplaced]])](
            TrieFailure(s"Group ${tx.groupId.toUtf8.asString} not found")
          )
    yield result

object GroupsReducer:
  // Groups module needs access to accounts tables for Named account verification
  type GroupsNeeds =
    Entry["accounts", Utf8, AccountInfo] *:
      Entry["nameKey", (Utf8, KeyId20), KeyInfo] *:
      EmptyTuple

/** Groups module blueprint.
  *
  * Phase 6 example blueprint implementing ADR-0011 (Blockchain Account Group Management).
  *
  * DEPENDENCY: This blueprint depends on AccountsSchema for Named account key verification.
  * The provider parameter must supply the accounts and nameKey tables from AccountsBP.
  */
object GroupsBP:
  def apply[F[_]: Monad](
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  )(using @annotation.unused nodeStore: merkle.MerkleTrie.NodeStore[F]): ModuleBlueprint[F, "groups", GroupsSchema.GroupsSchema, GroupsReducer.GroupsNeeds, EmptyTuple] =
    import GroupsSchema.*

    new ModuleBlueprint[F, "groups", GroupsSchema, GroupsReducer.GroupsNeeds, EmptyTuple](
      owns = groupsEntries,
      reducer0 = new GroupsReducer[F],
      txs = TxRegistry.empty,
      provider = provider,
    )
