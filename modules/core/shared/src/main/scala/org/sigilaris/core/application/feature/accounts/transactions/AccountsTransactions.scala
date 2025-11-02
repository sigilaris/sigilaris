package org.sigilaris.core.application.feature.accounts.transactions

import java.time.Instant

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{Hash, Recover}
import org.sigilaris.core.datatype.{BigNat, Utf8}

import org.sigilaris.core.application.feature.accounts.domain.*
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.application.transactions.model.Tx

/** Common envelope for all transactions.
  *
  * Contains metadata for auditing and replay protection across chains.
  *
  * @param networkId chain/network identifier for cross-chain replay protection
  * @param createdAt transaction creation timestamp
  * @param memo optional memo for auditing/operational purposes
  */
final case class TxEnvelope(
    networkId: BigNat,
    createdAt: Instant,
    memo: Option[Utf8],
) derives ByteEncoder, ByteDecoder

object TxEnvelope:
  given txEnvelopeEq: Eq[TxEnvelope] = Eq.fromUniversalEquals

/** Create a new Named account.
  *
  * Requires signature from the initial key's private key.
  *
  * @param envelope common transaction metadata
  * @param name account name (UTF-8 string)
  * @param initialKeyId initial public key identifier
  * @param guardian optional guardian account
  */
final case class CreateNamedAccount(
    envelope: TxEnvelope,
    name: Utf8,
    initialKeyId: KeyId20,
    guardian: Option[Account],
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event = AccountsEvent[AccountCreated]

object CreateNamedAccount:
  given createNamedAccountEq: Eq[CreateNamedAccount] = Eq.fromUniversalEquals
  given createNamedAccountHash: Hash[CreateNamedAccount] = Hash.build
  given createNamedAccountRecover: Recover[CreateNamedAccount] = Recover.build

/** Update account information (change guardian).
  *
  * Requires signature from one of the account's registered keys or the guardian.
  *
  * @param envelope common transaction metadata
  * @param name account name
  * @param nonce must match current account nonce
  * @param newGuardian new guardian (can be None to remove, Some to set/change)
  */
final case class UpdateAccount(
    envelope: TxEnvelope,
    name: Utf8,
    nonce: BigNat,
    newGuardian: Option[Account],
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event = AccountsEvent[AccountUpdated]

object UpdateAccount:
  given updateAccountEq: Eq[UpdateAccount] = Eq.fromUniversalEquals
  given updateAccountHash: Hash[UpdateAccount] = Hash.build
  given updateAccountRecover: Recover[UpdateAccount] = Recover.build

/** Add new public keys to a Named account.
  *
  * Requires signature from one of the account's registered keys or the guardian.
  *
  * @param envelope common transaction metadata
  * @param name account name
  * @param nonce must match current account nonce
  * @param keyIds map of KeyId20 to description (user-provided)
  * @param expiresAt optional expiration timestamp applied to all new keys
  */
final case class AddKeyIds(
    envelope: TxEnvelope,
    name: Utf8,
    nonce: BigNat,
    keyIds: Map[KeyId20, Utf8],
    expiresAt: Option[Instant],
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event = AccountsEvent[KeysAdded]

object AddKeyIds:
  given addKeyIdsEq: Eq[AddKeyIds] = Eq.fromUniversalEquals
  given addKeyIdsHash: Hash[AddKeyIds] = Hash.build
  given addKeyIdsRecover: Recover[AddKeyIds] = Recover.build

/** Remove public keys from a Named account.
  *
  * Requires signature from one of the account's registered keys or the guardian.
  *
  * @param envelope common transaction metadata
  * @param name account name
  * @param nonce must match current account nonce
  * @param keyIds set of KeyId20 to remove
  */
final case class RemoveKeyIds(
    envelope: TxEnvelope,
    name: Utf8,
    nonce: BigNat,
    keyIds: Set[KeyId20],
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event = AccountsEvent[KeysRemoved]

object RemoveKeyIds:
  given removeKeyIdsEq: Eq[RemoveKeyIds] = Eq.fromUniversalEquals
  given removeKeyIdsHash: Hash[RemoveKeyIds] = Hash.build
  given removeKeyIdsRecover: Recover[RemoveKeyIds] = Recover.build

/** Remove a Named account entirely.
  *
  * Requires signature from one of the account's registered keys or the guardian.
  * Does not check for UTXO balances (module boundary concern).
  *
  * @param envelope common transaction metadata
  * @param name account name
  * @param nonce must match current account nonce
  */
final case class RemoveAccount(
    envelope: TxEnvelope,
    name: Utf8,
    nonce: BigNat,
) extends Tx derives ByteEncoder, ByteDecoder:
  type Reads = Entry["accounts", Utf8, AccountInfo] *: EmptyTuple
  type Writes = Entry["accounts", Utf8, AccountInfo] *:
    Entry["nameKey", (Utf8, KeyId20), KeyInfo] *: EmptyTuple
  type Result = AccountsResult[Unit]
  type Event = AccountsEvent[AccountRemoved]

object RemoveAccount:
  given removeAccountEq: Eq[RemoveAccount] = Eq.fromUniversalEquals
  given removeAccountHash: Hash[RemoveAccount] = Hash.build
  given removeAccountRecover: Recover[RemoveAccount] = Recover.build

// Event types
sealed trait AccountEvent

final case class AccountCreated(name: Utf8, guardian: Option[Account]) extends AccountEvent
final case class AccountUpdated(name: Utf8, newGuardian: Option[Account]) extends AccountEvent
final case class KeysAdded(name: Utf8, keyIds: Set[KeyId20]) extends AccountEvent
final case class KeysRemoved(name: Utf8, keyIds: Set[KeyId20]) extends AccountEvent
final case class AccountRemoved(name: Utf8) extends AccountEvent
