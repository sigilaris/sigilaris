package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.gossip.{
  CanonicalRejection,
  ChainId,
  ControlBatch,
  ControlOp,
  DirectionalSessionId,
  GossipClock,
  PeerIdentity,
}

final class HotStuffReplayBackfillMaterializationSuite extends CatsEffectSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-05T18:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )
  private val session1 =
    session("11111111-1111-4111-8111-111111111111", "node-b")

  test(
    "in-memory replay/backfill services follow anchor ancestry instead of height-only filtering",
  ):
    val genesis   = genesisProposal("10")
    val proposal1 = childProposal(genesis, "11", 1L)
    val proposal2 = childProposal(proposal1, "12", 2L)
    val anchor    = childProposal(proposal2, "13", 3L)
    val replay1   = childProposal(anchor, "14", 4L)
    val replay2   = childProposal(replay1, "15", 5L)
    val unrelated = childProposal(proposal1, "16", 4L)

    for
      services <- bootstrapServices(
        Vector(
          genesis,
          proposal1,
          proposal2,
          anchor,
          replay1,
          replay2,
          unrelated,
        ),
      )
      replay <- services.proposalReplay.readNext(
        session = session1,
        chainId = chainId,
        anchorBlockId = anchor.targetBlockId,
        nextHeight = BlockHeight.unsafeFromLong(4L),
        limit = 16,
      )
      backfill <- services.historicalBackfill.readPrevious(
        session = session1,
        chainId = chainId,
        beforeBlockId = anchor.targetBlockId,
        beforeHeight = anchor.block.height,
        limit = 16,
      )
      unknown <- services.historicalBackfill.readPrevious(
        session = session1,
        chainId = chainId,
        beforeBlockId = BlockId(hex("ff")),
        beforeHeight = anchor.block.height,
        limit = 16,
      )
      replayUnknown <- services.proposalReplay.readNext(
        session = session1,
        chainId = chainId,
        anchorBlockId = BlockId(hex("ee")),
        nextHeight = BlockHeight.unsafeFromLong(4L),
        limit = 16,
      )
    yield
      assertEquals(
        replay.map(_.map(_.proposalId)),
        Right(Vector(replay1.proposalId, replay2.proposalId)),
      )
      assertEquals(
        backfill.map(_.map(_.proposalId)),
        Right(
          Vector(proposal2.proposalId, proposal1.proposalId, genesis.proposalId),
        ),
      )
      assertEquals(
        unknown.left.map(_.reason),
        Left("historicalBackfillAnchorUnknown"),
      )
      assertEquals(
        replayUnknown.left.map(_.reason),
        Left("proposalReplayAnchorUnknown"),
      )

  test(
    "bootstrap coordinator materializes forward catch-up state into the runtime-owned store",
  ):
    val anchor         = finalizedSuggestion("20", 2L)
    val replay1        = childProposal(anchor.proposal, "21", 3L)
    val replay2        = childProposal(replay1, "22", 4L)
    val forwardStoreIO = ForwardCatchUpStore.inMemory[IO]
    val readiness =
      new ProposalCatchUpReadiness[IO]:
        override def assess(
            proposal: Proposal,
        ): IO[Either[BootstrapCoordinatorFailure, ProposalCatchUpAssessment]] =
          if proposal.proposalId === replay1.proposalId then
            ProposalCatchUpAssessment(
              voteReadiness = BootstrapVoteReadiness.Ready,
              controlBatch = None,
            ).asRight[BootstrapCoordinatorFailure].pure[IO]
          else
            ProposalCatchUpAssessment(
              voteReadiness = BootstrapVoteReadiness.Held("missingTxPayload"),
              controlBatch = Some(
                ControlBatch
                  .create(
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    Vector(
                      ControlOp.RequestByIdTx(
                        chainId,
                        Vector(
                          replay2.txSet.txIds.headOption.getOrElse(
                            proposalIdAsTxId(replay2.proposalId),
                          ),
                        ),
                      ),
                    ),
                  )
                  .toOption
                  .get,
              ),
            ).asRight[BootstrapCoordinatorFailure].pure[IO]

    for
      forwardStore <- forwardStoreIO
      coordinator <- BootstrapCoordinator.create[IO](
        retryPolicy = BootstrapRetryPolicy.boundedDefault,
        validatorSetLookup = ValidatorSetLookup.static[IO](
          BootstrapTrustRoot.staticValidatorSet(validatorSet),
        ),
        finalizedAnchorSuggestions = suggestionService(Right(Some(anchor))),
        snapshotCoordinator = completedSnapshotCoordinator,
        proposalReplay = replayService(Vector(replay1, replay2)),
        readiness = readiness,
        forwardStore = forwardStore,
      )
      result <- coordinator.bootstrap(
        chainId,
        Vector(session1),
        startedAt,
        Vector.empty,
      )
      stored <- forwardStore.current(chainId)
    yield
      assertEquals(
        result.map(_.forwardCatchUp.applied.map(_.proposalId)),
        Right(Vector(replay1.proposalId)),
      )
      assertEquals(
        result.map(_.forwardCatchUp.queued.map(_.proposalId)),
        Right(Vector(replay2.proposalId)),
      )
      stored match
        case Some(materialized) =>
          assertEquals(materialized.anchor, anchor.snapshotAnchor)
          assertEquals(
            materialized.applied.map(_.proposalId),
            Vector(replay1.proposalId),
          )
          assertEquals(
            materialized.queued.map(_.proposalId),
            Vector(replay2.proposalId),
          )
          assertEquals(materialized.controlBatches.size, 1)
          assertEquals(
            materialized.voteReadiness,
            BootstrapVoteReadiness.Held("missingTxPayload"),
          )
        case None =>
          fail("expected materialized forward catch-up state")

  test(
    "historical backfill archives unique proposals and fails duplicate batches explicitly",
  ):
    val anchor    = finalizedSuggestion("30", 3L)
    val genesis   = genesisProposal("31")
    val proposal1 = childProposal(genesis, "32", 1L)
    val proposal2 = childProposal(proposal1, "33", 2L)

    for
      responses <- Ref
        .of[IO, Vector[Either[CanonicalRejection, Vector[Proposal]]]](
          Vector(
            Right(Vector(proposal2)),
            Right(Vector(proposal2)),
          ),
        )
      archive <- HistoricalProposalArchive.inMemory[IO]
      worker <- HistoricalBackfillWorker.createWithNow[IO](
        policy = HistoricalBackfillPolicy(
          batchSize = 1,
          interBatchDelay = Duration.ofMillis(10L),
        ),
        historicalBackfill = sequentialBackfillService(responses),
        archive = archive,
        now = IO.pure(startedAt),
      )
      _ <- worker.start(
        chainId,
        Vector(session1),
        anchor.snapshotAnchor,
        startedAt,
      )
      failed <- awaitValue(worker.current, attempts = 40):
        case HistoricalBackfillStatus.Failed(
              "historicalBackfillDuplicateBatch",
              _,
              _,
            ) =>
          true
        case _ =>
          false
      archived <- archive.list(chainId)
    yield
      failed match
        case HistoricalBackfillStatus.Failed(reason, detail, progress) =>
          assertEquals(reason, "historicalBackfillDuplicateBatch")
          assertEquals(detail, Some(proposal2.targetBlockId.toHexLower))
          assertEquals(progress.fetchedProposalCount, 1L)
        case other =>
          fail("expected duplicate-batch failure but saw " + other.toString)
      assertEquals(
        archived.map(_.proposal.proposalId),
        Vector(proposal2.proposalId),
      )

  private def bootstrapServices(
      proposals: Vector[Proposal],
  ): IO[HotStuffBootstrapServices[IO]] =
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)
    for
      source <- InMemoryHotStuffArtifactSource.create[IO]
      sink <- InMemoryHotStuffArtifactSink.create[IO](
        validatorSet = validatorSet,
        relayPolicy = HotStuffRelayPolicy(relayValidatedArtifacts = false),
        relayPublisher = source,
      )
      _ <- proposals.zipWithIndex.traverse_ { case (proposal, index) =>
        source
          .append(
            HotStuffGossipArtifact.ProposalArtifact(proposal),
            startedAt.plusSeconds(index.toLong),
          )
          .flatMap: event =>
            sink
              .applyEvent(event)
              .flatMap:
                case Left(rejection) =>
                  IO.raiseError(new IllegalStateException(rejection.reason))
                case Right(_) =>
                  IO.unit
      }
    yield HotStuffBootstrapServicesRuntime.inMemory[IO](validatorSet, sink)

  private def suggestionService(
      response: Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]],
  ): FinalizedAnchorSuggestionService[IO] =
    new FinalizedAnchorSuggestionService[IO]:
      override def bestFinalized(
          session: BootstrapSessionBinding,
          chainId: ChainId,
      ): IO[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
        IO.pure(response)

  private def completedSnapshotCoordinator: SnapshotCoordinator[IO] =
    new SnapshotCoordinator[IO]:
      override def sync(
          anchor: FinalizedAnchorSuggestion,
          sessions: Vector[BootstrapSessionBinding],
          startedAt: Instant,
      ): IO[Either[SnapshotSyncFailure, SnapshotSyncResult]] =
        IO.pure(
          Right(
            SnapshotSyncResult(
              metadata = SnapshotMetadata(
                anchor = anchor.snapshotAnchor,
                status = SnapshotStatus.Complete,
                verifiedNodeCount = 1L,
                pendingNodeCount = 0L,
                lastUpdatedAt = startedAt,
              ),
              fetchedNodeCount = 1L,
            ),
          ),
        )

  private def replayService(
      proposals: Vector[Proposal],
  ): ProposalReplayService[IO] =
    new ProposalReplayService[IO]:
      override def readNext(
          session: BootstrapSessionBinding,
          chainId: ChainId,
          anchorBlockId: BlockId,
          nextHeight: BlockHeight,
          limit: Int,
      ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
        IO.pure(Right(proposals.take(limit.max(0))))

  private def sequentialBackfillService(
      responses: Ref[IO, Vector[Either[CanonicalRejection, Vector[Proposal]]]],
  ): HistoricalBackfillService[IO] =
    new HistoricalBackfillService[IO]:
      override def readPrevious(
          session: BootstrapSessionBinding,
          chainId: ChainId,
          beforeBlockId: BlockId,
          beforeHeight: BlockHeight,
          limit: Int,
      ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
        responses.modify:
          case current if current.nonEmpty =>
            current.tail -> current.head
          case current =>
            current -> Right(Vector.empty)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def awaitValue[A](
      effect: IO[A],
      attempts: Int,
      delay: scala.concurrent.duration.FiniteDuration =
        scala.concurrent.duration.DurationInt(25).millis,
  )(
      predicate: A => Boolean,
  ): IO[A] =
    effect.flatMap: value =>
      if predicate(value) then IO.pure(value)
      else if attempts <= 1 then
        IO.raiseError(new IllegalStateException("condition not met"))
      else IO.sleep(delay) *> awaitValue(effect, attempts - 1, delay)(predicate)

  private def session(
      sessionId: String,
      peer: String,
  ): BootstrapSessionBinding =
    BootstrapSessionBinding(
      peer = PeerIdentity.unsafe(peer),
      sessionId = DirectionalSessionId.parse(sessionId).toOption.get,
    )

  private def finalizedSuggestion(
      seed: String,
      anchorHeight: Long,
  ): FinalizedAnchorSuggestion =
    val baseHeight = anchorHeight - 1L
    val bootstrapSubject = QuorumCertificateSubject(
      window =
        HotStuffWindow(chainId, baseHeight, baseHeight, validatorSet.hash),
      proposalId = ProposalId(hex(seed + "01")),
      blockId = BlockId(hex(seed + "02")),
    )
    val bootstrapQc =
      QuorumCertificateAssembler
        .assemble(
          bootstrapSubject,
          quorumVotes(bootstrapSubject.window, bootstrapSubject.proposalId),
          validatorSet,
        )
        .toOption
        .get
    val anchorBlock =
      block(
        parent = Some(bootstrapSubject.blockId),
        height = anchorHeight,
        stateRootHex = seed + "10",
        bodyRootHex = seed + "11",
      )
    val anchor =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(
              chainId,
              anchorHeight,
              anchorHeight,
              validatorSet.hash,
            ),
            proposer = validatorSet.members.head.id,
            targetBlockId = BlockHeader.computeId(anchorBlock),
            block = anchorBlock,
            txSet = ProposalTxSet.empty,
            justify = bootstrapQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get
    val child      = childProposal(anchor, seed + "20", anchorHeight + 1L)
    val grandchild = childProposal(child, seed + "30", anchorHeight + 2L)

    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

  private def genesisProposal(
      seed: String,
  ): Proposal =
    val bootstrapSubject = QuorumCertificateSubject(
      window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash),
      proposalId = ProposalId(hex(seed + "01")),
      blockId = BlockId(hex(seed + "02")),
    )
    val bootstrapQc =
      QuorumCertificateAssembler
        .assemble(
          bootstrapSubject,
          quorumVotes(bootstrapSubject.window, bootstrapSubject.proposalId),
          validatorSet,
        )
        .toOption
        .get
    val genesisBlock =
      block(
        parent = None,
        height = 0L,
        stateRootHex = seed + "10",
        bodyRootHex = seed + "11",
      )
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash),
          proposer = validatorSet.members.head.id,
          targetBlockId = BlockHeader.computeId(genesisBlock),
          block = genesisBlock,
          txSet = ProposalTxSet.empty,
          justify = bootstrapQc,
        ),
        validatorKeys.head,
      )
      .toOption
      .get

  private def childProposal(
      parentProposal: Proposal,
      seed: String,
      height: Long,
  ): Proposal =
    val blockHeader =
      block(
        parent = Some(parentProposal.targetBlockId),
        height = height,
        stateRootHex = seed + "10",
        bodyRootHex = seed + "11",
      )
    val signerIndex = (height.toInt % 3).min(2)
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, height, height, validatorSet.hash),
          proposer = validatorSet.members(signerIndex).id,
          targetBlockId = BlockHeader.computeId(blockHeader),
          block = blockHeader,
          txSet = ProposalTxSet.empty,
          justify = qcFor(parentProposal),
        ),
        validatorKeys(signerIndex),
      )
      .toOption
      .get

  private def qcFor(
      proposal: Proposal,
  ): QuorumCertificate =
    QuorumCertificateAssembler
      .assemble(
        QuorumCertificateSubject(
          window = proposal.window,
          proposalId = proposal.proposalId,
          blockId = proposal.targetBlockId,
        ),
        quorumVotes(proposal.window, proposal.proposalId),
        validatorSet,
      )
      .toOption
      .get

  private def quorumVotes(
      window: HotStuffWindow,
      proposalId: ProposalId,
  ): Vector[Vote] =
    validatorSet.members
      .take(3)
      .zipWithIndex
      .map: (member, index) =>
        Vote
          .sign(
            UnsignedVote(
              window = window,
              voter = member.id,
              targetProposalId = proposalId,
            ),
            validatorKeys(index),
          )
          .toOption
          .get

  private def block(
      parent: Option[BlockId],
      height: Long,
      stateRootHex: String,
      bodyRootHex: String,
  ): BlockHeader =
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = StateRoot(hex(stateRootHex)),
      bodyRoot = BodyRoot(hex(bodyRootHex)),
      timestamp =
        BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli + height),
    )

  private def proposalIdAsTxId(
      proposalId: ProposalId,
  ): org.sigilaris.node.jvm.runtime.gossip.StableArtifactId =
    org.sigilaris.node.jvm.runtime.gossip.StableArtifactId.unsafeFromBytes(
      proposalId.toUInt256.bytes,
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value.padTo(64, '0')).toOption.get
