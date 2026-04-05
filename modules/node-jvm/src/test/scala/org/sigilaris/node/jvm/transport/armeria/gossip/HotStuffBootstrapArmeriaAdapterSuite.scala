package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}

import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.{Encoder, Json}
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.{CryptoOps, Hash}
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeader, BlockHeight, BlockId, BlockTimestamp, BodyRoot, StateRoot}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.*
import org.sigilaris.node.jvm.transport.armeria.{ArmeriaServer, ArmeriaServerConfig}

final class HotStuffBootstrapArmeriaAdapterSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val startedAt = Instant.parse("2026-04-05T09:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      )
  )
  private val subscription = SessionSubscription.unsafe(
    ChainTopic(chainId, GossipTopic.consensusProposal),
    ChainTopic(chainId, GossipTopic.consensusVote),
  )

  test("bootstrap transport serves finalized suggestion, snapshot nodes, replay, and backfill on an open session"):
    (
      for
        client <- Harness.resource(
          startedAt,
          localNodeId = "node-a",
          knownPeers = List("node-b"),
          directNeighbors = List("node-b"),
        )
        server <- Harness.resource(
          startedAt,
          localNodeId = "node-b",
          knownPeers = List("node-a"),
          directNeighbors = List("node-a"),
        )
      yield (client, server)
    ).use: (client, server) =>
      val graph = snapshotGraph("10")
      val anchor =
        finalizedSuggestion(
          seed = "10",
          anchorHeight = 3L,
          stateRoot = StateRoot(graph.rootHash.toUInt256),
        )
      val history = historicalChain("1f")

      for
        _ <- server.seedSnapshot(graph.rootNode, graph.leftNode, graph.rightNode)
        _ <- server.seedProposals(
          Vector(
            history.genesis,
            history.proposal1,
            history.proposal2,
            anchor.proposal,
            anchor.finalizedProof.child,
            anchor.finalizedProof.grandchild,
          )
        )
        session <- openOutboundViaHttp(client, server)
        transport = bootstrapTransport(server)
        suggestion <- transport.finalizedAnchorSuggestions.bestFinalized(session, chainId)
        nodes <- transport.snapshotNodeFetch.fetchNodes(
          session = session,
          chainId = chainId,
          stateRoot = anchor.stateRoot,
          hashes = Vector(graph.rootHash, graph.leftHash, graph.rightHash),
        )
        replay <- transport.proposalReplay.readNext(
          session = session,
          chainId = chainId,
          anchorBlockId = anchor.anchorBlockId,
          nextHeight = BlockHeight.unsafeFromLong(4L),
          limit = 8,
        )
        backfill <- transport.historicalBackfill.readPrevious(
          session = session,
          chainId = chainId,
          beforeBlockId = anchor.anchorBlockId,
          beforeHeight = anchor.anchorHeight,
          limit = 8,
        )
      yield
        assertEquals(suggestion, Right(Some(anchor)))
        assertEquals(nodes.map(_.map(_.hash).toSet), Right(Set(graph.rootHash, graph.leftHash, graph.rightHash)))
        assertEquals(
          replay.map(_.map(_.proposalId)),
          Right(
            Vector(
              anchor.finalizedProof.child.proposalId,
              anchor.finalizedProof.grandchild.proposalId,
            )
          ),
        )
        assertEquals(
          backfill.map(_.map(_.proposalId)),
          Right(
            Vector(
              history.proposal2.proposalId,
              history.proposal1.proposalId,
              history.genesis.proposalId,
            )
          ),
        )

  test("bootstrap transport rejects requests after the parent session is disconnected"):
    (
      for
        client <- Harness.resource(
          startedAt,
          localNodeId = "node-a",
          knownPeers = List("node-b"),
          directNeighbors = List("node-b"),
        )
        server <- Harness.resource(
          startedAt,
          localNodeId = "node-b",
          knownPeers = List("node-a"),
          directNeighbors = List("node-a"),
        )
      yield (client, server)
    ).use: (client, server) =>
      val graph = snapshotGraph("20")
      val anchor =
        finalizedSuggestion(
          seed = "20",
          anchorHeight = 3L,
          stateRoot = StateRoot(graph.rootHash.toUInt256),
        )

      for
        _ <- server.seedSnapshot(graph.rootNode, graph.leftNode, graph.rightNode)
        _ <- server.seedProposals(
          Vector(
            anchor.proposal,
            anchor.finalizedProof.child,
            anchor.finalizedProof.grandchild,
          )
        )
        session <- openOutboundViaHttp(client, server)
        _ <- server.postNoBody(s"/gossip/session/${session.sessionId.value}/disconnect")
        result <- bootstrapTransport(server)
          .finalizedAnchorSuggestions
          .bestFinalized(session, chainId)
      yield
        result match
          case Left(rejection: CanonicalRejection.HandshakeRejected) =>
            assertEquals(rejection.reason, "sessionNotOpen")
          case other =>
            fail("expected handshake rejection but saw " + other.toString)

  test("assembled runtime bootstrap can sync a snapshot from remote HTTP bootstrap peers"):
    (
      for
        requesterClock <- Resource.eval(TestClock.create(startedAt))
        serverB <- Harness.resource(
          startedAt,
          localNodeId = "node-b",
          knownPeers = List("node-a"),
          directNeighbors = List("node-a"),
        )
        serverC <- Harness.resource(
          startedAt,
          localNodeId = "node-c",
          knownPeers = List("node-a"),
          directNeighbors = List("node-a"),
        )
      yield (requesterClock, serverB, serverC)
    ).use: (requesterClock, serverB, serverC) =>
      val graph = snapshotGraph("30")
      val anchor =
        finalizedSuggestion(
          seed = "30",
          anchorHeight = 3L,
          stateRoot = StateRoot(graph.rootHash.toUInt256),
        )
      val consensusConfig =
        HotStuffBootstrapConfig(
          role = LocalNodeRole.Audit,
          validatorSet = validatorSet,
          holders = Vector.empty,
          localKeys = Map.empty,
        )

      for
        topology <- IO.fromEither(
          StaticPeerTopology
            .parse(
              localNodeIdentity = "node-a",
              knownPeers = List("node-b", "node-c"),
              directNeighbors = List("node-b", "node-c"),
            )
            .leftMap(new IllegalArgumentException(_))
        )
        _ <- serverB.seedSnapshot(graph.rootNode, graph.leftNode)
        _ <- serverC.seedSnapshot(graph.rightNode)
        _ <- serverB.seedProposals(
          Vector(
            anchor.proposal,
            anchor.finalizedProof.child,
            anchor.finalizedProof.grandchild,
          )
        )
        _ <- serverC.seedProposals(
          Vector(
            anchor.proposal,
            anchor.finalizedProof.child,
            anchor.finalizedProof.grandchild,
          )
        )
        requesterEither <- HotStuffRuntimeBootstrap.fromTopology[IO](
          topology = topology,
          consensusConfig = consensusConfig,
          clock = requesterClock,
          bootstrapTransport = Some(
            HotStuffBootstrapHttpTransport.services[IO](
              Map(
                PeerIdentity.unsafe("node-b") -> serverB.baseUri,
                PeerIdentity.unsafe("node-c") -> serverC.baseUri,
              )
            )
          ),
        )
        requester <- IO.fromEither(requesterEither.leftMap(new IllegalArgumentException(_)))
        sessionB <- openOutboundViaHttp(requester.runtime, serverB)
        sessionC <- openOutboundViaHttp(requester.runtime, serverC)
        result <- requester.consensus.bootstrap(
          chainId = chainId,
          sessions = Vector(sessionB, sessionC),
          startedAt = startedAt,
          liveProposals = Vector.empty,
        )
        diagnostics <- requester.consensus.currentBootstrapDiagnostics
        storedRoot <- IO.fromOption(
          requester.consensus.bootstrapLifecycle.flatMap(_.nodeStore.some),
        )(new IllegalStateException("missingBootstrapLifecycle"))
          .flatMap(_.get(graph.rootHash))
        storedLeft <- IO.fromOption(
          requester.consensus.bootstrapLifecycle.flatMap(_.nodeStore.some),
        )(new IllegalStateException("missingBootstrapLifecycle"))
          .flatMap(_.get(graph.leftHash))
        storedRight <- IO.fromOption(
          requester.consensus.bootstrapLifecycle.flatMap(_.nodeStore.some),
        )(new IllegalStateException("missingBootstrapLifecycle"))
          .flatMap(_.get(graph.rightHash))
      yield
        assertEquals(result.map(_.anchor), Right(anchor))
        assertEquals(result.map(_.snapshot.metadata.status), Right(SnapshotStatus.Complete))
        assertEquals(result.map(_.snapshot.fetchedNodeCount), Right(3L))
        assertEquals(
          result.map(_.forwardCatchUp.voteReadiness),
          Right(BootstrapVoteReadiness.Held("forwardCatchUpUnavailable")),
        )
        assertEquals(diagnostics.phase, BootstrapPhase.ForwardCatchUp)
        assertEquals(diagnostics.chains(chainId).pinnedAnchor, Some(anchor.snapshotAnchor))
        assertEquals(storedRoot, Some(graph.rootNode.node))
        assertEquals(storedLeft, Some(graph.leftNode.node))
        assertEquals(storedRight, Some(graph.rightNode.node))

  test("runtime with remote bootstrap transport still serves bootstrap endpoints from local state"):
    (
      for
        requesterClock <- Resource.eval(TestClock.create(startedAt))
        remote <- Harness.resource(
          startedAt,
          localNodeId = "node-b",
          knownPeers = List("node-a"),
          directNeighbors = List("node-a"),
        )
        client <- Harness.resource(
          startedAt,
          localNodeId = "node-c",
          knownPeers = List("node-a"),
          directNeighbors = List("node-a"),
        )
      yield (requesterClock, remote, client)
    ).use: (requesterClock, remote, client) =>
      val remoteGraph = snapshotGraph("40")
      val remoteAnchor =
        finalizedSuggestion(
          seed = "40",
          anchorHeight = 3L,
          stateRoot = StateRoot(remoteGraph.rootHash.toUInt256),
        )
      val localGraph = snapshotGraph("41")
      val localAnchor =
        finalizedSuggestion(
          seed = "41",
          anchorHeight = 3L,
          stateRoot = StateRoot(localGraph.rootHash.toUInt256),
        )
      val consensusConfig =
        HotStuffBootstrapConfig(
          role = LocalNodeRole.Audit,
          validatorSet = validatorSet,
          holders = Vector.empty,
          localKeys = Map.empty,
        )

      for
        topology <- IO.fromEither(
          StaticPeerTopology
            .parse(
              localNodeIdentity = "node-a",
              knownPeers = List("node-b", "node-c"),
              directNeighbors = List("node-b", "node-c"),
            )
            .leftMap(new IllegalArgumentException(_))
        )
        _ <- remote.seedSnapshot(remoteGraph.rootNode, remoteGraph.leftNode, remoteGraph.rightNode)
        _ <- remote.seedProposals(
          Vector(
            remoteAnchor.proposal,
            remoteAnchor.finalizedProof.child,
            remoteAnchor.finalizedProof.grandchild,
          )
        )
        requesterEither <- HotStuffRuntimeBootstrap.fromTopology[IO](
          topology = topology,
          consensusConfig = consensusConfig,
          clock = requesterClock,
          bootstrapTransport = Some(
            HotStuffBootstrapHttpTransport.services[IO](
              Map(PeerIdentity.unsafe("node-b") -> remote.baseUri)
            )
          ),
        )
        requester <- IO.fromEither(requesterEither.leftMap(new IllegalArgumentException(_)))
        _ <- seedRuntimeBootstrapLocal(
          requester,
          proposals = Vector(
            localAnchor.proposal,
            localAnchor.finalizedProof.child,
            localAnchor.finalizedProof.grandchild,
          ),
          snapshotNodes = Vector(localGraph.rootNode, localGraph.leftNode, localGraph.rightNode),
        )
        response <- RuntimeServer.resource(requester).use: requesterServer =>
          for
            session <- openOutboundViaHttp(client.runtime, requesterServer)
            transport =
              HotStuffBootstrapHttpTransport.services[IO](
                Map(PeerIdentity.unsafe("node-a") -> requesterServer.baseUri)
              )
            suggestion <- transport.finalizedAnchorSuggestions.bestFinalized(session, chainId)
            nodes <- transport.snapshotNodeFetch.fetchNodes(
              session = session,
              chainId = chainId,
              stateRoot = localAnchor.stateRoot,
              hashes = Vector(localGraph.rootHash),
            )
          yield suggestion -> nodes
      yield
        assertEquals(response._1, Right(Some(localAnchor)))
        assertEquals(response._2.map(_.map(_.hash)), Right(Vector(localGraph.rootHash)))

  private def bootstrapTransport(
      server: Harness,
  ): HotStuffBootstrapTransportServices[IO] =
    HotStuffBootstrapHttpTransport.services[IO](
      Map(PeerIdentity.unsafe(server.localNodeId) -> server.baseUri)
    )

  private def openOutboundViaHttp(
      from: Harness,
      to: Harness,
  ): IO[BootstrapSessionBinding] =
    openOutboundViaHttp(from.runtime, to)

  private def openOutboundViaHttp(
      from: TxGossipRuntime[IO, HotStuffGossipArtifact],
      to: Harness,
  ): IO[BootstrapSessionBinding] =
    for
      proposalEither <- from.startOutbound(
        PeerIdentity.unsafe(to.localNodeId),
        subscription,
      )
      proposal <- IO.fromEither(
        proposalEither.leftMap(rejection => new IllegalStateException(rejection.reason))
      )
      response <- to.postJson(
        "/gossip/session/open",
        toProposalWire(proposal).asJson.noSpaces,
      )
      ackWire <- IO.fromEither(
        decode[SessionOpenAckWire](response.body).leftMap(new IllegalStateException(_))
      )
      ack <- IO.fromEither(toAck(ackWire).leftMap(new IllegalArgumentException(_)))
      applied <- from.applyHandshakeAck(ack)
      _ <- IO.fromEither(applied.leftMap(rejection => new IllegalStateException(rejection.reason)))
    yield
      BootstrapSessionBinding(
        peer = PeerIdentity.unsafe(to.localNodeId),
        sessionId = proposal.sessionId,
      )

  private def openOutboundViaHttp(
      from: TxGossipRuntime[IO, HotStuffGossipArtifact],
      to: RuntimeServer,
  ): IO[BootstrapSessionBinding] =
    for
      proposalEither <- from.startOutbound(
        PeerIdentity.unsafe(to.localNodeId),
        subscription,
      )
      proposal <- IO.fromEither(
        proposalEither.leftMap(rejection => new IllegalStateException(rejection.reason))
      )
      response <- to.postJson(
        "/gossip/session/open",
        toProposalWire(proposal).asJson.noSpaces,
      )
      ackWire <- IO.fromEither(
        decode[SessionOpenAckWire](response.body).leftMap(new IllegalStateException(_))
      )
      ack <- IO.fromEither(toAck(ackWire).leftMap(new IllegalArgumentException(_)))
      applied <- from.applyHandshakeAck(ack)
      _ <- IO.fromEither(applied.leftMap(rejection => new IllegalStateException(rejection.reason)))
    yield
      BootstrapSessionBinding(
        peer = PeerIdentity.unsafe(to.localNodeId),
        sessionId = proposal.sessionId,
      )

  private def toProposalWire(
      proposal: SessionOpenProposal,
  ): SessionOpenProposalWire =
    SessionOpenProposalWire(
      sessionId = proposal.sessionId.value,
      peerCorrelationId = proposal.peerCorrelationId.value,
      initiator = proposal.initiator.value,
      acceptor = proposal.acceptor.value,
      subscriptions = proposal.subscriptions.values.toVector.map(ct => ChainTopicWire(ct.chainId.value, ct.topic.value)),
      heartbeatIntervalMs = proposal.heartbeatInterval.map(_.toMillis),
      livenessTimeoutMs = proposal.livenessTimeout.map(_.toMillis),
      maxControlRetryIntervalMs = proposal.maxControlRetryInterval.map(_.toMillis),
    )

  private def toAck(
      wire: SessionOpenAckWire,
  ): Either[String, SessionOpenAck] =
    for
      sessionId <- DirectionalSessionId.parse(wire.sessionId)
      peerCorrelationId <- PeerCorrelationId.parse(wire.peerCorrelationId)
      initiator <- PeerIdentity.parse(wire.initiator)
      acceptor <- PeerIdentity.parse(wire.acceptor)
      subscriptions <- wire.subscriptions.toVector
        .traverse: entry =>
          for
            chainId <- ChainId.parse(entry.chainId)
            topic <- GossipTopic.parse(entry.topic)
          yield ChainTopic(chainId, topic)
        .map(_.toSet)
        .flatMap(SessionSubscription.fromSet)
    yield SessionOpenAck(
      sessionId = sessionId,
      peerCorrelationId = peerCorrelationId,
      initiator = initiator,
      acceptor = acceptor,
      subscriptions = subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofMillis(wire.heartbeatIntervalMs),
        livenessTimeout = Duration.ofMillis(wire.livenessTimeoutMs),
        maxControlRetryInterval = Duration.ofMillis(wire.maxControlRetryIntervalMs),
      ),
    )

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

    SnapshotGraph(
      rootHash = rootHash,
      leftHash = leftHash,
      rightHash = rightHash,
      rootNode = SnapshotTrieNode(rootHash, root),
      leftNode = SnapshotTrieNode(leftHash, leftLeaf),
      rightNode = SnapshotTrieNode(rightHash, rightLeaf),
    )

  private def finalizedSuggestion(
      seed: String,
      anchorHeight: Long,
      stateRoot: StateRoot,
  ): FinalizedAnchorSuggestion =
    val baseHeight = anchorHeight - 1L
    val bootstrapSubject = QuorumCertificateSubject(
      window = HotStuffWindow(chainId, baseHeight, baseHeight, validatorSet.hash),
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
        stateRoot = stateRoot,
        bodyRoot = BodyRoot(hex(seed + "11")),
      )
    val anchor =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, anchorHeight, anchorHeight, validatorSet.hash),
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
    val child =
      childProposal(anchor, seed + "20", anchorHeight + 1L)
    val grandchild =
      childProposal(child, seed + "30", anchorHeight + 2L)

    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(
        child = child,
        grandchild = grandchild,
      ),
    )

  private def historicalChain(
      seed: String,
  ): HistoricalChain =
    val genesis = genesisProposal(seed + "00")
    val proposal1 = childProposal(genesis, seed + "10", 1L)
    val proposal2 = childProposal(proposal1, seed + "20", 2L)
    HistoricalChain(
      genesis = genesis,
      proposal1 = proposal1,
      proposal2 = proposal2,
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
        stateRoot = StateRoot(hex(seed + "10")),
        bodyRoot = BodyRoot(hex(seed + "11")),
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
      stateRoot: StateRoot,
  ): Proposal =
    val blockHeader =
      block(
        parent = Some(parentProposal.targetBlockId),
        height = height,
        stateRoot = stateRoot,
        bodyRoot = BodyRoot(hex(seed + "13")),
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

  private def childProposal(
      parentProposal: Proposal,
      seed: String,
      height: Long,
  ): Proposal =
    childProposal(
      parentProposal = parentProposal,
      seed = seed,
      height = height,
      stateRoot = StateRoot(hex(seed + "12")),
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
    validatorSet.members.take(3).zipWithIndex.map: (member, index) =>
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
      stateRoot: StateRoot,
      bodyRoot: BodyRoot,
  ): BlockHeader =
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = stateRoot,
      bodyRoot = bodyRoot,
      timestamp = BlockTimestamp.unsafeFromEpochMillis(
        startedAt.toEpochMilli + (height * 1000L),
      ),
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value.padTo(64, '0')).toOption.get

  private final case class Response(
      status: Int,
      body: String,
  )

  private final case class SnapshotGraph(
      rootHash: MerkleTrieNode.MerkleHash,
      leftHash: MerkleTrieNode.MerkleHash,
      rightHash: MerkleTrieNode.MerkleHash,
      rootNode: SnapshotTrieNode,
      leftNode: SnapshotTrieNode,
      rightNode: SnapshotTrieNode,
  )

  private final case class HistoricalChain(
      genesis: Proposal,
      proposal1: Proposal,
      proposal2: Proposal,
  )

  private final case class Harness(
      localNodeId: String,
      runtime: TxGossipRuntime[IO, HotStuffGossipArtifact],
      source: InMemoryHotStuffArtifactSource[IO],
      sink: InMemoryHotStuffArtifactSink[IO],
      nodeStore: SnapshotNodeStore[IO],
      baseUri: String,
  ):
    def postJson(path: String, body: String): IO[Response] =
      IO.blocking:
        val client = HttpClient.newHttpClient()
        val request = HttpRequest
          .newBuilder(URI.create(s"$baseUri$path"))
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        Response(response.statusCode(), response.body())

    def postNoBody(path: String): IO[Response] =
      IO.blocking:
        val client = HttpClient.newHttpClient()
        val request = HttpRequest
          .newBuilder(URI.create(s"$baseUri$path"))
          .POST(HttpRequest.BodyPublishers.noBody())
          .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        Response(response.statusCode(), response.body())

    def seedProposals(
        proposals: Vector[Proposal],
    ): IO[Unit] =
      proposals.zipWithIndex.traverse_ { case (proposal, index) =>
        source
          .append(
            HotStuffGossipArtifact.ProposalArtifact(proposal),
            startedAt.plusSeconds(index.toLong),
          )
          .flatMap: event =>
            sink.applyEvent(event).flatMap:
              case Left(rejection) =>
                IO.raiseError(new IllegalStateException(rejection.reason))
              case Right(_) =>
                IO.unit
      }

    def seedSnapshot(
        nodes: SnapshotTrieNode*
    ): IO[Unit] =
      nodeStore.putAll(nodes.toVector)

  private object Harness:
    def resource(
        start: Instant,
        localNodeId: String,
        knownPeers: List[String],
        directNeighbors: List[String],
    ): Resource[IO, Harness] =
      for
        topology <- Resource.eval(
          IO.fromEither(
            StaticPeerTopology
              .parse(
                localNodeIdentity = localNodeId,
                knownPeers = knownPeers,
                directNeighbors = directNeighbors,
              )
              .leftMap(new IllegalArgumentException(_))
          )
        )
        registry = StaticPeerRegistry(topology)
        authenticator = StaticPeerAuthenticator[IO](registry)
        clock <- Resource.eval(TestClock.create(start))
        given GossipClock[IO] = clock
        source <- Resource.eval(InMemoryHotStuffArtifactSource.create[IO])
        sink <- Resource.eval(
          InMemoryHotStuffArtifactSink.create[IO](
            validatorSet = validatorSet,
            relayPolicy = HotStuffRelayPolicy(relayValidatedArtifacts = false),
            relayPublisher = source,
          )
        )
        stateStore <- Resource.eval(
          TxGossipStateStore.inMemory[IO](
            GossipSessionEngine(registry.localPeer, topology),
          )
        )
        nodeStore <- Resource.eval(SnapshotNodeStore.inMemory[IO])
        runtime = TxGossipRuntime.default[IO, HotStuffGossipArtifact](
          peerAuthenticator = authenticator,
          clock = clock,
          source = source,
          sink = sink,
          topicContracts = HotStuffTopic.registry(),
          stateStore = stateStore,
        )
        bootstrapServices =
          HotStuffBootstrapServicesRuntime.inMemoryWithNodeStore[IO](
            validatorSet = validatorSet,
            sink = sink,
            snapshotNodeStore = nodeStore.some,
            diagnostics = BootstrapDiagnosticsSource.const[IO](BootstrapDiagnostics.empty),
          )
        server <- ArmeriaServer.resource[IO](
          ArmeriaServerConfig(port = 0),
          TxGossipArmeriaAdapter.endpoints[IO, HotStuffGossipArtifact](runtime) ++
            HotStuffBootstrapArmeriaAdapter.endpoints[IO, HotStuffGossipArtifact](
              sessionRuntime = runtime,
              bootstrapServices = bootstrapServices,
            ),
        )
      yield Harness(
        localNodeId = localNodeId,
        runtime = runtime,
        source = source,
        sink = sink,
        nodeStore = nodeStore,
        baseUri = s"http://127.0.0.1:${server.activeLocalPort()}",
      )

  private final case class RuntimeServer(
      localNodeId: String,
      baseUri: String,
  ):
    def postJson(path: String, body: String): IO[Response] =
      IO.blocking:
        val client = HttpClient.newHttpClient()
        val request = HttpRequest
          .newBuilder(URI.create(s"$baseUri$path"))
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        Response(response.statusCode(), response.body())

  private object RuntimeServer:
    def resource(
        bootstrap: HotStuffRuntimeBootstrap[IO],
    ): Resource[IO, RuntimeServer] =
      ArmeriaServer
        .resource[IO](
          ArmeriaServerConfig(port = 0),
          HotStuffGossipArmeriaAdapter.endpoints[IO](bootstrap),
        )
        .map: server =>
          RuntimeServer(
            localNodeId = bootstrap.topology.localNodeIdentity.value,
            baseUri = s"http://127.0.0.1:${server.activeLocalPort()}",
          )

  private def seedRuntimeBootstrapLocal(
      bootstrap: HotStuffRuntimeBootstrap[IO],
      proposals: Vector[Proposal],
      snapshotNodes: Vector[SnapshotTrieNode],
  ): IO[Unit] =
    (
      IO.fromOption(bootstrap.consensus.inMemorySource)(
        new IllegalStateException("missing in-memory source"),
      ),
      IO.fromOption(bootstrap.consensus.inMemorySink)(
        new IllegalStateException("missing in-memory sink"),
      ),
      IO.fromOption(bootstrap.consensus.bootstrapLifecycle)(
        new IllegalStateException("missing bootstrap lifecycle"),
      ),
    ).tupled.flatMap: (source, sink, lifecycle) =>
      proposals.zipWithIndex.traverse_ { case (proposal, index) =>
        source
          .append(
            HotStuffGossipArtifact.ProposalArtifact(proposal),
            startedAt.plusSeconds(index.toLong),
          )
          .flatMap: event =>
            sink.applyEvent(event).flatMap:
              case Left(rejection) =>
                IO.raiseError(new IllegalStateException(rejection.reason))
              case Right(_) =>
                IO.unit
      } *> lifecycle.nodeStore.putAll(snapshotNodes)

  private final class TestClock private (ref: Ref[IO, Instant]) extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))

  private given Encoder[HotStuffGossipArtifact] =
    Encoder.instance:
      case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
        Json.obj(
          "kind" -> Json.fromString("proposal"),
          "id" -> Json.fromString(proposal.proposalId.toHexLower),
        )
      case HotStuffGossipArtifact.VoteArtifact(vote) =>
        Json.obj(
          "kind" -> Json.fromString("vote"),
          "id" -> Json.fromString(vote.voteId.toHexLower),
        )
