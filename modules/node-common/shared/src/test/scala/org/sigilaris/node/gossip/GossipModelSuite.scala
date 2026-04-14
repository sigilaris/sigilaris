package org.sigilaris.node.gossip

import java.time.{Duration, Instant}

import munit.FunSuite
import scodec.bits.ByteVector
import scala.util.Random

final class GossipModelSuite extends FunSuite:

  private val localPeer  = PeerIdentity.unsafe("node-a")
  private val remotePeer = PeerIdentity.unsafe("node-b")
  private val chainId    = ChainId.unsafe("chain-main")
  private val txSubscription =
    SessionSubscription.unsafe(ChainTopic(chainId, GossipTopic.tx))
  private val openingNow = Instant.parse("2026-03-31T10:15:30Z")

  test(
    "directional session id and peer correlation id require lowercase canonical UUIDv4",
  ):
    val valid = "9b746bd2-3b0f-4d66-bd67-cba72d6628f8"
    assertEquals(DirectionalSessionId.parse(valid).map(_.value), Right(valid))
    assertEquals(PeerCorrelationId.parse(valid).map(_.value), Right(valid))
    assert(DirectionallyInvalid("9B746BD2-3B0F-4D66-BD67-CBA72D6628F8"))
    assert(DirectionallyInvalid("9b746bd2-3b0f-1d66-bd67-cba72d6628f8"))

  test("peer correlation ids use lexicographic tiebreak"):
    val smaller = PeerCorrelationId
      .parse("11111111-1111-4111-8111-111111111111")
      .toOption
      .get
    val larger = PeerCorrelationId
      .parse("22222222-2222-4222-8222-222222222222")
      .toOption
      .get

    assert(PeerCorrelationId.lexicographicCompare(smaller, larger) < 0)
    assert(PeerCorrelationId.lexicographicCompare(larger, smaller) > 0)

  test("random id helpers support reproducible seeded generation"):
    val sessionA = DirectionalSessionId.fromRandom(Random(42L))
    val sessionB = DirectionalSessionId.fromRandom(Random(42L))
    val correlationA = PeerCorrelationId.fromRandom(Random(99L))
    val correlationB = PeerCorrelationId.fromRandom(Random(99L))

    assertEquals(sessionA.value, sessionB.value)
    assertEquals(correlationA.value, correlationB.value)
    assert(DirectionalSessionId.parse(sessionA.value).isRight)
    assert(PeerCorrelationId.parse(correlationA.value).isRight)

  test("handshake negotiation applies defaults and validates ranges"):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = None,
      livenessTimeout = None,
      maxControlRetryInterval = None,
    )

    val resolved = SessionNegotiation.resolveProposal(proposal)
    assertEquals(
      resolved,
      Right(
        NegotiatedSessionParameters(
          heartbeatInterval = Duration.ofSeconds(10),
          livenessTimeout = Duration.ofSeconds(30),
          maxControlRetryInterval = Duration.ofSeconds(30),
        ),
      ),
    )

    val invalid = proposal.copy(
      heartbeatInterval = Some(Duration.ofMillis(999)),
      livenessTimeout = Some(Duration.ofSeconds(2)),
    )
    assert(SessionNegotiation.resolveProposal(invalid).isLeft)

  test(
    "handshake ack bundled invalid payload is rejected at the first structural mismatch",
  ):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val invalidAck = SessionOpenAck(
      sessionId = proposal.sessionId,
      peerCorrelationId = proposal.peerCorrelationId,
      initiator = proposal.initiator,
      acceptor = proposal.acceptor,
      subscriptions = SessionSubscription.unsafe(
        ChainTopic(chainId, GossipTopic.tx),
        ChainTopic(ChainId.unsafe("chain-side"), GossipTopic.tx),
      ),
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(12),
        livenessTimeout = Duration.ofSeconds(25),
        maxControlRetryInterval = Duration.ofSeconds(46),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, invalidAck).left.map(_.reason),
      Left("handshakeAckSubscriptionMismatch"),
    )

    val validAck = SessionNegotiation
      .acknowledge(
        proposal = proposal,
        heartbeatInterval = Duration.ofSeconds(8),
        livenessTimeout = Duration.ofSeconds(35),
        maxControlRetryInterval = Duration.ofSeconds(30),
      )
      .toOption
      .get
    assert(SessionNegotiation.validateAck(proposal, validAck).isRight)

  test("validateAck rejects subscription mismatch independently"):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val ack = SessionOpenAck(
      sessionId = proposal.sessionId,
      peerCorrelationId = proposal.peerCorrelationId,
      initiator = proposal.initiator,
      acceptor = proposal.acceptor,
      subscriptions = SessionSubscription.unsafe(
        ChainTopic(chainId, GossipTopic.tx),
        ChainTopic(ChainId.unsafe("chain-side"), GossipTopic.tx),
      ),
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(45),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, ack).left.map(_.reason),
      Left("handshakeAckSubscriptionMismatch"),
    )

  test("validateAck rejects session id mismatch independently"):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val ack = SessionOpenAck(
      sessionId = DirectionalSessionId
        .parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        .toOption
        .get,
      peerCorrelationId = proposal.peerCorrelationId,
      initiator = proposal.initiator,
      acceptor = proposal.acceptor,
      subscriptions = proposal.subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(45),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, ack).left.map(_.reason),
      Left("handshakeAckSessionMismatch"),
    )

  test("validateAck rejects peer correlation mismatch independently"):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val ack = SessionOpenAck(
      sessionId = proposal.sessionId,
      peerCorrelationId = PeerCorrelationId
        .parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        .toOption
        .get,
      initiator = proposal.initiator,
      acceptor = proposal.acceptor,
      subscriptions = proposal.subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(45),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, ack).left.map(_.reason),
      Left("handshakeAckPeerCorrelationMismatch"),
    )

  test("validateAck rejects initiator mismatch independently"):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val ack = SessionOpenAck(
      sessionId = proposal.sessionId,
      peerCorrelationId = proposal.peerCorrelationId,
      initiator = PeerIdentity.unsafe("node-c"),
      acceptor = proposal.acceptor,
      subscriptions = proposal.subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(45),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, ack).left.map(_.reason),
      Left("handshakeAckInitiatorMismatch"),
    )

  test("validateAck rejects acceptor mismatch independently"):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val ack = SessionOpenAck(
      sessionId = proposal.sessionId,
      peerCorrelationId = proposal.peerCorrelationId,
      initiator = proposal.initiator,
      acceptor = PeerIdentity.unsafe("node-c"),
      subscriptions = proposal.subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(45),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, ack).left.map(_.reason),
      Left("handshakeAckAcceptorMismatch"),
    )

  test("validateAck rejects heartbeat interval widening independently"):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val ack = SessionOpenAck(
      sessionId = proposal.sessionId,
      peerCorrelationId = proposal.peerCorrelationId,
      initiator = proposal.initiator,
      acceptor = proposal.acceptor,
      subscriptions = proposal.subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(11),
        livenessTimeout = Duration.ofSeconds(33),
        maxControlRetryInterval = Duration.ofSeconds(45),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, ack).left.map(_.reason),
      Left("invalidNegotiationValue"),
    )

  test(
    "validateAck rejects liveness timeout below heartbeat floor independently",
  ):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val ack = SessionOpenAck(
      sessionId = proposal.sessionId,
      peerCorrelationId = proposal.peerCorrelationId,
      initiator = proposal.initiator,
      acceptor = proposal.acceptor,
      subscriptions = proposal.subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(29),
        maxControlRetryInterval = Duration.ofSeconds(45),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, ack).left.map(_.reason),
      Left("invalidNegotiationValue"),
    )

  test("validateAck rejects control retry interval widening independently"):
    val proposal = SessionOpenProposal(
      sessionId = DirectionalSessionId
        .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .toOption
        .get,
      peerCorrelationId = PeerCorrelationId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = txSubscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(45)),
    )

    val ack = SessionOpenAck(
      sessionId = proposal.sessionId,
      peerCorrelationId = proposal.peerCorrelationId,
      initiator = proposal.initiator,
      acceptor = proposal.acceptor,
      subscriptions = proposal.subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(46),
      ),
    )

    assertEquals(
      SessionNegotiation.validateAck(proposal, ack).left.map(_.reason),
      Left("invalidNegotiationValue"),
    )

  test(
    "control retry horizon must stay within 2x to 10x of negotiated max retry",
  ):
    val policy = HandshakePolicy.default
    val negotiated = NegotiatedSessionParameters(
      heartbeatInterval = Duration.ofSeconds(10),
      livenessTimeout = Duration.ofSeconds(30),
      maxControlRetryInterval = Duration.ofSeconds(20),
    )

    assert(
      policy
        .validateControlRetryHorizon(Duration.ofSeconds(40), negotiated)
        .isRight,
    )
    assert(
      policy
        .validateControlRetryHorizon(Duration.ofSeconds(200), negotiated)
        .isRight,
    )
    assert(
      policy
        .validateControlRetryHorizon(Duration.ofSeconds(39), negotiated)
        .isLeft,
    )
    assert(
      policy
        .validateControlRetryHorizon(Duration.ofSeconds(201), negotiated)
        .isLeft,
    )

  test(
    "static peer topology parses local node, known peers, and direct neighbors",
  ):
    val topology = StaticPeerTopology.parse(
      localNodeIdentity = "node-a",
      knownPeers = List("node-b", "node-c"),
      directNeighbors = List("node-b"),
    )
    assertEquals(
      topology,
      Right(
        StaticPeerTopology(
          localNodeIdentity = PeerIdentity.unsafe("node-a"),
          knownPeers =
            Set(PeerIdentity.unsafe("node-b"), PeerIdentity.unsafe("node-c")),
          directNeighbors = Set(PeerIdentity.unsafe("node-b")),
        ),
      ),
    )
    assert(
      StaticPeerTopology.parse("node-a", List("node-b"), List("node-z")).isLeft,
    )

  test(
    "composite cursor treats missing keys as origin replay and validates version prefixes",
  ):
    val token =
      CursorToken.issue(ByteVector.encodeUtf8("cursor-a").toOption.get)
    val txKey  = ChainTopic(chainId, GossipTopic.tx)
    val cursor = CompositeCursor(Map(txKey -> token))

    assertEquals(cursor.tokenFor(txKey), Some(token))
    assert(!cursor.isOriginReplay(txKey))
    assert(CompositeCursor.empty.isOriginReplay(txKey))
    assertEquals(token.version, CursorToken.CurrentVersion)
    assert(token.validateVersion().isRight)
    assertEquals(CursorToken.decodeBase64Url(token.toBase64Url), Right(token))

    val stale = CursorToken.issue(ByteVector.fromByte(0x7f.toByte), version = 2)
    assert(stale.validateVersion().isLeft)
    intercept[IllegalArgumentException]:
      CursorToken.issue(ByteVector.empty, version = 256)

  test(
    "control batch structural validation rejects invalid idempotency keys and unknown op kinds",
  ):
    assert(ControlBatch.create("not-a-uuid", Vector.empty).isLeft)
    assert(ControlOpKind.parse("unknown-op").isLeft)
    assert(
      ControlBatch
        .create("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", Vector.empty)
        .isRight,
    )

  test(
    "event stream keepalive and control keepalive ack are runtime typed messages",
  ):
    val sessionId = DirectionalSessionId
      .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
      .toOption
      .get
    val eventKeepAlive =
      EventStreamMessage.KeepAlive[String](sessionId, openingNow)
    val controlKeepAlive =
      ControlChannelMessage.KeepAlive(sessionId, openingNow)
    val controlAck = ControlChannelMessage.Ack(sessionId, openingNow)

    assertEquals(
      eventKeepAlive,
      EventStreamMessage.KeepAlive[String](sessionId, openingNow),
    )
    assertEquals(
      controlKeepAlive,
      ControlChannelMessage.KeepAlive(sessionId, openingNow),
    )
    assertEquals(controlAck, ControlChannelMessage.Ack(sessionId, openingNow))

  private def DirectionallyInvalid(value: String): Boolean =
    DirectionalSessionId.parse(value).isLeft && PeerCorrelationId
      .parse(value)
      .isLeft
