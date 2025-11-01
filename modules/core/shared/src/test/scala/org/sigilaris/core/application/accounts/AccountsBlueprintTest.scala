package org.sigilaris.core.application.accounts

import java.time.Instant

import cats.Id
import cats.data.{EitherT, Kleisli}
import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.application.accounts.domain.{Account, KeyId20}
import org.sigilaris.core.application.accounts.domain.AccountsEvent.*
import org.sigilaris.core.application.accounts.domain.AccountsResult.*
import org.sigilaris.core.application.accounts.module.AccountsBP
import org.sigilaris.core.application.accounts.transactions.{
  AddKeyIds,
  CreateNamedAccount,
  RemoveAccount,
  RemoveKeyIds,
  TxEnvelope,
  UpdateAccount,
}
import org.sigilaris.core.application.domain.StoreState
import org.sigilaris.core.application.module.StateModule
import org.sigilaris.core.application.transactions.{AccountSignature, Signed, Tx}
import org.sigilaris.core.crypto.{CryptoOps, Hash, KeyPair, Sign}
import org.sigilaris.core.crypto.Sign.ops.*
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}

/** Tests for the Accounts blueprint (Phase 6).
  *
  * Verifies:
  *   - Account creation, update, and removal
  *   - Key management (add/remove)
  *   - Nonce-based replay protection
  *   - Guardian management
  */
