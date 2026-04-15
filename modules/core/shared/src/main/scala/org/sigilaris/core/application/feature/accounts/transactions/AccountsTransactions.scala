package org.sigilaris.core.application.feature.accounts.transactions

import java.time.Instant
import scala.annotation.targetName

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{Hash, Recover}
import org.sigilaris.core.datatype.{BigNat, Utf8}

import org.sigilaris.core.application.feature.accounts.domain.*
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.application.transactions.{Tx, TxEnvelope}

/** Create a new Named account.
  *
  * Requires signature from the initial key's private key.
  *
  * @param envelope
  *   common transaction metadata
  * @param name
  *   account name (UTF-8 string)
  * @param initialKeyId
  *   initial public key identifier
  * @param guardian
  *   optional guardian account
  */
final case class CreateNamedAccount(
    envelope: TxEnvelope,
    name: Utf8,
    initialKeyId: KeyId20,
    guardian: Option[Account],
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event  = AccountsEvent[AccountCreated]

/** Companion for [[CreateNamedAccount]], providing codec and crypto instances. */
object CreateNamedAccount:
  given createNamedAccountEq: Eq[CreateNamedAccount] = Eq.fromUniversalEquals
  given createNamedAccountHash: Hash[CreateNamedAccount]       = Hash.build
  given createNamedAccountRecover: Recover[CreateNamedAccount] = Recover.build

/** Update account information (change guardian).
  *
  * Requires signature from one of the account's registered keys or the
  * guardian.
  *
  * @param envelope
  *   common transaction metadata
  * @param name
  *   account name
  * @param nonce
  *   must match current account nonce
  * @param newGuardian
  *   new guardian (can be None to remove, Some to set/change)
  */
final case class UpdateAccount(
    envelope: TxEnvelope,
    name: Utf8,
    nonce: AccountNonce,
    newGuardian: Option[Account],
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads  = Entry["accounts", Utf8, AccountInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event  = AccountsEvent[AccountUpdated]

/** Companion for [[UpdateAccount]], providing codec and crypto instances. */
object UpdateAccount:
  @targetName("applyBigNat")
  def apply(
      envelope: TxEnvelope,
      name: Utf8,
      nonce: BigNat,
      newGuardian: Option[Account],
  ): UpdateAccount =
    UpdateAccount(
      envelope = envelope,
      name = name,
      nonce = AccountNonce(nonce),
      newGuardian = newGuardian,
    )

  given updateAccountEq: Eq[UpdateAccount]           = Eq.fromUniversalEquals
  given updateAccountHash: Hash[UpdateAccount]       = Hash.build
  given updateAccountRecover: Recover[UpdateAccount] = Recover.build

/** Add new public keys to a Named account.
  *
  * Requires signature from one of the account's registered keys or the
  * guardian.
  *
  * @param envelope
  *   common transaction metadata
  * @param name
  *   account name
  * @param nonce
  *   must match current account nonce
  * @param keyIds
  *   map of KeyId20 to description (user-provided)
  * @param expiresAt
  *   optional expiration timestamp applied to all new keys
  */
final case class AddKeyIds private (
    envelope: TxEnvelope,
    name: Utf8,
    nonce: AccountNonce,
    keyIds: NonEmptyKeyIdDescriptions,
    expiresAt: Option[Instant],
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event  = AccountsEvent[KeysAdded]

/** Companion for [[AddKeyIds]], providing codec and crypto instances. */
object AddKeyIds:
  def apply(
      envelope: TxEnvelope,
      name: Utf8,
      nonce: BigNat,
      keyIds: Map[KeyId20, Utf8],
      expiresAt: Option[Instant],
  ): Either[String, AddKeyIds] =
    NonEmptyKeyIdDescriptions(keyIds).map: validatedKeyIds =>
      AddKeyIds(
        envelope = envelope,
        name = name,
        nonce = AccountNonce(nonce),
        keyIds = validatedKeyIds,
        expiresAt = expiresAt,
      )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(
      envelope: TxEnvelope,
      name: Utf8,
      nonce: BigNat,
      keyIds: Map[KeyId20, Utf8],
      expiresAt: Option[Instant],
  ): AddKeyIds =
    apply(
      envelope = envelope,
      name = name,
      nonce = nonce,
      keyIds = keyIds,
      expiresAt = expiresAt,
    ) match
      case Right(tx)    => tx
      case Left(error)  => throw new IllegalArgumentException(error)

  given addKeyIdsEq: Eq[AddKeyIds]           = Eq.fromUniversalEquals
  given addKeyIdsHash: Hash[AddKeyIds]       = Hash.build
  given addKeyIdsRecover: Recover[AddKeyIds] = Recover.build

/** Remove public keys from a Named account.
  *
  * Requires signature from one of the account's registered keys or the
  * guardian.
  *
  * @param envelope
  *   common transaction metadata
  * @param name
  *   account name
  * @param nonce
  *   must match current account nonce
  * @param keyIds
  *   set of KeyId20 to remove
  */
final case class RemoveKeyIds private (
    envelope: TxEnvelope,
    name: Utf8,
    nonce: AccountNonce,
    keyIds: NonEmptyKeyIds,
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event  = AccountsEvent[KeysRemoved]

/** Companion for [[RemoveKeyIds]], providing codec and crypto instances. */
object RemoveKeyIds:
  def apply(
      envelope: TxEnvelope,
      name: Utf8,
      nonce: BigNat,
      keyIds: Set[KeyId20],
  ): Either[String, RemoveKeyIds] =
    NonEmptyKeyIds(keyIds).map: validatedKeyIds =>
      RemoveKeyIds(
        envelope = envelope,
        name = name,
        nonce = AccountNonce(nonce),
        keyIds = validatedKeyIds,
      )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(
      envelope: TxEnvelope,
      name: Utf8,
      nonce: BigNat,
      keyIds: Set[KeyId20],
  ): RemoveKeyIds =
    apply(
      envelope = envelope,
      name = name,
      nonce = nonce,
      keyIds = keyIds,
    ) match
      case Right(tx)    => tx
      case Left(error)  => throw new IllegalArgumentException(error)

  given removeKeyIdsEq: Eq[RemoveKeyIds]           = Eq.fromUniversalEquals
  given removeKeyIdsHash: Hash[RemoveKeyIds]       = Hash.build
  given removeKeyIdsRecover: Recover[RemoveKeyIds] = Recover.build

/** Remove a Named account entirely.
  *
  * Requires signature from one of the account's registered keys or the
  * guardian. Does not check for UTXO balances (module boundary concern).
  *
  * @param envelope
  *   common transaction metadata
  * @param name
  *   account name
  * @param nonce
  *   must match current account nonce
  */
final case class RemoveAccount(
    envelope: TxEnvelope,
    name: Utf8,
    nonce: AccountNonce,
) extends Tx
    derives ByteEncoder,
      ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event  = AccountsEvent[AccountRemoved]

/** Companion for [[RemoveAccount]], providing codec and crypto instances. */
object RemoveAccount:
  @targetName("applyBigNat")
  def apply(
      envelope: TxEnvelope,
      name: Utf8,
      nonce: BigNat,
  ): RemoveAccount =
    RemoveAccount(
      envelope = envelope,
      name = name,
      nonce = AccountNonce(nonce),
    )

  given removeAccountEq: Eq[RemoveAccount]           = Eq.fromUniversalEquals
  given removeAccountHash: Hash[RemoveAccount]       = Hash.build
  given removeAccountRecover: Recover[RemoveAccount] = Recover.build

/** Sealed base trait for all account-related domain events. */
sealed trait AccountEvent

/** Event emitted when a new named account is created.
  *
  * @param name the account name
  * @param guardian optional guardian account
  */
final case class AccountCreated(name: Utf8, guardian: Option[Account])
    extends AccountEvent

/** Event emitted when an account's metadata is updated.
  *
  * @param name the account name
  * @param newGuardian the updated guardian (None if removed)
  */
final case class AccountUpdated(name: Utf8, newGuardian: Option[Account])
    extends AccountEvent

/** Event emitted when public keys are added to an account.
  *
  * @param name the account name
  * @param keyIds the set of key identifiers that were added
  */
final case class KeysAdded(name: Utf8, keyIds: Set[KeyId20])
    extends AccountEvent

/** Event emitted when public keys are removed from an account.
  *
  * @param name the account name
  * @param keyIds the set of key identifiers that were removed
  */
final case class KeysRemoved(name: Utf8, keyIds: Set[KeyId20])
    extends AccountEvent

/** Event emitted when an account is removed.
  *
  * @param name the account name
  */
final case class AccountRemoved(name: Utf8) extends AccountEvent
