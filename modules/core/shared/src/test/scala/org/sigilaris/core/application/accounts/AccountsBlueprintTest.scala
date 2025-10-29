package org.sigilaris.core
package application
package accounts

import java.time.Instant

import cats.Id
import cats.data.{EitherT, Kleisli}
import munit.FunSuite

import datatype.{BigNat, Utf8}
import merkle.{MerkleTrie, MerkleTrieNode, MerkleTrieState}
import scodec.bits.ByteVector

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

  val initialState: MerkleTrieState = MerkleTrieState.empty

  test("AccountsBP: create named account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    val keyId = KeyId20.unsafeApply(ByteVector.fill(20)(0x01))
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

    val result = mounted.reducer.apply(tx).run(initialState).value
    assert(result.isRight, s"Expected successful account creation, got: $result")

    result match
      case Right((newState, ((), events))) =>
        assertEquals(events.size, 1)
        assert(events.head.isInstanceOf[AccountCreated])
        val event = events.head.asInstanceOf[AccountCreated]
        assertEquals(event.name, Utf8("alice"))
        assertEquals(event.guardian, None)
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: create account with guardian"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    val keyId = KeyId20.unsafeApply(ByteVector.fill(20)(0x02))
    val guardianKeyId = KeyId20.unsafeApply(ByteVector.fill(20)(0xff))
    val guardian = Account.Unnamed(guardianKeyId)

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

    val result = mounted.reducer.apply(tx).run(initialState).value
    assert(result.isRight)

    result match
      case Right((_, ((), events))) =>
        assertEquals(events.size, 1)
        val event = events.head.asInstanceOf[AccountCreated]
        assertEquals(event.name, Utf8("bob"))
        assertEquals(event.guardian, Some(guardian))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: cannot create duplicate account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    val keyId = KeyId20.unsafeApply(ByteVector.fill(20)(0x03))
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

    // First creation should succeed
    val result1 = mounted.reducer.apply(tx).run(initialState).value
    assert(result1.isRight)

    val stateAfterFirst = result1.toOption.get._1

    // Second creation should fail
    val result2 = mounted.reducer.apply(tx).run(stateAfterFirst).value
    assert(result2.isLeft, "Expected error for duplicate account creation")

  test("AccountsBP: update account guardian"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    val keyId = KeyId20.unsafeApply(ByteVector.fill(20)(0x04))
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

    val result1 = mounted.reducer.apply(createTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Update guardian
    val newGuardianKeyId = KeyId20.unsafeApply(ByteVector.fill(20)(0xaa))
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

    val result2 = mounted.reducer.apply(updateTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, ((), events))) =>
        assertEquals(events.size, 1)
        val event = events.head.asInstanceOf[AccountUpdated]
        assertEquals(event.name, Utf8("dave"))
        assertEquals(event.newGuardian, Some(newGuardian))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: nonce mismatch fails"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    val keyId = KeyId20.unsafeApply(ByteVector.fill(20)(0x05))
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

    val result1 = mounted.reducer.apply(createTx).run(initialState).value
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

    val result2 = mounted.reducer.apply(updateTx).run(stateAfterCreate).value
    assert(result2.isLeft, "Expected error for nonce mismatch")

  test("AccountsBP: add keys to account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    val keyId1 = KeyId20.unsafeApply(ByteVector.fill(20)(0x06))
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

    val result1 = mounted.reducer.apply(createTx).run(initialState).value
    assert(result1.isRight)
    val stateAfterCreate = result1.toOption.get._1

    // Add additional keys
    val keyId2 = KeyId20.unsafeApply(ByteVector.fill(20)(0x07))
    val keyId3 = KeyId20.unsafeApply(ByteVector.fill(20)(0x08))

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

    val result2 = mounted.reducer.apply(addKeysTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, ((), events))) =>
        assertEquals(events.size, 1)
        val event = events.head.asInstanceOf[KeysAdded]
        assertEquals(event.name, Utf8("frank"))
        assertEquals(event.keyIds, Set(keyId2, keyId3))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: remove keys from account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    val keyId1 = KeyId20.unsafeApply(ByteVector.fill(20)(0x09))
    val keyId2 = KeyId20.unsafeApply(ByteVector.fill(20)(0x0a))

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

    val result1 = mounted.reducer.apply(createTx).run(initialState).value
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

    val result2 = mounted.reducer.apply(addKeysTx).run(stateAfterCreate).value
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

    val result3 = mounted.reducer.apply(removeKeysTx).run(stateAfterAdd).value
    assert(result3.isRight)

    result3 match
      case Right((_, ((), events))) =>
        assertEquals(events.size, 1)
        val event = events.head.asInstanceOf[KeysRemoved]
        assertEquals(event.name, Utf8("grace"))
        assertEquals(event.keyIds, Set(keyId1))
      case Left(err) =>
        fail(s"Unexpected error: $err")

  test("AccountsBP: remove account"):
    val bp = AccountsBP[Id]
    val mounted = StateModule.mount[("app", "accounts")](bp)

    val keyId = KeyId20.unsafeApply(ByteVector.fill(20)(0x0b))
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

    val result1 = mounted.reducer.apply(createTx).run(initialState).value
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

    val result2 = mounted.reducer.apply(removeTx).run(stateAfterCreate).value
    assert(result2.isRight)

    result2 match
      case Right((_, ((), events))) =>
        assertEquals(events.size, 1)
        val event = events.head.asInstanceOf[AccountRemoved]
        assertEquals(event.name, Utf8("heidi"))
      case Left(err) =>
        fail(s"Unexpected error: $err")
