package org.sigilaris.core
package application
package accounts

import java.time.Instant

import cats.Monad
import cats.data.{EitherT, StateT}
import cats.syntax.eq.*

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.byte.ByteEncoder.ops.*
import crypto.{Hash, Recover, PublicKey}
import datatype.{BigNat, Utf8}
import failure.{TrieFailure, CryptoFailure}

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
    yield codec.byte.DecodeResult((nameResult.value, keyIdResult.value), keyIdResult.remainder)

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
  * ADR-0012 Signature Verification Plan:
  * =====================================
  * Currently, this reducer accepts Signed[T] but does NOT yet verify signatures.
  * This provides compile-time safety (unsigned txs won't compile) but not runtime security.
  *
  * TODO: Implement signature verification following ADR-0012 specification:
  *
  * 1. Extract signature and account from signedTx.sig
  * 2. Compute transaction hash: Hash.ops.toHash(signedTx.value)
  * 3. Recover public key: Recover.ops.recover(hash, signedTx.sig.sig)
  * 4. Derive KeyId20 from recovered public key (Keccak256 last 20 bytes)
  * 5. Verify the derived KeyId20 matches one of the account's registered keys:
  *    - For Named accounts: lookup in nameKey table
  *    - For Unnamed accounts: compare with account identifier directly
  * 6. Check key expiration (expiresAt field in KeyInfo)
  * 7. Return TrieFailure if any verification step fails
  *
  * This verification should be added as a common helper method that all handlers call
  * before processing the transaction logic. Example:
  *
  * ```scala
  * private def verifySignature[T <: Tx](signedTx: Signed[T])(using
  *     ownsTables: Tables[F, AccountsSchema],
  * ): StoreF[F, Unit] = ???
  *
  * private def handleCreateNamedAccount(signedTx: Signed[CreateNamedAccount])(using
  *     ownsTables: Tables[F, AccountsSchema],
  * ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
  *   for
  *     _ <- verifySignature(signedTx)  // Verify BEFORE processing
  *     result <- // ... actual logic
  *   yield result
  * ```
  *
  * Reference: ADR-0012 lines 70-97 for complete verification protocol.
  */
@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Overloading"))
class AccountsReducer[F[_]: Monad] extends StateReducer0[F, AccountsSchema.AccountsSchema, EmptyTuple]:
  import AccountsSchema.*

  /** Derive KeyId20 from a recovered public key following Ethereum convention.
    *
    * ADR-0012: KeyId20 = Keccak256(publicKey)[12..32] (last 20 bytes)
    *
    * @param pubKey the recovered public key (64 bytes uncompressed)
    * @return KeyId20 derived from the public key
    */
  private def deriveKeyId20(pubKey: PublicKey): KeyId20 =
    val pubKeyBytes = pubKey.toBytes // 64 bytes: x || y
    val hash = crypto.CryptoOps.keccak256(pubKeyBytes.toArray)
    KeyId20.unsafeApply(scodec.bits.ByteVector.view(hash).takeRight(20))

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
      StateT.pure[Eff[F], merkle.MerkleTrieState, Unit](())
    else
      // Second check: signer is the guardian
      for
        maybeInfo <- accountsTable.get(accountsTable.brand(targetName))
        _ <- maybeInfo match
          case Some(info) if info.guardian.contains(accountSig.account) =>
            StateT.pure[Eff[F], merkle.MerkleTrieState, Unit](())
          case Some(_) =>
            StateT.liftF[Eff[F], merkle.MerkleTrieState, Unit]:
              EitherT.leftT:
                CryptoFailure(
                  s"Unauthorized: ${accountSig.account} is not owner or guardian of account ${targetName.asString}"
                )
          case None =>
            StateT.liftF[Eff[F], merkle.MerkleTrieState, Unit]:
              EitherT.leftT:
                TrieFailure(s"Account ${targetName.asString} not found during authorization check")
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
    * IMPORTANT: Key expiration is checked against the transaction's envelope.createdAt
    * timestamp to ensure deterministic validation across all nodes in the blockchain.
    *
    * @param signedTx the signed transaction to verify
    * @param ownsTables the owned tables for account/key lookups
    * @return Either verification failure or Unit on success
    */
  private def verifySignature[T <: Tx](signedTx: Signed[T])(using
      hashT: Hash[T],
      recoverT: Recover[T],
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][Unit] =
    val (accountsTable *: nameKeyTable *: EmptyTuple) = ownsTables
    val tx = signedTx.value
    val accountSig = signedTx.sig

    // Step 1: Compute transaction hash
    val txHash = hashT(tx)

    // Step 2: Recover public key from signature
    val recoveryResult: Either[failure.SigilarisFailure, PublicKey] =
      recoverT.fromHash(txHash, accountSig.sig)

    recoveryResult match
      case Left(err) =>
        StateT.liftF(EitherT.leftT(CryptoFailure(s"Signature recovery failed: ${err.msg}")))

      case Right(recoveredPubKey) =>
        // Step 3: Derive KeyId20 from recovered public key
        val recoveredKeyId = deriveKeyId20(recoveredPubKey)

        // Step 4 & 5: Verify key is registered and not expired
        accountSig.account match
          case Account.Named(name) =>
            // For Named accounts: look up key in nameKey table
            for
              maybeKeyInfo <- nameKeyTable.get(nameKeyTable.brand((name, recoveredKeyId)))
              _ <- maybeKeyInfo match
                case None =>
                  StateT.liftF[Eff[F], merkle.MerkleTrieState, Unit]:
                    EitherT.leftT:
                      CryptoFailure(s"Key ${recoveredKeyId.bytes.toHex} not registered for account ${name.asString}")
                case Some(keyInfo) =>
                  // Check expiration using deterministic timestamp from transaction envelope
                  // This ensures all nodes reach consensus on key expiration
                  // Extract timestamp from transaction envelope (all accounts transactions have this field)
                  val txTimestamp = tx match
                    case tx: CreateNamedAccount => tx.envelope.createdAt
                    case tx: UpdateAccount      => tx.envelope.createdAt
                    case tx: AddKeyIds          => tx.envelope.createdAt
                    case tx: RemoveKeyIds       => tx.envelope.createdAt
                    case tx: RemoveAccount      => tx.envelope.createdAt
                    case _ =>
                      // This should never happen for accounts transactions
                      Instant.MIN // Fallback to epoch, will never expire

                  keyInfo.expiresAt match
                    case Some(expiresAt) if txTimestamp.isAfter(expiresAt) =>
                      StateT.liftF[Eff[F], merkle.MerkleTrieState, Unit]:
                        EitherT.leftT:
                          CryptoFailure(s"Key expired at $expiresAt, transaction timestamp: $txTimestamp")
                    case _ =>
                      StateT.pure[Eff[F], merkle.MerkleTrieState, Unit](())
            yield ()

          case Account.Unnamed(keyId) =>
            // For Unnamed accounts: key ID must match account ID directly
            if recoveredKeyId === keyId then
              StateT.pure[Eff[F], merkle.MerkleTrieState, Unit](())
            else
              StateT.liftF[Eff[F], merkle.MerkleTrieState, Unit]:
                EitherT.leftT:
                  CryptoFailure(s"Recovered key ${recoveredKeyId.bytes.toHex} does not match unnamed account ${keyId.bytes.toHex}")

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
        StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[Nothing])](
          EitherT.leftT[F, (Unit, List[Nothing])](TrieFailure(s"Unknown transaction type: ${tx.getClass.getName}"))
        )

    result.asInstanceOf[StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])]]

  private def handleCreateNamedAccount(tx: CreateNamedAccount, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (accountsTable *: nameKeyTable *: EmptyTuple) = ownsTables

    // Special verification for CreateNamedAccount: verify recovered key matches tx.initialKeyId
    // This must be done before checking if account exists, because the account doesn't exist yet
    val txHash = summon[Hash[CreateNamedAccount]](tx)
    val recoveryResult = summon[Recover[CreateNamedAccount]].fromHash(txHash, sig.sig)

    // Helper to verify and proceed with account creation
    def verifyAndCreate(recoveredPubKey: PublicKey): StoreF[F][(Unit, List[AccountCreated])] =
      val recoveredKeyId = deriveKeyId20(recoveredPubKey)

      // Verify recovered key matches the initial key being registered
      if recoveredKeyId =!= tx.initialKeyId then
        StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[AccountCreated])]:
          EitherT.leftT:
            CryptoFailure(
              s"Recovered key ${recoveredKeyId.bytes.toHex} does not match initialKeyId ${tx.initialKeyId.bytes.toHex}"
            )
      else
        // Signature is valid, proceed with account creation
        for
          maybeExisting <- accountsTable.get(accountsTable.brand(tx.name))
          result <- maybeExisting match
            case Some(_) =>
              StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[AccountCreated])]:
                EitherT.leftT:
                  TrieFailure(s"Account ${tx.name.asString} already exists")
            case None =>
              val accountInfo = AccountInfo(guardian = tx.guardian, nonce = BigNat.Zero)
              val keyInfo = KeyInfo(addedAt = tx.envelope.createdAt, expiresAt = None, description = Utf8(""))
              for
                _ <- accountsTable.put(accountsTable.brand(tx.name), accountInfo)
                _ <- nameKeyTable.put(nameKeyTable.brand((tx.name, tx.initialKeyId)), keyInfo)
              yield ((), List(AccountCreated(tx.name, tx.guardian)))
        yield result

    recoveryResult match
      case Left(err) =>
        StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[AccountCreated])]:
          EitherT.leftT:
            CryptoFailure(s"Signature recovery failed for CreateNamedAccount: ${err.msg}")
      case Right(recoveredPubKey) =>
        verifyAndCreate(recoveredPubKey)

  private def verifyAndHandleUpdateAccount(tx: UpdateAccount, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[UpdateAccount]],
        recoverT = summon[Recover[UpdateAccount]],
        ownsTables = ownsTables,
      )
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
            yield ((), List(AccountUpdated(tx.name, tx.newGuardian)))
          else
            StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[AccountUpdated])]:
              EitherT.leftT(TrieFailure(s"Nonce mismatch: expected ${info.nonce}, got ${tx.nonce}"))
        case None =>
          StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[AccountUpdated])]:
            EitherT.leftT(TrieFailure(s"Account ${tx.name.asString} not found"))
    yield result

  private def verifyAndHandleAddKeyIds(tx: AddKeyIds, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[AddKeyIds]],
        recoverT = summon[Recover[AddKeyIds]],
        ownsTables = ownsTables,
      )
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
            val addKeysEffect = tx.keyIds.foldLeft(StateT.pure[Eff[F], merkle.MerkleTrieState, Unit](())) {
              case (acc, (keyId, description)) =>
                for
                  _ <- acc
                  maybeExisting <- nameKeyTable.get(nameKeyTable.brand((tx.name, keyId)))
                  _ <- maybeExisting match
                    case Some(_) => StateT.pure[Eff[F], merkle.MerkleTrieState, Unit](()) // Skip existing
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
            yield ((), List(KeysAdded(tx.name, tx.keyIds.keySet)))
          else
            StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[KeysAdded])]:
              EitherT.leftT(TrieFailure(s"Nonce mismatch: expected ${info.nonce}, got ${tx.nonce}"))
        case None =>
          StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[KeysAdded])]:
            EitherT.leftT(TrieFailure(s"Account ${tx.name.asString} not found"))
    yield result

  private def verifyAndHandleRemoveKeyIds(tx: RemoveKeyIds, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[RemoveKeyIds]],
        recoverT = summon[Recover[RemoveKeyIds]],
        ownsTables = ownsTables,
      )
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
            val removeKeysEffect = tx.keyIds.foldLeft(StateT.pure[Eff[F], merkle.MerkleTrieState, Unit](())):
              case (acc, keyId) =>
                for
                  _ <- acc
                  _ <- nameKeyTable.remove(nameKeyTable.brand((tx.name, keyId)))
                yield ()

            val newInfo = AccountInfo(
              guardian = info.guardian,
              nonce = BigNat.unsafeFromBigInt(info.nonce.toBigInt + 1),
            )
            for
              _ <- removeKeysEffect
              _ <- accountsTable.put(accountsTable.brand(tx.name), newInfo)
            yield ((), List(KeysRemoved(tx.name, tx.keyIds)))
          else
            StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[KeysRemoved])]:
              EitherT.leftT:
                TrieFailure(s"Nonce mismatch: expected ${info.nonce}, got ${tx.nonce}")
        case None =>
          StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[KeysRemoved])]:
            EitherT.leftT:
              TrieFailure(s"Account ${tx.name.asString} not found")
    yield result

  private def verifyAndHandleRemoveAccount(tx: RemoveAccount, sig: AccountSignature)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[RemoveAccount]],
        recoverT = summon[Recover[RemoveAccount]],
        ownsTables = ownsTables,
      )
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
              ((), List(AccountRemoved(tx.name)))
          else
            StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[AccountRemoved])]:
              EitherT.leftT:
                TrieFailure(s"Nonce mismatch: expected ${info.nonce}, got ${tx.nonce}")
        case None =>
          StateT.liftF[Eff[F], merkle.MerkleTrieState, (Unit, List[AccountRemoved])]:
            EitherT.leftT(TrieFailure(s"Account ${tx.name.asString} not found"))
    yield result

/** Accounts module blueprint.
  *
  * Phase 6 example blueprint implementing ADR-0010 (Blockchain Account Model and Key Management).
  */
object AccountsBP:
  def apply[F[_]: Monad](using @annotation.unused nodeStore: merkle.MerkleTrie.NodeStore[F]): ModuleBlueprint[F, "accounts", AccountsSchema.AccountsSchema, EmptyTuple, EmptyTuple] =
    import AccountsSchema.*

    new ModuleBlueprint[F, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      owns = accountsEntries,
      reducer0 = new AccountsReducer[F],
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[F],
    )
