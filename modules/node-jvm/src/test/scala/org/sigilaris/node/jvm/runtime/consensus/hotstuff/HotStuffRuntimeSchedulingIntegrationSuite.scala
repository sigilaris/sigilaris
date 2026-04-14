package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.application.scheduling.{
  CompatibilityReason,
  ConflictFootprint,
  SchedulingClassification,
  StateRef,
}
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.{CryptoOps, Hash}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockHeight,
  BlockStore,
  BlockTimestamp,
  BlockView,
  StateRoot,
}
import org.sigilaris.node.gossip.{
  ChainId,
  GossipClock,
  GossipEvent,
  PeerIdentity,
}

final class HotStuffRuntimeSchedulingIntegrationSuite extends CatsEffectSuite:
  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-04T09:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        ValidatorId.unsafe(s"validator-${index + 1}"),
        keyPair.publicKey,
      ),
  )

  private val holders = Vector(
    ValidatorKeyHolder(
      validatorSet.members(0).id,
      PeerIdentity.unsafe("node-a"),
      ValidatorKeyHolderStatus.Active,
    ),
    ValidatorKeyHolder(
      validatorSet.members(1).id,
      PeerIdentity.unsafe("node-a"),
      ValidatorKeyHolderStatus.Active,
    ),
    ValidatorKeyHolder(
      validatorSet.members(2).id,
      PeerIdentity.unsafe("node-a"),
      ValidatorKeyHolderStatus.Active,
    ),
    ValidatorKeyHolder(
      validatorSet.members(3).id,
      PeerIdentity.unsafe("node-b"),
      ValidatorKeyHolderStatus.Active,
    ),
  )

  private final case class TestTxRef(
      id: Utf8,
  ) derives ByteEncoder

  private given Hash[TestTxRef] = Hash.build

  private type TestRecord =
    org.sigilaris.node.jvm.runtime.block.BlockRecord[TestTxRef, Utf8, Utf8]

  test(
    "emitProposalFromCandidates stores and emits only schedulable conflict-free block-body selections",
  ):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)

    val recordA  = record("a")
    val recordB  = record("b")
    val compat   = record("compat")
    val conflict = record("conflict")
    val classification = Map(
      recordA.tx  -> schedulable(writes = Set("alpha")),
      recordB.tx  -> schedulable(writes = Set("beta")),
      compat.tx   -> compatibility("dynamicDiscovery"),
      conflict.tx -> schedulable(reads = Set("alpha")),
    )

    for
      runtime    <- createRuntime()
      blockStore <- BlockStore.inMemory[IO, TestTxRef, Utf8, Utf8]
      emissionEither <- runtime.emitProposalFromCandidates(
        proposer = validatorSet.members.head.id,
        candidates = Vector(recordA, compat, conflict, recordB),
        parent = Some(bootstrapQc().subject.blockId),
        height = BlockHeight.unsafeFromLong(2L),
        stateRoot = StateRoot(hex("81")),
        timestamp =
          BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli),
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        justify = bootstrapQc(),
        ts = startedAt,
        blockStore = blockStore,
      )(tx => classification(tx))
      emission <- IO.fromEither(
        emissionEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      storedView <- blockStore
        .getView(BlockHeader.computeId(emission.view.header))
        .value
    yield
      assertEquals(
        emission.selection.accepted.map(_.tx.id.asString),
        Vector("a", "b"),
      )
      assertEquals(
        emission.selection.rejected.map(_.record.tx.id.asString),
        Vector("compat", "conflict"),
      )
      assertEquals(storedView, Right(Some(emission.view)))
      assertEquals(
        proposalPayload(emission.event).targetBlockId,
        BlockHeader.computeId(emission.view.header),
      )
      assertEquals(
        proposalPayload(emission.event).txSet,
        ProposalTxSet.fromTxs(emission.selection.accepted.map(_.tx)),
      )

  test(
    "emitVoteForProposalView rejects proposals whose stored bodies violate scheduling rules",
  ):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)

    val writer = record("writer")
    val reader = record("reader")
    val body   = BlockBody(Set(writer, reader))
    val classification = Map(
      writer.tx -> schedulable(writes = Set("shared")),
      reader.tx -> schedulable(reads = Set("shared")),
    )

    for
      runtime    <- createRuntime()
      blockStore <- BlockStore.inMemory[IO, TestTxRef, Utf8, Utf8]
      view = blockView(
        body,
        rootHex = "91",
        timestampMillis = startedAt.toEpochMilli,
      )
      _ <- unwrapBlock(blockStore.putView(view))
      proposalEvent <- runtime
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = view.header,
          txSet = ProposalTxSet.fromTxs(view.body.records.toVector.map(_.tx)),
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = bootstrapQc(),
          ts = startedAt,
        )
        .flatMap(unwrapPolicy)
      voteResult <- runtime.emitVoteForProposalView(
        voter = validatorSet.members(1).id,
        proposal = proposalPayload(proposalEvent),
        ts = startedAt.plusMillis(1),
        blockQuery = blockStore,
      )(tx => classification(tx))
    yield assertEquals(
      voteResult.left.map(_.reason),
      Left("conflictingBlockBodyTransaction"),
    )

  test(
    "validated in-memory sink rejects body-visible proposals before acceptance",
  ):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)

    val compatRecord = record("compat")
    val body         = BlockBody(Set(compatRecord))

    for
      proposerRuntime <- createRuntime()
      blockStore      <- BlockStore.inMemory[IO, TestTxRef, Utf8, Utf8]
      relayPublisher  <- InMemoryHotStuffArtifactSource.create[IO]
      sink <- InMemoryHotStuffArtifactSink
        .createWithProposalValidation[IO, TestTxRef, Utf8, Utf8](
          validatorSet = validatorSet,
          relayPolicy = HotStuffRelayPolicy(relayValidatedArtifacts = false),
          relayPublisher = relayPublisher,
          blockQuery = blockStore,
        )(_ => compatibility("automaticInputSelection"))
      view = blockView(
        body,
        rootHex = "92",
        timestampMillis = startedAt.toEpochMilli,
      )
      _ <- unwrapBlock(blockStore.putView(view))
      proposalEvent <- proposerRuntime
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = view.header,
          txSet = ProposalTxSet.fromTxs(view.body.records.toVector.map(_.tx)),
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = bootstrapQc(),
          ts = startedAt,
        )
        .flatMap(unwrapPolicy)
      applyResult <- sink.applyEvent(proposalEvent)
    yield assertEquals(
      applyResult.left.map(_.reason),
      Left("compatibilityTransactionInBlockBody"),
    )

  private def createRuntime()(using
      clock: GossipClock[IO],
  ): IO[HotStuffNodeRuntime[IO]] =
    HotStuffNodeRuntime
      .create[IO](
        localPeer = PeerIdentity.unsafe("node-a"),
        role = LocalNodeRole.Validator,
        holders = holders,
        validatorSet = validatorSet,
        localKeys = Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      .flatMap(unwrapPolicy)

  private def proposalPayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): Proposal =
    event.payload match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) => proposal
      case _ => throw new IllegalStateException("expected proposal")

  private def schedulable(
      reads: Set[String] = Set.empty[String],
      writes: Set[String] = Set.empty[String],
  ): SchedulingClassification =
    SchedulingClassification.Schedulable(
      ConflictFootprint(
        reads = reads.map(stateRef),
        writes = writes.map(stateRef),
      ),
    )

  private def compatibility(
      reason: String,
      detail: Option[String] = None,
  ): SchedulingClassification =
    SchedulingClassification.Compatibility(
      CompatibilityReason(reason, detail),
    )

  private def stateRef(
      label: String,
  ): StateRef =
    StateRef.fromBytes(ByteVector.encodeUtf8(label).toOption.get)

  private def record(
      id: String,
  ): TestRecord =
    org.sigilaris.node.jvm.runtime.block.BlockRecord(
      tx = TestTxRef(Utf8(id)),
      result = None,
      events = Vector.empty[Utf8],
    )

  private def blockView(
      body: BlockBody[TestTxRef, Utf8, Utf8],
      rootHex: String,
      timestampMillis: Long,
  ): BlockView[TestTxRef, Utf8, Utf8] =
    BlockView(
      header = blockHeader(body, rootHex, timestampMillis),
      body = body,
    )

  private def blockHeader(
      body: BlockBody[TestTxRef, Utf8, Utf8],
      rootHex: String,
      timestampMillis: Long,
  ): BlockHeader =
    BlockHeader(
      parent = Some(bootstrapQc().subject.blockId),
      height = BlockHeight.unsafeFromLong(2L),
      stateRoot = StateRoot(hex(rootHex)),
      bodyRoot = BlockBody.computeBodyRoot(body).toOption.get,
      timestamp = BlockTimestamp.unsafeFromEpochMillis(timestampMillis),
    )

  private def bootstrapQc(): QuorumCertificate =
    val window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val subject = QuorumCertificateSubject(
      window = window,
      proposalId = ProposalId(hex("70")),
      blockId = BlockId(hex("71")),
    )
    QuorumCertificateAssembler
      .assemble(
        subject,
        Vector(
          signedVoteFor(window, subject.proposalId, 0),
          signedVoteFor(window, subject.proposalId, 1),
          signedVoteFor(window, subject.proposalId, 2),
        ),
        validatorSet,
      )
      .toOption
      .get

  private def signedVoteFor(
      window: HotStuffWindow,
      proposalId: ProposalId,
      index: Int,
  ): Vote =
    Vote
      .sign(
        UnsignedVote(window, validatorSet.members(index).id, proposalId),
        validatorKeys(index),
      )
      .toOption
      .get

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private def unwrapPolicy[A](
      result: Either[HotStuffPolicyViolation, A],
  ): IO[A] =
    IO.fromEither(
      result.leftMap(rejection => new IllegalStateException(rejection.reason)),
    )

  private def unwrapBlock[A](
      result: cats.data.EitherT[
        IO,
        org.sigilaris.node.jvm.runtime.block.BlockValidationFailure,
        A,
      ],
  ): IO[A] =
    result.value.flatMap:
      case Left(failure) =>
        IO.raiseError(new IllegalStateException(failure.reason))
      case Right(success) => IO.pure(success)
