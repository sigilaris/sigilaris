package org.sigilaris.core.application.feature.group.module

import java.time.Instant

import cats.Monad
import cats.syntax.eq.*
import scala.Tuple.++

import org.sigilaris.core.application.feature.accounts.domain.{
  Account,
  AccountInfo,
  KeyId20,
  KeyInfo,
}
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.feature.group.domain.*
import org.sigilaris.core.application.feature.group.transactions.*
import org.sigilaris.core.application.module.blueprint.{ModuleBlueprint, StateReducer0}
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.security.SignatureVerifier
import org.sigilaris.core.application.support.ReducerMessageSupport
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.support.encoding.TupleKeyCodecs
import org.sigilaris.core.application.transactions.{
  AccountSignature,
  ReducerCoverage,
  Signed,
  Tx,
  TxRegistry,
}
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{Hash, Recover}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.failure.{
  CryptoFailure,
  FailureCode,
  TrieFailure,
}

/** Groups module schema.
  *
  * Tables:
  *   - groups: groupId -> GroupData (name, coordinator, nonce, createdAt)
  *   - groupAccounts: (groupId, account) -> Unit (membership indicator)
  */
object GroupsSchema:
  given tupleByteEncoder: ByteEncoder[(GroupId, Account)] =
    TupleKeyCodecs.pairEncoder[GroupId, Account]

  given tupleByteDecoder: ByteDecoder[(GroupId, Account)] =
    TupleKeyCodecs.pairDecoder[GroupId, Account]

  /** The groups module table schema: groups table and group-accounts membership table. */
  type GroupsSchema =
    Entry["groups", GroupId, GroupData] *:
      Entry["groupAccounts", (GroupId, Account), Unit] *:
      EmptyTuple

  /** Entry descriptor for the groups table (groupId -> GroupData). */
  val groupsEntry = new Entry["groups", GroupId, GroupData]("groups")

  /** Entry descriptor for the group-accounts table ((groupId, account) -> Unit). */
  val groupAccountsEntry = new Entry["groupAccounts", (GroupId, Account), Unit]("groupAccounts")

  /** All entry descriptors for the groups module as a typed tuple. */
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
  *
  * @tparam F the effect type
  */
