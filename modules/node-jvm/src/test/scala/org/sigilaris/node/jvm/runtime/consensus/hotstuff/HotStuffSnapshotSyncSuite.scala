package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeader, BlockHeight, BlockTimestamp, BlockId, BodyRoot, StateRoot}
import org.sigilaris.node.jvm.runtime.gossip.{CanonicalRejection, ChainId, DirectionalSessionId, PeerIdentity}

final class HotStuffSnapshotSyncSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val startedAt = Instant.parse("2026-04-05T02:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      )
  )
  private val peer1 =
    BootstrapSessionBinding(
      peer = PeerIdentity.unsafe("node-b"),
      sessionId =
        DirectionalSessionId
          .parse("11111111-1111-4111-8111-111111111111")
          .toOption
          .get,
    )
  private val peer2 =
    BootstrapSessionBinding(
      peer = PeerIdentity.unsafe("node-c"),
      sessionId =
        DirectionalSessionId
          .parse("22222222-2222-4222-8222-222222222222")
          .toOption
          .get,
    )

  test("snapshot coordinator completes by mixing verified nodes from multiple peers"):
    val graph = snapshotGraph("10")
    val progressInstants =
      Vector(
        startedAt.plusSeconds(1L),
        startedAt.plusSeconds(2L),
        startedAt.plusSeconds(3L),
      )

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      localNodeStore <- SnapshotNodeStore.inMemory[IO]
      peer1Store <- SnapshotNodeStore.inMemory[IO]
      peer2Store <- SnapshotNodeStore.inMemory[IO]
      currentInstant <- sequencedInstants(progressInstants*)
      _ <- peer1Store.putAll(Vector(graph.rootNode, graph.leftNode))
      _ <- peer2Store.put(graph.rightNode)
      fetchService = multiplexedFetchService(
        peer1.peer -> SnapshotNodeFetchServiceRuntime.fromNodeStore[IO](peer1Store),
        peer2.peer -> SnapshotNodeFetchServiceRuntime.fromNodeStore[IO](peer2Store),
      )
      coordinator =
        SnapshotCoordinator.createWithNow[IO](
          chainId,
          metadataStore,
          localNodeStore,
          fetchService,
          currentInstant,
        )
      result <- coordinator.sync(graph.anchorSuggestion, Vector(peer1, peer2), startedAt)
      metadata <- metadataStore.get(chainId)
      storedRoot <- localNodeStore.get(graph.rootHash)
      storedLeft <- localNodeStore.get(graph.leftHash)
      storedRight <- localNodeStore.get(graph.rightHash)
    yield
      assertEquals(result.map(_.metadata.status), Right(SnapshotStatus.Complete))
      assertEquals(result.map(_.fetchedNodeCount), Right(3L))
      assertEquals(metadata.map(_.status), Some(SnapshotStatus.Complete))
      assertEquals(metadata.map(_.verifiedNodeCount), Some(3L))
      assertEquals(metadata.map(_.lastUpdatedAt), Some(progressInstants.last))
      assertEquals(storedRoot, Some(graph.rootNode.node))
      assertEquals(storedLeft, Some(graph.leftNode.node))
      assertEquals(storedRight, Some(graph.rightNode.node))

  test("snapshot coordinator rejects fetched nodes whose advertised hash does not match content"):
    val graph = snapshotGraph("20")
    val invalidNode =
      SnapshotTrieNode(
        hash = graph.leftHash,
        node = graph.rootNode.node,
      )
    val invalidFetchService =
      new SnapshotNodeFetchService[IO]:
        override def fetchNodes(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            stateRoot: StateRoot,
            hashes: Vector[MerkleTrieNode.MerkleHash],
        ): IO[Either[org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection, Vector[SnapshotTrieNode]]] =
          IO.pure(Right(Vector(invalidNode)))

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      localNodeStore <- SnapshotNodeStore.inMemory[IO]
      coordinator = SnapshotCoordinator.create[IO](chainId, metadataStore, localNodeStore, invalidFetchService)
      result <- coordinator.sync(graph.anchorSuggestion, Vector(peer1), startedAt)
      metadata <- metadataStore.get(chainId)
    yield
      assertEquals(result.left.map(_.reason), Left("invalidSnapshotNodeHash"))
      assertEquals(metadata.map(_.status), Some(SnapshotStatus.Failed))

  test("snapshot coordinator surfaces fetch rejections from peers"):
    val graph = snapshotGraph("25")
    val rejectingFetchService =
      new SnapshotNodeFetchService[IO]:
        override def fetchNodes(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            stateRoot: StateRoot,
            hashes: Vector[MerkleTrieNode.MerkleHash],
        ): IO[Either[CanonicalRejection, Vector[SnapshotTrieNode]]] =
          IO.pure(Left(CanonicalRejection.BackfillUnavailable("peerRefused")))

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      localNodeStore <- SnapshotNodeStore.inMemory[IO]
      coordinator =
        SnapshotCoordinator.createWithNow[IO](
          chainId,
          metadataStore,
          localNodeStore,
          rejectingFetchService,
          IO.pure(startedAt.plusSeconds(1L)),
        )
      result <- coordinator.sync(graph.anchorSuggestion, Vector(peer1), startedAt)
      metadata <- metadataStore.get(chainId)
    yield
      assertEquals(result.left.map(_.reason), Left("snapshotFetchRejected"))
      assertEquals(result.left.map(_.detail), Left(Some("peerRefused")))
      assertEquals(metadata.map(_.status), Some(SnapshotStatus.Failed))

  test("snapshot coordinator does not promote incomplete closures to complete"):
    val graph = snapshotGraph("30")

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      localNodeStore <- SnapshotNodeStore.inMemory[IO]
      peerStore <- SnapshotNodeStore.inMemory[IO]
      _ <- peerStore.putAll(Vector(graph.rootNode, graph.leftNode))
      fetchService = SnapshotNodeFetchServiceRuntime.fromNodeStore[IO](peerStore)
      coordinator = SnapshotCoordinator.create[IO](chainId, metadataStore, localNodeStore, fetchService)
      result <- coordinator.sync(graph.anchorSuggestion, Vector(peer1), startedAt)
      metadata <- metadataStore.get(chainId)
    yield
      assertEquals(result.left.map(_.reason), Left("snapshotClosureIncomplete"))
      assertEquals(metadata.map(_.status), Some(SnapshotStatus.Failed))
      assertEquals(metadata.map(_.pendingNodeCount), Some(1L))

  test("snapshot coordinator surfaces an explicit failure when no sessions are available"):
    val graph = snapshotGraph("35")

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      localNodeStore <- SnapshotNodeStore.inMemory[IO]
      coordinator =
        SnapshotCoordinator.createWithNow[IO](
          chainId,
          metadataStore,
          localNodeStore,
          multiplexedFetchService(),
          IO.pure(startedAt.plusSeconds(1L)),
        )
      result <- coordinator.sync(graph.anchorSuggestion, Vector.empty, startedAt)
      metadata <- metadataStore.get(chainId)
    yield
      assertEquals(result.left.map(_.reason), Left("snapshotNoPeersAvailable"))
      assertEquals(metadata.map(_.status), Some(SnapshotStatus.Failed))

  test("snapshot coordinator skips peer fetches when all required nodes are already local"):
    val graph = snapshotGraph("40")

    for
      metadataStore <- SnapshotMetadataStore.inMemory[IO]
      localNodeStore <- SnapshotNodeStore.inMemory[IO]
      fetchCount <- Ref.of[IO, Int](0)
      currentInstant <- sequencedInstants(
        startedAt.plusSeconds(1L),
        startedAt.plusSeconds(2L),
        startedAt.plusSeconds(3L),
      )
      _ <- localNodeStore.putAll(Vector(graph.rootNode, graph.leftNode, graph.rightNode))
      fetchService =
        new SnapshotNodeFetchService[IO]:
          override def fetchNodes(
              session: BootstrapSessionBinding,
              chainId: ChainId,
              stateRoot: StateRoot,
              hashes: Vector[MerkleTrieNode.MerkleHash],
          ): IO[Either[CanonicalRejection, Vector[SnapshotTrieNode]]] =
            fetchCount.update(_ + 1).map(_ => Right(Vector.empty))
      coordinator =
        SnapshotCoordinator.createWithNow[IO](
          chainId,
          metadataStore,
          localNodeStore,
          fetchService,
          currentInstant,
        )
      result <- coordinator.sync(graph.anchorSuggestion, Vector(peer1), startedAt)
      calls <- fetchCount.get
      metadata <- metadataStore.get(chainId)
    yield
      assertEquals(result.map(_.metadata.status), Right(SnapshotStatus.Complete))
      assertEquals(result.map(_.fetchedNodeCount), Right(0L))
      assertEquals(calls, 0)
      assertEquals(metadata.map(_.status), Some(SnapshotStatus.Complete))

  test("snapshot node verifier derives the trie root hash from the snapshot state root"):
    val graph = snapshotGraph("45")

    assertEquals(
      SnapshotNodeVerifier.rootHash(graph.anchorSuggestion.snapshotAnchor.stateRoot),
      graph.rootHash,
    )

  private def multiplexedFetchService(
      services: (PeerIdentity, SnapshotNodeFetchService[IO])*
  ): SnapshotNodeFetchService[IO] =
    val byPeer = services.toMap
    new SnapshotNodeFetchService[IO]:
      override def fetchNodes(
          session: BootstrapSessionBinding,
          chainId: ChainId,
          stateRoot: StateRoot,
          hashes: Vector[MerkleTrieNode.MerkleHash],
      ): IO[Either[org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection, Vector[SnapshotTrieNode]]] =
        byPeer(session.peer)
          .fetchNodes(session, chainId, stateRoot, hashes)

  private def sequencedInstants(
      instants: Instant*
  ): IO[IO[Instant]] =
    Ref.of[IO, List[Instant]](instants.toList).map: ref =>
      ref.modify:
        case head :: tail => tail -> head
        case Nil =>
          val fallback = instants.lastOption.getOrElse(startedAt)
          Nil -> fallback

  private def snapshotGraph(
      seed: String,
  ): SnapshotGraph =
    val leftLeaf =
      MerkleTrieNode.leaf(
        ByteVector.empty.toNibbles,
        ByteVector.fromHexDescriptive(seed + "aa").toOption.get,
      )
    val rightLeaf =
      MerkleTrieNode.leaf(
        ByteVector.empty.toNibbles,
        ByteVector.fromHexDescriptive(seed + "bb").toOption.get,
      )
    val leftHash = leftLeaf.toHash
    val rightHash = rightLeaf.toHash
    val children =
      MerkleTrieNode.Children.empty
        .updateChild(0, Some(leftHash))
        .updateChild(1, Some(rightHash))
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        children,
      )
    val rootHash = root.toHash
    val suggestion =
      threeChain(
        seed = seed,
        stateRoot = StateRoot(rootHash.toUInt256),
      )

    SnapshotGraph(
      anchorSuggestion = suggestion,
      rootHash = rootHash,
      leftHash = leftHash,
      rightHash = rightHash,
      rootNode = SnapshotTrieNode(rootHash, root),
      leftNode = SnapshotTrieNode(leftHash, leftLeaf),
      rightNode = SnapshotTrieNode(rightHash, rightLeaf),
    )

  private def threeChain(
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
            targetBlockId = BlockHeader.computeId(block(Some(anchor.targetBlockId), 2L, stateRoot, seed + "20")),
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
            targetBlockId = BlockHeader.computeId(block(Some(child.targetBlockId), 3L, stateRoot, seed + "30")),
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

  private final case class SnapshotGraph(
      anchorSuggestion: FinalizedAnchorSuggestion,
      rootHash: MerkleTrieNode.MerkleHash,
      leftHash: MerkleTrieNode.MerkleHash,
      rightHash: MerkleTrieNode.MerkleHash,
      rootNode: SnapshotTrieNode,
      leftNode: SnapshotTrieNode,
      rightNode: SnapshotTrieNode,
  )
