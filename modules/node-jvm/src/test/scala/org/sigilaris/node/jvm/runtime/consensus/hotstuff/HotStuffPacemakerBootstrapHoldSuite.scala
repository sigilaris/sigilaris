package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}

import scala.util.Using

import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.application.scheduling.{
  ConflictFootprint,
  SchedulingClassification,
}
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.storage.swaydb.StorageLayout

final class HotStuffPacemakerBootstrapHoldSuite extends CatsEffectSuite:
  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-02T00:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorIds =
    validatorKeys.indices.toVector.map(index =>
      ValidatorId.unsafe(s"validator-${index + 1}"),
    )

  private final case class TestTx(
      body: Utf8,
  ) derives ByteEncoder
  private given Hash[TestTx] = Hash.build

  test(
    "automatic pacemaker suppresses timeout emission while bootstrap vote-readiness is held and releases it once ready",
  ):
    tempDirResource.use: root =>
      val merkleRoot =
        MerkleTrieNode.branch(
          ByteVector.empty.toNibbles,
          MerkleTrieNode.Children.empty,
        )
      val merkleRootHash = merkleRoot.toHash
      val anchor =
        replayableSuggestion(
          seed = "f1",
          stateRoot = StateRoot(merkleRootHash.toUInt256),
          childTxs = Vector(TestTx(Utf8("tx-1"))),
          grandchildTxs = Vector(TestTx(Utf8("tx-2"))),
        )
      val child          = anchor.finalizedProof.child
      val grandchild     = anchor.finalizedProof.grandchild
      val childTx        = TestTx(Utf8("tx-1"))
      val grandchildTx   = TestTx(Utf8("tx-2"))
      val grandchildTxId = grandchild.txSet.txIds.head
      val localValidatorKey =
        HotStuffPacemakerKey(chainId, validatorIds.head)

      for
        knownTxIds <- Ref.of[IO, Set[StableArtifactId]](
          Set(child.txSet.txIds.head),
        )
        blockStore <- BlockStore.inMemory[IO, TestTx, Utf8, Utf8]
        _          <- putProposalView(blockStore, child, Vector(childTx))
        _     <- putProposalView(blockStore, grandchild, Vector(grandchildTx))
        clock <- TestClock.create(startedAt)
        topology = staticTopology("node-a", Vector("node-b"))
        bootstrapEither <- HotStuffRuntimeBootstrap
          .fromTopology[IO](
            topology = topology,
            transportAuth = StaticPeerTransportAuth.testing(topology),
            consensusConfig = validatorConfig(),
            clock = clock,
            bootstrapTransport = Some(
              proposalTransport(
                anchor = anchor,
                replayed = Vector(child, grandchild),
                snapshotRoot = merkleRootHash -> merkleRoot,
                proposalCatchUpReadiness = Some(
                  HotStuffRuntimeBootstrap
                    .proposalCatchUpReadinessFromBlockQuery[
                      IO,
                      TestTx,
                      Utf8,
                      Utf8,
                    ](
                      validatorSet = validatorSet,
                      knownTxIds = knownTxIds.get,
                      blockQuery = blockStore,
                    )(_ => schedulable()),
                ),
              ),
            ),
            storageLayout = StorageLayout.fromRoot(root),
          )
          .use(IO.pure)
        bootstrap <- IO.fromEither(
          bootstrapEither.leftMap(new IllegalArgumentException(_)),
        )
        session =
          BootstrapSessionBinding(
            peer = PeerIdentity.unsafe("node-b"),
            sessionId = DirectionalSessionId
              .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
              .toOption
              .get,
          )
        first <- bootstrap.consensus.bootstrap(
          chainId = chainId,
          sessions = Vector(session),
          startedAt = startedAt,
          liveProposals = Vector.empty,
        )
        proposalEvent <- bootstrap.consensus.services.publisher.append(
          HotStuffGossipArtifact.ProposalArtifact(grandchild),
          startedAt.plusSeconds(10L),
        )
        _ <- bootstrap.consensus.sink
          .applyEvent(proposalEvent)
          .flatMap(result =>
            IO.fromEither(
              result.leftMap(rejection =>
                new IllegalStateException(rejection.reason),
              ),
            ).void,
          )
        _ <- clock.advance(
          HotStuffPacemakerPolicy.default.baseTimeout.plus(
            Duration.ofSeconds(1L),
          ),
        )
        heldTimeoutVotes <- bootstrap.consensus.source
          .readAfter(chainId, GossipTopic.consensusTimeoutVote, None)
          .flatMap(result =>
            IO.fromEither(
              result.leftMap(rejection =>
                new IllegalStateException(rejection.toString),
              ),
            ),
          )
        heldPacemaker <- bootstrap.consensus.currentPacemakerSnapshot
        _             <- knownTxIds.update(_ + grandchildTxId)
        second <- bootstrap.consensus.bootstrap(
          chainId = chainId,
          sessions = Vector(session),
          startedAt = startedAt.plusSeconds(2L),
          liveProposals = Vector.empty,
        )
        _ <- clock.advance(
          HotStuffPacemakerPolicy.default.baseTimeout.plus(
            Duration.ofSeconds(1L),
          ),
        )
        readyTimeoutVotes <- bootstrap.consensus.source
          .readAfter(chainId, GossipTopic.consensusTimeoutVote, None)
          .flatMap(result =>
            IO.fromEither(
              result.leftMap(rejection =>
                new IllegalStateException(rejection.toString),
              ),
            ),
          )
        readyPacemaker <- bootstrap.consensus.currentPacemakerSnapshot
      yield
        assertEquals(
          first.map(_.forwardCatchUp.voteReadiness),
          Right(BootstrapVoteReadiness.Held("missingTxPayload")),
        )
        assertEquals(heldTimeoutVotes.map(_.event.payload), Vector.empty)
        assertEquals(
          heldPacemaker
            .flatMap(_.entries.get(localValidatorKey))
            .flatMap(_.state.map(_.activeWindow)),
          Some(grandchild.window),
        )
        assertEquals(
          heldPacemaker
            .flatMap(_.entries.get(localValidatorKey))
            .flatMap(_.proposalEligibility),
          Some(
            HotStuffPacemakerProposalEligibility.BootstrapHeld(
              reason = "missingTxPayload",
              expectedLeader = validatorIds(3),
            ),
          ),
        )
        assertEquals(
          second.map(_.forwardCatchUp.voteReadiness),
          Right(BootstrapVoteReadiness.Ready),
        )
        assertEquals(
          readyTimeoutVotes
            .map(available => timeoutVotePayload(available.event).voter)
            .toSet,
          validatorIds.take(3).toSet,
        )
        assert(
          readyPacemaker
            .flatMap(_.entries.get(localValidatorKey))
            .flatMap(_.state.map(_.activeWindow))
            .exists(window =>
              window.height > grandchild.window.height ||
                (window.height === grandchild.window.height &&
                  window.view >= HotStuffView.unsafeFromLong(4L)),
            ),
        )

  override def afterAll(): Unit =
    val _ = HistoricalProposalArchive.resetSharedStoresForTesting.attempt
      .unsafeRunSync()
    super.afterAll()

  private def validatorSet: ValidatorSet =
    ValidatorSet.unsafe(
      validatorKeys.zipWithIndex.map: (keyPair, index) =>
        ValidatorMember(
          id = validatorIds(index),
          publicKey = keyPair.publicKey,
        ),
    )

  private def validatorConfig(): HotStuffBootstrapConfig =
    HotStuffBootstrapConfig(
      role = LocalNodeRole.Validator,
      validatorSet = validatorSet,
      holders = validatorHolders(),
      localKeys = Map(
        validatorIds(0) -> validatorKeys(0),
        validatorIds(1) -> validatorKeys(1),
        validatorIds(2) -> validatorKeys(2),
      ),
    )

  private def validatorHolders(): Vector[ValidatorKeyHolder] =
    Vector(
      ValidatorKeyHolder(
        validatorIds(0),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(1),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(2),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(3),
        PeerIdentity.unsafe("node-b"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

  private def staticTopology(
      localPeer: String,
      peers: Vector[String],
  ): StaticPeerTopology =
    StaticPeerTopology
      .parse(
        localNodeIdentity = localPeer,
        knownPeers = peers.toList,
        directNeighbors = peers.toList,
      )
      .toOption
      .get

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
        UnsignedVote(window, validatorIds(index), proposalId),
        validatorKeys(index),
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
        Vector(
          signedVoteFor(proposal.window, proposal.proposalId, 0),
          signedVoteFor(proposal.window, proposal.proposalId, 1),
          signedVoteFor(proposal.window, proposal.proposalId, 2),
        ),
        validatorSet,
      )
      .toOption
      .get

  private def replayableSuggestion(
      seed: String,
      stateRoot: StateRoot,
      childTxs: Vector[TestTx],
      grandchildTxs: Vector[TestTx],
  ): FinalizedAnchorSuggestion =
    val anchor =
      signedReplayProposal(
        parent = Some(bootstrapQc().subject.blockId),
        height = 1L,
        proposerIndex = 0,
        justify = bootstrapQc(),
        stateRoot = stateRoot,
        txs = Vector.empty,
        bodyHexFallback = seed + "10",
      )
    val child =
      signedReplayProposal(
        parent = Some(anchor.targetBlockId),
        height = 2L,
        proposerIndex = 1,
        justify = qcFor(anchor),
        stateRoot = stateRoot,
        txs = childTxs,
        bodyHexFallback = seed + "20",
      )
    val grandchild =
      signedReplayProposal(
        parent = Some(child.targetBlockId),
        height = 3L,
        proposerIndex = 2,
        justify = qcFor(child),
        stateRoot = stateRoot,
        txs = grandchildTxs,
        bodyHexFallback = seed + "30",
      )
    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

  private def signedReplayProposal(
      parent: Option[BlockId],
      height: Long,
      proposerIndex: Int,
      justify: QuorumCertificate,
      stateRoot: StateRoot,
      txs: Vector[TestTx],
      bodyHexFallback: String,
  ): Proposal =
    val header =
      txs match
        case Vector() =>
          block(
            parent = parent,
            height = height,
            stateRoot = stateRoot,
            bodyHex = bodyHexFallback,
          )
        case _ =>
          val body     = blockBodyOf(txs)
          val bodyRoot = BlockBody.computeBodyRoot(body).toOption.get
          BlockHeader(
            parent = parent,
            height = BlockHeight.unsafeFromLong(height),
            stateRoot = stateRoot,
            bodyRoot = bodyRoot,
            timestamp = BlockTimestamp.unsafeFromEpochMillis(
              startedAt.toEpochMilli + height,
            ),
          )
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, height, height, validatorSet.hash),
          proposer = validatorIds(proposerIndex),
          targetBlockId = BlockHeader.computeId(header),
          block = header,
          txSet = ProposalTxSet.fromTxs(txs),
          justify = justify,
        ),
        validatorKeys(proposerIndex),
      )
      .toOption
      .get

  private def block(
      parent: Option[BlockId],
      height: Long,
      stateRoot: StateRoot,
      bodyHex: String,
  ): BlockHeader =
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = stateRoot,
      bodyRoot = BodyRoot(hex(bodyHex)),
      timestamp =
        BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli + height),
    )

  private def blockBodyOf(
      txs: Vector[TestTx],
  ): BlockBody[TestTx, Utf8, Utf8] =
    BlockBody(
      txs
        .map(tx =>
          BlockRecord[TestTx, Utf8, Utf8](
            tx = tx,
            result = None,
            events = Vector.empty,
          ),
        )
        .toSet,
    )

  private def putProposalView(
      blockStore: BlockStore[IO, TestTx, Utf8, Utf8],
      proposal: Proposal,
      txs: Vector[TestTx],
  ): IO[Unit] =
    blockStore
      .putView(
        BlockView(
          header = proposal.block,
          body = blockBodyOf(txs),
        ),
      )
      .value
      .flatMap:
        case Left(error) =>
          IO.raiseError(new IllegalStateException(error.reason))
        case Right(_) =>
          IO.unit

  private def proposalTransport(
      anchor: FinalizedAnchorSuggestion,
      replayed: Vector[Proposal],
      snapshotRoot: (MerkleTrieNode.MerkleHash, MerkleTrieNode),
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[IO]],
  ): HotStuffBootstrapTransportServices[IO] =
    val (rootHash, root) = snapshotRoot
    HotStuffBootstrapTransportServices[IO](
      finalizedAnchorSuggestions = new FinalizedAnchorSuggestionService[IO]:
        override def bestFinalized(
            session: BootstrapSessionBinding,
            chainId: ChainId,
        ): IO[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
          IO.pure(Right(Some(anchor)))
      ,
      snapshotNodeFetch = new SnapshotNodeFetchService[IO]:
        override def fetchNodes(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            stateRoot: StateRoot,
            hashes: Vector[MerkleTrieNode.MerkleHash],
        ): IO[Either[CanonicalRejection, Vector[SnapshotTrieNode]]] =
          IO.pure(
            Right(
              hashes
                .filter(_ === rootHash)
                .map(_ => SnapshotTrieNode(rootHash, root)),
            ),
          )
      ,
      proposalReplay = new ProposalReplayService[IO]:
        override def readNext(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            anchorBlockId: BlockId,
            nextHeight: BlockHeight,
            limit: Int,
        ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
          IO.pure(
            Right(
              replayed
                .filter(proposal =>
                  Ordering[BlockHeight].gteq(proposal.block.height, nextHeight),
                )
                .sortBy(proposal =>
                  (proposal.block.height, proposal.proposalId.toHexLower),
                )
                .take(limit.max(0)),
            ),
          )
      ,
      historicalBackfill = new HistoricalBackfillService[IO]:
        override def readPrevious(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            beforeBlockId: BlockId,
            beforeHeight: BlockHeight,
            limit: Int,
        ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
          IO.pure(Right(Vector.empty))
      ,
      proposalCatchUpReadiness = proposalCatchUpReadiness,
    )

  private def timeoutVotePayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): TimeoutVote =
    event.payload match
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        timeoutVote
      case _ =>
        throw new IllegalStateException("expected timeout vote")

  private def schedulable(): SchedulingClassification =
    SchedulingClassification.Schedulable(ConflictFootprint.empty)

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private def tempDirResource: Resource[IO, Path] =
    Resource.make(
      IO.blocking(Files.createTempDirectory("sigilaris-pacemaker-bootstrap")),
    )(dir => IO.blocking(deleteRecursively(dir)))

  private def deleteRecursively(
      path: Path,
  ): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)): stream =>
        import scala.jdk.CollectionConverters.*
        stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)

  private final class TestClock private (ref: Ref[IO, Instant])
      extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

    def advance(duration: Duration): IO[Unit] =
      ref.update(_.plus(duration))

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))