@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Overloading"))
class GroupsReducer[F[_]: Monad] extends StateReducer0[F, GroupsSchema.GroupsSchema, GroupsReducer.GroupsNeeds]:
  import GroupsSchema.*

  private val UnsupportedTransactionCode =
    FailureCode.unsafe("groups.unsupported_transaction")
  private val GroupNotFoundCode =
    FailureCode.unsafe("groups.group_not_found")
  private val GroupAlreadyExistsCode =
    FailureCode.unsafe("groups.group_already_exists")
  private val GroupNonceMismatchCode =
    FailureCode.unsafe("groups.group_nonce_mismatch")
  private val GroupNotEmptyCode =
    FailureCode.unsafe("groups.group_not_empty")
  private val AccountsEmptyCode =
    FailureCode.unsafe("groups.accounts_empty")

  private inline def resultOf[A](value: A): GroupsResult[A] = GroupsResult(value)
  private inline def eventOf[A](value: A): GroupsEvent[A] = GroupsEvent(value)

  private def invalidRequest(
      code: FailureCode,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    ReducerMessageSupport.invalidRequest("groups", code, reason, message, detail)

  private def notFound(
      code: FailureCode,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    ReducerMessageSupport.notFound("groups", code, reason, message, detail)

  private def conflict(
      code: FailureCode,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    ReducerMessageSupport.conflict("groups", code, reason, message, detail)

  private def verifySigner[T <: Tx](tx: T, sig: AccountSignature, envelopeTimestamp: Instant, context: Option[String])(using
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
      hashT: Hash[T],
      recoverT: Recover[T],
  ): StoreF[F][KeyId20] =
    val (_ *: nameKeyTable *: EmptyTuple) = provider.tables

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
          StoreF.raise[F, Unit]:
            CryptoFailure:
              s"Unauthorized: ${accountSig.account} is not the coordinator of group ${groupId.toUtf8.asString}"
        case None =>
          StoreF.raise[F, Unit]:
            TrieFailure:
              notFound(
                GroupNotFoundCode,
                "group_not_found",
                s"Group ${groupId.toUtf8.asString} not found during authorization check",
                Some(s"groupId=${groupId.toUtf8.asString}"),
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
        StoreF.raise[F, (signedTx.value.Result, List[signedTx.value.Event])]:
          TrieFailure:
            invalidRequest(
              UnsupportedTransactionCode,
              "unsupported_transaction",
              s"Unknown transaction type: ${tx.getClass.getName}",
              None,
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
      StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupCreated]])]:
        CryptoFailure:
          s"Unauthorized: signer ${sig.account} does not match coordinator ${tx.coordinator}"
    else
      for
        maybeExisting <- groupsTable.get(groupsTable.brand(tx.groupId))
        result <- maybeExisting match
          case Some(_) =>
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupCreated]])]:
              TrieFailure:
                conflict(
                  GroupAlreadyExistsCode,
                  "group_already_exists",
                  s"Group ${tx.groupId.toUtf8.asString} already exists",
                  Some(s"groupId=${tx.groupId.toUtf8.asString}"),
                )
          case None =>
            val groupData = GroupData(
              name = tx.name,
              coordinator = tx.coordinator,
              nonce = GroupNonce.Zero,
              memberCount = MemberCount.Zero,
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
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupDisbanded]])]:
              TrieFailure:
                invalidRequest(
                  GroupNonceMismatchCode,
                  "group_nonce_mismatch",
                  s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}",
                  Some(s"groupId=${tx.groupId.toUtf8.asString}"),
                )
            
          else if groupData.memberCount =!= MemberCount.Zero then
            // SAFETY INVARIANT: Only empty groups can be disbanded.
            // This prevents orphaned membership entries in the groupAccounts table.
            // Without this check, disbanding a group with members would leave
            // (groupId, account) entries behind, which could resurrect if the
            // group is recreated with the same ID.
            //
            // To disband a group with members, the coordinator must first
            // remove all members via RemoveAccounts transactions.
            StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupDisbanded]])]:
              TrieFailure:
                invalidRequest(
                  GroupNotEmptyCode,
                  "group_not_empty",
                  s"Cannot disband group ${tx.groupId.toUtf8.asString} with ${groupData.memberCount} members. Remove all members first.",
                  Some(s"groupId=${tx.groupId.toUtf8.asString}"),
                )
            
          else
            for
              _ <- groupsTable.remove(groupsTable.brand(tx.groupId))
            yield (resultOf(()), List(eventOf(GroupDisbanded(tx.groupId))))
        case None =>
          StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupDisbanded]])]:
            TrieFailure:
              notFound(
                GroupNotFoundCode,
                "group_not_found",
                s"Group ${tx.groupId.toUtf8.asString} not found",
                Some(s"groupId=${tx.groupId.toUtf8.asString}"),
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

    if tx.accounts.toSet.isEmpty then
      // Legacy byte/json decoders still accept historical empty payloads, so
      // reducers keep this runtime guard for backwards-compatible decoding.
      StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersAdded]])]:
        TrieFailure:
          invalidRequest(
            AccountsEmptyCode,
            "accounts_empty",
            "AddAccounts requires non-empty accounts set",
            Some(s"groupId=${tx.groupId.toUtf8.asString}"),
          )
    else
      for
          maybeData <- groupsTable.get(groupsTable.brand(tx.groupId))
          result <- maybeData match
            case Some(groupData) if groupData.nonce === tx.groupNonce =>
              val addAccountsEffect =
                tx.accounts.toSet.foldLeft(StoreF.pure[F, Set[Account]](Set.empty[Account])) {
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
                  nonce = groupData.nonce.next,
                  memberCount = groupData.memberCount.add(actuallyAdded.size),
                  createdAt = groupData.createdAt,
                )
                _ <- groupsTable.put(groupsTable.brand(tx.groupId), newData)
              yield (resultOf(()), List(eventOf(GroupMembersAdded(tx.groupId, actuallyAdded))))

            case Some(groupData) =>
              StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersAdded]])]:
                TrieFailure:
                  invalidRequest(
                    GroupNonceMismatchCode,
                    "group_nonce_mismatch",
                    s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}",
                    Some(s"groupId=${tx.groupId.toUtf8.asString}"),
                  )

            case None =>
              StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersAdded]])]:
                TrieFailure:
                  notFound(
                    GroupNotFoundCode,
                    "group_not_found",
                    s"Group ${tx.groupId.toUtf8.asString} not found",
                    Some(s"groupId=${tx.groupId.toUtf8.asString}"),
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

    if tx.accounts.toSet.isEmpty then
      // Legacy byte/json decoders still accept historical empty payloads, so
      // reducers keep this runtime guard for backwards-compatible decoding.
      StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersRemoved]])]:
        TrieFailure:
          invalidRequest(
            AccountsEmptyCode,
            "accounts_empty",
            "RemoveAccounts requires non-empty accounts set",
            Some(s"groupId=${tx.groupId.toUtf8.asString}"),
          )
    else
      for
          maybeData <- groupsTable.get(groupsTable.brand(tx.groupId))
          result <- maybeData match
            case Some(groupData) if groupData.nonce === tx.groupNonce =>
              val removeAccountsEffect =
                tx.accounts.toSet.foldLeft(StoreF.pure[F, Set[Account]](Set.empty[Account])) {
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
                  nonce = groupData.nonce.next,
                  memberCount =
                    groupData.memberCount.unsafeSubtract(actuallyRemoved.size),
                  createdAt = groupData.createdAt,
                )
                _ <- groupsTable.put(groupsTable.brand(tx.groupId), newData)
              yield (resultOf(()), List(eventOf(GroupMembersRemoved(tx.groupId, actuallyRemoved))))

            case Some(groupData) =>
              StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersRemoved]])]:
                TrieFailure:
                  invalidRequest(
                    GroupNonceMismatchCode,
                    "group_nonce_mismatch",
                    s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}",
                    Some(s"groupId=${tx.groupId.toUtf8.asString}"),
                  )

            case None =>
              StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupMembersRemoved]])]:
                TrieFailure:
                  notFound(
                    GroupNotFoundCode,
                    "group_not_found",
                    s"Group ${tx.groupId.toUtf8.asString} not found",
                    Some(s"groupId=${tx.groupId.toUtf8.asString}"),
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
            nonce = groupData.nonce.next,
            memberCount = groupData.memberCount,
            createdAt = groupData.createdAt,
          )
          for
            _ <- groupsTable.put(groupsTable.brand(tx.groupId), newData)
          yield (resultOf(()), List(eventOf(GroupCoordinatorReplaced(tx.groupId, oldCoordinator, tx.newCoordinator))))

        case Some(groupData) =>
          StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupCoordinatorReplaced]])]:
            TrieFailure:
              invalidRequest(
                GroupNonceMismatchCode,
                "group_nonce_mismatch",
                s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}",
                Some(s"groupId=${tx.groupId.toUtf8.asString}"),
              )
            
        case None =>
          StoreF.raise[F, (GroupsResult[Unit], List[GroupsEvent[GroupCoordinatorReplaced]])]:
            TrieFailure:
              notFound(
                GroupNotFoundCode,
                "group_not_found",
                s"Group ${tx.groupId.toUtf8.asString} not found",
                Some(s"groupId=${tx.groupId.toUtf8.asString}"),
              )
            
    yield result

