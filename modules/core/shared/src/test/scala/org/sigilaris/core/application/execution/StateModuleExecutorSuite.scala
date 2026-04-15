package org.sigilaris.core.application.execution

import java.time.Instant

import cats.data.{EitherT, Kleisli}
import cats.instances.either.given
import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.application.feature.accounts.domain.{Account, KeyId20}
import org.sigilaris.core.application.feature.accounts.module.AccountsBP
import org.sigilaris.core.application.feature.accounts.transactions.CreateNamedAccount
import org.sigilaris.core.application.module.blueprint.{Blueprint, ModuleBlueprint, StateReducer0}
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.state.{AccessLog, StoreState}
import org.sigilaris.core.application.state.{Entry, StateTable, StoreF, Tables}
import org.sigilaris.core.application.transactions.{AccountSignature, ModuleId, ModuleRoutedTx, NetworkId, Signed, Tx, TxEnvelope, TxRegistry}
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.crypto.Signature
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.failure.{SigilarisFailure, TrieFailure}
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}
import org.sigilaris.core.application.module.runtime.StateModule
import org.sigilaris.core.application.scheduling.ConflictFootprint
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.application.support.runtime.SignedTxBuilder
import cats.data.StateT

final class StateModuleExecutorSuite extends FunSuite:
  private type TestF[A] = Either[SigilarisFailure, A]

  given MerkleTrie.NodeStore[TestF] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[TestF, String](None)

  private val accountsBP = AccountsBP[TestF]
  private val module = StateModule.mount[("dsl", "accounts")](accountsBP)

  private type RoutedSchema1 =
    Entry["module1Table", Utf8, Long] *: EmptyTuple
  private type RoutedSchema2 =
    Entry["module2Table", Utf8, Long] *: EmptyTuple

  private final case class RoutedPutTx(
      key: Utf8,
      value: Long,
  ) extends Tx
      with ModuleRoutedTx:
    val moduleId: ModuleId["module1" *: EmptyTuple] =
      ModuleId("module1" *: EmptyTuple)
    type Reads = EmptyTuple
    type Writes = RoutedSchema1
    type Result = Long
    type Event = Nothing

  private def dummySignature: Signature =
    Signature(
      v = 27,
      r = UInt256.unsafeFromBytesBE(ByteVector.fill(32)(0x00)),
      s = UInt256.unsafeFromBytesBE(ByteVector.fill(32)(0x00)),
    )

  private def signDummy[A <: Tx](
      tx: A,
  ): Signed[A] =
    Signed(
      AccountSignature(Account.Named(Utf8("tester")), dummySignature),
      tx,
    )

  private def routedModule =
    val entry1 = Entry["module1Table", Utf8, Long]
    val entry2 = Entry["module2Table", Utf8, Long]

    val bp1 = new ModuleBlueprint[TestF, "module1", RoutedSchema1, EmptyTuple, EmptyTuple](
      owns = entry1 *: EmptyTuple,
      reducer0 = new StateReducer0[TestF, RoutedSchema1, EmptyTuple]:
        def apply[T <: Tx](signedTx: Signed[T])(using
            requiresReads: Requires[signedTx.value.Reads, RoutedSchema1],
            requiresWrites: Requires[signedTx.value.Writes, RoutedSchema1],
            ownsTables: Tables[TestF, RoutedSchema1],
          provider: TablesProvider[TestF, EmptyTuple],
        ): StoreF[TestF][(signedTx.value.Result, List[signedTx.value.Event])] =
          signedTx.value match
            case tx: RoutedPutTx =>
              val table =
                ownsTables
                  .asInstanceOf[Tuple]
                  .productElement(0)
                  .asInstanceOf[StateTable[TestF] {
                    type Name = "module1Table"; type K = Utf8; type V = Long
                  }]
              val key = table.brand(tx.key)
              for
                existing <- table.get(key)
                _ <- existing match
                  case Some(_) => failStore(TrieFailure("duplicate routed key"))
                  case None => table.put(key, tx.value).map(_ => ())
              yield (tx.value.asInstanceOf[signedTx.value.Result], List.empty)
            case _ =>
              StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty))
      ,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[TestF],
    )

    val bp2 = new ModuleBlueprint[TestF, "module2", RoutedSchema2, EmptyTuple, EmptyTuple](
      owns = entry2 *: EmptyTuple,
      reducer0 = new StateReducer0[TestF, RoutedSchema2, EmptyTuple]:
        def apply[T <: Tx](signedTx: Signed[T])(using
            requiresReads: Requires[signedTx.value.Reads, RoutedSchema2],
            requiresWrites: Requires[signedTx.value.Writes, RoutedSchema2],
            ownsTables: Tables[TestF, RoutedSchema2],
            provider: TablesProvider[TestF, EmptyTuple],
        ): StoreF[TestF][(signedTx.value.Result, List[signedTx.value.Event])] =
          StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty))
      ,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[TestF],
    )

    type Path = "app" *: EmptyTuple
    StateModule.mountComposed[Path](Blueprint.composeBlueprint[TestF, "combined"](bp1, bp2))

  private def deriveKeyId(
      keyPair: KeyPair,
  ): KeyId20 =
    KeyId20.fromPublicKey(keyPair.publicKey)

  private def createSignedCreateNamedAccount(
      name: String,
      keyPair: KeyPair,
  ) =
    val envelope = TxEnvelope(
      networkId = NetworkId.unsafeFromLong(1),
      createdAt = Instant.now(),
      memo = None,
    )
    val tx = CreateNamedAccount(
      envelope = envelope,
      name = Utf8(name),
      initialKeyId = deriveKeyId(keyPair),
      guardian = None,
    )
    SignedTxBuilder
      .sign(tx, Account.Named(Utf8(name)), keyPair)
      .toOption
      .get

  private def failStore[A](
      failure: SigilarisFailure,
  ): StoreF[TestF][A] =
    StateT.liftF(EitherT.leftT[TestF, A](failure))

  test("runExecution captures per-tx actual witness and resets the continuation state log"):
    val signedTx = createSignedCreateNamedAccount("alice", CryptoOps.generate())

    val execution =
      StateModuleExecutor
        .runExecution(StoreState.empty, signedTx)(using module)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(execution.actualAccessLog.readCount, 1)
    assertEquals(execution.actualAccessLog.writeCount, 2)
    assertEquals(execution.nextState.accessLog, AccessLog.empty)
    assertEquals(execution.observedState.accessLog, execution.actualAccessLog)
    assertEquals(execution.compatibilityTuple._1, execution.observedState)
    assertEquals(execution.compatibilityTuple._2._1, execution.result)
    assertEquals(execution.compatibilityTuple._2._2, execution.events)
    assertEquals(execution.receiptProjection.actualFootprint, execution.actualFootprint)
    assertEquals(execution.receiptProjection.result, execution.result)
    assertEquals(execution.receiptProjection.events, execution.events)
    assert(execution.actualFootprint.isRight)
    assertEquals(
      execution.actualFootprint,
      ConflictFootprint.fromAccessLog(execution.actualAccessLog),
    )

  test("TxExecution can surface access-log invariant violations through actualFootprint"):
    val invalidAccessLog =
      AccessLog.empty.recordWrite(
        ByteVector.fromValidHex("aa"),
        ByteVector.fromValidHex("bb00"),
      )
    val execution = TxExecution(
      nextTrieState = StoreState.empty.trieState,
      actualAccessLog = invalidAccessLog,
      actualFootprint = ConflictFootprint.fromAccessLog(invalidAccessLog),
      result = (),
      events = Nil,
    )

    assert(execution.actualFootprint.isLeft)

  test("TxExecution.receiptProjection preserves error-path footprint without witness state"):
    val invalidAccessLog =
      AccessLog.empty.recordWrite(
        ByteVector.fromValidHex("cc"),
        ByteVector.fromValidHex("dd00"),
      )
    val execution = TxExecution(
      nextTrieState = StoreState.empty.trieState,
      actualAccessLog = invalidAccessLog,
      actualFootprint = ConflictFootprint.fromAccessLog(invalidAccessLog),
      result = Utf8("result"),
      events = List(Utf8("event")),
    )

    assert(execution.receiptProjection.actualFootprint.isLeft)
    assertEquals(
      execution.receiptProjection.actualFootprint,
      execution.actualFootprint,
    )
    assertEquals(execution.receiptProjection.result, Utf8("result"))
    assertEquals(execution.receiptProjection.events, List(Utf8("event")))

  test("run and runWithModule ignore pre-existing access logs on the delegated back-compat path"):
    val dirtyInitial = StoreState(
      trieState = StoreState.empty.trieState,
      accessLog = AccessLog.empty
        .recordRead(ByteVector.fromValidHex("aa"), ByteVector.fromValidHex("aa00"))
        .recordWrite(ByteVector.fromValidHex("aa"), ByteVector.fromValidHex("aa01")),
    )

    val explicitModuleResult =
      StateModuleExecutor
        .runWithModule(
          dirtyInitial,
          createSignedCreateNamedAccount("grace", CryptoOps.generate()),
          module,
        )
        .value
        .flatMap(identity)
        .toOption
        .get

    val implicitModuleResult =
      StateModuleExecutor
        .run(
          dirtyInitial,
          createSignedCreateNamedAccount("heidi", CryptoOps.generate()),
        )(using module)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(explicitModuleResult._1.accessLog.readCount, 1)
    assertEquals(explicitModuleResult._1.accessLog.writeCount, 2)
    assertEquals(implicitModuleResult._1.accessLog.readCount, 1)
    assertEquals(implicitModuleResult._1.accessLog.writeCount, 2)

  test("runFromEmpty variants keep only the per-tx witness log"):
    val explicitModuleResult =
      StateModuleExecutor
        .runFromEmptyWithModule(
          createSignedCreateNamedAccount("ivan", CryptoOps.generate()),
          module,
        )
        .value
        .flatMap(identity)
        .toOption
        .get

    val implicitModuleResult =
      StateModuleExecutor
        .runFromEmpty(createSignedCreateNamedAccount("judy", CryptoOps.generate()))(using module)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(explicitModuleResult._1.accessLog.readCount, 1)
    assertEquals(explicitModuleResult._1.accessLog.writeCount, 2)
    assertEquals(implicitModuleResult._1.accessLog.readCount, 1)
    assertEquals(implicitModuleResult._1.accessLog.writeCount, 2)

  test("runExecutionFromEmpty variants expose per-tx witness and clean continuation state"):
    val explicitModuleExecution =
      StateModuleExecutor
        .runExecutionFromEmptyWithModule(
          createSignedCreateNamedAccount("mallory", CryptoOps.generate()),
          module,
        )
        .value
        .flatMap(identity)
        .toOption
        .get

    val implicitModuleExecution =
      StateModuleExecutor
        .runExecutionFromEmpty(
          createSignedCreateNamedAccount("nancy", CryptoOps.generate()),
        )(using module)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(explicitModuleExecution.actualAccessLog.readCount, 1)
    assertEquals(explicitModuleExecution.actualAccessLog.writeCount, 2)
    assertEquals(explicitModuleExecution.nextState.accessLog, AccessLog.empty)
    assertEquals(implicitModuleExecution.actualAccessLog.readCount, 1)
    assertEquals(implicitModuleExecution.actualAccessLog.writeCount, 2)
    assertEquals(implicitModuleExecution.nextState.accessLog, AccessLog.empty)

  test("run uses only the current trie state and does not accumulate prior access logs"):
    val signedAlice = createSignedCreateNamedAccount("alice", CryptoOps.generate())
    val signedBob = createSignedCreateNamedAccount("bob", CryptoOps.generate())

    val firstResult =
      StateModuleExecutor
        .run(StoreState.empty, signedAlice)(using module)
        .value
        .flatMap(identity)
        .toOption
        .get
    val firstState = firstResult._1

    assertNotEquals(firstState.trieState, StoreState.empty.trieState)
    assertEquals(firstState.accessLog.readCount, 1)
    assertEquals(firstState.accessLog.writeCount, 2)

    val secondResult =
      StateModuleExecutor
        .run(firstState, signedBob)(using module)
        .value
        .flatMap(identity)
        .toOption
        .get
    val secondState = secondResult._1

    assertEquals(secondState.accessLog.readCount, 1)
    assertEquals(secondState.accessLog.writeCount, 2)

  test("runExecution ignores any pre-existing access log on the initial store state"):
    val signedTx = createSignedCreateNamedAccount("carol", CryptoOps.generate())
    val dirtyInitial = StoreState(
      trieState = StoreState.empty.trieState,
      accessLog = AccessLog.empty
        .recordRead(ByteVector.fromValidHex("ff"), ByteVector.fromValidHex("ff00"))
        .recordWrite(ByteVector.fromValidHex("ff"), ByteVector.fromValidHex("ff01")),
    )

    val execution =
      StateModuleExecutor
        .runExecution(dirtyInitial, signedTx)(using module)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(execution.actualAccessLog.readCount, 1)
    assertEquals(execution.actualAccessLog.writeCount, 2)

  test("runExecution chains via nextState without carrying forward the old access log"):
    val firstExecution =
      StateModuleExecutor
        .runExecution(
          StoreState.empty,
          createSignedCreateNamedAccount("dave", CryptoOps.generate()),
        )(using module)
        .value
        .flatMap(identity)
        .toOption
        .get

    val secondExecution =
      StateModuleExecutor
        .runExecution(
          firstExecution.nextState,
          createSignedCreateNamedAccount("erin", CryptoOps.generate()),
        )(using module)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(firstExecution.nextState.accessLog, AccessLog.empty)
    assertEquals(secondExecution.actualAccessLog.readCount, 1)
    assertEquals(secondExecution.actualAccessLog.writeCount, 2)
    assertEquals(secondExecution.nextState.accessLog, AccessLog.empty)

  test("runExecution preserves trie mutations and propagates reducer failures through nextState"):
    val signedTx = createSignedCreateNamedAccount("frank", CryptoOps.generate())

    val firstExecution =
      StateModuleExecutor
        .runExecution(StoreState.empty, signedTx)(using module)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertNotEquals(firstExecution.nextTrieState, StoreState.empty.trieState)

    val secondAttempt =
      StateModuleExecutor
        .runExecution(firstExecution.nextState, signedTx)(using module)
        .value
        .flatMap(identity)

    assert(secondAttempt.isLeft)

  test("runExecutionRouted captures the same per-tx witness behavior for composed modules"):
    val execution =
      StateModuleExecutor
        .runExecutionRouted(
          StoreState.empty,
          signDummy(RoutedPutTx(Utf8("routed"), 7L)),
        )(using routedModule)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(execution.actualAccessLog.readCount, 1)
    assertEquals(execution.actualAccessLog.writeCount, 1)
    assertEquals(execution.nextState.accessLog, AccessLog.empty)
    assertEquals(execution.actualFootprint.isRight, true)

  test("runExecutionRouted ignores any pre-existing access log on the initial store state"):
    val dirtyInitial = StoreState(
      trieState = StoreState.empty.trieState,
      accessLog = AccessLog.empty
        .recordRead(ByteVector.fromValidHex("dd"), ByteVector.fromValidHex("dd00"))
        .recordWrite(ByteVector.fromValidHex("dd"), ByteVector.fromValidHex("dd01")),
    )

    val execution =
      StateModuleExecutor
        .runExecutionRouted(
          dirtyInitial,
          signDummy(RoutedPutTx(Utf8("routed-dirty"), 8L)),
        )(using routedModule)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(execution.actualAccessLog.readCount, 1)
    assertEquals(execution.actualAccessLog.writeCount, 1)

  test("runRouted does not accumulate prior access logs across chained executions"):
    val firstResult =
      StateModuleExecutor
        .runRouted(
          StoreState.empty,
          signDummy(RoutedPutTx(Utf8("routed-a"), 1L)),
        )(using routedModule)
        .value
        .flatMap(identity)
        .toOption
        .get
    val secondResult =
      StateModuleExecutor
        .runRouted(
          firstResult._1,
          signDummy(RoutedPutTx(Utf8("routed-b"), 2L)),
        )(using routedModule)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(firstResult._1.accessLog.readCount, 1)
    assertEquals(firstResult._1.accessLog.writeCount, 1)
    assertEquals(secondResult._1.accessLog.readCount, 1)
    assertEquals(secondResult._1.accessLog.writeCount, 1)

  test("runExecutionRouted chains via nextState without carrying forward old routed access logs"):
    val firstExecution =
      StateModuleExecutor
        .runExecutionRouted(
          StoreState.empty,
          signDummy(RoutedPutTx(Utf8("routed-next-a"), 11L)),
        )(using routedModule)
        .value
        .flatMap(identity)
        .toOption
        .get

    val secondExecution =
      StateModuleExecutor
        .runExecutionRouted(
          firstExecution.nextState,
          signDummy(RoutedPutTx(Utf8("routed-next-b"), 12L)),
        )(using routedModule)
        .value
        .flatMap(identity)
        .toOption
        .get

    assertEquals(firstExecution.nextState.accessLog, AccessLog.empty)
    assertEquals(secondExecution.actualAccessLog.readCount, 1)
    assertEquals(secondExecution.actualAccessLog.writeCount, 1)
    assertEquals(secondExecution.nextState.accessLog, AccessLog.empty)

  test("runExecutionRouted propagates reducer failures through nextState"):
    val firstExecution =
      StateModuleExecutor
        .runExecutionRouted(
          StoreState.empty,
          signDummy(RoutedPutTx(Utf8("routed-dup"), 21L)),
        )(using routedModule)
        .value
        .flatMap(identity)
        .toOption
        .get

    val secondAttempt =
      StateModuleExecutor
        .runExecutionRouted(
          firstExecution.nextState,
          signDummy(RoutedPutTx(Utf8("routed-dup"), 22L)),
        )(using routedModule)
        .value
        .flatMap(identity)

    assert(secondAttempt.isLeft)

  test("runValue variants ignore pre-existing access logs through the plain F surface"):
    val dirtyInitial = StoreState(
      trieState = StoreState.empty.trieState,
      accessLog = AccessLog.empty
        .recordRead(ByteVector.fromValidHex("cc"), ByteVector.fromValidHex("cc00"))
        .recordWrite(ByteVector.fromValidHex("cc"), ByteVector.fromValidHex("cc01")),
    )

    val explicitModuleResult =
      StateModuleExecutor
        .runValueWithModule(
          dirtyInitial,
          createSignedCreateNamedAccount("kate", CryptoOps.generate()),
          module,
        )
        .flatMap(identity)
        .toOption
        .get

    val implicitModuleResult =
      StateModuleExecutor
        .runValue(
          dirtyInitial,
          createSignedCreateNamedAccount("louis", CryptoOps.generate()),
        )(using module)
        .flatMap(identity)
        .toOption
        .get

    assertEquals(explicitModuleResult._1.accessLog.readCount, 1)
    assertEquals(explicitModuleResult._1.accessLog.writeCount, 2)
    assertEquals(implicitModuleResult._1.accessLog.readCount, 1)
    assertEquals(implicitModuleResult._1.accessLog.writeCount, 2)
