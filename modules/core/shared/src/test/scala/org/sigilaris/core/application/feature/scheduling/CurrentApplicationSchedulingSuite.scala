package org.sigilaris.core.application.feature.scheduling

import java.time.Instant
import scala.annotation.targetName

import cats.data.{EitherT, Kleisli}
import cats.instances.either.given
import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.application.execution.StateModuleExecutor
import org.sigilaris.core.application.feature.accounts.domain.{Account, KeyId20}
import org.sigilaris.core.application.feature.accounts.module.AccountsBP
import org.sigilaris.core.application.feature.accounts.transactions.{AddKeyIds, CreateNamedAccount, RemoveAccount, RemoveKeyIds, UpdateAccount}
import org.sigilaris.core.application.feature.group.domain.GroupId
import org.sigilaris.core.application.feature.group.module.GroupsBP
import org.sigilaris.core.application.feature.group.transactions.{AddAccounts, CreateGroup, DisbandGroup, RemoveAccounts, ReplaceCoordinator}
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.module.runtime.StateModule
import org.sigilaris.core.application.scheduling.{BatchPlan, BatchPlanner, ConflictFootprintConformance, SchedulingClassification}
import org.sigilaris.core.application.state.StoreState
import org.sigilaris.core.application.transactions.{AccountSignature, NetworkId, Signed, Tx, TxEnvelope}
import org.sigilaris.core.crypto.{CryptoOps, Hash, KeyPair, Sign}
import org.sigilaris.core.crypto.Signature
import org.sigilaris.core.crypto.Sign.ops.*
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}
import org.sigilaris.core.failure.SigilarisFailure
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}

