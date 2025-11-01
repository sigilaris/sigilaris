package org.sigilaris.core.application.support

import java.time.Instant

import cats.data.{EitherT, Kleisli}
import cats.instances.either.given
import munit.FunSuite
import scodec.bits.ByteVector

import _root_.org.sigilaris.core.application.accounts.domain.{Account, AccountsResult, KeyId20}
import _root_.org.sigilaris.core.application.accounts.module.AccountsBP
import _root_.org.sigilaris.core.application.accounts.transactions.{AccountCreated, CreateNamedAccount, TxEnvelope}
import _root_.org.sigilaris.core.application.domain.StoreState
import _root_.org.sigilaris.core.application.module.StateModule
import _root_.org.sigilaris.core.application.support.StateModuleExecutor
import _root_.org.sigilaris.core.failure.SigilarisFailure
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}

class SignedTxBuilderTest extends FunSuite:
  private type TestF[A] = Either[SigilarisFailure, A]

  given MerkleTrie.NodeStore[TestF] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[TestF, String](None)

  private val initialState: StoreState = StoreState.empty

  private def deriveKeyId(keyPair: KeyPair): KeyId20 =
    val hash = CryptoOps.keccak256(keyPair.publicKey.toBytes.toArray)
    KeyId20.unsafeApply(ByteVector.view(hash).takeRight(20))

  test("SignedTxBuilder.sign produces signed transaction and executes via StateModuleExecutor"):
    val accountsBP = AccountsBP[TestF]
    val module = StateModule.mount[("dsl", "accounts")](accountsBP)

    val keyPair = CryptoOps.generate()
    val keyId = deriveKeyId(keyPair)
    val account = Account.Named(Utf8("alice"))
    val envelope = TxEnvelope(
      networkId = BigNat.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = Some(Utf8("SignedTxBuilder smoke test")),
    )

    val tx = CreateNamedAccount(
      envelope = envelope,
      name = Utf8("alice"),
      initialKeyId = keyId,
      guardian = None,
    )

    val signedEither = SignedTxBuilder.sign(tx, account, keyPair)
    assert(signedEither.isRight, s"Expected signing success, got: $signedEither")

    val signedTx = signedEither.toOption.get
    val result = StateModuleExecutor
      .run(initialState, signedTx)(using module)
      .value
      .flatMap(identity)

    assert(result.isRight, s"Expected reducer success, got: $result")
    val (nextState, (accountResult, events)) = result.toOption.get
    assertEquals(accountResult.value, ())
    assertEquals(events.size, 1)
    assert(events.head.isInstanceOf[AccountCreated], "Expected AccountCreated event")
    assertEquals(nextState.accessLog.readCount, 1)
    assertEquals(nextState.accessLog.writeCount, 2)

  test("SignedTxBuilder.forAccount caches account and key pair"):
    val keyPair = CryptoOps.generate()
    val account = Account.Named(Utf8("bob"))
    val builder = SignedTxBuilder.forAccount(account, keyPair)

    val envelope = TxEnvelope(
      networkId = BigNat.unsafeFromLong(42),
      createdAt = Instant.now(),
      memo = None,
    )

    val keyId = deriveKeyId(keyPair)
    val tx = CreateNamedAccount(
      envelope = envelope,
      name = Utf8("bob"),
      initialKeyId = keyId,
      guardian = None,
    )

    val signed1 = builder.sign(tx)
    assert(signed1.isRight)

    val signed2 = builder.sign(tx).toOption.get

    assertEquals(signed1.toOption.get, signed2)
