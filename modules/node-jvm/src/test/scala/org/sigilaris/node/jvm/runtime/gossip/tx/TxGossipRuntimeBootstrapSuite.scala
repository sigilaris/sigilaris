package org.sigilaris.node.jvm.runtime.gossip.tx

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite

import com.typesafe.config.ConfigFactory

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.gossip.*

final class TxGossipRuntimeBootstrapSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val subscription = SessionSubscription.unsafe(ChainTopic(chainId, GossipTopic.tx))
  private val startedAt = Instant.parse("2026-04-01T00:00:00Z")

  test("config loader builds runtime bootstrap and wires neighbor admission through the runtime"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  local-node-identity = "node-a"
        |  known-peers = ["node-b", "node-c"]
        |  direct-neighbors = ["node-b"]
        |}
        |""".stripMargin
    )

    for
      clock <- TestClock.create(startedAt)
      given GossipClock[IO] = clock
      source <- InMemoryTxArtifactSource.create[IO, TestTx]
      sink <- InMemoryTxArtifactSink.create[IO, TestTx]
      bootstrapEither <- TxGossipRuntimeBootstrap.fromConfig[IO, TestTx](
        config = config,
        clock = clock,
        source = source,
        sink = sink,
        topicContracts = GossipTopicContractRegistry.single(TxTopic.contract[TestTx]),
      )
      bootstrap <- IO.fromEither(bootstrapEither.leftMap(new IllegalArgumentException(_)))
      outboundAllowed <- bootstrap.runtime.startOutbound(PeerIdentity.unsafe("node-b"), subscription)
      outboundRejected <- bootstrap.runtime.startOutbound(PeerIdentity.unsafe("node-c"), subscription)
      inboundRejected <- bootstrap.runtime.handleInboundProposal(
        SessionOpenProposal(
          sessionId = DirectionalSessionId.random(),
          peerCorrelationId = PeerCorrelationId.random(),
          initiator = PeerIdentity.unsafe("node-c"),
          acceptor = PeerIdentity.unsafe("node-a"),
          subscriptions = subscription,
          heartbeatInterval = None,
          livenessTimeout = None,
          maxControlRetryInterval = None,
        )
      )
    yield
      assertEquals(bootstrap.topology.localNodeIdentity, PeerIdentity.unsafe("node-a"))
      assertEquals(bootstrap.registry.directNeighbors, Set(PeerIdentity.unsafe("node-b")))
      assertEquals(outboundAllowed.map(_.acceptor), Right(PeerIdentity.unsafe("node-b")))
      assertEquals(outboundRejected.left.map(_.reason), Left("nonNeighborPeer"))
      inboundRejected match
        case InboundHandshakeResult.Accepted(_, _) =>
          fail("expected non-neighbor inbound proposal to be rejected")
        case InboundHandshakeResult.Rejected(rejection) =>
          assertEquals(rejection.reason, "nonNeighborPeer")

  test("config loader accepts camelCase keys and reports missing sections"):
    val camelCase = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  localNodeIdentity = "node-a"
        |  knownPeers = ["node-b"]
        |  directNeighbors = ["node-b"]
        |}
        |""".stripMargin
    )

    val missing = ConfigFactory.parseString("sigilaris.node.gossip {}")

    assertEquals(
      StaticPeerTopologyConfig.load(camelCase).map(_.directNeighbors),
      Right(Set(PeerIdentity.unsafe("node-b"))),
    )
    assertEquals(
      StaticPeerTopologyConfig.load(missing).left.map(_.startsWith("missing config path")),
      Left(true),
    )

  private final class TestClock private (ref: Ref[IO, Instant]) extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))

  private final case class TestTx(body: String)

  private given ByteEncoder[TestTx] = ByteEncoder[Utf8].contramap(tx => Utf8(tx.body))
  private given Hash[TestTx] = Hash.build
  private given TxIdentity[TestTx] = TxIdentity.fromHash[TestTx]
