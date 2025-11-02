package org.sigilaris.core.application.feature.group.transactions

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{Hash, Recover}
import org.sigilaris.core.datatype.{BigNat, Utf8}

import org.sigilaris.core.application.feature.accounts.domain.Account
import org.sigilaris.core.application.feature.accounts.transactions.TxEnvelope
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.application.transactions.model.Tx
import org.sigilaris.core.application.feature.group.domain.*

/** Create a new group.
  *
  * Requires signature from the coordinator account.
  *
  * @param envelope common transaction metadata
  * @param groupId group identifier (UTF-8 string, no format constraints)
  * @param name human-readable group name (immutable after creation)
  * @param coordinator initial coordinator account
  */
final case class CreateGroup(
    envelope: TxEnvelope,
    groupId: GroupId,
    name: Utf8,
    coordinator: Account,
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event = GroupsEvent[GroupCreated]

object CreateGroup:
  given createGroupEq: Eq[CreateGroup] = Eq.fromUniversalEquals
  given createGroupHash: Hash[CreateGroup] = Hash.build
  given createGroupRecover: Recover[CreateGroup] = Recover.build

/** Disband a group, removing all group data and membership.
  *
  * Requires signature from the coordinator.
  *
  * @param envelope common transaction metadata
  * @param groupId group identifier
  * @param groupNonce must match current group nonce
  */
final case class DisbandGroup(
    envelope: TxEnvelope,
    groupId: GroupId,
    groupNonce: BigNat,
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event = GroupsEvent[GroupDisbanded]

object DisbandGroup:
  given disbandGroupEq: Eq[DisbandGroup] = Eq.fromUniversalEquals
  given disbandGroupHash: Hash[DisbandGroup] = Hash.build
  given disbandGroupRecover: Recover[DisbandGroup] = Recover.build

/** Add accounts to a group.
  *
  * Requires signature from the coordinator.
  * Idempotent: accounts already in the group are no-op.
  *
  * @param envelope common transaction metadata
  * @param groupId group identifier
  * @param accounts set of accounts to add (must be non-empty)
  * @param groupNonce must match current group nonce
  */
final case class AddAccounts(
    envelope: TxEnvelope,
    groupId: GroupId,
    accounts: Set[Account],
    groupNonce: BigNat,
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event = GroupsEvent[GroupMembersAdded]

object AddAccounts:
  given addAccountsEq: Eq[AddAccounts] = Eq.fromUniversalEquals
  given addAccountsHash: Hash[AddAccounts] = Hash.build
  given addAccountsRecover: Recover[AddAccounts] = Recover.build

/** Remove accounts from a group.
  *
  * Requires signature from the coordinator.
  * Idempotent: accounts not in the group are no-op.
  *
  * @param envelope common transaction metadata
  * @param groupId group identifier
  * @param accounts set of accounts to remove (must be non-empty)
  * @param groupNonce must match current group nonce
  */
final case class RemoveAccounts(
    envelope: TxEnvelope,
    groupId: GroupId,
    accounts: Set[Account],
    groupNonce: BigNat,
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *:
    Entry["groupAccounts", (GroupId, Account), Unit] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event = GroupsEvent[GroupMembersRemoved]

object RemoveAccounts:
  given removeAccountsEq: Eq[RemoveAccounts] = Eq.fromUniversalEquals
  given removeAccountsHash: Hash[RemoveAccounts] = Hash.build
  given removeAccountsRecover: Recover[RemoveAccounts] = Recover.build

/** Replace the group coordinator.
  *
  * Requires signature from the current coordinator.
  * Idempotent: if new coordinator equals current, nonce still increments.
  *
  * @param envelope common transaction metadata
  * @param groupId group identifier
  * @param newCoordinator new coordinator account
  * @param groupNonce must match current group nonce
  */
final case class ReplaceCoordinator(
    envelope: TxEnvelope,
    groupId: GroupId,
    newCoordinator: Account,
    groupNonce: BigNat,
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Writes = Entry["groups", GroupId, GroupData] *: EmptyTuple
  type Result = GroupsResult[Unit]
  type Event = GroupsEvent[GroupCoordinatorReplaced]

object ReplaceCoordinator:
  given replaceCoordinatorEq: Eq[ReplaceCoordinator] = Eq.fromUniversalEquals
  given replaceCoordinatorHash: Hash[ReplaceCoordinator] = Hash.build
  given replaceCoordinatorRecover: Recover[ReplaceCoordinator] = Recover.build

// Event types
sealed trait GroupEvent

final case class GroupCreated(groupId: GroupId, coordinator: Account, name: Utf8) extends GroupEvent
final case class GroupDisbanded(groupId: GroupId) extends GroupEvent
final case class GroupMembersAdded(groupId: GroupId, added: Set[Account]) extends GroupEvent
final case class GroupMembersRemoved(groupId: GroupId, removed: Set[Account]) extends GroupEvent
final case class GroupCoordinatorReplaced(groupId: GroupId, oldCoordinator: Account, newCoordinator: Account) extends GroupEvent
