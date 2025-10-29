package org.sigilaris.core
package application
package accounts

import cats.Monad
import cats.data.{EitherT, StateT}
import cats.syntax.eq.*

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.byte.ByteEncoder.ops.*
import datatype.{BigNat, Utf8}
import failure.TrieFailure

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
  */
@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.AsInstanceOf"))
class AccountsReducer[F[_]: Monad] extends StateReducer0[F, AccountsSchema.AccountsSchema, EmptyTuple]:
  import AccountsSchema.*

  def apply[T <: Tx](tx: T)(using
      requiresReads: Requires[tx.Reads, AccountsSchema],
      requiresWrites: Requires[tx.Writes, AccountsSchema],
      ownsTables: Tables[F, AccountsSchema],
      provider: TablesProvider[F, EmptyTuple],
  ): StoreF[F][(tx.Result, List[tx.Event])] =

    (tx match
      case tx: CreateNamedAccount => handleCreateNamedAccount(tx)
      case tx: UpdateAccount => handleUpdateAccount(tx)
      case tx: AddKeyIds => handleAddKeyIds(tx)
      case tx: RemoveKeyIds => handleRemoveKeyIds(tx)
      case tx: RemoveAccount => handleRemoveAccount(tx)
      case _ => StateT.liftF(EitherT.leftT(TrieFailure(s"Unknown transaction type: ${tx.getClass.getName}")))
    ).asInstanceOf[StoreF[F][(tx.Result, List[tx.Event])]]

  private def handleCreateNamedAccount(tx: CreateNamedAccount)(using
      ownsTables: Tables[F, AccountsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (accountsTable *: nameKeyTable *: EmptyTuple) = ownsTables

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
