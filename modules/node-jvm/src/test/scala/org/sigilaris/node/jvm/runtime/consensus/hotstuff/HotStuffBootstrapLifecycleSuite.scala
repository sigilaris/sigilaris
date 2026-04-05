package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeader, BlockHeight, BlockId, BlockTimestamp, BodyRoot, StateRoot}
import org.sigilaris.node.jvm.runtime.gossip.ChainId

final class HotStuffBootstrapLifecycleSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val startedAt = Instant.parse("2026-04-05T05:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )

  test("concurrent bootstrap calls share a single coordinator build for the same chain"):
    val services = HotStuffBootstrapServices.static[IO](validatorSet)
    val readiness = ProposalCatchUpReadiness.static[IO](
      ProposalCatchUpAssessment(
        voteReadiness = BootstrapVoteReadiness.Ready,
        controlBatch = None,
      ),
    )

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      nodeStore <- SnapshotNodeStore.inMemory[IO]
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      hookCalls <- Ref.of[IO, Int](0)
      lifecycle <- HotStuffBootstrapLifecycle.inMemory[IO](
        metadataStore = metadataStore,
        nodeStore = nodeStore,
        validatorSetLookup = services.validatorSetLookup,
        finalizedAnchorSuggestions = services.finalizedAnchorSuggestions,
        snapshotNodeFetch = services.snapshotNodeFetch,
        proposalReplay = services.proposalReplay,
        historicalBackfill = services.historicalBackfill,
        readiness = readiness,
        beforeCoordinatorBuild = Some(_ =>
          hookCalls.update(_ + 1) *>
            entered.complete(()).void *>
            release.get,
        ),
        currentInstant = IO.pure(startedAt),
      )
      bootstrap1 <- lifecycle
        .bootstrap(
          chainId = chainId,
          sessions = Vector.empty,
          startedAt = startedAt,
          liveProposals = Vector.empty,
        )
        .start
      _ <- entered.get
      bootstrap2 <- lifecycle
        .bootstrap(
          chainId = chainId,
          sessions = Vector.empty,
          startedAt = startedAt.plusSeconds(1L),
          liveProposals = Vector.empty,
        )
        .start
      _ <- release.complete(())
      result1 <- bootstrap1.joinWithNever
      result2 <- bootstrap2.joinWithNever
      count <- hookCalls.get
    yield
      assertEquals(count, 1)
      assertEquals(result1.left.map(_.reason), Left("noVerifiableFinalizedAnchor"))
      assertEquals(result2.left.map(_.reason), Left("noVerifiableFinalizedAnchor"))

  test("bootstrap lifecycle removes a failed building slot so a later retry can rebuild"):
    val services = HotStuffBootstrapServices.static[IO](validatorSet)
    val readiness = ProposalCatchUpReadiness.static[IO](
      ProposalCatchUpAssessment(
        voteReadiness = BootstrapVoteReadiness.Ready,
        controlBatch = None,
      ),
    )

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      nodeStore <- SnapshotNodeStore.inMemory[IO]
      failFirst <- Ref.of[IO, Boolean](true)
      lifecycle <- HotStuffBootstrapLifecycle.inMemory[IO](
        metadataStore = metadataStore,
        nodeStore = nodeStore,
        validatorSetLookup = services.validatorSetLookup,
        finalizedAnchorSuggestions = services.finalizedAnchorSuggestions,
        snapshotNodeFetch = services.snapshotNodeFetch,
        proposalReplay = services.proposalReplay,
        historicalBackfill = services.historicalBackfill,
        readiness = readiness,
        beforeCoordinatorBuild = Some(_ =>
          failFirst.modify: shouldFail =>
            if shouldFail then false -> IO.raiseError[Unit](
              new IllegalStateException("bootstrapBuildFailed"),
            )
            else false -> IO.unit
          .flatten,
        ),
        currentInstant = IO.pure(startedAt),
      )
      firstAttempt <- lifecycle
        .bootstrap(
          chainId = chainId,
          sessions = Vector.empty,
          startedAt = startedAt,
          liveProposals = Vector.empty,
        )
        .attempt
      secondAttempt <- lifecycle.bootstrap(
        chainId = chainId,
        sessions = Vector.empty,
        startedAt = startedAt.plusSeconds(1L),
        liveProposals = Vector.empty,
      )
    yield
      assertEquals(
        firstAttempt.left.map(_.getMessage),
        Left("bootstrapBuildFailed"),
      )
      assertEquals(
        secondAttempt.left.map(_.reason),
        Left("noVerifiableFinalizedAnchor"),
      )

  test("bootstrap lifecycle can recover from discovery failure and clear the vote hold after a later successful bootstrap"):
    val session = Vector(
      BootstrapSessionBinding(
        peer = org.sigilaris.node.jvm.runtime.gossip.PeerIdentity.unsafe("node-b"),
        sessionId =
          org.sigilaris.node.jvm.runtime.gossip.DirectionalSessionId
            .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            .toOption
            .get,
      ),
    )
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        MerkleTrieNode.Children.empty,
      )
    val rootHash = root.toHash
    val anchor = finalizedSuggestion("a1", StateRoot(rootHash.toUInt256))

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      nodeStore <- SnapshotNodeStore.inMemory[IO]
      suggestionAvailable <- Ref.of[IO, Boolean](false)
      lifecycle <- HotStuffBootstrapLifecycle.inMemory[IO](
        metadataStore = metadataStore,
        nodeStore = nodeStore,
        validatorSetLookup = ValidatorSetLookup.static[IO](
          BootstrapTrustRoot.staticValidatorSet(validatorSet),
        ),
        finalizedAnchorSuggestions = new FinalizedAnchorSuggestionService[IO]:
          override def bestFinalized(
              session: BootstrapSessionBinding,
              chainId: ChainId,
          ): IO[Either[
            org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection,
            Option[FinalizedAnchorSuggestion],
          ]] =
            suggestionAvailable.get.map:
              enabled => Option.when(enabled)(anchor)
                .asRight[
                  org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection
                ]
        ,
        snapshotNodeFetch = new SnapshotNodeFetchService[IO]:
          override def fetchNodes(
              session: BootstrapSessionBinding,
              chainId: ChainId,
              stateRoot: StateRoot,
              hashes: Vector[MerkleTrieNode.MerkleHash],
          ): IO[Either[
            org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection,
            Vector[SnapshotTrieNode],
          ]] =
            IO.pure(
              hashes
                .filter(_ === rootHash)
                .map(_ => SnapshotTrieNode(rootHash, root))
                .asRight[
                  org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection
                ],
            )
        ,
        proposalReplay = new ProposalReplayService[IO]:
          override def readNext(
              session: BootstrapSessionBinding,
              chainId: ChainId,
              anchorBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
              nextHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
              limit: Int,
          ): IO[Either[
            org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection,
            Vector[Proposal],
          ]] =
            IO.pure(Right(Vector.empty))
        ,
        historicalBackfill = new HistoricalBackfillService[IO]:
          override def readPrevious(
              session: BootstrapSessionBinding,
              chainId: ChainId,
              beforeBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
              beforeHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
              limit: Int,
          ): IO[Either[
            org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection,
            Vector[Proposal],
          ]] =
            IO.pure(Right(Vector.empty))
        ,
        readiness = ProposalCatchUpReadiness.static[IO](
          ProposalCatchUpAssessment(
            voteReadiness = BootstrapVoteReadiness.Ready,
            controlBatch = None,
          ),
        ),
        currentInstant = IO.pure(startedAt),
      )
      firstAttempt <- lifecycle.bootstrap(
        chainId = chainId,
        sessions = session,
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      heldAfterFailure <- lifecycle.voteReadiness(chainId)
      _ <- suggestionAvailable.set(true)
      secondAttempt <- lifecycle.bootstrap(
        chainId = chainId,
        sessions = session,
        startedAt = startedAt.plusSeconds(1L),
        liveProposals = Vector.empty,
      )
      readinessAfterSuccess <- lifecycle.voteReadiness(chainId)
    yield
      assertEquals(
        firstAttempt.left.map(_.reason),
        Left("noVerifiableFinalizedAnchor"),
      )
      assertEquals(heldAfterFailure, BootstrapVoteReadiness.Held("snapshotPending"))
      assert(secondAttempt.isRight)
      assertEquals(readinessAfterSuccess, BootstrapVoteReadiness.Ready)

  private def finalizedSuggestion(
      seed: String,
      stateRoot: StateRoot,
  ): FinalizedAnchorSuggestion =
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
    val anchorBlock =
      block(
        parent = Some(bootstrapSubject.blockId),
        height = 1L,
        stateRoot = stateRoot,
        bodyHex = seed + "10",
      )
    val anchor =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
            proposer = validatorSet.members(0).id,
            targetBlockId = BlockHeader.computeId(anchorBlock),
            block = anchorBlock,
            txSet = ProposalTxSet.empty,
            justify = bootstrapQc,
          ),
          validatorKeys(0),
        )
        .toOption
        .get
    val child =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 2L, 2L, validatorSet.hash),
            proposer = validatorSet.members(1).id,
            targetBlockId = BlockHeader.computeId(
              block(Some(anchor.targetBlockId), 2L, stateRoot, seed + "20"),
            ),
            block = block(Some(anchor.targetBlockId), 2L, stateRoot, seed + "20"),
            txSet = ProposalTxSet.empty,
            justify = qcFor(anchor),
          ),
          validatorKeys(1),
        )
        .toOption
        .get
    val grandchild =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 3L, 3L, validatorSet.hash),
            proposer = validatorSet.members(2).id,
            targetBlockId = BlockHeader.computeId(
              block(Some(child.targetBlockId), 3L, stateRoot, seed + "30"),
            ),
            block = block(Some(child.targetBlockId), 3L, stateRoot, seed + "30"),
            txSet = ProposalTxSet.empty,
            justify = qcFor(child),
          ),
          validatorKeys(2),
        )
        .toOption
        .get

    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

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
    Vector(0, 1, 2).map: index =>
      Vote
        .sign(
          UnsignedVote(
            window = window,
            voter = validatorSet.members(index).id,
            targetProposalId = proposalId,
          ),
          validatorKeys(index),
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
      timestamp = BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli + height),
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