class AccountsBlueprintTest extends FunSuite:
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  val initialState: StoreState = StoreState.empty

  /** Helper keypairs for testing - these correspond to the test accounts.
    * In production, keys would be securely managed.
    */

  lazy val aliceKeyPair: KeyPair = CryptoOps.generate()
  lazy val bobKeyPair: KeyPair = CryptoOps.generate()
  lazy val charlieKeyPair: KeyPair = CryptoOps.generate()
  lazy val daveKeyPair: KeyPair = CryptoOps.generate()
  lazy val eveKeyPair: KeyPair = CryptoOps.generate()
  lazy val frankKeyPair: KeyPair = CryptoOps.generate()
  lazy val graceKeyPair: KeyPair = CryptoOps.generate()
  lazy val heidiKeyPair: KeyPair = CryptoOps.generate()

  /** Helper to create a signed transaction with real cryptographic signature.
    *
    * @param tx the transaction to sign
    * @param account the account signing the transaction
    * @param keyPair the keypair to sign with
    */
  def signTx[A <: Tx: Hash: Sign](tx: A, account: Account, keyPair: KeyPair): Signed[A] =
    val sig = keyPair.sign(tx).toOption.get
    Signed(AccountSignature(account, sig), tx)

  test("AccountsBP: create named account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Derive KeyId20 from Alice's public key
    val alicePubKeyBytes = aliceKeyPair.publicKey.toBytes
    val aliceKeyHash = CryptoOps.keccak256(alicePubKeyBytes.toArray)
    val keyId = KeyId20.unsafeApply(ByteVector.view(aliceKeyHash).takeRight(20))

    val account = Account.Named(Utf8("alice"))
    val envelope = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = Some(Utf8("test account creation")),
    )

    val tx = CreateNamedAccount(
      envelope = envelope,
      name = Utf8("alice"),
      initialKeyId = keyId,
      guardian = None,
    )
    val signedTx = signTx(tx, account, aliceKeyPair)

    val result = mounted.reducer.apply(signedTx).run(initialState).value
    assert(result.isRight, s"Expected successful account creation, got: $result")

    result match
      case Right((newState, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.name, Utf8("alice"))
        assertEquals(event.guardian, None)
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: create account with guardian"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Derive KeyId20 from Bob's public key
    val bobPubKeyBytes = bobKeyPair.publicKey.toBytes
    val bobKeyHash = CryptoOps.keccak256(bobPubKeyBytes.toArray)
    val keyId = KeyId20.unsafeApply(ByteVector.view(bobKeyHash).takeRight(20))

    // Derive guardian KeyId20 from Charlie's public key
    val charliePubKeyBytes = charlieKeyPair.publicKey.toBytes
    val charlieKeyHash = CryptoOps.keccak256(charliePubKeyBytes.toArray)
    val guardianKeyId = KeyId20.unsafeApply(ByteVector.view(charlieKeyHash).takeRight(20))
    val guardian = Account.Unnamed(guardianKeyId)
    val account = Account.Named(Utf8("bob"))

    val envelope = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val tx = CreateNamedAccount(
      envelope = envelope,
      name = Utf8("bob"),
      initialKeyId = keyId,
      guardian = Some(guardian),
    )
    val signedTx = signTx(tx, account, bobKeyPair)

    val result = mounted.reducer.apply(signedTx).run(initialState).value
    assert(result.isRight)

    result match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.name, Utf8("bob"))
        assertEquals(event.guardian, Some(guardian))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: cannot create duplicate account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Derive KeyId20 from Charlie's public key
    val charliePubKeyBytes = charlieKeyPair.publicKey.toBytes
    val charlieKeyHash = CryptoOps.keccak256(charliePubKeyBytes.toArray)
    val keyId = KeyId20.unsafeApply(ByteVector.view(charlieKeyHash).takeRight(20))

    val account = Account.Named(Utf8("charlie"))
    val envelope = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val tx = CreateNamedAccount(
      envelope = envelope,
      name = Utf8("charlie"),
      initialKeyId = keyId,
      guardian = None,
    )
    val signedTx = signTx(tx, account, charlieKeyPair)

    // First creation should succeed
    val result1 = mounted.reducer.apply(signedTx).run(initialState).value
    assert(result1.isRight)

    val stateAfterFirst = result1.toOption.get._1

    // Second creation should fail
    val result2 = mounted.reducer.apply(signedTx).run(stateAfterFirst).value
    assert(result2.isLeft, "Expected error for duplicate account creation")

  test("AccountsBP: update account guardian"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Derive KeyId20 from Dave's public key
    val davePubKeyBytes = daveKeyPair.publicKey.toBytes
    val daveKeyHash = CryptoOps.keccak256(davePubKeyBytes.toArray)
    val keyId = KeyId20.unsafeApply(ByteVector.view(daveKeyHash).takeRight(20))

    val account = Account.Named(Utf8("dave"))
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateNamedAccount(
      envelope = envelope1,
      name = Utf8("dave"),
      initialKeyId = keyId,
      guardian = None,
    )
    val signedCreateTx = signTx(createTx, account, daveKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Update guardian - derive KeyId20 from Eve's public key
    val evePubKeyBytes = eveKeyPair.publicKey.toBytes
    val eveKeyHash = CryptoOps.keccak256(evePubKeyBytes.toArray)
    val newGuardianKeyId = KeyId20.unsafeApply(ByteVector.view(eveKeyHash).takeRight(20))
    val newGuardian = Account.Unnamed(newGuardianKeyId)

    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val updateTx = UpdateAccount(
      envelope = envelope2,
      name = Utf8("dave"),
      nonce = BigNat.Zero, // Initial nonce is 0
      newGuardian = Some(newGuardian),
    )
    val signedUpdateTx = signTx(updateTx, account, daveKeyPair)

    val result2 = mounted.reducer.apply(signedUpdateTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.name, Utf8("dave"))
        assertEquals(event.newGuardian, Some(newGuardian))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: nonce mismatch fails"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Derive KeyId20 from Eve's public key
    val evePubKeyBytes = eveKeyPair.publicKey.toBytes
    val eveKeyHash = CryptoOps.keccak256(evePubKeyBytes.toArray)
    val keyId = KeyId20.unsafeApply(ByteVector.view(eveKeyHash).takeRight(20))

    val account = Account.Named(Utf8("eve"))
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateNamedAccount(
      envelope = envelope1,
      name = Utf8("eve"),
      initialKeyId = keyId,
      guardian = None,
    )
    val signedCreateTx = signTx(createTx, account, eveKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Try to update with wrong nonce
    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val updateTx = UpdateAccount(
      envelope = envelope2,
      name = Utf8("eve"),
      nonce = BigNat.unsafeFromLong(999), // Wrong nonce
      newGuardian = None,
    )
    val signedUpdateTx = signTx(updateTx, account, eveKeyPair)

    val result2 = mounted.reducer.apply(signedUpdateTx).run(stateAfterCreate).value
    assert(result2.isLeft, "Expected error for nonce mismatch")

  test("AccountsBP: add keys to account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Derive KeyId20 from Frank's public key
    val frankPubKeyBytes = frankKeyPair.publicKey.toBytes
    val frankKeyHash = CryptoOps.keccak256(frankPubKeyBytes.toArray)
    val keyId1 = KeyId20.unsafeApply(ByteVector.view(frankKeyHash).takeRight(20))

    val account = Account.Named(Utf8("frank"))
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateNamedAccount(
      envelope = envelope1,
      name = Utf8("frank"),
      initialKeyId = keyId1,
      guardian = None,
    )
    val signedCreateTx = signTx(createTx, account, frankKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Add additional keys - derive from Grace and Heidi's public keys
    val gracePubKeyBytes = graceKeyPair.publicKey.toBytes
    val graceKeyHash = CryptoOps.keccak256(gracePubKeyBytes.toArray)
    val keyId2 = KeyId20.unsafeApply(ByteVector.view(graceKeyHash).takeRight(20))

    val heidiPubKeyBytes = heidiKeyPair.publicKey.toBytes
    val heidiKeyHash = CryptoOps.keccak256(heidiPubKeyBytes.toArray)
    val keyId3 = KeyId20.unsafeApply(ByteVector.view(heidiKeyHash).takeRight(20))

    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addKeysTx = AddKeyIds(
      envelope = envelope2,
      name = Utf8("frank"),
      nonce = BigNat.Zero,
      keyIds = Map(
        keyId2 -> Utf8("laptop key"),
        keyId3 -> Utf8("mobile key"),
      ),
      expiresAt = None,
    )
    val signedAddKeysTx = signTx(addKeysTx, account, frankKeyPair)

    val result2 = mounted.reducer.apply(signedAddKeysTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.name, Utf8("frank"))
        assertEquals(event.keyIds, Set(keyId2, keyId3))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: remove keys from account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Derive KeyId20 from Grace's public key
    val gracePubKeyBytes = graceKeyPair.publicKey.toBytes
    val graceKeyHash = CryptoOps.keccak256(gracePubKeyBytes.toArray)
    val keyId1 = KeyId20.unsafeApply(ByteVector.view(graceKeyHash).takeRight(20))

    // Derive second KeyId20 from Bob's public key (reusing for second key)
    val bobPubKeyBytes = bobKeyPair.publicKey.toBytes
    val bobKeyHash = CryptoOps.keccak256(bobPubKeyBytes.toArray)
    val keyId2 = KeyId20.unsafeApply(ByteVector.view(bobKeyHash).takeRight(20))

    val account = Account.Named(Utf8("grace"))

    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateNamedAccount(
      envelope = envelope1,
      name = Utf8("grace"),
      initialKeyId = keyId1,
      guardian = None,
    )
    val signedCreateTx = signTx(createTx, account, graceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Add a second key
    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val addKeysTx = AddKeyIds(
      envelope = envelope2,
      name = Utf8("grace"),
      nonce = BigNat.Zero,
      keyIds = Map(keyId2 -> Utf8("second key")),
      expiresAt = None,
    )
    val signedAddKeysTx = signTx(addKeysTx, account, graceKeyPair)

    val result2 = mounted.reducer.apply(signedAddKeysTx).run(stateAfterCreate).value
    assert(result2.isRight)
    val stateAfterAdd = result2.toOption.get._1

    // Remove the first key
    val envelope3 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val removeKeysTx = RemoveKeyIds(
      envelope = envelope3,
      name = Utf8("grace"),
      nonce = BigNat.unsafeFromLong(1), // Nonce incremented after AddKeyIds
      keyIds = Set(keyId1),
    )
    val signedRemoveKeysTx = signTx(removeKeysTx, account, graceKeyPair)

    val result3 = mounted.reducer.apply(signedRemoveKeysTx).run(stateAfterAdd).value
    assert(result3.isRight)

    result3 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.name, Utf8("grace"))
        assertEquals(event.keyIds, Set(keyId1))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: remove account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Derive KeyId20 from Heidi's public key
    val heidiPubKeyBytes = heidiKeyPair.publicKey.toBytes
    val heidiKeyHash = CryptoOps.keccak256(heidiPubKeyBytes.toArray)
    val keyId = KeyId20.unsafeApply(ByteVector.view(heidiKeyHash).takeRight(20))

    val account = Account.Named(Utf8("heidi"))
    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createTx = CreateNamedAccount(
      envelope = envelope1,
      name = Utf8("heidi"),
      initialKeyId = keyId,
      guardian = None,
    )
    val signedCreateTx = signTx(createTx, account, heidiKeyPair)

    val result1 = mounted.reducer.apply(signedCreateTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Remove account
    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val removeTx = RemoveAccount(
      envelope = envelope2,
      name = Utf8("heidi"),
      nonce = BigNat.Zero,
    )
    val signedRemoveTx = signTx(removeTx, account, heidiKeyPair)

    val result2 = mounted.reducer.apply(signedRemoveTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.name, Utf8("heidi"))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: unauthorized update should fail"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Create Bob's account first
    val bobPubKeyBytes = bobKeyPair.publicKey.toBytes
    val bobKeyHash = CryptoOps.keccak256(bobPubKeyBytes.toArray)
    val bobKeyId = KeyId20.unsafeApply(ByteVector.view(bobKeyHash).takeRight(20))
    val bobAccount = Account.Named(Utf8("bob"))

    val envelope0 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createBobTx = CreateNamedAccount(
      envelope = envelope0,
      name = Utf8("bob"),
      initialKeyId = bobKeyId,
      guardian = None,
    )
    val signedCreateBobTx = signTx(createBobTx, bobAccount, bobKeyPair)

    val resultBob = mounted.reducer.apply(signedCreateBobTx).run(initialState).value
    assert(resultBob.isRight)
    val stateAfterBob = resultBob.toOption.get._1

    // Create Alice's account
    val alicePubKeyBytes = aliceKeyPair.publicKey.toBytes
    val aliceKeyHash = CryptoOps.keccak256(alicePubKeyBytes.toArray)
    val aliceKeyId = KeyId20.unsafeApply(ByteVector.view(aliceKeyHash).takeRight(20))
    val aliceAccount = Account.Named(Utf8("alice"))

    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val createAliceTx = CreateNamedAccount(
      envelope = envelope1,
      name = Utf8("alice"),
      initialKeyId = aliceKeyId,
      guardian = None,
    )
    val signedCreateAliceTx = signTx(createAliceTx, aliceAccount, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateAliceTx).run(stateAfterBob).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Bob tries to update Alice's account - this should FAIL
    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    // Bob creates a malicious transaction to update Alice's account
    val maliciousUpdateTx = UpdateAccount(
      envelope = envelope2,
      name = Utf8("alice"), // Targeting Alice's account
      nonce = BigNat.Zero,
      newGuardian = Some(Account.Unnamed(bobKeyId)), // Bob trying to make himself guardian
    )

    // Bob signs with his own key, claiming to be Bob (not Alice)
    val maliciousSignedTx = signTx(maliciousUpdateTx, bobAccount, bobKeyPair)

    val result2 = mounted.reducer.apply(maliciousSignedTx).run(stateAfterCreate).value

    // This should fail with an authorization error
    result2 match
      case Left(err) =>
        assert(err.msg.contains("Unauthorized"))
      case Right(_) =>
        fail("Expected authorization failure, but transaction succeeded!")

  test("AccountsBP: guardian can update account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    // Create Alice's account with Bob as guardian
    val alicePubKeyBytes = aliceKeyPair.publicKey.toBytes
    val aliceKeyHash = CryptoOps.keccak256(alicePubKeyBytes.toArray)
    val aliceKeyId = KeyId20.unsafeApply(ByteVector.view(aliceKeyHash).takeRight(20))
    val aliceAccount = Account.Named(Utf8("alice"))

    val bobPubKeyBytes = bobKeyPair.publicKey.toBytes
    val bobKeyHash = CryptoOps.keccak256(bobPubKeyBytes.toArray)
    val bobKeyId = KeyId20.unsafeApply(ByteVector.view(bobKeyHash).takeRight(20))
    val bobAccount = Account.Named(Utf8("bob"))

    val envelope1 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    // First create Bob's account so he can be a guardian
    val createBobTx = CreateNamedAccount(
      envelope = envelope1,
      name = Utf8("bob"),
      initialKeyId = bobKeyId,
      guardian = None,
    )
    val signedCreateBobTx = signTx(createBobTx, bobAccount, bobKeyPair)

    val resultBob = mounted.reducer.apply(signedCreateBobTx).run(initialState).value
    assert(resultBob.isRight)
    val stateAfterBob = resultBob.toOption.get._1

    // Now create Alice's account with Bob as guardian
    val createAliceTx = CreateNamedAccount(
      envelope = envelope1,
      name = Utf8("alice"),
      initialKeyId = aliceKeyId,
      guardian = Some(bobAccount),
    )
    val signedCreateAliceTx = signTx(createAliceTx, aliceAccount, aliceKeyPair)

    val result1 = mounted.reducer.apply(signedCreateAliceTx).run(stateAfterBob).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Bob (as guardian) updates Alice's account - this should SUCCEED
    val envelope2 = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )

    val updateTx = UpdateAccount(
      envelope = envelope2,
      name = Utf8("alice"),
      nonce = BigNat.Zero,
      newGuardian = None, // Remove guardian
    )

    // Bob signs as guardian
    val signedUpdateTx = signTx(updateTx, bobAccount, bobKeyPair)

    val result2 = mounted.reducer.apply(signedUpdateTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, (res, events))) =>
        assertEquals(res.value, ())
        assertEquals(events.size, 1)
        val event = events.head.value
        assertEquals(event.name, Utf8("alice"))
        assertEquals(event.newGuardian, None)
      case Left(err) =>
        fail(s"Guardian should be able to update account, but got error: $err")
