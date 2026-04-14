package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import scala.concurrent.duration.DurationInt

import cats.effect.{Deferred, IO, Ref}
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
import org.sigilaris.node.gossip.{
  CanonicalRejection,
  ChainId,
  DirectionalSessionId,
  PeerIdentity,
}

final class HotStuffHistoricalBackfillSuite extends CatsEffectSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-05T06:00:00Z")
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
    "background historical backfill starts after bootstrap without blocking ready diagnostics",
  ):
    val anchor  = finalizedSuggestion("80", 3L, validatorSet, validatorKeys)
    val genesis = genesisProposal("01")

    for
      started <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      archive <- HistoricalProposalArchive.inMemory[IO]
      suggestions <- Ref.of[IO, Map[
        PeerIdentity,
        Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]],
      ]](
        Map(session1.peer -> Right(Some(anchor))),
      )
      backfill <- HistoricalBackfillWorker.createWithNow[IO](
        policy = HistoricalBackfillPolicy(
          batchSize = 1,
          interBatchDelay = Duration.ofMillis(10L),
        ),
        historicalBackfill = gatedBackfillService(
          started = started,
          release = release,
          response = Right(Vector(genesis)),
        ),
        archive = archive,
        now = IO.pure(startedAt),
      )
      coordinator <- BootstrapCoordinator.createWithBackfill[IO](
        retryPolicy = BootstrapRetryPolicy.boundedDefault,
        validatorSetLookup = ValidatorSetLookup.static[IO](
          BootstrapTrustRoot.staticValidatorSet(validatorSet),
        ),
        finalizedAnchorSuggestions = suggestionService(suggestions),
        snapshotCoordinator = completedSnapshotCoordinator,
        proposalReplay = replayService(Vector.empty),
        readiness = ProposalCatchUpReadiness.static[IO](
          ProposalCatchUpAssessment(BootstrapVoteReadiness.Ready, None),
        ),
        forwardStore = ForwardCatchUpStore.noop[IO],
        historicalBackfill = backfill,
      )
      bootstrap <- coordinator.bootstrap(
        chainId,
        Vector(session1),
        startedAt,
        Vector.empty,
      )
      _ <- started.get
      running <- awaitValue(
        coordinator.current.map(_.historicalBackfill),
        attempts = 40,
      ):
        case HistoricalBackfillStatus.Running(
              _,
              HistoricalBackfillPriority.Background,
            ) =>
          true
        case _ =>
          false
      _ <- release.complete(()).void
      completed <- awaitValue(
        coordinator.current.map(_.historicalBackfill),
        attempts = 40,
      ):
        case HistoricalBackfillStatus.Completed("genesisReached", progress)
            if progress.fetchedProposalCount === 1L =>
          true
        case _ =>
          false
      diagnostics <- coordinator.current
    yield
      assertEquals(
        bootstrap.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )
      assertEquals(diagnostics.phase, BootstrapPhase.Ready)
      assertEquals(
        diagnostics.chains(chainId).voteReadiness,
        BootstrapVoteReadiness.Ready,
      )
      running match
        case HistoricalBackfillStatus.Running(progress, priority) =>
          assertEquals(priority, HistoricalBackfillPriority.Background)
          assertEquals(progress.anchor, anchor.snapshotAnchor)
          assertEquals(progress.nextBeforeHeight, anchor.anchorHeight)
          assertEquals(progress.fetchedProposalCount, 0L)
        case other =>
          fail("expected running backfill status but saw " + other.toString)
      completed match
        case HistoricalBackfillStatus.Completed(reason, progress) =>
          assertEquals(reason, "genesisReached")
          assertEquals(progress.fetchedProposalCount, 1L)
          assertEquals(progress.nextBeforeHeight, BlockHeight.Genesis)
        case other =>
          fail("expected completed backfill status but saw " + other.toString)

  test(
    "background historical backfill failure is isolated from ready bootstrap state",
  ):
    val anchor = finalizedSuggestion("90", 3L, validatorSet, validatorKeys)

    for
      suggestions <- Ref.of[IO, Map[
        PeerIdentity,
        Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]],
      ]](
        Map(session1.peer -> Right(Some(anchor))),
      )
      backfill <- HistoricalBackfillWorker.createWithNow[IO](
        policy = HistoricalBackfillPolicy(
          batchSize = 1,
          interBatchDelay = Duration.ofMillis(10L),
        ),
        historicalBackfill = failingBackfillService(
          CanonicalRejection.BackfillUnavailable(
            reason = "historicalBackfillUnavailable",
            detail = Some("missingArchive"),
          ),
        ),
        archive = HistoricalProposalArchive.noop[IO],
        now = IO.pure(startedAt),
      )
      coordinator <- BootstrapCoordinator.createWithBackfill[IO](
        retryPolicy = BootstrapRetryPolicy.boundedDefault,
        validatorSetLookup = ValidatorSetLookup.static[IO](
          BootstrapTrustRoot.staticValidatorSet(validatorSet),
        ),
        finalizedAnchorSuggestions = suggestionService(suggestions),
        snapshotCoordinator = completedSnapshotCoordinator,
        proposalReplay = replayService(Vector.empty),
        readiness = ProposalCatchUpReadiness.static[IO](
          ProposalCatchUpAssessment(BootstrapVoteReadiness.Ready, None),
        ),
        forwardStore = ForwardCatchUpStore.noop[IO],
        historicalBackfill = backfill,
      )
      bootstrap <- coordinator.bootstrap(
        chainId,
        Vector(session1),
        startedAt,
        Vector.empty,
      )
      failed <- awaitValue(
        coordinator.current.map(_.historicalBackfill),
        attempts = 40,
      ):
        case HistoricalBackfillStatus.Failed(
              "historicalBackfillUnavailable",
              Some("missingArchive"),
              _,
            ) =>
          true
        case _ =>
          false
      diagnostics <- coordinator.current
    yield
      assertEquals(
        bootstrap.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )
      assertEquals(diagnostics.phase, BootstrapPhase.Ready)
      assertEquals(
        diagnostics.chains(chainId).voteReadiness,
        BootstrapVoteReadiness.Ready,
      )
      assertEquals(diagnostics.lastFailure, None)
      failed match
        case HistoricalBackfillStatus.Failed(reason, detail, progress) =>
          assertEquals(reason, "historicalBackfillUnavailable")
          assertEquals(detail, Some("missingArchive"))
          assertEquals(progress.anchor, anchor.snapshotAnchor)
        case other =>
          fail("expected failed backfill status but saw " + other.toString)

  test(
    "archive-grade historical sync reports archive priority and archive source",
  ):
    val anchor  = finalizedSuggestion("95", 3L, validatorSet, validatorKeys)
    val genesis = genesisProposal("15")

    for
      started <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      archive <- HistoricalProposalArchive.inMemory[IO]
      worker <- HistoricalBackfillWorker.createWithNow[IO](
        policy = HistoricalBackfillPolicy.archiveDefault.copy(
          batchSize = 1,
          interBatchDelay = Duration.ofMillis(10L),
        ),
        historicalBackfill = gatedBackfillService(
          started = started,
          release = release,
          response = Right(Vector(genesis)),
        ),
        archive = archive,
        now = IO.pure(startedAt),
      )
      _ <- worker.start(
        chainId,
        Vector(session1),
        anchor.snapshotAnchor,
        startedAt,
      )
      _ <- started.get
      running <- awaitValue(worker.current, attempts = 40):
        case HistoricalBackfillStatus.Running(
              _,
              HistoricalBackfillPriority.Archive,
            ) =>
          true
        case _ =>
          false
      _ <- release.complete(()).void
      completed <- awaitValue(worker.current, attempts = 40):
        case HistoricalBackfillStatus.Completed("genesisReached", progress)
            if progress.fetchedProposalCount === 1L =>
          true
        case _ =>
          false
      archived <- archive.list(chainId)
    yield
      running match
        case HistoricalBackfillStatus.Running(progress, priority) =>
          assertEquals(priority, HistoricalBackfillPriority.Archive)
          assertEquals(progress.anchor, anchor.snapshotAnchor)
          assertEquals(progress.fetchedProposalCount, 0L)
        case other =>
          fail:
            "expected archive running backfill status but saw " + other.toString
      completed match
        case HistoricalBackfillStatus.Completed(reason, progress) =>
          assertEquals(reason, "genesisReached")
          assertEquals(progress.nextBeforeHeight, BlockHeight.Genesis)
        case other =>
          fail:
            "expected archive completed backfill status but saw " + other.toString
      assertEquals(
        archived.map(_.proposal.proposalId),
        Vector(genesis.proposalId),
      )
      assertEquals(
        archived.map(_.source),
        Vector(HistoricalArchiveSource.ArchiveSync),
      )

  test(
    "historical backfill policy maps validator and audit roles to their default sync modes",
  ):
    val validatorPolicy =
      HistoricalBackfillPolicy.forRole(LocalNodeRole.Validator)
    val auditPolicy = HistoricalBackfillPolicy.forRole(LocalNodeRole.Audit)
    val validatorOptOut =
      HistoricalBackfillPolicy.forRole(
        LocalNodeRole.Validator,
        enabled = false,
      )

    assertEquals(
      validatorPolicy.priority,
      HistoricalBackfillPriority.Background,
    )
    assertEquals(
      validatorPolicy.archiveSource,
      HistoricalArchiveSource.BackgroundBackfill,
    )
    assertEquals(validatorPolicy.enabled, true)
    assertEquals(auditPolicy.priority, HistoricalBackfillPriority.Archive)
    assertEquals(auditPolicy.archiveSource, HistoricalArchiveSource.ArchiveSync)
    assertEquals(auditPolicy.enabled, true)
    assertEquals(
      validatorOptOut.priority,
      HistoricalBackfillPriority.Background,
    )
    assertEquals(validatorOptOut.enabled, false)

  test(
    "historical backfill worker supports pause and resume while reporting progress to genesis",
  ):
    val anchor    = finalizedSuggestion("a0", 3L, validatorSet, validatorKeys)
    val genesis   = genesisProposal("11")
    val proposal1 = childProposal(genesis, "12", 1L)
    val proposal2 = childProposal(proposal1, "13", 2L)

    for
      responses <- Ref
        .of[IO, Vector[Either[CanonicalRejection, Vector[Proposal]]]](
          Vector(
            Right(Vector(proposal2)),
            Right(Vector(proposal1)),
            Right(Vector(genesis)),
          ),
        )
      archive <- HistoricalProposalArchive.inMemory[IO]
      worker <- HistoricalBackfillWorker.createWithNow[IO](
        policy = HistoricalBackfillPolicy(
          batchSize = 1,
          interBatchDelay = Duration.ofMillis(200L),
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
      firstProgress <- awaitValue(worker.current, attempts = 40):
        case HistoricalBackfillStatus.Running(progress, _)
            if progress.fetchedProposalCount === 1L &&
              progress.nextBeforeHeight === BlockHeight.unsafeFromLong(2L) =>
          true
        case _ =>
          false
      _ <- worker.pause("operatorPaused", startedAt.plusSeconds(1L))
      paused <- awaitValue(worker.current, attempts = 20):
        case HistoricalBackfillStatus.Paused("operatorPaused", progress, _)
            if progress.fetchedProposalCount === 1L =>
          true
        case _ =>
          false
      _ <- worker.resume(startedAt.plusSeconds(2L))
      completed <- awaitValue(worker.current, attempts = 60):
        case HistoricalBackfillStatus.Completed("genesisReached", progress)
            if progress.fetchedProposalCount === 3L =>
          true
        case _ =>
          false
    yield
      firstProgress match
        case HistoricalBackfillStatus.Running(progress, priority) =>
          assertEquals(priority, HistoricalBackfillPriority.Background)
          assertEquals(progress.nextBeforeBlockId, proposal2.targetBlockId)
          assertEquals(progress.fetchedProposalCount, 1L)
        case other =>
          fail("expected first running progress but saw " + other.toString)
      paused match
        case HistoricalBackfillStatus.Paused(reason, progress, priority) =>
          assertEquals(reason, "operatorPaused")
          assertEquals(priority, HistoricalBackfillPriority.Background)
          assertEquals(progress.fetchedProposalCount, 1L)
        case other =>
          fail("expected paused status but saw " + other.toString)
      completed match
        case HistoricalBackfillStatus.Completed(reason, progress) =>
          assertEquals(reason, "genesisReached")
          assertEquals(progress.nextBeforeHeight, BlockHeight.Genesis)
          assertEquals(progress.fetchedProposalCount, 3L)
        case other =>
          fail("expected completed status but saw " + other.toString)

  test(
    "historical backfill pause waits for in-flight archive writes before changing generation",
  ):
    val anchor    = finalizedSuggestion("b0", 3L, validatorSet, validatorKeys)
    val genesis   = genesisProposal("21")
    val proposal1 = childProposal(genesis, "22", 1L)
    val proposal2 = childProposal(proposal1, "23", 2L)

    for
      responses <- Ref
        .of[IO, Vector[Either[CanonicalRejection, Vector[Proposal]]]](
          Vector(Right(Vector(proposal2))),
        )
      archiveEntered <- Deferred[IO, Unit]
      archiveRelease <- Deferred[IO, Unit]
      innerArchive   <- HistoricalProposalArchive.inMemory[IO]
      archive = new HistoricalProposalArchive[IO]:
        override def close: IO[Unit] =
          IO.unit

        override def list(
            chainId: ChainId,
        ): IO[Vector[HistoricalArchiveEntry]] =
          innerArchive.list(chainId)

        override def contains(
            chainId: ChainId,
            proposalId: ProposalId,
        ): IO[Boolean] =
          innerArchive.contains(chainId, proposalId)

        override def putAll(
            chainId: ChainId,
            proposals: Vector[Proposal],
            source: HistoricalArchiveSource,
            storedAt: Instant,
        ): IO[Vector[ProposalId]] =
          archiveEntered.complete(()).attempt *> archiveRelease.get *>
            innerArchive.putAll(chainId, proposals, source, storedAt)

        override def removeAll(
            chainId: ChainId,
            proposalIds: Vector[ProposalId],
        ): IO[Int] =
          innerArchive.removeAll(chainId, proposalIds)
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
      _              <- archiveEntered.get
      pauseCompleted <- Ref.of[IO, Boolean](false)
      pauseFiber <- (worker.pause(
        "operatorPaused",
        startedAt.plusSeconds(1L),
      ) *> pauseCompleted.set(true)).start
      pauseBeforeRelease <- IO.sleep(100.millis) *> pauseCompleted.get
      _                  <- archiveRelease.complete(()).void
      _                  <- pauseFiber.joinWithNever
      paused <- awaitValue(worker.current, attempts = 20):
        case HistoricalBackfillStatus.Paused("operatorPaused", progress, _)
            if progress.fetchedProposalCount === 1L =>
          true
        case _ =>
          false
      archived <- innerArchive.list(chainId)
    yield
      assertEquals(pauseBeforeRelease, false)
      paused match
        case HistoricalBackfillStatus.Paused(reason, progress, priority) =>
          assertEquals(reason, "operatorPaused")
          assertEquals(priority, HistoricalBackfillPriority.Background)
          assertEquals(progress.fetchedProposalCount, 1L)
          assertEquals(progress.nextBeforeBlockId, proposal2.targetBlockId)
        case other =>
          fail("expected paused status but saw " + other.toString)
      assertEquals(
        archived.map(_.proposal.proposalId),
        Vector(proposal2.proposalId),
      )

  private def suggestionService(
      ref: Ref[IO, Map[PeerIdentity, Either[CanonicalRejection, Option[
        FinalizedAnchorSuggestion,
      ]]]],
  ): FinalizedAnchorSuggestionService[IO] =
    new FinalizedAnchorSuggestionService[IO]:
      override def bestFinalized(
          session: BootstrapSessionBinding,
          chainId: ChainId,
      ): IO[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
        ref.get.map(_.getOrElse(session.peer, Right(None)))

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

  private def gatedBackfillService(
      started: Deferred[IO, Unit],
      release: Deferred[IO, Unit],
      response: Either[CanonicalRejection, Vector[Proposal]],
  ): HistoricalBackfillService[IO] =
    new HistoricalBackfillService[IO]:
      override def readPrevious(
          session: BootstrapSessionBinding,
          chainId: ChainId,
          beforeBlockId: BlockId,
          beforeHeight: BlockHeight,
          limit: Int,
      ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
        started.complete(()).attempt *> release.get.as(response)

  private def failingBackfillService(
      rejection: CanonicalRejection,
  ): HistoricalBackfillService[IO] =
    new HistoricalBackfillService[IO]:
      override def readPrevious(
          session: BootstrapSessionBinding,
          chainId: ChainId,
          beforeBlockId: BlockId,
          beforeHeight: BlockHeight,
          limit: Int,
      ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
        IO.pure(Left(rejection))

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
      delay: scala.concurrent.duration.FiniteDuration = 25.millis,
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
      activeValidatorSet: ValidatorSet,
      keys: Vector[org.sigilaris.core.crypto.KeyPair],
  ): FinalizedAnchorSuggestion =
    val baseHeight = anchorHeight - 1L
    val bootstrapSubject = QuorumCertificateSubject(
      window = HotStuffWindow(
        chainId,
        baseHeight,
        baseHeight,
        activeValidatorSet.hash,
      ),
      proposalId = ProposalId(hex(seed + "01")),
      blockId = BlockId(hex(seed + "02")),
    )
    val bootstrapQc =
      QuorumCertificateAssembler
        .assemble(
          bootstrapSubject,
          quorumVotes(
            activeValidatorSet,
            keys,
            bootstrapSubject.window,
            bootstrapSubject.proposalId,
          ),
          activeValidatorSet,
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
              activeValidatorSet.hash,
            ),
            proposer = activeValidatorSet.members(0).id,
            targetBlockId = BlockHeader.computeId(anchorBlock),
            block = anchorBlock,
            txSet = ProposalTxSet.empty,
            justify = bootstrapQc,
          ),
          keys(0),
        )
        .toOption
        .get
    val child =
      childProposal(
        anchor,
        seed + "20",
        anchorHeight + 1L,
        activeValidatorSet,
        keys,
      )
    val grandchild =
      childProposal(
        child,
        seed + "30",
        anchorHeight + 2L,
        activeValidatorSet,
        keys,
      )

    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

  private def genesisProposal(
      seed: String,
      activeValidatorSet: ValidatorSet = validatorSet,
      keys: Vector[org.sigilaris.core.crypto.KeyPair] = validatorKeys,
  ): Proposal =
    val bootstrapSubject = QuorumCertificateSubject(
      window = HotStuffWindow(chainId, 0L, 0L, activeValidatorSet.hash),
      proposalId = ProposalId(hex(seed + "01")),
      blockId = BlockId(hex(seed + "02")),
    )
    val bootstrapQc =
      QuorumCertificateAssembler
        .assemble(
          bootstrapSubject,
          quorumVotes(
            activeValidatorSet,
            keys,
            bootstrapSubject.window,
            bootstrapSubject.proposalId,
          ),
          activeValidatorSet,
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
          window = HotStuffWindow(chainId, 0L, 0L, activeValidatorSet.hash),
          proposer = activeValidatorSet.members(0).id,
          targetBlockId = BlockHeader.computeId(genesisBlock),
          block = genesisBlock,
          txSet = ProposalTxSet.empty,
          justify = bootstrapQc,
        ),
        keys(0),
      )
      .toOption
      .get

  private def childProposal(
      parentProposal: Proposal,
      seed: String,
      height: Long,
      activeValidatorSet: ValidatorSet = validatorSet,
      keys: Vector[org.sigilaris.core.crypto.KeyPair] = validatorKeys,
  ): Proposal =
    val blockHeader =
      BlockHeader(
        parent = Some(parentProposal.targetBlockId),
        height = BlockHeight.unsafeFromLong(height),
        stateRoot = StateRoot(hex(seed + "02")),
        bodyRoot = BodyRoot(hex(seed + "03")),
        timestamp =
          BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli + height),
      )
    Proposal
      .sign(
        UnsignedProposal(
          window =
            HotStuffWindow(chainId, height, height, activeValidatorSet.hash),
          proposer = activeValidatorSet.members((height.toInt % 3).min(2)).id,
          targetBlockId = BlockHeader.computeId(blockHeader),
          block = blockHeader,
          txSet = ProposalTxSet.empty,
          justify = qcFor(activeValidatorSet, keys, parentProposal),
        ),
        keys((height.toInt % 3).min(2)),
      )
      .toOption
      .get

  private def qcFor(
      activeValidatorSet: ValidatorSet,
      keys: Vector[org.sigilaris.core.crypto.KeyPair],
      proposal: Proposal,
  ): QuorumCertificate =
    QuorumCertificateAssembler
      .assemble(
        QuorumCertificateSubject(
          window = proposal.window,
          proposalId = proposal.proposalId,
          blockId = proposal.targetBlockId,
        ),
        quorumVotes(
          activeValidatorSet,
          keys,
          proposal.window,
          proposal.proposalId,
        ),
        activeValidatorSet,
      )
      .toOption
      .get

  private def quorumVotes(
      activeValidatorSet: ValidatorSet,
      keys: Vector[org.sigilaris.core.crypto.KeyPair],
      window: HotStuffWindow,
      proposalId: ProposalId,
  ): Vector[Vote] =
    Vector(0, 1, 2).map: index =>
      Vote
        .sign(
          UnsignedVote(
            window = window,
            voter = activeValidatorSet.members(index).id,
            targetProposalId = proposalId,
          ),
          keys(index),
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

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
