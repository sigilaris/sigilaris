package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import org.sigilaris.core.application.scheduling.{
  ConflictFootprint,
  SchedulingClassification,
}
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.{CryptoOps, Hash}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockHeight,
  BlockRecord,
  BlockStore,
  BlockTimestamp,
  BlockView,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.gossip.{
  CanonicalRejection,
  ChainId,
  ControlOp,
  DirectionalSessionId,
  PeerIdentity,
  StableArtifactId,
}
import org.sigilaris.node.jvm.runtime.gossip.tx.TxRuntimePolicy

final class HotStuffBootstrapCoordinatorSuite extends CatsEffectSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-05T04:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )
  private val peer1 = session("11111111-1111-4111-8111-111111111111", "node-b")
  private val peer2 = session("22222222-2222-4222-8222-222222222222", "node-c")

  private final case class TestTx(
      body: Utf8,
  ) derives ByteEncoder

  private given Hash[TestTx] = Hash.build

  test(
    "bootstrap coordinator selects the highest verified anchor and keeps the pinned anchor when discovery later sees a newer tip",
  ):
    val lowerAnchor = finalizedSuggestion("10", 2L, validatorSet, validatorKeys)
    val pinnedAnchor =
      finalizedSuggestion("20", 4L, validatorSet, validatorKeys)
    val newerTip = finalizedSuggestion("30", 6L, validatorSet, validatorKeys)

    for
      suggestions <- Ref.of[IO, Map[
        PeerIdentity,
        Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]],
      ]](
        Map(
          peer1.peer -> Right(Some(lowerAnchor)),
          peer2.peer -> Right(Some(pinnedAnchor)),
        ),
      )
      coordinator <- BootstrapCoordinator.create[IO](
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
      )
      firstRun <- coordinator.bootstrap(
        chainId,
        Vector(peer1, peer2),
        startedAt,
        Vector.empty,
      )
      _ <- suggestions.set(
        Map(
          peer1.peer -> Right(Some(newerTip)),
          peer2.peer -> Right(Some(pinnedAnchor)),
        ),
      )
      refreshed <- coordinator.discover(
        chainId,
        Vector(peer1, peer2),
        startedAt.plusSeconds(30L),
      )
      diagnostics <- coordinator.current
    yield
      assertEquals(
        firstRun.map(_.anchor.snapshotAnchor),
        Right(pinnedAnchor.snapshotAnchor),
      )
      assertEquals(
        refreshed.map(_.map(_.snapshotAnchor)),
        Right(Some(newerTip.snapshotAnchor)),
      )
      assertEquals(diagnostics.phase, BootstrapPhase.Ready)
      assertEquals(
        diagnostics.chains(chainId).bestFinalized,
        Some(newerTip.snapshotAnchor),
      )
      assertEquals(
        diagnostics.chains(chainId).selectedAnchor,
        Some(pinnedAnchor.snapshotAnchor),
      )
      assertEquals(
        diagnostics.chains(chainId).pinnedAnchor,
        Some(pinnedAnchor.snapshotAnchor),
      )

  test(
    "bootstrap coordinator keeps discovery diagnostics and bounded backoff when no candidate is verifiable",
  ):
    val otherKeys = Vector.fill(4)(CryptoOps.generate())
    val otherValidatorSet = ValidatorSet.unsafe(
      otherKeys.zipWithIndex.map: (keyPair, index) =>
        ValidatorMember(
          id = ValidatorId.unsafe(s"other-validator-${index + 1}"),
          publicKey = keyPair.publicKey,
        ),
    )
    val invalidAnchor =
      finalizedSuggestion("40", 3L, otherValidatorSet, otherKeys)

    for
      suggestions <- Ref.of[IO, Map[
        PeerIdentity,
        Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]],
      ]](
        Map(
          peer1.peer -> Right(None),
          peer2.peer -> Right(Some(invalidAnchor)),
        ),
      )
      coordinator <- BootstrapCoordinator.create[IO](
        retryPolicy = BootstrapRetryPolicy(
          baseDelay = Duration.ofSeconds(5L),
          maxDelay = Duration.ofSeconds(30L),
        ),
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
      )
      discovered <- coordinator.discover(
        chainId,
        Vector(peer1, peer2),
        startedAt,
      )
      diagnostics <- coordinator.current
    yield
      assertEquals(discovered, Right(None))
      assertEquals(diagnostics.phase, BootstrapPhase.Discovery)
      assertEquals(diagnostics.retryAttempts, 1)
      assertEquals(diagnostics.nextRetryAt, Some(startedAt.plusSeconds(5L)))
      assertEquals(diagnostics.lastFailure, Some("noVerifiableFinalizedAnchor"))
      assertEquals(diagnostics.chains(chainId).bestFinalized, None)

  test(
    "forward catch-up replays the contiguous prefix first, holds votes for missing tx payloads, and becomes ready once txs are available",
  ):
    val anchor = finalizedSuggestion("50", 2L, validatorSet, validatorKeys)
    val proposal1 =
      childProposal(anchor.proposal, "51", 3L, Vector(TestTx(Utf8("tx-1"))))
    val proposal2 =
      childProposal(proposal1, "52", 4L, Vector(TestTx(Utf8("tx-2"))))
    val proposal3 = childProposal(proposal2, "53", 5L, Vector.empty)
    val idempotencyKeys =
      Map(
        proposal1.proposalId -> "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        proposal2.proposalId -> "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        proposal3.proposalId -> "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      )

    val tx1Id = proposal1.txSet.txIds.head
    val tx2Id = proposal2.txSet.txIds.head

    def idempotencyKeyFor(
        proposal: Proposal,
    ): String =
      idempotencyKeys(proposal.proposalId)

    for
      knownTxIds <- Ref.of[IO, Set[StableArtifactId]](Set(tx1Id))
      blockStore <- BlockStore.inMemory[IO, TestTx, Utf8, Utf8]
      _ <- putProposalView(blockStore, proposal1, Vector(TestTx(Utf8("tx-1"))))
      _ <- putProposalView(blockStore, proposal2, Vector(TestTx(Utf8("tx-2"))))
      _ <- putProposalView(blockStore, proposal3, Vector.empty)
      readiness = ProposalCatchUpReadiness
        .fromBlockQuery[IO, TestTx, Utf8, Utf8](
          validatorSet = validatorSet,
          knownTxIds = knownTxIds.get,
          blockQuery = blockStore,
          txPolicy = TxRuntimePolicy(),
          idempotencyKeyFor = idempotencyKeyFor,
        )(_ => schedulable())
      firstPass <- HotStuffForwardCatchUp.plan(
        anchor = anchor,
        replayed = Vector(proposal1, proposal2),
        live = Vector(proposal3),
        readiness = readiness,
      )
      _ <- knownTxIds.update(_ + tx2Id)
      secondPass <- HotStuffForwardCatchUp.plan(
        anchor = anchor,
        replayed = Vector(proposal1, proposal2),
        live = Vector(proposal3),
        readiness = readiness,
      )
    yield
      assertEquals(
        firstPass.map(_.applied.map(_.proposalId)),
        Right(Vector(proposal1.proposalId)),
      )
      assertEquals(
        firstPass.map(_.queued.map(_.proposalId)),
        Right(Vector(proposal2.proposalId, proposal3.proposalId)),
      )
      assertEquals(
        firstPass.map(_.voteReadiness),
        Right(BootstrapVoteReadiness.Held("missingTxPayload")),
      )
      assertEquals(
        firstPass.map(_.controlBatches.flatMap(_.ops)),
        Right(
          Vector(
            ControlOp.SetKnownTx(chainId, proposal2.txSet.txIds),
            ControlOp.RequestByIdTx(chainId, Vector(tx2Id)),
          ),
        ),
      )
      assertEquals(
        secondPass.map(_.applied.map(_.proposalId)),
        Right(
          Vector(
            proposal1.proposalId,
            proposal2.proposalId,
            proposal3.proposalId,
          ),
        ),
      )
      assertEquals(secondPass.map(_.queued), Right(Vector.empty))
      assertEquals(
        secondPass.map(_.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )

  test(
    "bootstrap coordinator pages replay batches beyond the first replay window",
  ):
    val anchor = finalizedSuggestion("60", 2L, validatorSet, validatorKeys)
    val replayed =
      proposalChain(anchor.proposal, startSeed = 0x6000, length = 300)

    for
      suggestions <- Ref.of[IO, Map[
        PeerIdentity,
        Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]],
      ]](
        Map(peer1.peer -> Right(Some(anchor))),
      )
      coordinator <- BootstrapCoordinator.create[IO](
        retryPolicy = BootstrapRetryPolicy.boundedDefault,
        validatorSetLookup = ValidatorSetLookup.static[IO](
          BootstrapTrustRoot.staticValidatorSet(validatorSet),
        ),
        finalizedAnchorSuggestions = suggestionService(suggestions),
        snapshotCoordinator = completedSnapshotCoordinator,
        proposalReplay = replayService(replayed),
        readiness = ProposalCatchUpReadiness.static[IO](
          ProposalCatchUpAssessment(BootstrapVoteReadiness.Ready, None),
        ),
        forwardStore = ForwardCatchUpStore.noop[IO],
      )
      result <- coordinator.bootstrap(
        chainId,
        Vector(peer1),
        startedAt,
        Vector.empty,
      )
    yield
      assertEquals(
        result.map(_.forwardCatchUp.applied.size),
        Right(replayed.size),
      )
      assertEquals(result.map(_.forwardCatchUp.queued), Right(Vector.empty))
      assertEquals(
        result.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )

  test(
    "bootstrap coordinator ignores stale replay branches once a contiguous highest prefix is available",
  ):
    val anchor = finalizedSuggestion("65", 2L, validatorSet, validatorKeys)
    val replayed =
      proposalChain(anchor.proposal, startSeed = 0xff00, length = 300)
    val peer1Noise =
      (0 until 256).toVector.map: index =>
        unrelatedProposal(
          justifyProposal = anchor.proposal,
          seed = f"${0x6500 + index}%04x",
          height = 3L + index.toLong,
        )
    val peer2PrefixNoise =
      (0 until 256).toVector.map: index =>
        unrelatedProposal(
          justifyProposal = anchor.proposal,
          seed = f"${0x7500 + index}%04x",
          height = 3L,
        )

    for
      suggestions <- Ref.of[IO, Map[
        PeerIdentity,
        Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]],
      ]](
        Map(
          peer1.peer -> Right(Some(anchor)),
          peer2.peer -> Right(Some(anchor)),
        ),
      )
      coordinator <- BootstrapCoordinator.create[IO](
        retryPolicy = BootstrapRetryPolicy.boundedDefault,
        validatorSetLookup = ValidatorSetLookup.static[IO](
          BootstrapTrustRoot.staticValidatorSet(validatorSet),
        ),
        finalizedAnchorSuggestions = suggestionService(suggestions),
        snapshotCoordinator = completedSnapshotCoordinator,
        proposalReplay = replayServiceByPeer(
          Map(
            peer1.peer -> peer1Noise,
            peer2.peer -> (peer2PrefixNoise ++ replayed),
          ),
        ),
        readiness = ProposalCatchUpReadiness.static[IO](
          ProposalCatchUpAssessment(BootstrapVoteReadiness.Ready, None),
        ),
        forwardStore = ForwardCatchUpStore.noop[IO],
      )
      result <- coordinator.bootstrap(
        chainId,
        Vector(peer1, peer2),
        startedAt,
        Vector.empty,
      )
    yield
      assertEquals(
        result.map(_.forwardCatchUp.applied.map(_.proposalId)),
        Right(replayed.map(_.proposalId)),
      )
      assertEquals(result.map(_.forwardCatchUp.queued), Right(Vector.empty))
      assertEquals(
        result.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )

  test(
    "forward catch-up keeps proposalReplayGap when no contiguous child is reachable from the anchor",
  ):
    val anchor    = finalizedSuggestion("68", 2L, validatorSet, validatorKeys)
    val proposal1 = childProposal(anchor.proposal, "69", 3L, Vector.empty)
    val proposal2 = childProposal(proposal1, "6a", 4L, Vector.empty)

    for result <- HotStuffForwardCatchUp.plan(
        anchor = anchor,
        replayed = Vector(proposal2),
        live = Vector.empty,
        readiness = ProposalCatchUpReadiness.ready[IO],
      )
    yield
      assertEquals(result.map(_.applied), Right(Vector.empty))
      assertEquals(
        result.map(_.queued.map(_.proposalId)),
        Right(Vector(proposal2.proposalId)),
      )
      assertEquals(
        result.map(_.voteReadiness),
        Right(BootstrapVoteReadiness.Held("proposalReplayGap")),
      )

  test(
    "forward catch-up recovers once the proposal block view becomes locally available",
  ):
    val anchor    = finalizedSuggestion("70", 2L, validatorSet, validatorKeys)
    val proposal1 = childProposal(anchor.proposal, "71", 3L, Vector.empty)

    for
      blockStore <- BlockStore.inMemory[IO, TestTx, Utf8, Utf8]
      readiness = ProposalCatchUpReadiness
        .fromBlockQuery[IO, TestTx, Utf8, Utf8](
          validatorSet = validatorSet,
          knownTxIds = IO.pure(Set.empty[StableArtifactId]),
          blockQuery = blockStore,
          txPolicy = TxRuntimePolicy(),
          idempotencyKeyFor = _ => "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        )(_ => schedulable())
      firstPass <- HotStuffForwardCatchUp.plan(
        anchor = anchor,
        replayed = Vector(proposal1),
        live = Vector.empty,
        readiness = readiness,
      )
      _ <- putProposalView(blockStore, proposal1, Vector.empty)
      secondPass <- HotStuffForwardCatchUp.plan(
        anchor = anchor,
        replayed = Vector(proposal1),
        live = Vector.empty,
        readiness = readiness,
      )
    yield
      assertEquals(
        firstPass.map(_.voteReadiness),
        Right(BootstrapVoteReadiness.Held("proposalViewUnavailable")),
      )
      assertEquals(
        firstPass.map(_.queued.map(_.proposalId)),
        Right(Vector(proposal1.proposalId)),
      )
      assertEquals(
        secondPass.map(_.applied.map(_.proposalId)),
        Right(Vector(proposal1.proposalId)),
      )
      assertEquals(
        secondPass.map(_.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
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
          anchorBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
          nextHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
          limit: Int,
      ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
        // Test helper for replay window sizing and contiguous merge behavior.
        // Anchor lineage filtering is intentionally not modeled here.
        IO.pure(
          Right(
            proposals
              .filter(proposal =>
                Ordering[BlockHeight].gteq(proposal.block.height, nextHeight),
              )
              .sortBy(proposal =>
                (proposal.block.height, proposal.proposalId.toHexLower),
              )
              .take(limit.max(0)),
          ),
        )

  private def replayServiceByPeer(
      proposalsByPeer: Map[PeerIdentity, Vector[Proposal]],
  ): ProposalReplayService[IO] =
    new ProposalReplayService[IO]:
      override def readNext(
          session: BootstrapSessionBinding,
          chainId: ChainId,
          anchorBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
          nextHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
          limit: Int,
      ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
        // This variant keeps the same simplification as replayService above and
        // only varies responses by peer so paging/merge behavior can be isolated.
        IO.pure(
          Right(
            proposalsByPeer
              .getOrElse(session.peer, Vector.empty)
              .filter(proposal =>
                Ordering[BlockHeight].gteq(proposal.block.height, nextHeight),
              )
              .sortBy(proposal =>
                (proposal.block.height, proposal.proposalId.toHexLower),
              )
              .take(limit.max(0)),
          ),
        )

  private def putProposalView(
      blockStore: BlockStore[IO, TestTx, Utf8, Utf8],
      proposal: Proposal,
      txs: Vector[TestTx],
  ): IO[Unit] =
    val records =
      txs.zipWithIndex.map: (tx, _) =>
        BlockRecord[TestTx, Utf8, Utf8](
          tx = tx,
          result = Option.empty[Utf8],
          events = Vector.empty[Utf8],
        )
    val actualBody       = BlockBody[TestTx, Utf8, Utf8](records.toSet)
    val computedBodyRoot = BlockBody.computeBodyRoot(actualBody).toOption.get
    val view =
      BlockView(
        header = proposal.block.copy(bodyRoot = computedBodyRoot),
        body = actualBody,
      )
    blockStore
      .putView(view)
      .value
      .flatMap:
        case Left(error) =>
          IO.raiseError(new IllegalStateException(error.reason))
        case Right(_) => IO.unit

  private def schedulable(): SchedulingClassification =
    SchedulingClassification.Schedulable(ConflictFootprint.empty)

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
        Vector.empty,
        activeValidatorSet,
        keys,
      )
    val grandchild =
      childProposal(
        child,
        seed + "30",
        anchorHeight + 2L,
        Vector.empty,
        activeValidatorSet,
        keys,
      )

    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

  private def childProposal(
      parentProposal: Proposal,
      seed: String,
      height: Long,
      txs: Vector[TestTx],
      activeValidatorSet: ValidatorSet = validatorSet,
      keys: Vector[org.sigilaris.core.crypto.KeyPair] = validatorKeys,
  ): Proposal =
    val txSet = ProposalTxSet.fromTxs(txs)
    val bodyRoot =
      BlockBody
        .computeBodyRoot(
          BlockBody[TestTx, Utf8, Utf8](
            txs.zipWithIndex
              .map: (tx, _) =>
                BlockRecord[TestTx, Utf8, Utf8](
                  tx = tx,
                  result = Option.empty[Utf8],
                  events = Vector.empty[Utf8],
                )
              .toSet,
          ),
        )
        .toOption
        .get
    val blockHeader =
      BlockHeader(
        parent = Some(parentProposal.targetBlockId),
        height = BlockHeight.unsafeFromLong(height),
        stateRoot = StateRoot(hex(seed + "02")),
        bodyRoot = bodyRoot,
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
          txSet = txSet,
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
      parent: Option[org.sigilaris.node.jvm.runtime.block.BlockId],
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

  private def proposalChain(
      parentProposal: Proposal,
      startSeed: Int,
      length: Int,
  ): Vector[Proposal] =
    (0 until length)
      .foldLeft((Vector.empty[Proposal], parentProposal)):
        case ((acc, parent), index) =>
          val height = parent.block.height.toBigNat.toBigInt.toLong + 1L
          val proposal =
            childProposal(
              parentProposal = parent,
              seed = f"${startSeed + index}%04x",
              height = height,
              txs = Vector.empty,
            )
          (acc :+ proposal, proposal)
      ._1

  private def unrelatedProposal(
      justifyProposal: Proposal,
      seed: String,
      height: Long,
      activeValidatorSet: ValidatorSet = validatorSet,
      keys: Vector[org.sigilaris.core.crypto.KeyPair] = validatorKeys,
  ): Proposal =
    val blockHeader =
      block(
        parent = Some(BlockId(hex(seed + "90"))),
        height = height,
        stateRootHex = seed + "91",
        bodyRootHex = seed + "92",
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
          justify = qcFor(activeValidatorSet, keys, justifyProposal),
        ),
        keys((height.toInt % 3).min(2)),
      )
      .toOption
      .get
