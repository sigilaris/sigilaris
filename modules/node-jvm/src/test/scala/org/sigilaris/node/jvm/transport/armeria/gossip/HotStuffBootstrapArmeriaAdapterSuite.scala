package org.sigilaris.node.jvm.transport.armeria.gossip

import java.nio.file.{Files, Path}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import java.util.Optional
import java.util.concurrent.CompletableFuture

import scala.concurrent.duration.DurationInt
import scala.util.Using
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import scala.jdk.CollectionConverters.*

import org.sigilaris.core.crypto.{CryptoOps, Hash}
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.*
import org.sigilaris.node.jvm.storage.swaydb.StorageLayout
import org.sigilaris.node.jvm.transport.armeria.{
  ArmeriaServer,
  ArmeriaServerConfig,
}

final class HotStuffBootstrapArmeriaAdapterSuite extends CatsEffectSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-05T09:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )
  private val subscription = SessionSubscription.unsafe(
    ChainTopic(chainId, GossipTopic.consensusProposal),
    ChainTopic(chainId, GossipTopic.consensusVote),
  )

  private def storageLayoutResource: Resource[IO, StorageLayout] =
    Resource
      .make(
        IO.blocking(
          Files.createTempDirectory("sigilaris-bootstrap-http-storage"),
        ),
      ) { root =>
        IO.blocking(deleteRecursively(root))
      }
      .map(StorageLayout.fromRoot)

  private def deleteRecursively(
      path: Path,
  ): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)): stream =>
        stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)

  private def transportAuthFor(
      localNodeId: String,
      knownPeers: List[String],
  ): StaticPeerTransportAuth =
    StaticPeerTransportAuth.testing(
      StaticPeerTopology
        .parse(
          localNodeIdentity = localNodeId,
          knownPeers = knownPeers,
          directNeighbors = knownPeers,
        )
        .toOption
        .get,
    )

  private def signedTransportProof(
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: String,
      path: String,
      body: String,
  ): String =
    GossipTransportAuth
      .issueTransportProof(
        transportAuth = transportAuth,
        authenticatedPeer = PeerIdentity.unsafe(authenticatedPeer),
        httpMethod = "POST",
        requestPath = path,
        requestBodyBytes = body.getBytes(StandardCharsets.UTF_8),
      )
      .orElse(
        GossipTransportAuth.issueTransportProof(
          transportAuth = transportAuthFor(authenticatedPeer, List.empty),
          authenticatedPeer = PeerIdentity.unsafe(authenticatedPeer),
          httpMethod = "POST",
          requestPath = path,
          requestBodyBytes = body.getBytes(StandardCharsets.UTF_8),
        ),
      )
      .toOption
      .get

  private def bootstrapCapability(
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: String,
      targetPeer: String,
      sessionId: DirectionalSessionId,
      path: String,
      body: String,
  ): String =
    GossipTransportAuth
      .issueBootstrapCapability(
        transportAuth = transportAuth,
        authenticatedPeer = PeerIdentity.unsafe(authenticatedPeer),
        targetPeer = PeerIdentity.unsafe(targetPeer),
        sessionId = sessionId,
        httpMethod = "POST",
        requestPath = path,
        requestBodyBytes = body.getBytes(StandardCharsets.UTF_8),
      )
      .toOption
      .get

  test(
    "bootstrap transport serves finalized suggestion, snapshot nodes, replay, and backfill on an open session",
  ):
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
      val graph   = snapshotGraph("10")
      val history = historicalChain("1f")
      val anchor =
        finalizedSuggestionFromParent(
          parentProposal = history.proposal2,
          seed = "10",
          anchorHeight = 3L,
          stateRoot = StateRoot(graph.rootHash.toUInt256),
        )

      for
        _ <- server.seedSnapshot(
          graph.rootNode,
          graph.leftNode,
          graph.rightNode,
        )
        _ <- server.seedProposals(
          Vector(
            history.genesis,
            history.proposal1,
            history.proposal2,
            anchor.proposal,
            anchor.finalizedProof.child,
            anchor.finalizedProof.grandchild,
          ),
        )
        session <- openOutboundViaHttp(client, server)
        transport = bootstrapTransport(server)
        suggestion <- transport.finalizedAnchorSuggestions.bestFinalized(
          session,
          chainId,
        )
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
        assertEquals(
          nodes.map(_.map(_.hash).toSet),
          Right(Set(graph.rootHash, graph.leftHash, graph.rightHash)),
        )
        assertEquals(
          replay.map(_.map(_.proposalId)),
          Right(
            Vector(
              anchor.finalizedProof.child.proposalId,
              anchor.finalizedProof.grandchild.proposalId,
            ),
          ),
        )
        assertEquals(
          backfill.map(_.map(_.proposalId)),
          Right(
            Vector(
              history.proposal2.proposalId,
              history.proposal1.proposalId,
              history.genesis.proposalId,
            ),
          ),
        )

  test(
    "bootstrap transport rejects requests after the parent session is disconnected",
  ):
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
        _ <- server.seedSnapshot(
          graph.rootNode,
          graph.leftNode,
          graph.rightNode,
        )
        _ <- server.seedProposals(
          Vector(
            anchor.proposal,
            anchor.finalizedProof.child,
            anchor.finalizedProof.grandchild,
          ),
        )
        session <- openOutboundViaHttp(client, server)
        _ <- server.postNoBody(
          s"/gossip/session/${session.sessionId.value}/disconnect",
          authenticatedPeer = Some(client.localNodeId),
        )
        result <- bootstrapTransport(server).finalizedAnchorSuggestions
          .bestFinalized(session, chainId)
      yield result match
        case Left(rejection: CanonicalRejection.HandshakeRejected) =>
          assertEquals(rejection.reason, "sessionNotOpen")
        case other =>
          fail("expected handshake rejection but saw " + other.toString)

  test("bootstrap endpoints require a session-bound capability header"):
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
      for
        session <- openOutboundViaHttp(client, server)
        requestPath =
          s"/gossip/bootstrap/finalized/${session.sessionId.value}/${chainId.value}"
        missingResponse <- server.postNoBody(
          requestPath,
          authenticatedPeer = Some(client.localNodeId),
          bootstrapCapability = None,
        )
        missingRejection <- IO.fromEither(
          decode[RejectionWire](missingResponse.body).leftMap(
            new IllegalStateException(_),
          ),
        )
        mismatchedResponse <- server.postNoBody(
          requestPath,
          authenticatedPeer = Some(client.localNodeId),
          bootstrapCapability = Some(
            bootstrapCapability(
              transportAuth = server.transportAuth,
              authenticatedPeer = client.localNodeId,
              targetPeer = "node-c",
              sessionId = session.sessionId,
              path = requestPath,
              body = "",
            ),
          ),
        )
        mismatchedRejection <- IO.fromEither(
          decode[RejectionWire](mismatchedResponse.body)
            .leftMap(new IllegalStateException(_)),
        )
      yield
        assertEquals(missingResponse.status, 400)
        assertEquals(missingRejection.reason, "missingBootstrapCapability")
        assertEquals(mismatchedResponse.status, 400)
        assertEquals(mismatchedRejection.reason, "bootstrapCapabilityMismatch")

  test(
    "assembled runtime bootstrap can sync a snapshot from remote HTTP bootstrap peers",
  ):
    (
      for
        requesterClock  <- Resource.eval(TestClock.create(startedAt))
        requesterLayout <- storageLayoutResource
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
      yield (requesterClock, requesterLayout, serverB, serverC)
    ).use: (requesterClock, requesterLayout, serverB, serverC) =>
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
            .leftMap(new IllegalArgumentException(_)),
        )
        _ <- serverB.seedSnapshot(graph.rootNode, graph.leftNode)
        _ <- serverC.seedSnapshot(graph.rightNode)
        _ <- serverB.seedProposals(
          Vector(
            anchor.proposal,
            anchor.finalizedProof.child,
            anchor.finalizedProof.grandchild,
          ),
        )
        _ <- serverC.seedProposals(
          Vector(
            anchor.proposal,
            anchor.finalizedProof.child,
            anchor.finalizedProof.grandchild,
          ),
        )
        bootstrapResult <- HotStuffRuntimeBootstrap
          .fromTopology[IO](
            topology = topology,
            transportAuth = StaticPeerTransportAuth.testing(topology),
            consensusConfig = consensusConfig,
            clock = requesterClock,
            storageLayout = requesterLayout,
            bootstrapTransport = Some(
              HotStuffBootstrapHttpTransport.services[IO](
                Map(
                  PeerIdentity.unsafe("node-b") -> serverB.baseUri,
                  PeerIdentity.unsafe("node-c") -> serverC.baseUri,
                ),
                transportAuth = StaticPeerTransportAuth.testing(topology),
                proposalCatchUpReadiness =
                  Some(ProposalCatchUpReadiness.ready[IO]),
              ),
            ),
          )
          .use: requesterEither =>
            for
              requester <- IO.fromEither(
                requesterEither.leftMap(new IllegalArgumentException(_)),
              )
              sessionB <- openOutboundViaHttp(requester.runtime, serverB)
              sessionC <- openOutboundViaHttp(requester.runtime, serverC)
              result <- requester.consensus.bootstrap(
                chainId = chainId,
                sessions = Vector(sessionB, sessionC),
                startedAt = startedAt,
                liveProposals = Vector.empty,
              )
              diagnostics <- requester.consensus.currentBootstrapDiagnostics
              storedRoot <- IO
                .fromOption(
                  requester.consensus.bootstrapLifecycle
                    .flatMap(_.nodeStore.some),
                )(new IllegalStateException("missingBootstrapLifecycle"))
                .flatMap(_.get(graph.rootHash))
              storedLeft <- IO
                .fromOption(
                  requester.consensus.bootstrapLifecycle
                    .flatMap(_.nodeStore.some),
                )(new IllegalStateException("missingBootstrapLifecycle"))
                .flatMap(_.get(graph.leftHash))
              storedRight <- IO
                .fromOption(
                  requester.consensus.bootstrapLifecycle
                    .flatMap(_.nodeStore.some),
                )(new IllegalStateException("missingBootstrapLifecycle"))
                .flatMap(_.get(graph.rightHash))
            yield (result, diagnostics, storedRoot, storedLeft, storedRight)
      yield
        val (result, diagnostics, storedRoot, storedLeft, storedRight) =
          bootstrapResult
        assertEquals(result.map(_.anchor), Right(anchor))
        assertEquals(
          result.map(_.snapshot.metadata.status),
          Right(SnapshotStatus.Complete),
        )
        assertEquals(result.map(_.snapshot.fetchedNodeCount), Right(3L))
        assertEquals(
          result.map(_.forwardCatchUp.voteReadiness),
          Right(BootstrapVoteReadiness.Ready),
        )
        assertEquals(diagnostics.phase, BootstrapPhase.Ready)
        assertEquals(
          diagnostics.chains(chainId).pinnedAnchor,
          Some(anchor.snapshotAnchor),
        )
        assertEquals(storedRoot, Some(graph.rootNode.node))
        assertEquals(storedLeft, Some(graph.leftNode.node))
        assertEquals(storedRight, Some(graph.rightNode.node))

  test(
    "runtime with remote bootstrap transport still serves bootstrap endpoints from local state",
  ):
    (
      for
        requesterClock  <- Resource.eval(TestClock.create(startedAt))
        requesterLayout <- storageLayoutResource
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
      yield (requesterClock, requesterLayout, remote, client)
    ).use: (requesterClock, requesterLayout, remote, client) =>
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
            .leftMap(new IllegalArgumentException(_)),
        )
        _ <- remote.seedSnapshot(
          remoteGraph.rootNode,
          remoteGraph.leftNode,
          remoteGraph.rightNode,
        )
        _ <- remote.seedProposals(
          Vector(
            remoteAnchor.proposal,
            remoteAnchor.finalizedProof.child,
            remoteAnchor.finalizedProof.grandchild,
          ),
        )
        response <- HotStuffRuntimeBootstrap
          .fromTopology[IO](
            topology = topology,
            transportAuth = StaticPeerTransportAuth.testing(topology),
            consensusConfig = consensusConfig,
            clock = requesterClock,
            storageLayout = requesterLayout,
            bootstrapTransport = Some(
              HotStuffBootstrapHttpTransport.services[IO](
                Map(PeerIdentity.unsafe("node-b") -> remote.baseUri),
                transportAuth = StaticPeerTransportAuth.testing(topology),
              ),
            ),
          )
          .use: requesterEither =>
            for
              requester <- IO.fromEither(
                requesterEither.leftMap(new IllegalArgumentException(_)),
              )
              _ <- seedRuntimeBootstrapLocal(
                requester,
                proposals = Vector(
                  localAnchor.proposal,
                  localAnchor.finalizedProof.child,
                  localAnchor.finalizedProof.grandchild,
                ),
                snapshotNodes = Vector(
                  localGraph.rootNode,
                  localGraph.leftNode,
                  localGraph.rightNode,
                ),
              )
              response <- RuntimeServer
                .resource(requester)
                .use: requesterServer =>
                  for
                    session <- openOutboundViaHttp(
                      client.runtime,
                      requesterServer,
                    )
                    transport =
                      HotStuffBootstrapHttpTransport.services[IO](
                        Map(
                          PeerIdentity
                            .unsafe("node-a") -> requesterServer.baseUri,
                        ),
                        transportAuth = requesterServer.transportAuth,
                      )
                    suggestion <- transport.finalizedAnchorSuggestions
                      .bestFinalized(session, chainId)
                    nodes <- transport.snapshotNodeFetch.fetchNodes(
                      session = session,
                      chainId = chainId,
                      stateRoot = localAnchor.stateRoot,
                      hashes = Vector(localGraph.rootHash),
                    )
                  yield suggestion -> nodes
            yield response
      yield
        assertEquals(response._1, Right(Some(localAnchor)))
        assertEquals(
          response._2.map(_.map(_.hash)),
          Right(Vector(localGraph.rootHash)),
        )

  test(
    "bootstrap http transport applies explicit request timeouts to outbound requests",
  ):
    val httpClient = RecordingHttpClient()
    httpClient.respondImmediately(
      200,
      FinalizedSuggestionResponseWire(None).asJson.noSpaces,
    )
    val session =
      BootstrapSessionBinding(
        peer = PeerIdentity.unsafe("node-b"),
        sessionId = DirectionalSessionId
          .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
          .toOption
          .get,
        authenticatedPeer = PeerIdentity.unsafe("node-a"),
      )

    for result <- HotStuffBootstrapHttpTransport
        .services[IO](
          peerBaseUris = Map(session.peer -> "http://bootstrap.test"),
          transportAuth = transportAuthFor(
            localNodeId = session.authenticatedPeer.value,
            knownPeers = List(session.peer.value),
          ),
          httpClient = httpClient,
          requestTimeout = Duration.ofMillis(250L),
        )
        .finalizedAnchorSuggestions
        .bestFinalized(session, chainId)
    yield
      assertEquals(result, Right(None))
      assertEquals(
        httpClient.recordedTimeouts,
        Vector(Some(Duration.ofMillis(250L))),
      )
      assertEquals(
        httpClient.recordedHeaderValues(
          GossipTransportAuth.AuthenticatedPeerHeaderName,
        ),
        Vector(Some("node-a")),
      )
      assertEquals(
        httpClient.recordedHeaderValues(
          GossipTransportAuth.BootstrapCapabilityHeaderName,
        ),
        Vector(
          Some(
            bootstrapCapability(
              transportAuth = transportAuthFor("node-a", List("node-b")),
              authenticatedPeer = "node-a",
              targetPeer = "node-b",
              sessionId = session.sessionId,
              path =
                s"/gossip/bootstrap/finalized/${session.sessionId.value}/${chainId.value}",
              body = "",
            ),
          ),
        ),
      )

  test("bootstrap http transport bounds concurrent outbound requests"):
    val httpClient = RecordingHttpClient()
    val session =
      BootstrapSessionBinding(
        peer = PeerIdentity.unsafe("node-b"),
        sessionId = DirectionalSessionId
          .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
          .toOption
          .get,
        authenticatedPeer = PeerIdentity.unsafe("node-a"),
      )
    val transport =
      HotStuffBootstrapHttpTransport.services[IO](
        peerBaseUris = Map(session.peer -> "http://bootstrap.test"),
        transportAuth = transportAuthFor(
          localNodeId = session.authenticatedPeer.value,
          knownPeers = List(session.peer.value),
        ),
        httpClient = httpClient,
        maxConcurrentRequests = 1,
      )

    for
      fiber1 <- transport.finalizedAnchorSuggestions
        .bestFinalized(session, chainId)
        .start
      _ <- IO.blocking(httpClient.awaitStartedCount(1))
      fiber2 <- transport.finalizedAnchorSuggestions
        .bestFinalized(session, chainId)
        .start
      _                      <- IO.sleep(100.millis)
      startedBeforeRelease   <- IO(httpClient.startedCount)
      maxActiveBeforeRelease <- IO(httpClient.maxActiveCount)
      _ <- IO.blocking(
        httpClient.completeNext(
          200,
          FinalizedSuggestionResponseWire(None).asJson.noSpaces,
        ),
      )
      _ <- IO.blocking(httpClient.awaitStartedCount(2))
      _ <- IO.blocking(
        httpClient.completeNext(
          200,
          FinalizedSuggestionResponseWire(None).asJson.noSpaces,
        ),
      )
      result1 <- fiber1.joinWithNever
      result2 <- fiber2.joinWithNever
    yield
      assertEquals(startedBeforeRelease, 1)
      assertEquals(maxActiveBeforeRelease, 1)
      assertEquals(httpClient.maxActiveCount, 1)
      assertEquals(result1, Right(None))
      assertEquals(result2, Right(None))

  private def bootstrapTransport(
      server: Harness,
  ): HotStuffBootstrapTransportServices[IO] =
    HotStuffBootstrapHttpTransport.services[IO](
      Map(PeerIdentity.unsafe(server.localNodeId) -> server.baseUri),
      transportAuth = server.transportAuth,
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
        proposalEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      response <- to.postJson(
        "/gossip/session/open",
        toProposalWire(proposal).asJson.noSpaces,
        authenticatedPeer = Some(proposal.initiator.value),
      )
      ackWire <- IO.fromEither(
        decode[SessionOpenAckWire](response.body).leftMap(
          new IllegalStateException(_),
        ),
      )
      ack <- IO.fromEither(
        toAck(ackWire).leftMap(new IllegalArgumentException(_)),
      )
      applied <- from.applyHandshakeAck(ack)
      _ <- IO.fromEither(
        applied.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
    yield BootstrapSessionBinding(
      peer = PeerIdentity.unsafe(to.localNodeId),
      sessionId = proposal.sessionId,
      authenticatedPeer = proposal.initiator,
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
        proposalEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      response <- to.postJson(
        "/gossip/session/open",
        toProposalWire(proposal).asJson.noSpaces,
        authenticatedPeer = Some(proposal.initiator.value),
      )
      ackWire <- IO.fromEither(
        decode[SessionOpenAckWire](response.body).leftMap(
          new IllegalStateException(_),
        ),
      )
      ack <- IO.fromEither(
        toAck(ackWire).leftMap(new IllegalArgumentException(_)),
      )
      applied <- from.applyHandshakeAck(ack)
      _ <- IO.fromEither(
        applied.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
    yield BootstrapSessionBinding(
      peer = PeerIdentity.unsafe(to.localNodeId),
      sessionId = proposal.sessionId,
      authenticatedPeer = proposal.initiator,
    )

  private def toProposalWire(
      proposal: SessionOpenProposal,
  ): SessionOpenProposalWire =
    SessionOpenProposalWire(
      sessionId = proposal.sessionId.value,
      peerCorrelationId = proposal.peerCorrelationId.value,
      initiator = proposal.initiator.value,
      acceptor = proposal.acceptor.value,
      subscriptions = proposal.subscriptions.values.toVector.map(ct =>
        ChainTopicWire(ct.chainId.value, ct.topic.value),
      ),
      heartbeatIntervalMs = proposal.heartbeatInterval.map(_.toMillis),
      livenessTimeoutMs = proposal.livenessTimeout.map(_.toMillis),
      maxControlRetryIntervalMs =
        proposal.maxControlRetryInterval.map(_.toMillis),
    )

  private def toAck(
      wire: SessionOpenAckWire,
  ): Either[String, SessionOpenAck] =
    for
      sessionId         <- DirectionalSessionId.parse(wire.sessionId)
      peerCorrelationId <- PeerCorrelationId.parse(wire.peerCorrelationId)
      initiator         <- PeerIdentity.parse(wire.initiator)
      acceptor          <- PeerIdentity.parse(wire.acceptor)
      subscriptions <- wire.subscriptions.toVector
        .traverse: entry =>
          for
            chainId <- ChainId.parse(entry.chainId)
            topic   <- GossipTopic.parse(entry.topic)
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
        maxControlRetryInterval =
          Duration.ofMillis(wire.maxControlRetryIntervalMs),
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
    val leftHash  = leftLeaf.toHash
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
        stateRoot = stateRoot,
        bodyRoot = BodyRoot(hex(seed + "11")),
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

  private def finalizedSuggestionFromParent(
      parentProposal: Proposal,
      seed: String,
      anchorHeight: Long,
      stateRoot: StateRoot,
  ): FinalizedAnchorSuggestion =
    val anchor =
      childProposal(
        parentProposal = parentProposal,
        seed = seed + "10",
        height = anchorHeight,
        stateRoot = stateRoot,
      )
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
    val genesis   = genesisProposal(seed + "00")
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
      transportAuth: StaticPeerTransportAuth,
      runtime: TxGossipRuntime[IO, HotStuffGossipArtifact],
      source: InMemoryHotStuffArtifactSource[IO],
      sink: InMemoryHotStuffArtifactSink[IO],
      nodeStore: SnapshotNodeStore[IO],
      baseUri: String,
  ):
    def postJson(
        path: String,
        body: String,
        authenticatedPeer: Option[String] = None,
        transportProof: Option[String] = None,
        autoSignTransportProof: Boolean = true,
        bootstrapCapability: Option[String] = None,
    ): IO[Response] =
      IO.blocking:
        val client = HttpClient.newHttpClient()
        val builder = HttpRequest
          .newBuilder(URI.create(s"$baseUri$path"))
          .header("content-type", "application/json")
        val resolvedProof =
          transportProof.orElse:
            authenticatedPeer
              .filter(_ => autoSignTransportProof)
              .map(peer =>
                signedTransportProof(
                  transportAuth = transportAuth,
                  authenticatedPeer = peer,
                  path = path,
                  body = body,
                ),
              )
        authenticatedPeer.foreach: value =>
          builder.header(
            GossipTransportAuth.AuthenticatedPeerHeaderName,
            value,
          )
        resolvedProof.foreach: value =>
          builder.header(
            GossipTransportAuth.TransportProofHeaderName,
            value,
          )
        bootstrapCapability.foreach: value =>
          builder.header(
            GossipTransportAuth.BootstrapCapabilityHeaderName,
            value,
          )
        val request =
          builder
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response =
          client.send(request, HttpResponse.BodyHandlers.ofString())
        Response(response.statusCode(), response.body())

    def postNoBody(
        path: String,
        authenticatedPeer: Option[String] = None,
        transportProof: Option[String] = None,
        autoSignTransportProof: Boolean = true,
        bootstrapCapability: Option[String] = None,
    ): IO[Response] =
      IO.blocking:
        val client = HttpClient.newHttpClient()
        val builder = HttpRequest
          .newBuilder(URI.create(s"$baseUri$path"))
        val resolvedProof =
          transportProof.orElse:
            authenticatedPeer
              .filter(_ => autoSignTransportProof)
              .map(peer =>
                signedTransportProof(
                  transportAuth = transportAuth,
                  authenticatedPeer = peer,
                  path = path,
                  body = "",
                ),
              )
        authenticatedPeer.foreach: value =>
          builder.header(
            GossipTransportAuth.AuthenticatedPeerHeaderName,
            value,
          )
        resolvedProof.foreach: value =>
          builder.header(
            GossipTransportAuth.TransportProofHeaderName,
            value,
          )
        bootstrapCapability.foreach: value =>
          builder.header(
            GossipTransportAuth.BootstrapCapabilityHeaderName,
            value,
          )
        val request =
          builder
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response =
          client.send(request, HttpResponse.BodyHandlers.ofString())
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
            sink
              .applyEvent(event)
              .flatMap:
                case Left(rejection) =>
                  IO.raiseError(new IllegalStateException(rejection.reason))
                case Right(_) =>
                  IO.unit
      }

    def seedSnapshot(
        nodes: SnapshotTrieNode*,
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
              .leftMap(new IllegalArgumentException(_)),
          ),
        )
        registry      = StaticPeerRegistry(topology)
        transportAuth = StaticPeerTransportAuth.testing(topology)
        authenticator = StaticPeerAuthenticator[IO](registry)
        clock <- Resource.eval(TestClock.create(start))
        given GossipClock[IO] = clock
        source <- Resource.eval(InMemoryHotStuffArtifactSource.create[IO])
        sink <- Resource.eval(
          InMemoryHotStuffArtifactSink.create[IO](
            validatorSet = validatorSet,
            relayPolicy = HotStuffRelayPolicy(relayValidatedArtifacts = false),
            relayPublisher = source,
          ),
        )
        stateStore <- Resource.eval(
          TxGossipStateStore.inMemory[IO](
            GossipSessionEngine(registry.localPeer, topology),
          ),
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
            diagnostics =
              BootstrapDiagnosticsSource.const[IO](BootstrapDiagnostics.empty),
          )
        server <- ArmeriaServer.resource[IO](
          ArmeriaServerConfig(port = 0),
          TxGossipArmeriaAdapter
            .endpoints[IO, HotStuffGossipArtifact](runtime, transportAuth) ++
            HotStuffBootstrapArmeriaAdapter
              .endpoints[IO, HotStuffGossipArtifact](
                sessionRuntime = runtime,
                bootstrapServices = bootstrapServices,
                transportAuth = transportAuth,
              ),
        )
      yield Harness(
        localNodeId = localNodeId,
        transportAuth = transportAuth,
        runtime = runtime,
        source = source,
        sink = sink,
        nodeStore = nodeStore,
        baseUri = s"http://127.0.0.1:${server.activeLocalPort()}",
      )

  private final case class RuntimeServer(
      localNodeId: String,
      transportAuth: StaticPeerTransportAuth,
      baseUri: String,
  ):
    def postJson(
        path: String,
        body: String,
        authenticatedPeer: Option[String] = None,
        transportProof: Option[String] = None,
        autoSignTransportProof: Boolean = true,
        bootstrapCapability: Option[String] = None,
    ): IO[Response] =
      IO.blocking:
        val client = HttpClient.newHttpClient()
        val builder = HttpRequest
          .newBuilder(URI.create(s"$baseUri$path"))
          .header("content-type", "application/json")
        val resolvedProof =
          transportProof.orElse:
            authenticatedPeer
              .filter(_ => autoSignTransportProof)
              .map(peer =>
                signedTransportProof(
                  transportAuth = transportAuth,
                  authenticatedPeer = peer,
                  path = path,
                  body = body,
                ),
              )
        authenticatedPeer.foreach: value =>
          builder.header(
            GossipTransportAuth.AuthenticatedPeerHeaderName,
            value,
          )
        resolvedProof.foreach: value =>
          builder.header(
            GossipTransportAuth.TransportProofHeaderName,
            value,
          )
        bootstrapCapability.foreach: value =>
          builder.header(
            GossipTransportAuth.BootstrapCapabilityHeaderName,
            value,
          )
        val request =
          builder
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response =
          client.send(request, HttpResponse.BodyHandlers.ofString())
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
            transportAuth = bootstrap.transportAuth,
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
            sink
              .applyEvent(event)
              .flatMap:
                case Left(rejection) =>
                  IO.raiseError(new IllegalStateException(rejection.reason))
                case Right(_) =>
                  IO.unit
      } *> lifecycle.nodeStore.putAll(snapshotNodes)

  private final class TestClock private (ref: Ref[IO, Instant])
      extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))

  private final class RecordingHttpClient private () extends HttpClient:
    private val timeouts =
      new java.util.concurrent.CopyOnWriteArrayList[Option[Duration]]()
    private val requests =
      new java.util.concurrent.CopyOnWriteArrayList[HttpRequest]()
    private val pendingResponses =
      new java.util.concurrent.LinkedBlockingQueue[
        CompletableFuture[HttpResponse[String]],
      ]()
    private val startedRef =
      new java.util.concurrent.atomic.AtomicInteger(0)
    private val activeRef =
      new java.util.concurrent.atomic.AtomicInteger(0)
    private val maxActiveRef =
      new java.util.concurrent.atomic.AtomicInteger(0)
    private val immediateResponseRef =
      new java.util.concurrent.atomic.AtomicReference[
        Option[(Int, String)],
      ](None)

    def respondImmediately(
        statusCode: Int,
        body: String,
    ): Unit =
      immediateResponseRef.set((statusCode, body).some)

    def completeNext(
        statusCode: Int,
        body: String,
    ): Unit =
      Option(pendingResponses.poll())
        .getOrElse(
          throw new IllegalStateException("no pending bootstrap request"),
        )
        .complete(httpResponse(statusCode, body)): Unit

    def awaitStartedCount(
        expected: Int,
    ): Unit =
      val deadlineNanos =
        System.nanoTime() + Duration.ofSeconds(5L).toNanos
      while startedRef.get() < expected && System.nanoTime() < deadlineNanos do
        Thread.sleep(10L)
      if startedRef.get() < expected then
        throw new IllegalStateException(
          s"timed out waiting for $expected started requests",
        )

    def recordedTimeouts: Vector[Option[Duration]] =
      timeouts.asScala.toVector

    def recordedHeaderValues(
        name: String,
    ): Vector[Option[String]] =
      requests.asScala.toVector.map: request =>
        val values = request.headers().allValues(name).asScala.toVector
        values.headOption

    def startedCount: Int =
      startedRef.get()

    def maxActiveCount: Int =
      maxActiveRef.get()

    override def cookieHandler(): Optional[java.net.CookieHandler] =
      Optional.empty()

    override def connectTimeout(): Optional[Duration] =
      Optional.empty()

    override def followRedirects(): HttpClient.Redirect =
      HttpClient.Redirect.NEVER

    override def proxy(): Optional[java.net.ProxySelector] =
      Optional.empty()

    override def sslContext(): javax.net.ssl.SSLContext =
      javax.net.ssl.SSLContext.getDefault

    override def sslParameters(): javax.net.ssl.SSLParameters =
      new javax.net.ssl.SSLParameters()

    override def authenticator(): Optional[java.net.Authenticator] =
      Optional.empty()

    override def version(): HttpClient.Version =
      HttpClient.Version.HTTP_1_1

    override def executor(): Optional[java.util.concurrent.Executor] =
      Optional.empty()

    override def send[A](
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler[A],
    ): HttpResponse[A] =
      throw new UnsupportedOperationException("send not used in tests")

    override def sendAsync[A](
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler[A],
    ): CompletableFuture[HttpResponse[A]] =
      track(request).asInstanceOf[CompletableFuture[HttpResponse[A]]]

    override def sendAsync[A](
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler[A],
        pushPromiseHandler: HttpResponse.PushPromiseHandler[A],
    ): CompletableFuture[HttpResponse[A]] =
      sendAsync(request, responseBodyHandler)

    override def newWebSocketBuilder(): java.net.http.WebSocket.Builder =
      throw new UnsupportedOperationException("websocket not used in tests")

    private def track(
        request: HttpRequest,
    ): CompletableFuture[HttpResponse[String]] =
      requests.add(request)
      timeouts.add(
        if request.timeout().isPresent then request.timeout().get().some
        else none[Duration],
      )
      startedRef.incrementAndGet()
      val active = activeRef.incrementAndGet()
      updateMaxActive(active)
      val future =
        immediateResponseRef.get() match
          case Some((statusCode, body)) =>
            CompletableFuture.completedFuture(httpResponse(statusCode, body))
          case None =>
            val pending = new CompletableFuture[HttpResponse[String]]()
            pendingResponses.put(pending)
            pending
      future.whenComplete((_: HttpResponse[String], _: Throwable) =>
        activeRef.decrementAndGet(): Unit,
      )
      future

    @SuppressWarnings(Array("org.wartremover.warts.While"))
    private def updateMaxActive(
        active: Int,
    ): Unit =
      var current = maxActiveRef.get()
      while active > current && !maxActiveRef.compareAndSet(current, active) do
        current = maxActiveRef.get()

    private def httpResponse(
        statusCodeValue: Int,
        bodyValue: String,
    ): HttpResponse[String] =
      new HttpResponse[String]:
        override def statusCode(): Int = statusCodeValue
        override def request(): HttpRequest =
          HttpRequest.newBuilder(URI.create("http://bootstrap.test")).build()
        override def previousResponse(): Optional[HttpResponse[String]] =
          Optional.empty()
        override def headers(): java.net.http.HttpHeaders =
          java.net.http.HttpHeaders.of(
            Map.empty[String, java.util.List[String]].asJava,
            (_, _) => true,
          )
        override def body(): String = bodyValue
        override def sslSession(): Optional[javax.net.ssl.SSLSession] =
          Optional.empty()
        override def uri(): URI =
          URI.create("http://bootstrap.test")
        override def version(): HttpClient.Version =
          HttpClient.Version.HTTP_1_1

  private object RecordingHttpClient:
    def apply(): RecordingHttpClient =
      new RecordingHttpClient()
