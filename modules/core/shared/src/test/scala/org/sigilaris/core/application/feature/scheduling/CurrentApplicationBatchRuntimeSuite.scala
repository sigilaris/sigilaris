package org.sigilaris.core.application.feature.scheduling

import java.time.Instant

import cats.data.{EitherT, Kleisli}
import cats.instances.either.given
import munit.FunSuite

import org.sigilaris.core.application.feature.accounts.domain.{Account, KeyId20}
import org.sigilaris.core.application.feature.accounts.transactions.{AddKeyIds, CreateNamedAccount, UpdateAccount}
import org.sigilaris.core.application.feature.group.domain.GroupId
import org.sigilaris.core.application.feature.group.transactions.CreateGroup
import org.sigilaris.core.application.scheduling.{CompatibilityReason, ConflictFootprint, SchedulableExecutionFailure, SchedulingClassification}
import org.sigilaris.core.application.transactions.{AccountSignature, NetworkId, Signed, Tx, TxEnvelope}
import org.sigilaris.core.crypto.{CryptoOps, Hash, KeyPair, Sign}
import org.sigilaris.core.crypto.Sign.ops.*
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}

final class CurrentApplicationBatchRuntimeSuite extends FunSuite:
  given MerkleTrie.NodeStore[CurrentApplicationBatchRuntime.RuntimeF] =
    Kleisli: (_: MerkleTrieNode.MerkleHash) =>
      EitherT.rightT[CurrentApplicationBatchRuntime.RuntimeF, String](None)

  private val networkId = NetworkId.unsafeFromLong(1)
  private val now       = Instant.parse("2026-04-04T00:00:00Z")

  private lazy val aliceKeyPair = CryptoOps.generate()
  private lazy val bobKeyPair   = CryptoOps.generate()
  private lazy val carolKeyPair = CryptoOps.generate()

  private val alice = Account.Named(Utf8("alice"))
  private val bob   = Account.Named(Utf8("bob"))

  private def deriveKeyId(
      keyPair: KeyPair,
  ): KeyId20 =
    KeyId20.fromPublicKey(keyPair.publicKey)

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

  private def createGroupTx(
      groupId: String,
      coordinator: Account,
      keyPair: KeyPair,
  ): Signed[CreateGroup] =
    signTx(
      CreateGroup(
        envelope = TxEnvelope(networkId, now, None),
        groupId = GroupId.unsafe(Utf8(groupId)),
        name = Utf8(s"$groupId-group"),
        coordinator = coordinator,
      ),
      account = coordinator,
      keyPair = keyPair,
    )

  private def updateAccountTx(
      name: String,
      signer: Account,
      keyPair: KeyPair,
      nonce: BigNat,
      guardian: Option[Account],
  ): Signed[UpdateAccount] =
    signTx(
      UpdateAccount(
        envelope = TxEnvelope(networkId, now, None),
        name = Utf8(name),
        nonce = nonce,
        newGuardian = guardian,
      ),
      account = signer,
      keyPair = keyPair,
    )

  private def addKeyIdsTx(
      name: String,
      signer: Account,
      signerKeyPair: KeyPair,
      keyIds: Map[KeyId20, Utf8],
      nonce: BigNat,
  ): Signed[AddKeyIds] =
    signTx(
      AddKeyIds.unsafe(
        envelope = TxEnvelope(networkId, now, None),
        name = Utf8(name),
        nonce = nonce,
        keyIds = keyIds,
        expiresAt = None,
      ),
      account = signer,
      keyPair = signerKeyPair,
    )

  private def expectApplied(
      result: Either[
        CurrentApplicationBatchRejected,
        (CurrentApplicationBatchRuntimeState, CurrentApplicationBatchOutcome),
      ],
  ): (CurrentApplicationBatchRuntimeState, CurrentApplicationBatchReceipt) =
    result match
      case Right((nextState, CurrentApplicationBatchOutcome.Applied(receipt))) =>
        nextState -> receipt
      case other =>
        fail(s"Expected applied batch outcome, got: $other")

  private def expectDeduplicated(
      result: Either[
        CurrentApplicationBatchRejected,
        (CurrentApplicationBatchRuntimeState, CurrentApplicationBatchOutcome),
      ],
  ): (CurrentApplicationBatchRuntimeState, CurrentApplicationBatchReceipt) =
    result match
      case Right((nextState, CurrentApplicationBatchOutcome.Deduplicated(receipt))) =>
        nextState -> receipt
      case other =>
        fail(s"Expected deduplicated batch outcome, got: $other")

  test("CurrentApplicationBatchRuntime routes account and group families through one concrete batch owner"):
    val runtime = CurrentApplicationBatchRuntime.createDefault()
    val aliceCreated = createAccountTx("alice", aliceKeyPair)
    val createAccountBatch =
      CurrentApplicationBatch.unsafe(
        idempotencyKey = "batch-accounts",
        items = Vector(aliceCreated),
      )

    val (stateAfterAccount, accountReceipt) =
      expectApplied(
        runtime.applyBatch(
          CurrentApplicationBatchRuntimeState.empty,
          createAccountBatch,
        )
      )

    val createGroupBatch =
      CurrentApplicationBatch.unsafe(
        idempotencyKey = "batch-group",
        items = Vector(createGroupTx("ops", alice, aliceKeyPair)),
      )

    val (stateAfterGroup, groupReceipt) =
      expectApplied(runtime.applyBatch(stateAfterAccount, createGroupBatch))

    assertEquals(accountReceipt.diagnostics.mode, CurrentApplicationBatchMode.Schedulable)
    assertEquals(groupReceipt.diagnostics.mode, CurrentApplicationBatchMode.Schedulable)
    assertEquals(accountReceipt.executions.size, 1)
    assertEquals(groupReceipt.executions.size, 1)
    assertNotEquals(
      stateAfterGroup.storeState.trieState,
      CurrentApplicationBatchRuntimeState.empty.storeState.trieState,
    )

  test("CurrentApplicationBatchRuntime executes conflict-free schedulable batches through the schedulable executor"):
    val runtime = CurrentApplicationBatchRuntime.createDefault()
    val batch =
      CurrentApplicationBatch.unsafe(
        idempotencyKey = "schedulable-batch",
        items = Vector(
          createAccountTx("alice", aliceKeyPair),
          createAccountTx("bob", bobKeyPair),
        ),
      )

    val (_, receipt) =
      expectApplied(
        runtime.applyBatch(CurrentApplicationBatchRuntimeState.empty, batch)
      )

    assertEquals(receipt.diagnostics.mode, CurrentApplicationBatchMode.Schedulable)
    assertEquals(receipt.executions.size, 2)
    assertEquals(receipt.diagnostics.duplicatesDropped, Vector.empty)

  test("CurrentApplicationBatchRuntime preserves idempotency behavior for schedulable batches"):
    val runtime = CurrentApplicationBatchRuntime.createDefault()
    val batch =
      CurrentApplicationBatch.unsafe(
        idempotencyKey = "schedulable-idempotency",
        items = Vector(createAccountTx("alice", aliceKeyPair)),
      )

    val (appliedState, appliedReceipt) =
      expectApplied(
        runtime.applyBatch(CurrentApplicationBatchRuntimeState.empty, batch)
      )
    val (deduplicatedState, deduplicatedReceipt) =
      expectDeduplicated(runtime.applyBatch(appliedState, batch))

    assertEquals(appliedReceipt.diagnostics.mode, CurrentApplicationBatchMode.Schedulable)
    assertEquals(deduplicatedState, appliedState)
    assertEquals(deduplicatedReceipt, appliedReceipt)

  test("CurrentApplicationBatchRuntime rejects conflicting all-schedulable batches before execution"):
    val runtime = CurrentApplicationBatchRuntime.createDefault()
    val (createdState, _) =
      expectApplied(
        runtime.applyBatch(
          CurrentApplicationBatchRuntimeState.empty,
          CurrentApplicationBatch.unsafe(
            idempotencyKey = "seed-alice",
            items = Vector(createAccountTx("alice", aliceKeyPair)),
          ),
        )
      )

    val conflictingBatch =
      CurrentApplicationBatch.unsafe(
        idempotencyKey = "conflict-alice",
        items = Vector(
          updateAccountTx(
            name = "alice",
            signer = alice,
            keyPair = aliceKeyPair,
            nonce = BigNat.Zero,
            guardian = Some(bob),
          ),
          addKeyIdsTx(
            name = "alice",
            signer = alice,
            signerKeyPair = aliceKeyPair,
            keyIds = Map(deriveKeyId(carolKeyPair) -> Utf8("carol")),
            nonce = BigNat.Zero,
          ),
        ),
      )

    val result = runtime.applyBatch(createdState, conflictingBatch)

    result match
      case Left(CurrentApplicationBatchRejected.SchedulingConflict(_, diagnostics)) =>
        assertEquals(diagnostics.mode, CurrentApplicationBatchMode.Schedulable)
        assertEquals(diagnostics.classifications.size, 2)
      case other =>
        fail(s"Expected scheduling conflict rejection, got: $other")

  test("CurrentApplicationBatchRuntime surfaces schedulable execution failures at the runtime boundary"):
    val runtime =
      CurrentApplicationBatchRuntime.createWithClassifier: _ =>
        SchedulingClassification.Schedulable(ConflictFootprint.empty)

    val result =
      runtime.applyBatch(
        CurrentApplicationBatchRuntimeState.empty,
        CurrentApplicationBatch.unsafe(
          idempotencyKey = "schedulable-failure",
          items = Vector(createAccountTx("alice", aliceKeyPair)),
        ),
      )

    result match
      case Left(
            CurrentApplicationBatchRejected.SchedulableExecutionFailed(
              SchedulableExecutionFailure.ConformanceFailed(_, _),
              diagnostics,
            )
          ) =>
        assertEquals(diagnostics.mode, CurrentApplicationBatchMode.Schedulable)
      case other =>
        fail(s"Expected schedulable execution failure rejection, got: $other")

  test("CurrentApplicationBatchRuntime surfaces Compatibility(reason) for mixed fallback batches"):
    val runtime =
      CurrentApplicationBatchRuntime.createWithClassifier: signedTx =>
        signedTx.value match
          case _: CreateGroup =>
            SchedulingClassification.Compatibility(
              CompatibilityReason(
                reason = "forcedCompatibility",
                detail = Some("group"),
              )
            )
          case _ =>
            CurrentApplicationBatchRuntime.defaultClassifier(signedTx)

    val batch =
      CurrentApplicationBatch.unsafe(
        idempotencyKey = "mixed-batch",
        items = Vector(
          createAccountTx("alice", aliceKeyPair),
          createGroupTx("ops", alice, aliceKeyPair),
        ),
      )

    val (_, receipt) =
      expectApplied(
        runtime.applyBatch(CurrentApplicationBatchRuntimeState.empty, batch)
      )

    receipt.diagnostics.mode match
      case CurrentApplicationBatchMode.Compatibility(reason, mode) =>
        assertEquals(mode, org.sigilaris.core.application.scheduling.CompatibilityMode.MixedBatch)
        assertEquals(reason.reason, "mixedBatch")
        assert(reason.detail.exists(_.contains("forcedCompatibility:group")))
      case other =>
        fail(s"Expected compatibility mode diagnostics, got: $other")
    assertEquals(receipt.executions.size, 2)

  test("CurrentApplicationBatchRuntime preserves duplicate and idempotency behavior for compatibility batches"):
    val runtime =
      CurrentApplicationBatchRuntime.createWithClassifier: _ =>
        SchedulingClassification.Compatibility(
          CompatibilityReason(
            reason = "forcedCompatibility",
            detail = Some("test"),
          )
        )

    val aliceCreated = createAccountTx("alice", aliceKeyPair)
    val batch =
      CurrentApplicationBatch.unsafe(
        idempotencyKey = "compat-dup",
        items = Vector(aliceCreated, aliceCreated),
      )

    val (appliedState, appliedReceipt) =
      expectApplied(
        runtime.applyBatch(CurrentApplicationBatchRuntimeState.empty, batch)
      )
    val (deduplicatedState, deduplicatedReceipt) =
      expectDeduplicated(runtime.applyBatch(appliedState, batch))

    appliedReceipt.diagnostics.mode match
      case CurrentApplicationBatchMode.Compatibility(reason, mode) =>
        assertEquals(mode, org.sigilaris.core.application.scheduling.CompatibilityMode.CompatibilityOnly)
        assertEquals(reason.reason, "forcedCompatibility")
      case other =>
        fail(s"Expected compatibility-only diagnostics, got: $other")
    assertEquals(appliedReceipt.executions.size, 1)
    assertEquals(appliedReceipt.diagnostics.duplicatesDropped.size, 1)
    assertEquals(deduplicatedState, appliedState)
    assertEquals(deduplicatedReceipt, appliedReceipt)

  test("CurrentApplicationBatchRuntime surfaces compatibility execution failures at the runtime boundary"):
    val runtime =
      CurrentApplicationBatchRuntime.createWithClassifier: _ =>
        SchedulingClassification.Compatibility(
          CompatibilityReason(
            reason = "forcedCompatibility",
            detail = Some("test"),
          )
        )

    val result =
      runtime.applyBatch(
        CurrentApplicationBatchRuntimeState.empty,
        CurrentApplicationBatch.unsafe(
          idempotencyKey = "compat-failure",
          items = Vector(
            updateAccountTx(
              name = "missing",
              signer = Account.Named(Utf8("missing")),
              keyPair = aliceKeyPair,
              nonce = BigNat.Zero,
              guardian = Some(bob),
            )
          ),
        ),
      )

    result match
      case Left(CurrentApplicationBatchRejected.CompatibilityExecutionFailed(_, _, diagnostics)) =>
        diagnostics.mode match
          case CurrentApplicationBatchMode.Compatibility(reason, mode) =>
            assertEquals(mode, org.sigilaris.core.application.scheduling.CompatibilityMode.CompatibilityOnly)
            assertEquals(reason.reason, "forcedCompatibility")
          case other =>
            fail(s"Expected compatibility diagnostics, got: $other")
      case other =>
        fail(s"Expected compatibility execution failure rejection, got: $other")
