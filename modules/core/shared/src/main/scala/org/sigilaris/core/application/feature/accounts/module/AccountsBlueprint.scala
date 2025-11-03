package org.sigilaris.core.application.feature.accounts.module

import cats.Monad
import cats.syntax.eq.*

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.core.failure.{TrieFailure, CryptoFailure}
import org.sigilaris.core.application.feature.accounts.domain.*
import org.sigilaris.core.application.feature.accounts.transactions.*
import org.sigilaris.core.application.module.blueprint.{ModuleBlueprint, StateReducer0}
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.security.SignatureVerifier
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.{AccountSignature, Signed, Tx, TxRegistry}

/** Accounts module schema.
  *
  * Tables:
  *   - accounts: name -> AccountInfo (guardian, nonce)
  *   - nameKey: (name, keyId) -> KeyInfo (addedAt, expiresAt, description)
  */
object AccountsSchema:
  // Need ByteCodec for tuple keys
  given tupleByteEncoder: ByteEncoder[(Utf8, KeyId20)] = (t: (Utf8, KeyId20)) =>
    t._1.toBytes ++ t._2.toBytes

  given tupleByteDecoder: ByteDecoder[(Utf8, KeyId20)] = bytes =>
    for
      nameResult <- ByteDecoder[Utf8].decode(bytes)
      keyIdResult <- ByteDecoder[KeyId20].decode(nameResult.remainder)
    yield org.sigilaris.core.codec.byte.DecodeResult((nameResult.value, keyIdResult.value), keyIdResult.remainder)

  type AccountsSchema =
    Entry["accounts", Utf8, AccountInfo] *:
      Entry["nameKey", (Utf8, KeyId20), KeyInfo] *:
      EmptyTuple

  val accountsEntry = new Entry["accounts", Utf8, AccountInfo]("accounts")
  val nameKeyEntry = new Entry["nameKey", (Utf8, KeyId20), KeyInfo]("nameKey")

  val accountsEntries: AccountsSchema = accountsEntry *: nameKeyEntry *: EmptyTuple

/** Accounts state reducer (path-agnostic).
  *
  * Implements transaction logic for account management:
  *   - CreateNamedAccount
  *   - UpdateAccount
  *   - AddKeyIds
  *   - RemoveKeyIds
  *   - RemoveAccount
  *
  * Signature verification follows ADR-0012 and is delegated to the shared
  * `SignatureVerifier` utility to guarantee consistent key recovery and
  * expiration checks.
  */