/** Companion for [[GroupsReducer]], defining dependency types. */
object GroupsReducer:
  /** External table dependencies required by the groups module for Named account key verification. */
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
  /** Tuple of all transaction types supported by the groups module. */
  type GroupsTxs =
    CreateGroup *:
      DisbandGroup *:
      AddAccounts *:
      RemoveAccounts *:
      ReplaceCoordinator *:
      EmptyTuple

  given ReducerCoverage[CreateGroup] with {}
  given ReducerCoverage[DisbandGroup] with {}
  given ReducerCoverage[AddAccounts] with {}
  given ReducerCoverage[RemoveAccounts] with {}
  given ReducerCoverage[ReplaceCoordinator] with {}

  /** Creates the groups module blueprint for a given effect type.
    *
    * @tparam F the effect type
    * @param provider the tables provider supplying accounts tables for key verification
    * @param nodeStore the MerkleTrie node store
    * @return a ModuleBlueprint for the groups module
    */
  def apply[F[_]: Monad](
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  )(using @annotation.unused nodeStore: org.sigilaris.core.merkle.MerkleTrie.NodeStore[F]): ModuleBlueprint[F, "groups", GroupsSchema.GroupsSchema, GroupsReducer.GroupsNeeds, GroupsTxs] =
    import GroupsSchema.*

    new ModuleBlueprint[F, "groups", GroupsSchema, GroupsReducer.GroupsNeeds, GroupsTxs](
      owns = groupsEntries,
      reducer0 = new GroupsReducer[F],
      txs = TxRegistry.of[GroupsTxs],
      provider = provider,
    )
