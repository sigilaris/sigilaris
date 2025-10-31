package org.sigilaris.core
package application
package group

import cats.Monad
import cats.data.{EitherT, StateT}
import cats.syntax.eq.*
import scala.Tuple.++

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.byte.ByteEncoder.ops.*
import crypto.{Hash, Recover, PublicKey}
import datatype.{BigNat, Utf8}
import failure.{TrieFailure, CryptoFailure}
import application.accounts.{Account, AccountInfo, KeyId20, KeyInfo}

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

  /** Derive KeyId20 from a recovered public key following Ethereum convention.
    *
    * ADR-0012: KeyId20 = Keccak256(publicKey)[12..32] (last 20 bytes)
    */
  private def deriveKeyId20(pubKey: PublicKey): KeyId20 =
    val pubKeyBytes = pubKey.toBytes
    val hash = crypto.CryptoOps.keccak256(pubKeyBytes.toArray)
    KeyId20.unsafeApply(scodec.bits.ByteVector.view(hash).takeRight(20))

  /** Verify transaction signature according to ADR-0012.
    *
    * Verification steps:
    * 1. Compute transaction hash
    * 2. Recover public key from signature
    * 3. Derive KeyId20 from recovered public key
    * 4. Verify the recovered key is registered for the claimed account
    *
    * SECURITY: This method prevents signature forgery by checking that the recovered
    * key actually belongs to the claimed account. Without this check, an attacker could
    * sign with their own key and claim to be any account.
    *
    * For Named accounts, we lookup the key in the accounts module's nameKey table.
    * For Unnamed accounts, the KeyId20 must match the account identifier directly.
    */
  private def verifySignature[T <: Tx](signedTx: Signed[T])(using
      hashT: Hash[T],
      recoverT: Recover[T],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][Unit] =
    val tx = signedTx.value
    val accountSig = signedTx.sig

    // Step 1: Compute transaction hash
    val txHash = hashT(tx)

    // Step 2: Recover public key from signature
    val recoveryResult: Either[failure.SigilarisFailure, PublicKey] =
      recoverT.fromHash(txHash, accountSig.sig)

    recoveryResult match
      case Left(err) =>
        StateT.liftF:
          EitherT.leftT(CryptoFailure(s"Signature recovery failed: ${err.msg}"))

      case Right(recoveredPubKey) =>
        // Step 3: Derive KeyId20 from recovered public key
        val recoveredKeyId = deriveKeyId20(recoveredPubKey)

        // Step 4: Verify key is registered for the claimed account
        accountSig.account match
          case Account.Named(name) =>
            // For Named accounts: lookup key in nameKey table (from AccountsBP)
            // SECURITY FIX: Previously this always returned success, allowing signature forgery
            val (_ *: nameKeyTable *: EmptyTuple) = provider.tables
            for
              maybeKeyInfo <- nameKeyTable.get(nameKeyTable.brand((name, recoveredKeyId)))
              _ <- maybeKeyInfo match
                case None =>
                  StateT.liftF[Eff[F], StoreState, Unit]:
                    EitherT.leftT:
                      CryptoFailure(
                        s"Key ${recoveredKeyId.bytes.toHex} not registered for Named account ${name.asString}"
                      )
                case Some(keyInfo) =>
                  // Key is registered - now check expiration (ADR-0012)
                  // Extract transaction timestamp from envelope
                  val txTimestamp = tx match
                    case tx: CreateGroup        => tx.envelope.createdAt
                    case tx: DisbandGroup       => tx.envelope.createdAt
                    case tx: AddAccounts        => tx.envelope.createdAt
                    case tx: RemoveAccounts     => tx.envelope.createdAt
                    case tx: ReplaceCoordinator => tx.envelope.createdAt
                    case _ =>
                      // Fallback for unknown transaction types
                      java.time.Instant.MIN

                  keyInfo.expiresAt match
                    case Some(expiresAt) if txTimestamp.isAfter(expiresAt) =>
                      StateT.liftF[Eff[F], StoreState, Unit]:
                        EitherT.leftT:
                          CryptoFailure(
                            s"Key expired at $expiresAt, transaction timestamp: $txTimestamp"
                          )
                    case _ =>
                      // Key has no expiration or has not expired yet
                      StateT.pure[Eff[F], StoreState, Unit](())
            yield ()

          case Account.Unnamed(keyId) =>
            // For Unnamed accounts: key ID must match account ID directly
            if recoveredKeyId === keyId then
              StateT.pure[Eff[F], StoreState, Unit](())
            else
              StateT.liftF[Eff[F], StoreState, Unit]:
                EitherT.leftT:
                  CryptoFailure(s"Recovered key ${recoveredKeyId.bytes.toHex} does not match unnamed account ${keyId.bytes.toHex}")

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
          StateT.pure[Eff[F], StoreState, Unit](())
        case Some(groupData) =>
          StateT.liftF[Eff[F], StoreState, Unit]:
            EitherT.leftT:
              CryptoFailure(
                s"Unauthorized: ${accountSig.account} is not the coordinator of group ${groupId.toUtf8.asString}"
              )
        case None =>
          StateT.liftF[Eff[F], StoreState, Unit]:
            EitherT.leftT:
              TrieFailure(s"Group ${groupId.toUtf8.asString} not found during authorization check")
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
        StateT.liftF[Eff[F], StoreState, (Unit, List[Nothing])]:
          EitherT.leftT[F, (Unit, List[Nothing])]:
            TrieFailure(s"Unknown transaction type: ${tx.getClass.getName}")

    result.asInstanceOf[StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])]]

  private def verifyAndHandleCreateGroup(tx: CreateGroup, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[CreateGroup]],
        recoverT = summon[Recover[CreateGroup]],
        provider = provider,
      )
      result <- handleCreateGroup(tx, sig)
    yield result

  private def handleCreateGroup(tx: CreateGroup, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (groupsTable *: _ *: EmptyTuple) = ownsTables

    // Verify signer is the coordinator
    if sig.account =!= tx.coordinator then
      StateT.liftF[Eff[F], StoreState, (Unit, List[GroupCreated])]:
        EitherT.leftT:
          CryptoFailure(
            s"Unauthorized: signer ${sig.account} does not match coordinator ${tx.coordinator}"
          )
    else
      for
        maybeExisting <- groupsTable.get(groupsTable.brand(tx.groupId))
        result <- maybeExisting match
          case Some(_) =>
            StateT.liftF[Eff[F], StoreState, (Unit, List[GroupCreated])]:
              EitherT.leftT:
                TrieFailure(s"Group ${tx.groupId.toUtf8.asString} already exists")
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
            yield ((), List(GroupCreated(tx.groupId, tx.coordinator, tx.name)))
      yield result

  private def verifyAndHandleDisbandGroup(tx: DisbandGroup, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[DisbandGroup]],
        recoverT = summon[Recover[DisbandGroup]],
        provider = provider,
      )
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
            StateT.liftF[Eff[F], StoreState, (Unit, List[GroupDisbanded])]:
              EitherT.leftT:
                TrieFailure(s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}")
          else if groupData.memberCount =!= BigNat.Zero then
            // SAFETY INVARIANT: Only empty groups can be disbanded.
            // This prevents orphaned membership entries in the groupAccounts table.
            // Without this check, disbanding a group with members would leave
            // (groupId, account) entries behind, which could resurrect if the
            // group is recreated with the same ID.
            //
            // To disband a group with members, the coordinator must first
            // remove all members via RemoveAccounts transactions.
            StateT.liftF[Eff[F], StoreState, (Unit, List[GroupDisbanded])]:
              EitherT.leftT:
                TrieFailure(s"Cannot disband group ${tx.groupId.toUtf8.asString} with ${groupData.memberCount} members. Remove all members first.")
          else
            for
              _ <- groupsTable.remove(groupsTable.brand(tx.groupId))
            yield ((), List(GroupDisbanded(tx.groupId)))
        case None =>
          StateT.liftF[Eff[F], StoreState, (Unit, List[GroupDisbanded])]:
            EitherT.leftT:
              TrieFailure(s"Group ${tx.groupId.toUtf8.asString} not found")
    yield result

  private def verifyAndHandleAddAccounts(tx: AddAccounts, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[AddAccounts]],
        recoverT = summon[Recover[AddAccounts]],
        provider = provider,
      )
      _ <- verifyCoordinatorSignature(tx.groupId, sig, ownsTables)
      result <- handleAddAccounts(tx)
    yield result

  private def handleAddAccounts(tx: AddAccounts)(using
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (groupsTable *: groupAccountsTable *: EmptyTuple) = ownsTables

    // Validate non-empty accounts set
    if tx.accounts.isEmpty then
      StateT.liftF[Eff[F], StoreState, (Unit, List[GroupMembersAdded])]:
        EitherT.leftT:
          TrieFailure("AddAccounts requires non-empty accounts set")
    else
      for
        maybeData <- groupsTable.get(groupsTable.brand(tx.groupId))
        result <- maybeData match
          case Some(groupData) =>
            if groupData.nonce === tx.groupNonce then
              // Add all accounts sequentially and track which were actually added (idempotent)
              val addAccountsEffect = tx.accounts.foldLeft(
                StateT.pure[Eff[F], StoreState, Set[Account]](Set.empty[Account])
              ) {
                case (acc, account) =>
                  for
                    alreadyAdded <- acc
                    maybeExisting <- groupAccountsTable.get(groupAccountsTable.brand((tx.groupId, account)))
                    newAdded <- maybeExisting match
                      case Some(_) =>
                        // Already a member, skip (idempotent)
                        StateT.pure[Eff[F], StoreState, Set[Account]](alreadyAdded)
                      case None =>
                        // Add new member
                        for
                          _ <- groupAccountsTable.put(groupAccountsTable.brand((tx.groupId, account)), ())
                        yield alreadyAdded + account
                  yield newAdded
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
              yield ((), List(GroupMembersAdded(tx.groupId, actuallyAdded)))
            else
              StateT.liftF[Eff[F], StoreState, (Unit, List[GroupMembersAdded])]:
                EitherT.leftT:
                  TrieFailure(s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}")
          case None =>
            StateT.liftF[Eff[F], StoreState, (Unit, List[GroupMembersAdded])]:
              EitherT.leftT:
                TrieFailure(s"Group ${tx.groupId.toUtf8.asString} not found")
      yield result

  private def verifyAndHandleRemoveAccounts(tx: RemoveAccounts, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[RemoveAccounts]],
        recoverT = summon[Recover[RemoveAccounts]],
        provider = provider,
      )
      _ <- verifyCoordinatorSignature(tx.groupId, sig, ownsTables)
      result <- handleRemoveAccounts(tx)
    yield result

  private def handleRemoveAccounts(tx: RemoveAccounts)(using
      ownsTables: Tables[F, GroupsSchema],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    val (groupsTable *: groupAccountsTable *: EmptyTuple) = ownsTables

    // Validate non-empty accounts set
    if tx.accounts.isEmpty then
      StateT.liftF[Eff[F], StoreState, (Unit, List[GroupMembersRemoved])]:
        EitherT.leftT:
          TrieFailure("RemoveAccounts requires non-empty accounts set")
    else
      for
        maybeData <- groupsTable.get(groupsTable.brand(tx.groupId))
        result <- maybeData match
          case Some(groupData) =>
            if groupData.nonce === tx.groupNonce then
              // Remove all accounts sequentially and track which were actually removed (idempotent)
              val removeAccountsEffect = tx.accounts.foldLeft(
                StateT.pure[Eff[F], StoreState, Set[Account]](Set.empty[Account])
              ):
                case (acc, account) =>
                  for
                    alreadyRemoved <- acc
                    wasRemoved <- groupAccountsTable.remove(groupAccountsTable.brand((tx.groupId, account)))
                    newRemoved = if wasRemoved then alreadyRemoved + account else alreadyRemoved
                  yield newRemoved

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
              yield ((), List(GroupMembersRemoved(tx.groupId, actuallyRemoved)))
            else
              StateT.liftF[Eff[F], StoreState, (Unit, List[GroupMembersRemoved])]:
                EitherT.leftT:
                  TrieFailure(s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}")
          case None =>
            StateT.liftF[Eff[F], StoreState, (Unit, List[GroupMembersRemoved])]:
              EitherT.leftT:
                TrieFailure(s"Group ${tx.groupId.toUtf8.asString} not found")
      yield result

  private def verifyAndHandleReplaceCoordinator(tx: ReplaceCoordinator, sig: AccountSignature)(using
      ownsTables: Tables[F, GroupsSchema],
      provider: TablesProvider[F, GroupsReducer.GroupsNeeds],
  ): StoreF[F][(tx.Result, List[tx.Event])] =
    for
      _ <- verifySignature(Signed(sig, tx))(using
        hashT = summon[Hash[ReplaceCoordinator]],
        recoverT = summon[Recover[ReplaceCoordinator]],
        provider = provider,
      )
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
        case Some(groupData) =>
          if groupData.nonce === tx.groupNonce then
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
            yield ((), List(GroupCoordinatorReplaced(tx.groupId, oldCoordinator, tx.newCoordinator)))
          else
            StateT.liftF[Eff[F], StoreState, (Unit, List[GroupCoordinatorReplaced])]:
              EitherT.leftT:
                TrieFailure(s"Nonce mismatch: expected ${groupData.nonce}, got ${tx.groupNonce}")
        case None =>
          StateT.liftF[Eff[F], StoreState, (Unit, List[GroupCoordinatorReplaced])]:
            EitherT.leftT:
              TrieFailure(s"Group ${tx.groupId.toUtf8.asString} not found")
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