@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Overloading"))
class AccountsReducer[F[_]: Monad] extends StateReducer0[F, AccountsSchema.AccountsSchema, EmptyTuple]:
  import AccountsSchema.*

  private inline def resultOf[A](value: A): AccountsResult[A] = AccountsResult(value)
  private inline def eventOf[A](value: A): AccountsEvent[A] = AccountsEvent(value)

  /** Verify authorization for account mutation operations.
    *
    * Checks that the signer has permission to modify the target account.
    * Authorization is granted if:
    * 1. Signer is the account owner (accountSig.account == Account.Named(targetName))
    * 2. Signer is the account's guardian (Some(accountSig.account) == accountInfo.guardian)
    *
    * @param targetName the account being modified
    * @param accountSig the signature claiming authorization
    * @param ownsTables the owned tables for account lookups
    * @return Either authorization failure or Unit on success
    */
  private def verifyAuthorization(
      targetName: Utf8,
      accountSig: AccountSignature,
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][Unit] =
    val (accountsTable *: _ *: EmptyTuple) = ownsTables

    // First check: signer is the account owner
    if accountSig.account === Account.Named(targetName) then
      StoreF.pure[F, Unit](())
    else
      // Second check: signer is the guardian
      for
        maybeInfo <- accountsTable.get(accountsTable.brand(targetName))
        _ <- maybeInfo match
          case Some(info) if info.guardian.contains(accountSig.account) =>
            StoreF.pure[F, Unit](())
          case Some(_) =>
            StoreF.raise[F, Unit](
              CryptoFailure(
                s"Unauthorized: ${accountSig.account} is not owner or guardian of account ${targetName.asString}"
              )
            )
          case None =>
            StoreF.raise[F, Unit](
              TrieFailure(s"Account ${targetName.asString} not found during authorization check")
            )
      yield ()

  /** Verify transaction signature according to ADR-0012.
    *
    * Verification steps:
    * 1. Compute transaction hash
    * 2. Recover public key from signature
    * 3. Derive KeyId20 from recovered public key
    * 4. Verify KeyId20 is registered for the signing account
    * 5. Check key expiration (for Named accounts) using deterministic timestamp
    *
    * NOTE: This only verifies that the signature is valid for the claimed signer.
    * For account mutation operations, you must also call verifyAuthorization
    * to ensure the signer has permission to modify the target account.
    *
    * @param signedTx the signed transaction to verify
    * @param hashT typeclass instance to hash the transaction
    * @param recoverT typeclass instance to recover the signing public key
    * @param ownsTables the owned tables for account/key lookups
    * @return Either verification failure or Unit on success
    */
  def apply[T <: Tx](signedTx: Signed[T])(using
      requiresReads: Requires[signedTx.value.Reads, AccountsSchema],
      requiresWrites: Requires[signedTx.value.Writes, AccountsSchema],
      ownsTables: Tables[F, AccountsSchema],
      provider: TablesProvider[F, EmptyTuple],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
    val tx = signedTx.value

    // Verify signature BEFORE processing any transaction logic
    // Pattern match on specific transaction types to get their Hash/Recover instances
    val result = tx match
      case tx: CreateNamedAccount =>
        // Special handling for CreateNamedAccount: account doesn't exist yet,
        // so we verify that the recovered key matches tx.initialKeyId inside the handler
        handleCreateNamedAccount(tx, signedTx.sig)
      case tx: UpdateAccount =>
        verifyAndHandleUpdateAccount(tx, signedTx.sig)
      case tx: AddKeyIds =>
        verifyAndHandleAddKeyIds(tx, signedTx.sig)
      case tx: RemoveKeyIds =>
        verifyAndHandleRemoveKeyIds(tx, signedTx.sig)
      case tx: RemoveAccount =>
        verifyAndHandleRemoveAccount(tx, signedTx.sig)
      case _ =>
        StoreF.raise[F, (Unit, List[Nothing])](
          TrieFailure(s"Unknown transaction type: ${tx.getClass.getName}")
        )

    result.asInstanceOf[StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])]]

  private def handleCreateNamedAccount(tx: CreateNamedAccount, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (accountsTable *: nameKeyTable *: EmptyTuple) = ownsTables

    for
      recoveredKeyId <- SignatureVerifier.recoverKeyId[F, CreateNamedAccount](
        Signed(sig, tx),
        context = Some("CreateNamedAccount"),
      )
      result <-
        if recoveredKeyId =!= tx.initialKeyId then
          StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[AccountCreated]])](
            CryptoFailure(
              s"Recovered key ${recoveredKeyId.bytes.toHex} does not match initialKeyId ${tx.initialKeyId.bytes.toHex}"
            )
          )
        else
          for
            maybeExisting <- accountsTable.get(accountsTable.brand(tx.name))
            created <- maybeExisting match
              case Some(_) =>
                StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[AccountCreated]])](
                  TrieFailure(s"Account ${tx.name.asString} already exists")
                )
              case None =>
                val accountInfo = AccountInfo(guardian = tx.guardian, nonce = BigNat.Zero)
                val keyInfo = KeyInfo(addedAt = tx.envelope.createdAt, expiresAt = None, description = Utf8(""))
                for
                  _ <- accountsTable.put(accountsTable.brand(tx.name), accountInfo)
                  _ <- nameKeyTable.put(nameKeyTable.brand((tx.name, tx.initialKeyId)), keyInfo)
                yield (resultOf(()), List(eventOf(AccountCreated(tx.name, tx.guardian))))
          yield created
    yield result

  private def verifyAndHandleUpdateAccount(tx: UpdateAccount, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (_ *: nameKeyTable *: EmptyTuple) = ownsTables

    for
      _ <- SignatureVerifier.verifySignature[F, UpdateAccount](
        Signed(sig, tx),
        tx.envelope.createdAt,
        context = None,
      ) { (name, keyId) =>
        nameKeyTable.get(nameKeyTable.brand((name, keyId)))
      }
      _ <- verifyAuthorization(tx.name, sig, ownsTables)
      result <- handleUpdateAccount(tx)
    yield result

  private def handleUpdateAccount(tx: UpdateAccount)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (accountsTable *: _ *: EmptyTuple) = ownsTables

    for
      maybeInfo <- accountsTable.get(accountsTable.brand(tx.name))
      result <- maybeInfo match
        case Some(info) =>
          if info.nonce === tx.nonce then
            val newInfo = AccountInfo(
              guardian = tx.newGuardian,
              nonce = BigNat.unsafeFromBigInt(info.nonce.toBigInt + 1),
            )
            for
              _ <- accountsTable.put(accountsTable.brand(tx.name), newInfo)
            yield (resultOf(()), List(eventOf(AccountUpdated(tx.name, tx.newGuardian))))
          else
            StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[AccountUpdated]])](
              TrieFailure(s"Nonce mismatch: expected ${info.nonce}, got ${tx.nonce}")
            )
        case None =>
          StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[AccountUpdated]])](
            TrieFailure(s"Account ${tx.name.asString} not found")
          )
    yield result

  private def verifyAndHandleAddKeyIds(tx: AddKeyIds, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (_ *: nameKeyTable *: EmptyTuple) = ownsTables

    for
      _ <- SignatureVerifier.verifySignature[F, AddKeyIds](
        Signed(sig, tx),
        tx.envelope.createdAt,
        context = None,
      ) { (name, keyId) =>
        nameKeyTable.get(nameKeyTable.brand((name, keyId)))
      }
      _ <- verifyAuthorization(tx.name, sig, ownsTables)
      result <- handleAddKeyIds(tx)
    yield result

  private def handleAddKeyIds(tx: AddKeyIds)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (accountsTable *: nameKeyTable *: EmptyTuple) = ownsTables

    for
      maybeInfo <- accountsTable.get(accountsTable.brand(tx.name))
      result <- maybeInfo match
        case Some(info) =>
          if info.nonce === tx.nonce then
            // Add all keys sequentially
            val addKeysEffect = tx.keyIds.foldLeft(StoreF.pure[F, Unit](())) {
              case (acc, (keyId, description)) =>
                for
                  _ <- acc
                  maybeExisting <- nameKeyTable.get(nameKeyTable.brand((tx.name, keyId)))
                  _ <- maybeExisting match
                    case Some(_) => StoreF.pure[F, Unit](()) // Skip existing
                    case None =>
                      val keyInfo = KeyInfo(
                        addedAt = tx.envelope.createdAt,
                        expiresAt = tx.expiresAt,
                        description = description,
                      )
                      nameKeyTable.put(nameKeyTable.brand((tx.name, keyId)), keyInfo)
                yield ()
            }

            val newInfo = AccountInfo(
              guardian = info.guardian,
              nonce = BigNat.unsafeFromBigInt(info.nonce.toBigInt + 1),
            )
            for
              _ <- addKeysEffect
              _ <- accountsTable.put(accountsTable.brand(tx.name), newInfo)
            yield (resultOf(()), List(eventOf(KeysAdded(tx.name, tx.keyIds.keySet))))
          else
            StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[KeysAdded]])](
              TrieFailure(s"Nonce mismatch: expected ${info.nonce}, got ${tx.nonce}")
            )
        case None =>
          StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[KeysAdded]])](
            TrieFailure(s"Account ${tx.name.asString} not found")
          )
    yield result

  private def verifyAndHandleRemoveKeyIds(tx: RemoveKeyIds, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (_ *: nameKeyTable *: EmptyTuple) = ownsTables

    for
      _ <- SignatureVerifier.verifySignature[F, RemoveKeyIds](
        Signed(sig, tx),
        tx.envelope.createdAt,
        context = None,
      ) { (name, keyId) =>
        nameKeyTable.get(nameKeyTable.brand((name, keyId)))
      }
      _ <- verifyAuthorization(tx.name, sig, ownsTables)
      result <- handleRemoveKeyIds(tx)
    yield result

  private def handleRemoveKeyIds(tx: RemoveKeyIds)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (accountsTable *: nameKeyTable *: EmptyTuple) = ownsTables

    for
      maybeInfo <- accountsTable.get(accountsTable.brand(tx.name))
      result <- maybeInfo match
        case Some(info) =>
          if info.nonce === tx.nonce then
            // Remove all keys sequentially
            val removeKeysEffect = tx.keyIds.foldLeft(StoreF.pure[F, Unit](())) {
              case (acc, keyId) =>
                for
                  _ <- acc
                  _ <- nameKeyTable.remove(nameKeyTable.brand((tx.name, keyId)))
                yield ()
            }

            val newInfo = AccountInfo(
              guardian = info.guardian,
              nonce = BigNat.unsafeFromBigInt(info.nonce.toBigInt + 1),
            )
            for
              _ <- removeKeysEffect
              _ <- accountsTable.put(accountsTable.brand(tx.name), newInfo)
            yield (resultOf(()), List(eventOf(KeysRemoved(tx.name, tx.keyIds))))
          else
            StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[KeysRemoved]])](
              TrieFailure(s"Nonce mismatch: expected ${info.nonce}, got ${tx.nonce}")
            )
        case None =>
          StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[KeysRemoved]])](
            TrieFailure(s"Account ${tx.name.asString} not found")
          )
    yield result

  private def verifyAndHandleRemoveAccount(tx: RemoveAccount, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (_ *: nameKeyTable *: EmptyTuple) = ownsTables

    for
      _ <- SignatureVerifier.verifySignature[F, RemoveAccount](
        Signed(sig, tx),
        tx.envelope.createdAt,
        context = None,
      ) { (name, keyId) =>
        nameKeyTable.get(nameKeyTable.brand((name, keyId)))
      }
      _ <- verifyAuthorization(tx.name, sig, ownsTables)
      result <- handleRemoveAccount(tx)
    yield result

  private def handleRemoveAccount(tx: RemoveAccount)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (accountsTable *: nameKeyTable *: EmptyTuple) = ownsTables

    for
      maybeInfo <- accountsTable.get(accountsTable.brand(tx.name))
      result <- maybeInfo match
        case Some(info) =>
          if info.nonce === tx.nonce then
            for
              _ <- accountsTable.remove(accountsTable.brand(tx.name))
            yield
              // Note: In a real implementation, we would need to iterate over all keys
              // and remove them. For now, this is a simplified version.
              // TODO: Add streaming API to iterate over all keys with prefix (name, *)
              (resultOf(()), List(eventOf(AccountRemoved(tx.name))))
          else
            StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[AccountRemoved]])](
              TrieFailure(s"Nonce mismatch: expected ${info.nonce}, got ${tx.nonce}")
            )
        case None =>
          StoreF.raise[F, (AccountsResult[Unit], List[AccountsEvent[AccountRemoved]])](
            TrieFailure(s"Account ${tx.name.asString} not found")
          )
    yield result

/** Accounts module blueprint.
  *
  * Phase 6 example blueprint implementing ADR-0010 (Blockchain Account Model and Key Management).
  */
object AccountsBP:
  def apply[F[_]: Monad](using @annotation.unused nodeStore: org.sigilaris.core.merkle.MerkleTrie.NodeStore[F]): ModuleBlueprint[F, "accounts", AccountsSchema.AccountsSchema, EmptyTuple, EmptyTuple] =
    import AccountsSchema.*

    new ModuleBlueprint[F, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      owns = accountsEntries,
      reducer0 = new AccountsReducer[F],
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[F],
    )
