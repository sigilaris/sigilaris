package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.gossip.{
  ChainId,
  GossipClock,
  GossipTopic,
  PeerIdentity,
  StableArtifactId,
}
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}

final class HotStuffProposalInputPacemakerIntegrationSuite
    extends CatsEffectSuite:
  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-05-16T01:00:00Z")
  private val validatorKeys = Vector.fill(4)(org.sigilaris.core.crypto.CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        ValidatorId.unsafe(s"validator-${index + 1}"),
        keyPair.publicKey,
      ),
  )
  private val holders =
    validatorSet.members.zipWithIndex.map: (member, index) =>
      ValidatorKeyHolder(
        member.id,
        if index < 3 then PeerIdentity.unsafe("node-a")
        else PeerIdentity.unsafe("node-b"),
        ValidatorKeyHolderStatus.Active,
      )

  test("automatic pacemaker emits non-empty provider-backed proposals"):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)
    val expectedTxSet = ProposalTxSet(Vector(txId("a1")))

    for
      runtime <- createRuntime(
        HotStuffProposalInputRuntimeConfig.requireProvider[IO](
          suppliedProvider(expectedTxSet),
        ),
      )
      _        <- applyGenesisProposal(runtime)
      snapshot <- inMemorySnapshot(runtime)
      proposal <- IO.fromOption(proposalAt(snapshot, 1L, 1L))(
        new IllegalStateException("provider proposal was not emitted"),
      )
      diagnostics <- proposalInputDiagnostics(runtime, validatorSet.members(1).id)
    yield
      assertEquals(proposal.proposer, validatorSet.members(1).id)
      assertEquals(proposal.txSet, expectedTxSet)
      assertEquals(proposal.block.parent, Some(genesisProposal.targetBlockId))
      assertEquals(HotStuffValidator.validateProposal(proposal, validatorSet), Right(()))
      assert(snapshot.proposals.contains(proposal.proposalId))
      assert(snapshot.qcs.contains(proposal.proposalId))
      assert(
        diagnostics.exists:
          case HotStuffPacemakerDiagnostic.ProposalInputResult(
                window,
                proposer,
                HotStuffProposalInputDiagnosticOutcome.Supplied,
                "supplied",
                None,
                false,
              ) =>
            window === proposal.window && proposer === proposal.proposer
          case _ => false,
      )

  test("provider no-work suppresses proposal when fallback is disabled"):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)

    for
      runtime <- createRuntime(
        HotStuffProposalInputRuntimeConfig(
          provider = Some(noWorkProvider),
          fallbackPolicy =
            HotStuffProposalInputFallbackPolicy.RequireProviderInput,
        ),
      )
      _           <- applyGenesisProposal(runtime)
      snapshot    <- inMemorySnapshot(runtime)
      diagnostics <- proposalInputDiagnostics(runtime, validatorSet.members(1).id)
    yield
      assert(proposalAt(snapshot, 1L, 1L).isEmpty)
      assert(
        diagnostics.exists:
          case HotStuffPacemakerDiagnostic.ProposalInputResult(
                _,
                proposer,
                HotStuffProposalInputDiagnosticOutcome.NoWork,
                "queueEmpty",
                None,
                false,
              ) =>
            proposer === validatorSet.members(1).id
          case _ => false,
      )

  test("provider no-work uses explicit legacy empty fallback when enabled"):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)

    for
      runtime <- createRuntime(
        HotStuffProposalInputRuntimeConfig.withProviderFallback[IO](
          noWorkProvider,
        ),
      )
      _        <- applyGenesisProposal(runtime)
      snapshot <- inMemorySnapshot(runtime)
      proposal <- IO.fromOption(proposalAt(snapshot, 1L, 1L))(
        new IllegalStateException("fallback proposal was not emitted"),
      )
      diagnostics <- proposalInputDiagnostics(runtime, validatorSet.members(1).id)
    yield
      assertEquals(proposal.txSet, ProposalTxSet.empty)
      assert(
        diagnostics.exists:
          case HotStuffPacemakerDiagnostic.ProposalInputResult(
                _,
                proposer,
                HotStuffProposalInputDiagnosticOutcome.NoWork,
                "queueEmpty",
                None,
                true,
              ) =>
            proposer === validatorSet.members(1).id
          case _ => false,
      )

  test("provider failure records sanitized diagnostics and does not publish proposal"):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)

    for
      runtime <- createRuntime(
        HotStuffProposalInputRuntimeConfig.requireProvider[IO](
          failingProvider,
        ),
      )
      _           <- applyGenesisProposal(runtime)
      snapshot    <- inMemorySnapshot(runtime)
      diagnostics <- proposalInputDiagnostics(runtime, validatorSet.members(1).id)
    yield
      assert(proposalAt(snapshot, 1L, 1L).isEmpty)
      assert(
        diagnostics.exists:
          case HotStuffPacemakerDiagnostic.ProposalInputResult(
                _,
                proposer,
                HotStuffProposalInputDiagnosticOutcome.Failed,
                "proposalInputProviderFailed",
                Some("java.lang.IllegalStateException"),
                false,
              ) =>
            proposer === validatorSet.members(1).id
          case _ => false,
      )

  test("repeated pacemaker reads do not emit more than one proposal per window"):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)
    val expectedTxSet = ProposalTxSet(Vector(txId("b1")))

    for
      runtime <- createRuntime(
        HotStuffProposalInputRuntimeConfig.requireProvider[IO](
          suppliedProvider(expectedTxSet),
        ),
      )
      _ <- applyGenesisProposal(runtime)
      _ <- runtime.source.readAfter(chainId, GossipTopic.consensusProposal, None)
      _ <- runtime.source.readAfter(chainId, GossipTopic.consensusProposal, None)
      snapshot <- inMemorySnapshot(runtime)
    yield
      assertEquals(
        snapshot.proposals.values.count(_.window === window(1L, 1L)),
        1,
      )

  private def createRuntime(
      proposalInputConfig: HotStuffProposalInputRuntimeConfig[IO],
  )(using
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
        automaticConsensus = true,
        proposalInputConfig = proposalInputConfig,
      )
      .flatMap(result =>
        IO.fromEither(
          result.leftMap(rejection =>
            new IllegalStateException(rejection.reason),
          ),
        ),
      )

  private def applyGenesisProposal(
      runtime: HotStuffNodeRuntime[IO],
  ): IO[Unit] =
    for
      event <- runtime.services.publisher.append(
        HotStuffGossipArtifact.ProposalArtifact(genesisProposal),
        startedAt,
      )
      result <- runtime.sink.applyEvent(event)
      _ <- IO.fromEither(
        result.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      ).void
    yield ()

  private def suppliedProvider(
      txSet: ProposalTxSet,
  ): HotStuffProposalInputProvider[IO] =
    request =>
      IO.pure:
        HotStuffProposalInputProviderResult.Supplied(
          HotStuffProposalInput(
            parent = request.parent,
            height = request.height,
            stateRoot = StateRoot(hex("81")),
            bodyRoot = BodyRoot(hex("82")),
            timestamp = request.timestamp,
            txSet = txSet,
          ),
        )

  private def noWorkProvider: HotStuffProposalInputProvider[IO] =
    _ =>
      IO.pure:
        HotStuffProposalInputProviderResult.NoWork("queueEmpty", None)

  private def failingProvider: HotStuffProposalInputProvider[IO] =
    _ => IO.raiseError(new IllegalStateException("payload=secret"))

  private def proposalInputDiagnostics(
      runtime: HotStuffNodeRuntime[IO],
      validatorId: ValidatorId,
  ): IO[Vector[HotStuffPacemakerDiagnostic]] =
    runtime.currentPacemakerSnapshot.map:
      _.flatMap(
        _.entries
          .get(HotStuffPacemakerKey(chainId, validatorId))
          .map(_.diagnostics),
      ).getOrElse(Vector.empty)

  private def inMemorySnapshot(
      runtime: HotStuffNodeRuntime[IO],
  ): IO[InMemoryHotStuffSinkSnapshot] =
    IO.fromOption(runtime.inMemorySink)(
      new IllegalStateException("expected in-memory sink"),
    ).flatMap(_.snapshot)

  private def proposalAt(
      snapshot: InMemoryHotStuffSinkSnapshot,
      height: Long,
      view: Long,
  ): Option[Proposal] =
    snapshot.proposals.values.find(_.window === window(height, view))

  private def genesisProposal: Proposal =
    val block =
      BlockHeader(
        parent = None,
        height = BlockHeight.Genesis,
        stateRoot = StateRoot(hex("70")),
        bodyRoot = BodyRoot(hex("71")),
        timestamp = BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli),
      )
    Proposal
      .sign(
        UnsignedProposal(
          window = window(0L, 0L),
          proposer = validatorSet.members.head.id,
          targetBlockId = BlockHeader.computeId(block),
          block = block,
          txSet = ProposalTxSet.empty,
          justify = bootstrapQc,
        ),
        validatorKeys.head,
      )
      .toOption
      .get

  private def bootstrapQc: QuorumCertificate =
    val qcWindow = window(0L, 0L)
    val subject =
      QuorumCertificateSubject(
        window = qcWindow,
        proposalId = ProposalId(hex("72")),
        blockId = BlockId(hex("73")),
      )
    QuorumCertificateAssembler
      .assemble(
        subject,
        Vector(
          signedVoteFor(qcWindow, subject.proposalId, 0),
          signedVoteFor(qcWindow, subject.proposalId, 1),
          signedVoteFor(qcWindow, subject.proposalId, 2),
        ),
        validatorSet,
      )
      .toOption
      .get

  private def signedVoteFor(
      voteWindow: HotStuffWindow,
      proposalId: ProposalId,
      index: Int,
  ): Vote =
    Vote
      .sign(
        UnsignedVote(voteWindow, validatorSet.members(index).id, proposalId),
        validatorKeys(index),
      )
      .toOption
      .get

  private def window(
      height: Long,
      view: Long,
  ): HotStuffWindow =
    HotStuffWindow.unsafe(chainId, height, view, validatorSet.hash)

  private def txId(
      value: String,
  ): StableArtifactId =
    StableArtifactId.unsafeFromBytes(hex(value).bytes)

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
