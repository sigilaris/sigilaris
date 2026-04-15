package org.sigilaris.core.application.feature.group.transactions

import cats.Eq
import scala.annotation.targetName

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{Hash, Recover}
import org.sigilaris.core.datatype.{BigNat, Utf8}

import org.sigilaris.core.application.feature.accounts.domain.Account
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.application.transactions.{Tx, TxEnvelope}
import org.sigilaris.core.application.feature.group.domain.*

/** Create a new group.
  *
  * Requires signature from the coordinator account.
  *
  * @param envelope
  *   common transaction metadata
  * @param groupId
  *   group identifier (UTF-8 string, no format constraints)
  * @param name
  *   human-readable group name (immutable after creation)
  * @param coordinator
  *   initial coordinator account
  */
final case class CreateGroup(
    envelope: TxEnvelope,
    groupId: GroupId,
    name: Utf8,
    coordinator: Account,
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads  = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event  = GroupsEvent[GroupCreated]

/** Companion for [[CreateGroup]], providing codec and crypto instances. */
object CreateGroup:
  given createGroupEq: Eq[CreateGroup]           = Eq.fromUniversalEquals
  given createGroupHash: Hash[CreateGroup]       = Hash.build
  given createGroupRecover: Recover[CreateGroup] = Recover.build

/** Disband a group, removing all group data and membership.
  *
  * Requires signature from the coordinator.
  *
  * @param envelope
  *   common transaction metadata
  * @param groupId
  *   group identifier
  * @param groupNonce
  *   must match current group nonce
  */
final case class DisbandGroup(
    envelope: TxEnvelope,
    groupId: GroupId,
    groupNonce: GroupNonce,
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event  = GroupsEvent[GroupDisbanded]

/** Companion for [[DisbandGroup]], providing codec and crypto instances. */
object DisbandGroup:
  @targetName("applyBigNat")
  def apply(
      envelope: TxEnvelope,
      groupId: GroupId,
      groupNonce: BigNat,
  ): DisbandGroup =
    DisbandGroup(
      envelope = envelope,
      groupId = groupId,
      groupNonce = GroupNonce(groupNonce),
    )

  given disbandGroupEq: Eq[DisbandGroup]           = Eq.fromUniversalEquals
  given disbandGroupHash: Hash[DisbandGroup]       = Hash.build
  given disbandGroupRecover: Recover[DisbandGroup] = Recover.build

/** Add accounts to a group.
  *
  * Requires signature from the coordinator. Idempotent: accounts already in the
  * group are no-op.
  *
  * @param envelope
  *   common transaction metadata
  * @param groupId
  *   group identifier
  * @param accounts
  *   set of accounts to add (must be non-empty)
  * @param groupNonce
  *   must match current group nonce
  */
final case class AddAccounts private (
    envelope: TxEnvelope,
    groupId: GroupId,
    accounts: NonEmptyGroupAccounts,
    groupNonce: GroupNonce,
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event  = GroupsEvent[GroupMembersAdded]

/** Companion for [[AddAccounts]], providing codec and crypto instances. */
object AddAccounts:
  def apply(
      envelope: TxEnvelope,
      groupId: GroupId,
      accounts: Set[Account],
      groupNonce: BigNat,
  ): Either[String, AddAccounts] =
    NonEmptyGroupAccounts(accounts).map: validatedAccounts =>
        AddAccounts(
          envelope = envelope,
          groupId = groupId,
          accounts = validatedAccounts,
          groupNonce = GroupNonce(groupNonce),
        )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(
      envelope: TxEnvelope,
      groupId: GroupId,
      accounts: Set[Account],
      groupNonce: BigNat,
  ): AddAccounts =
    apply(
      envelope = envelope,
      groupId = groupId,
      accounts = accounts,
      groupNonce = groupNonce,
    ) match
      case Right(tx)    => tx
      case Left(error)  => throw new IllegalArgumentException(error)

  given addAccountsEq: Eq[AddAccounts]           = Eq.fromUniversalEquals
  given addAccountsHash: Hash[AddAccounts]       = Hash.build
  given addAccountsRecover: Recover[AddAccounts] = Recover.build

/** Remove accounts from a group.
  *
  * Requires signature from the coordinator. Idempotent: accounts not in the
  * group are no-op.
  *
  * @param envelope
  *   common transaction metadata
  * @param groupId
  *   group identifier
  * @param accounts
  *   set of accounts to remove (must be non-empty)
  * @param groupNonce
  *   must match current group nonce
  */
final case class RemoveAccounts private (
    envelope: TxEnvelope,
    groupId: GroupId,
    accounts: NonEmptyGroupAccounts,
    groupNonce: GroupNonce,
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event  = GroupsEvent[GroupMembersRemoved]

/** Companion for [[RemoveAccounts]], providing codec and crypto instances. */
object RemoveAccounts:
  def apply(
      envelope: TxEnvelope,
      groupId: GroupId,
      accounts: Set[Account],
      groupNonce: BigNat,
  ): Either[String, RemoveAccounts] =
    NonEmptyGroupAccounts(accounts).map: validatedAccounts =>
        RemoveAccounts(
          envelope = envelope,
          groupId = groupId,
          accounts = validatedAccounts,
          groupNonce = GroupNonce(groupNonce),
        )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(
      envelope: TxEnvelope,
      groupId: GroupId,
      accounts: Set[Account],
      groupNonce: BigNat,
  ): RemoveAccounts =
    apply(
      envelope = envelope,
      groupId = groupId,
      accounts = accounts,
      groupNonce = groupNonce,
    ) match
      case Right(tx)    => tx
      case Left(error)  => throw new IllegalArgumentException(error)

  given removeAccountsEq: Eq[RemoveAccounts]           = Eq.fromUniversalEquals
  given removeAccountsHash: Hash[RemoveAccounts]       = Hash.build
  given removeAccountsRecover: Recover[RemoveAccounts] = Recover.build

/** Replace the group coordinator.
  *
  * Requires signature from the current coordinator. Idempotent: if new
  * coordinator equals current, nonce still increments.
  *
  * @param envelope
  *   common transaction metadata
  * @param groupId
  *   group identifier
  * @param newCoordinator
  *   new coordinator account
  * @param groupNonce
  *   must match current group nonce
  */
final case class ReplaceCoordinator(
    envelope: TxEnvelope,
    groupId: GroupId,
    newCoordinator: Account,
    groupNonce: GroupNonce,
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads  = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event  = GroupsEvent[GroupCoordinatorReplaced]

/** Companion for [[ReplaceCoordinator]], providing codec and crypto instances. */
object ReplaceCoordinator:
  @targetName("applyBigNat")
  def apply(
      envelope: TxEnvelope,
      groupId: GroupId,
      newCoordinator: Account,
      groupNonce: BigNat,
  ): ReplaceCoordinator =
    ReplaceCoordinator(
      envelope = envelope,
      groupId = groupId,
      newCoordinator = newCoordinator,
      groupNonce = GroupNonce(groupNonce),
    )

  given replaceCoordinatorEq: Eq[ReplaceCoordinator] = Eq.fromUniversalEquals
  given replaceCoordinatorHash: Hash[ReplaceCoordinator]       = Hash.build
  given replaceCoordinatorRecover: Recover[ReplaceCoordinator] = Recover.build

/** Sealed base trait for all group-related domain events. */
sealed trait GroupEvent

/** Event emitted when a new group is created.
  *
  * @param groupId the group identifier
  * @param coordinator the initial coordinator account
  * @param name the human-readable group name
  */
final case class GroupCreated(
    groupId: GroupId,
    coordinator: Account,
    name: Utf8,
) extends GroupEvent

/** Event emitted when a group is disbanded.
  *
  * @param groupId the group identifier
  */
final case class GroupDisbanded(groupId: GroupId) extends GroupEvent

/** Event emitted when members are added to a group.
  *
  * @param groupId the group identifier
  * @param added the set of accounts actually added (excludes already-present members)
  */
final case class GroupMembersAdded(groupId: GroupId, added: Set[Account])
    extends GroupEvent

/** Event emitted when members are removed from a group.
  *
  * @param groupId the group identifier
  * @param removed the set of accounts actually removed
  */
final case class GroupMembersRemoved(groupId: GroupId, removed: Set[Account])
    extends GroupEvent

/** Event emitted when a group's coordinator is replaced.
  *
  * @param groupId the group identifier
  * @param oldCoordinator the previous coordinator account
  * @param newCoordinator the new coordinator account
  */
final case class GroupCoordinatorReplaced(
    groupId: GroupId,
    oldCoordinator: Account,
    newCoordinator: Account,
) extends GroupEvent
