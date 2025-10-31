package org.sigilaris.core
package application
package group

import java.time.Instant

import cats.Id
import cats.data.{EitherT, Kleisli}
import munit.FunSuite

import datatype.{BigNat, Utf8}
import merkle.{MerkleTrie, MerkleTrieNode}
import scodec.bits.ByteVector
import application.accounts.{Account, KeyId20, TxEnvelope}
import GroupsEvent.*
import GroupsResult.*

/** Tests for the Groups blueprint (Phase 6).
  *
  * Verifies:
  *   - Group creation and disbandment
  *   - Member management (add/remove)
  *   - Coordinator management (replace)
  *   - Nonce-based replay protection
  *   - Idempotent operations
  */
class GroupBlueprintTest extends FunSuite:
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  val initialState: StoreState = StoreState.empty

  /** Stub provider for GroupsNeeds.
    *
    * Since these tests only use Unnamed accounts, the signature verification
    * will never actually look up keys in the accounts tables. However, the
    * type signature requires the provider.
    *
    * We create an empty AccountsBP module and use it as the provider.
    */
  given stubGroupsNeedsProvider: TablesProvider[Id, GroupsReducer.GroupsNeeds] =
    import application.accounts.AccountsBP

    // Create AccountsBP and mount it to get tables
    val accountsBP = AccountsBP[Id]
    val accountsModule = StateModule.mount[("stub", "accounts")](accountsBP)

    // Create provider from the mounted module
    TablesProvider.fromModule(accountsModule)

  /** Helper keypairs for testing. */
  import crypto.{CryptoOps, KeyPair, Hash, Sign}
  import crypto.Sign.ops.*

  lazy val aliceKeyPair: KeyPair = CryptoOps.generate()
  lazy val bobKeyPair: KeyPair = CryptoOps.generate()
  lazy val charlieKeyPair: KeyPair = CryptoOps.generate()
  lazy val daveKeyPair: KeyPair = CryptoOps.generate()

  /** Helper to derive KeyId20 from a keypair. */
  def deriveKeyId(keyPair: KeyPair): KeyId20 =
    val pubKeyBytes = keyPair.publicKey.toBytes
    val keyHash = CryptoOps.keccak256(pubKeyBytes.toArray)
    KeyId20.unsafeApply(ByteVector.view(keyHash).takeRight(20))

  /** Helper to create a signed transaction. */
  def signTx[A <: Tx: Hash: Sign](tx: A, account: Account, keyPair: KeyPair): Signed[A] =
    val sig = keyPair.sign(tx).toOption.get
    Signed(AccountSignature(account, sig), tx)

  test("GroupsBP: create group"):
    // GroupBP now requires a provider for AccountsSchema dependencies
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("developers"))

    val envelope = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = Some(Utf8("test group creation")),
    )

    val tx = CreateGroup(
      envelope = envelope,
      groupId = groupId,
      name = Utf8("Developers Group"),
      coordinator = coordinator,
    )
    val signedTx = signTx(tx, coordinator, aliceKeyPair)

    val result = mounted.reducer.apply(signedTx).run(initialState).value
    assert(result.isRight, s"Expected successful group creation, got: $result")

    result match
      case Right((newState, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.groupId, groupId)
        assertEquals(event.coordinator, coordinator)
        assertEquals(event.name, Utf8("Developers Group"))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("GroupsBP: cannot create duplicate group"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("admins"))

    val envelope = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val tx = CreateGroup(
      envelope = envelope,
      groupId = groupId,
      name = Utf8("Admins Group"),
      coordinator = coordinator,
    )
    val signedTx = signTx(tx, coordinator, aliceKeyPair)

    // First creation should succeed
    val result1 = mounted.reducer.apply(signedTx).run(initialState).value
    assert(result1.isRight)

    val stateAfterFirst = result1.toOption.get._1

    // Second creation should fail
    val result2 = mounted.reducer.apply(signedTx).run(stateAfterFirst).value
    assert(result2.isLeft, "Expected error for duplicate group creation")

  test("GroupsBP: add accounts to group"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("team"))

    // Create group
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateGroup(
      envelope = envelope1,
      groupId = groupId,
      name = Utf8("Team Group"),
      coordinator = coordinator,
    )
    val signedCreateTx = signTx(createTx, coordinator, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Add members
    val bobKeyId = deriveKeyId(bobKeyPair)
    val charlieKeyId = deriveKeyId(charlieKeyPair)
    val bobAccount = Account.Unnamed(bobKeyId)
    val charlieAccount = Account.Unnamed(charlieKeyId)

    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addTx = AddAccounts(
      envelope = envelope2,
      groupId = groupId,
      accounts = Set(bobAccount, charlieAccount),
      groupNonce = BigNat.Zero,
    )
    val signedAddTx = signTx(addTx, coordinator, aliceKeyPair)

    val result2 = mounted.reducer.apply(signedAddTx).run(stateAfterCreate).value
    assert(result2.isRight, s"Expected successful member addition, got: $result2")

    result2 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.groupId, groupId)
        assertEquals(event.added.size, 2)
        assert(event.added.contains(bobAccount))
        assert(event.added.contains(charlieAccount))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("GroupsBP: add accounts is idempotent"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("idempotent-test"))

    // Create group
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateGroup(
      envelope = envelope1,
      groupId = groupId,
      name = Utf8("Idempotent Test Group"),
      coordinator = coordinator,
    )
    val signedCreateTx = signTx(createTx, coordinator, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Add Bob
    val bobKeyId = deriveKeyId(bobKeyPair)
    val bobAccount = Account.Unnamed(bobKeyId)

    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addTx1 = AddAccounts(
      envelope = envelope2,
      groupId = groupId,
      accounts = Set(bobAccount),
      groupNonce = BigNat.Zero,
    )
    val signedAddTx1 = signTx(addTx1, coordinator, aliceKeyPair)

    val result2 = mounted.reducer.apply(signedAddTx1).run(stateAfterCreate).value
    assert(result2.isRight)
    val stateAfterAdd1 = result2.toOption.get._1

    // Add Bob again (idempotent - should succeed but report 0 added)
    val envelope3 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addTx2 = AddAccounts(
      envelope = envelope3,
      groupId = groupId,
      accounts = Set(bobAccount),
      groupNonce = BigNat.unsafeFromBigInt(1),
    )
    val signedAddTx2 = signTx(addTx2, coordinator, aliceKeyPair)

    val result3 = mounted.reducer.apply(signedAddTx2).run(stateAfterAdd1).value
    assert(result3.isRight)

    result3 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.added.size, 0, "Bob was already a member, should report 0 added")
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("GroupsBP: remove accounts from group"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("removal-test"))

    // Create group
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateGroup(
      envelope = envelope1,
      groupId = groupId,
      name = Utf8("Removal Test Group"),
      coordinator = coordinator,
    )
    val signedCreateTx = signTx(createTx, coordinator, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Add members
    val bobKeyId = deriveKeyId(bobKeyPair)
    val charlieKeyId = deriveKeyId(charlieKeyPair)
    val bobAccount = Account.Unnamed(bobKeyId)
    val charlieAccount = Account.Unnamed(charlieKeyId)

    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addTx = AddAccounts(
      envelope = envelope2,
      groupId = groupId,
      accounts = Set(bobAccount, charlieAccount),
      groupNonce = BigNat.Zero,
    )
    val signedAddTx = signTx(addTx, coordinator, aliceKeyPair)

    val result2 = mounted.reducer.apply(signedAddTx).run(stateAfterCreate).value
    assert(result2.isRight)
    val stateAfterAdd = result2.toOption.get._1

    // Remove Bob
    val envelope3 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val removeTx = RemoveAccounts(
      envelope = envelope3,
      groupId = groupId,
      accounts = Set(bobAccount),
      groupNonce = BigNat.unsafeFromBigInt(1),
    )
    val signedRemoveTx = signTx(removeTx, coordinator, aliceKeyPair)

    val result3 = mounted.reducer.apply(signedRemoveTx).run(stateAfterAdd).value
    assert(result3.isRight)

    result3 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.groupId, groupId)
        assertEquals(event.removed.size, 1)
        assert(event.removed.contains(bobAccount))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("GroupsBP: replace coordinator"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("coordinator-test"))

    // Create group
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateGroup(
      envelope = envelope1,
      groupId = groupId,
      name = Utf8("Coordinator Test Group"),
      coordinator = coordinator,
    )
    val signedCreateTx = signTx(createTx, coordinator, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Replace coordinator with Bob
    val bobKeyId = deriveKeyId(bobKeyPair)
    val newCoordinator = Account.Unnamed(bobKeyId)

    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val replaceTx = ReplaceCoordinator(
      envelope = envelope2,
      groupId = groupId,
      newCoordinator = newCoordinator,
      groupNonce = BigNat.Zero,
    )
    val signedReplaceTx = signTx(replaceTx, coordinator, aliceKeyPair)

    val result2 = mounted.reducer.apply(signedReplaceTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.groupId, groupId)
        assertEquals(event.oldCoordinator, coordinator)
        assertEquals(event.newCoordinator, newCoordinator)
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("GroupsBP: disband group"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("disband-test"))

    // Create group
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateGroup(
      envelope = envelope1,
      groupId = groupId,
      name = Utf8("Disband Test Group"),
      coordinator = coordinator,
    )
    val signedCreateTx = signTx(createTx, coordinator, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Disband group
    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val disbandTx = DisbandGroup(
      envelope = envelope2,
      groupId = groupId,
      groupNonce = BigNat.Zero,
    )
    val signedDisbandTx = signTx(disbandTx, coordinator, aliceKeyPair)

    val result2 = mounted.reducer.apply(signedDisbandTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.groupId, groupId)
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("GroupsBP: nonce mismatch prevents replay attack"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("nonce-test"))

    // Create group
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateGroup(
      envelope = envelope1,
      groupId = groupId,
      name = Utf8("Nonce Test Group"),
      coordinator = coordinator,
    )
    val signedCreateTx = signTx(createTx, coordinator, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Try to add accounts with wrong nonce
    val bobKeyId = deriveKeyId(bobKeyPair)
    val bobAccount = Account.Unnamed(bobKeyId)

    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addTx = AddAccounts(
      envelope = envelope2,
      groupId = groupId,
      accounts = Set(bobAccount),
      groupNonce = BigNat.unsafeFromBigInt(999), // Wrong nonce!
    )
    val signedAddTx = signTx(addTx, coordinator, aliceKeyPair)

    val result2 = mounted.reducer.apply(signedAddTx).run(stateAfterCreate).value
    assert(result2.isLeft, "Expected nonce mismatch error")

  test("GroupsBP: unauthorized coordinator cannot manage group"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val groupId = GroupId(Utf8("auth-test"))

    // Create group with Alice as coordinator
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateGroup(
      envelope = envelope1,
      groupId = groupId,
      name = Utf8("Auth Test Group"),
      coordinator = coordinator,
    )
    val signedCreateTx = signTx(createTx, coordinator, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Try to add accounts with Bob's signature (not the coordinator)
    val bobKeyId = deriveKeyId(bobKeyPair)
    val bobAccount = Account.Unnamed(bobKeyId)
    val charlieKeyId = deriveKeyId(charlieKeyPair)
    val charlieAccount = Account.Unnamed(charlieKeyId)

    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addTx = AddAccounts(
      envelope = envelope2,
      groupId = groupId,
      accounts = Set(charlieAccount),
      groupNonce = BigNat.Zero,
    )
    val signedAddTx = signTx(addTx, bobAccount, bobKeyPair) // Bob trying to manage Alice's group

    val result2 = mounted.reducer.apply(signedAddTx).run(stateAfterCreate).value
    assert(result2.isLeft, "Expected authorization failure")

  test("GroupsBP: cannot disband group with members"):
    val bp = GroupsBP[Id](stubGroupsNeedsProvider)
    val mounted = StateModule.mount[("app", "groups")](bp)

    val aliceKeyId = deriveKeyId(aliceKeyPair)
    val bobKeyId = deriveKeyId(bobKeyPair)
    val coordinator = Account.Unnamed(aliceKeyId)
    val member = Account.Unnamed(bobKeyId)
    val groupId = GroupId(Utf8("non-empty-group"))

    // Create group
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateGroup(
      envelope = envelope1,
      groupId = groupId,
      name = Utf8("Non-Empty Group"),
      coordinator = coordinator,
    )
    val signedCreateTx = signTx(createTx, coordinator, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Add a member
    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addTx = AddAccounts(
      envelope = envelope2,
      groupId = groupId,
      accounts = Set(member),
      groupNonce = BigNat.Zero,
    )
    val signedAddTx = signTx(addTx, coordinator, aliceKeyPair)

    val result2 = mounted.reducer.apply(signedAddTx).run(stateAfterCreate).value
    assert(result2.isRight)
    val stateAfterAdd = result2.toOption.get._1

    // Try to disband group with member - should fail
    val envelope3 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val disbandTx = DisbandGroup(
      envelope = envelope3,
      groupId = groupId,
      groupNonce = BigNat.unsafeFromBigInt(1), // nonce after add
    )
    val signedDisbandTx = signTx(disbandTx, coordinator, aliceKeyPair)

    val result3 = mounted.reducer.apply(signedDisbandTx).run(stateAfterAdd).value
    assert(result3.isLeft, "Expected failure when disbanding group with members")

    result3 match
      case Left(err) =>
        assert(err.msg.contains("Cannot disband group"), s"Expected 'Cannot disband group' error, got: ${err.msg}")
        assert(err.msg.contains("Remove all members first"), s"Expected 'Remove all members first' message, got: ${err.msg}")
      case Right(_) =>
        fail("Expected error when trying to disband group with members")

    // Now remove the member and try again - should succeed
    val envelope4 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val removeTx = RemoveAccounts(
      envelope = envelope4,
      groupId = groupId,
      accounts = Set(member),
      groupNonce = BigNat.unsafeFromBigInt(1),
    )
    val signedRemoveTx = signTx(removeTx, coordinator, aliceKeyPair)

    val result4 = mounted.reducer.apply(signedRemoveTx).run(stateAfterAdd).value
    assert(result4.isRight)
    val stateAfterRemove = result4.toOption.get._1

    // Now disband should succeed
    val envelope5 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val disbandTx2 = DisbandGroup(
      envelope = envelope5,
      groupId = groupId,
      groupNonce = BigNat.unsafeFromBigInt(2), // nonce after remove
    )
    val signedDisbandTx2 = signTx(disbandTx2, coordinator, aliceKeyPair)

    val result5 = mounted.reducer.apply(signedDisbandTx2).run(stateAfterRemove).value
    assert(result5.isRight, "Should succeed when disbanding empty group")

    result5 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        assert(events.head.value.isInstanceOf[GroupDisbanded])
      case Left(err) =>
        fail(s"Unexpected error: $err")