final class CurrentApplicationSchedulingSuite extends FunSuite:
  private type TestF[A] = Either[SigilarisFailure, A]

  given MerkleTrie.NodeStore[TestF] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[TestF, String](None)

  private val accountsModule = StateModule.mount[("app", "accounts")](AccountsBP[TestF])
  private val groupsModule =
    StateModule.mount[("app", "groups")](GroupsBP[TestF](TablesProvider.fromModule(accountsModule)))

  private val networkId = NetworkId.unsafeFromLong(1)
  private val now       = Instant.parse("2026-04-04T00:00:00Z")

  private lazy val aliceKeyPair = CryptoOps.generate()
  private lazy val bobKeyPair   = CryptoOps.generate()
  private lazy val carolKeyPair = CryptoOps.generate()

  private val alice = Account.Named(Utf8("alice"))
  private val bob   = Account.Named(Utf8("bob"))
  private val carol = Account.Named(Utf8("carol"))

  private def dummySignature: Signature =
    Signature(
      v = 27,
      r = UInt256.unsafeFromBytesBE(ByteVector.fill(32)(0x00)),
      s = UInt256.unsafeFromBytesBE(ByteVector.fill(32)(0x00)),
    )

  private def signDummy[A <: Tx](
      tx: A,
  ): Signed[A] =
    Signed(AccountSignature(alice, dummySignature), tx)

  private def deriveKeyId(
      keyPair: KeyPair,
  ): KeyId20 =
    val hash = CryptoOps.keccak256(keyPair.publicKey.toBytes.toArray)
    KeyId20.unsafeApply(ByteVector.view(hash).takeRight(20))

  private def signTx[A <: Tx: Hash: Sign](
      tx: A,
      account: Account,
      keyPair: KeyPair,
  ): Signed[A] =
    Signed(
      AccountSignature(account, keyPair.sign(tx).toOption.get),
      tx,
    )

  private def createAccountTx(
      name: String,
      keyPair: KeyPair,
  ): Signed[CreateNamedAccount] =
    signTx(
      CreateNamedAccount(
        envelope = TxEnvelope(networkId, now, None),
        name = Utf8(name),
        initialKeyId = deriveKeyId(keyPair),
        guardian = None,
      ),
      account = Account.Named(Utf8(name)),
      keyPair = keyPair,
    )

  @targetName("executeCreateNamedAccount")
  private def executeAccount(
      initial: StoreState,
      signedTx: Signed[CreateNamedAccount],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, accountsModule).value.flatMap(identity).toOption.get

  @targetName("executeUpdateAccount")
  private def executeAccount(
      initial: StoreState,
      signedTx: Signed[UpdateAccount],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, accountsModule).value.flatMap(identity).toOption.get

  @targetName("executeAddKeyIds")
  private def executeAccount(
      initial: StoreState,
      signedTx: Signed[AddKeyIds],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, accountsModule).value.flatMap(identity).toOption.get

  @targetName("executeRemoveKeyIds")
  private def executeAccount(
      initial: StoreState,
      signedTx: Signed[RemoveKeyIds],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, accountsModule).value.flatMap(identity).toOption.get

  @targetName("executeRemoveAccount")
  private def executeAccount(
      initial: StoreState,
      signedTx: Signed[RemoveAccount],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, accountsModule).value.flatMap(identity).toOption.get

  @targetName("executeCreateGroup")
  private def executeGroup(
      initial: StoreState,
      signedTx: Signed[CreateGroup],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, groupsModule).value.flatMap(identity).toOption.get

  @targetName("executeDisbandGroup")
  private def executeGroup(
      initial: StoreState,
      signedTx: Signed[DisbandGroup],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, groupsModule).value.flatMap(identity).toOption.get

  @targetName("executeAddAccounts")
  private def executeGroup(
      initial: StoreState,
      signedTx: Signed[AddAccounts],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, groupsModule).value.flatMap(identity).toOption.get

  @targetName("executeRemoveAccounts")
  private def executeGroup(
      initial: StoreState,
      signedTx: Signed[RemoveAccounts],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, groupsModule).value.flatMap(identity).toOption.get

  @targetName("executeReplaceCoordinator")
  private def executeGroup(
      initial: StoreState,
      signedTx: Signed[ReplaceCoordinator],
  ) =
    StateModuleExecutor.runExecutionWithModule(initial, signedTx, groupsModule).value.flatMap(identity).toOption.get

  private def expectSchedulable[A <: Tx](
      signedTx: Signed[A],
  ) =
    CurrentApplicationScheduling.classify(signedTx) match
      case SchedulingClassification.Schedulable(footprint) =>
        footprint
      case other =>
        fail(s"Expected schedulable classification, got: $other")

  private def assertConformant(
      derived: org.sigilaris.core.application.scheduling.ConflictFootprint,
      actual: org.sigilaris.core.application.scheduling.ConflictFootprint,
  ): Unit =
    assertEquals(ConflictFootprintConformance.validate(actual, derived), Right(()))

  private def stateWithAliceAndBob =
    val aliceCreated = createAccountTx("alice", aliceKeyPair)
    val aliceState = executeAccount(StoreState.empty, aliceCreated).nextState
    val bobCreated = createAccountTx("bob", bobKeyPair)
    val bobState = executeAccount(aliceState, bobCreated).nextState
    (bobState, aliceCreated, bobCreated)

  test("account tx families derive schedulable footprints that cover actual execution"):
    val (baseState, _, _) = stateWithAliceAndBob

    val update = signTx(
      UpdateAccount(
        envelope = TxEnvelope(networkId, now, None),
        name = Utf8("alice"),
        nonce = BigNat.Zero,
        newGuardian = Some(bob),
      ),
      alice,
      aliceKeyPair,
    )
    val updateDerived = expectSchedulable(update)
    val updateExecution = executeAccount(baseState, update)
    assertConformant(updateDerived, updateExecution.actualFootprint.toOption.get)

    val addedKey = deriveKeyId(carolKeyPair)
    val addKeys = signTx(
      AddKeyIds(
        envelope = TxEnvelope(networkId, now, None),
        name = Utf8("alice"),
        nonce = BigNat.Zero,
        keyIds = Map(addedKey -> Utf8("carol")),
        expiresAt = None,
      ),
      alice,
      aliceKeyPair,
    )
    val addDerived = expectSchedulable(addKeys)
    val addExecution = executeAccount(baseState, addKeys)
    assertConformant(addDerived, addExecution.actualFootprint.toOption.get)

    val addState = addExecution.nextState
    val removeKeys = signTx(
      RemoveKeyIds(
        envelope = TxEnvelope(networkId, now, None),
        name = Utf8("alice"),
        nonce = BigNat.unsafeFromBigInt(1),
        keyIds = Set(addedKey),
      ),
      alice,
      aliceKeyPair,
    )
    val removeDerived = expectSchedulable(removeKeys)
    val removeExecution = executeAccount(addState, removeKeys)
    assertConformant(removeDerived, removeExecution.actualFootprint.toOption.get)

    val carolCreate = createAccountTx("carol", carolKeyPair)
    val carolState = executeAccount(baseState, carolCreate).nextState
    val removeAccount = signTx(
      RemoveAccount(
        envelope = TxEnvelope(networkId, now, None),
        name = Utf8("carol"),
        nonce = BigNat.Zero,
      ),
      carol,
      carolKeyPair,
    )
    val removeAccountDerived = expectSchedulable(removeAccount)
    val removeAccountExecution = executeAccount(carolState, removeAccount)
    assertConformant(removeAccountDerived, removeAccountExecution.actualFootprint.toOption.get)

  test("create named account derives a schedulable footprint without dry-run"):
    val signedTx = createAccountTx("dave", CryptoOps.generate())
    val derived = expectSchedulable(signedTx)
    val execution = executeAccount(StoreState.empty, signedTx)
    assertConformant(derived, execution.actualFootprint.toOption.get)

  test("unnamed signers derive schedulable footprints without name-key reads"):
    val unnamedKeyPair = CryptoOps.generate()
    val unnamedAccount = Account.Unnamed(deriveKeyId(unnamedKeyPair))
    val signedTx = signTx(
      CreateGroup(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("unnamed-group")),
        name = Utf8("Unnamed Group"),
        coordinator = unnamedAccount,
      ),
      unnamedAccount,
      unnamedKeyPair,
    )

    val derived = expectSchedulable(signedTx)
    val execution = executeGroup(StoreState.empty, signedTx)

    assertEquals(derived.reads.size, 1)
    assertEquals(derived.writes.size, 1)
    assertConformant(derived, execution.actualFootprint.toOption.get)

  test("group tx families derive schedulable footprints that cover actual execution"):
    val (baseState, _, _) = stateWithAliceAndBob

    val createGroup = signTx(
      CreateGroup(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("core")),
        name = Utf8("Core"),
        coordinator = alice,
      ),
      alice,
      aliceKeyPair,
    )
    val createDerived = expectSchedulable(createGroup)
    val createExecution = executeGroup(baseState, createGroup)
    assertConformant(createDerived, createExecution.actualFootprint.toOption.get)

    val groupState = createExecution.nextState
    val addAccounts = signTx(
      AddAccounts(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("core")),
        accounts = Set(bob),
        groupNonce = BigNat.Zero,
      ),
      alice,
      aliceKeyPair,
    )
    val addDerived = expectSchedulable(addAccounts)
    val addExecution = executeGroup(groupState, addAccounts)
    assertConformant(addDerived, addExecution.actualFootprint.toOption.get)

    val removeAccounts = signTx(
      RemoveAccounts(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("core")),
        accounts = Set(bob),
        groupNonce = BigNat.unsafeFromBigInt(1),
      ),
      alice,
      aliceKeyPair,
    )
    val removeDerived = expectSchedulable(removeAccounts)
    val removeExecution = executeGroup(addExecution.nextState, removeAccounts)
    assertConformant(removeDerived, removeExecution.actualFootprint.toOption.get)

    val replaceCoordinator = signTx(
      ReplaceCoordinator(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("core")),
        newCoordinator = bob,
        groupNonce = BigNat.unsafeFromBigInt(2),
      ),
      alice,
      aliceKeyPair,
    )
    val replaceDerived = expectSchedulable(replaceCoordinator)
    val replaceExecution = executeGroup(removeExecution.nextState, replaceCoordinator)
    assertConformant(replaceDerived, replaceExecution.actualFootprint.toOption.get)

    val disband = signTx(
      DisbandGroup(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("core")),
        groupNonce = BigNat.unsafeFromBigInt(3),
      ),
      bob,
      bobKeyPair,
    )
    val disbandDerived = expectSchedulable(disband)
    val disbandExecution = executeGroup(replaceExecution.nextState, disband)
    assertConformant(disbandDerived, disbandExecution.actualFootprint.toOption.get)

  test("disband group remains an execution failure when members still exist"):
    val (baseState, _, _) = stateWithAliceAndBob

    val createGroup = signTx(
      CreateGroup(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("non-empty")),
        name = Utf8("Non Empty"),
        coordinator = alice,
      ),
      alice,
      aliceKeyPair,
    )
    val groupState = executeGroup(baseState, createGroup).nextState
    val addAccounts = signTx(
      AddAccounts(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("non-empty")),
        accounts = Set(bob),
        groupNonce = BigNat.Zero,
      ),
      alice,
      aliceKeyPair,
    )
    val populatedState = executeGroup(groupState, addAccounts).nextState
    val disband = signTx(
      DisbandGroup(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("non-empty")),
        groupNonce = BigNat.unsafeFromBigInt(1),
      ),
      alice,
      aliceKeyPair,
    )

    val derived = expectSchedulable(disband)
    val executionResult =
      StateModuleExecutor
        .runExecutionWithModule(populatedState, disband, groupsModule)
        .value
        .flatMap(identity)

    assertEquals(derived.reads.size, 2)
    assertEquals(derived.writes.size, 1)
    assert(executionResult.isLeft)

  test("CurrentApplicationScheduling supports heterogeneous batch planning across account and group families"):
    val update = signTx(
      UpdateAccount(
        envelope = TxEnvelope(networkId, now, None),
        name = Utf8("alice"),
        nonce = BigNat.Zero,
        newGuardian = Some(bob),
      ),
      alice,
      aliceKeyPair,
    )
    val createGroup = signTx(
      CreateGroup(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId(Utf8("mixed")),
        name = Utf8("Mixed"),
        coordinator = alice,
      ),
      alice,
      aliceKeyPair,
    )

    val plan =
      BatchPlanner.plan(
        Vector[Signed[? <: Tx]](update, createGroup),
      )(CurrentApplicationScheduling.classify)

    plan match
      case Right(BatchPlan.Schedulable(schedulable)) =>
        assertEquals(schedulable.items.size, 2)
      case other =>
        fail(s"Expected schedulable heterogeneous plan, got: $other")

  test("unsupported transaction families classify to compatibility"):
    final case class UnknownTx() extends Tx:
      type Reads = EmptyTuple
      type Writes = EmptyTuple
      type Result = Unit
      type Event = Nothing

    val classification = CurrentApplicationScheduling.classify(signDummy(UnknownTx()))

    classification match
      case SchedulingClassification.Compatibility(reason) =>
        assertEquals(reason.reason, "unsupportedTxFamily")
        assert(reason.detail.nonEmpty)
      case other =>
        fail(s"Expected compatibility classification, got: $other")

  test("current shipped account/group surface has no documented compatibility-only families"):
    assertEquals(CurrentApplicationScheduling.documentedCompatibilityFamilies, Vector.empty)
