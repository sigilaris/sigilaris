package org.sigilaris.node.jvm.runtime.gossip

import java.time.{Duration, Instant}

import munit.FunSuite

final class SessionEngineSuite extends FunSuite:

  private val localPeer = PeerIdentity.unsafe("node-a")
  private val remotePeer = PeerIdentity.unsafe("node-b")
  private val chainId = ChainId.unsafe("chain-main")
  private val subscription = SessionSubscription.unsafe(ChainTopic(chainId, GossipTopic.tx))
  private val topology = StaticPeerTopology.parse(
    localNodeIdentity = "node-a",
    knownPeers = List("node-b"),
    directNeighbors = List("node-b"),
  ).toOption.get
  private val createdAt = Instant.parse("2026-03-31T10:15:30Z")

  test("simultaneous open closes the losing provisional outbound lineage and reuses the winning correlation id on retry"):
    val engine = GossipSessionEngine(localPeer, topology)
    val localLosingCorrelation =
      PeerCorrelationId.parse("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee").toOption.get
    val remoteWinningCorrelation =
      PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get

    val (afterOutbound, outboundProposal) = engine
      .startOutbound(
        peer = remotePeer,
        subscriptions = subscription,
        now = createdAt,
        peerCorrelationId = Some(localLosingCorrelation),
      )
      .toOption
      .get

    val inboundProposal = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toOption.get,
      peerCorrelationId = remoteWinningCorrelation,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val (afterInbound, result) = afterOutbound.handleInboundProposal(inboundProposal, createdAt.plusSeconds(1))
    val accepted = result.asInstanceOf[InboundHandshakeResult.Accepted]
    assertEquals(accepted.supersededSessionId, Some(outboundProposal.sessionId))

    val relationship = afterInbound.relationshipWith(remotePeer).get
    assertEquals(relationship.peerCorrelationId, remoteWinningCorrelation)
    assertEquals(relationship.outbound.map(_.status), Some(DirectionalSessionStatus.Closed))
    assertEquals(relationship.inbound.map(_.status), Some(DirectionalSessionStatus.Open))

    val (afterRetry, retryProposal) = afterInbound
      .startOutbound(
        peer = remotePeer,
        subscriptions = subscription,
        now = createdAt.plusSeconds(2),
      )
      .toOption
      .get
    assertEquals(retryProposal.peerCorrelationId, remoteWinningCorrelation)
    assert(retryProposal.sessionId != outboundProposal.sessionId)
    assertEquals(afterRetry.relationshipWith(remotePeer).get.status, PeerRelationshipStatus.Opening)

  test("simultaneous open local-win path rejects the inbound loser and preserves the local opening lineage"):
    val engine = GossipSessionEngine(localPeer, topology)
    val localWinningCorrelation =
      PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get
    val remoteLosingCorrelation =
      PeerCorrelationId.parse("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee").toOption.get

    val (afterOutbound, outboundProposal) = engine
      .startOutbound(
        peer = remotePeer,
        subscriptions = subscription,
        now = createdAt,
        peerCorrelationId = Some(localWinningCorrelation),
      )
      .toOption
      .get

    val inboundProposal = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toOption.get,
      peerCorrelationId = remoteLosingCorrelation,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val (afterInbound, result) = afterOutbound.handleInboundProposal(inboundProposal, createdAt.plusSeconds(1))
    val rejected = result.asInstanceOf[InboundHandshakeResult.Rejected]

    assertEquals(rejected.rejection.reason, "simultaneousOpenLost")
    assertEquals(afterInbound.relationshipWith(remotePeer).flatMap(_.outbound).map(_.sessionId), Some(outboundProposal.sessionId))
    assertEquals(
      afterInbound.relationshipWith(remotePeer).flatMap(_.outbound).map(_.status),
      Some(DirectionalSessionStatus.Opening),
    )
    assertEquals(afterInbound.relationshipWith(remotePeer).flatMap(_.inbound), None)

  test("simultaneous open still applies lexicographic tiebreak when a stale inbound session record exists"):
    val localWinningCorrelation =
      PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get
    val remoteLosingCorrelation =
      PeerCorrelationId.parse("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee").toOption.get

    val outboundProposal = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toOption.get,
      peerCorrelationId = localWinningCorrelation,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )
    val staleInboundProposal = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toOption.get,
      peerCorrelationId = localWinningCorrelation,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val engine = GossipSessionEngine(
      localPeer = localPeer,
      topology = topology,
      relationships = Map(
        remotePeer -> PeerRelationship(
          peer = remotePeer,
          peerCorrelationId = localWinningCorrelation,
          outbound = Some(
            DirectionalSession(
              sessionId = outboundProposal.sessionId,
              direction = SessionDirection.Outbound,
              peer = remotePeer,
              peerCorrelationId = localWinningCorrelation,
              proposal = outboundProposal,
              negotiated = None,
              status = DirectionalSessionStatus.Opening,
              createdAt = createdAt,
              openedAt = None,
            )
          ),
          inbound = Some(
            DirectionalSession(
              sessionId = staleInboundProposal.sessionId,
              direction = SessionDirection.Inbound,
              peer = remotePeer,
              peerCorrelationId = localWinningCorrelation,
              proposal = staleInboundProposal,
              negotiated = Some(
                NegotiatedSessionParameters(
                  heartbeatInterval = Duration.ofSeconds(10),
                  livenessTimeout = Duration.ofSeconds(30),
                  maxControlRetryInterval = Duration.ofSeconds(30),
                )
              ),
              status = DirectionalSessionStatus.Closed,
              createdAt = createdAt.minusSeconds(10),
              openedAt = Some(createdAt.minusSeconds(9)),
            )
          ),
          status = PeerRelationshipStatus.HalfOpen,
          wasBidirectionalOpen = true,
        )
      ),
    )

    val inboundProposal = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc").toOption.get,
      peerCorrelationId = remoteLosingCorrelation,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val (_, result) = engine.handleInboundProposal(inboundProposal, createdAt.plusSeconds(1))
    assertEquals(
      result.asInstanceOf[InboundHandshakeResult.Rejected].rejection.reason,
      "simultaneousOpenLost",
    )

  test("pre-open event or control traffic rejects and closes the opening session without advancing state"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get

    val (afterReject, rejection) = afterOutbound
      .rejectPreOpenTraffic(proposal.sessionId, PreOpenTrafficKind.EventStream)
      .toOption
      .get

    assertEquals(rejection.reason, "preOpenEventTraffic")
    assertEquals(
      afterReject.relationshipWith(remotePeer).flatMap(_.outbound).map(_.status),
      Some(DirectionalSessionStatus.Closed),
    )
    assertEquals(afterReject.relationshipWith(remotePeer).map(_.status), Some(PeerRelationshipStatus.Closed))

  test("pre-open control traffic uses the dedicated rejection reason"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get

    val (_, rejection) = afterOutbound
      .rejectPreOpenTraffic(proposal.sessionId, PreOpenTrafficKind.ControlChannel)
      .toOption
      .get

    assertEquals(rejection.reason, "preOpenControlTraffic")

  test("pre-open rejection also updates inbound opening sessions through the inbound branch"):
    val inboundProposal = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toOption.get,
      peerCorrelationId = PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val engine = GossipSessionEngine(
      localPeer = localPeer,
      topology = topology,
      relationships = Map(
        remotePeer -> PeerRelationship(
          peer = remotePeer,
          peerCorrelationId = inboundProposal.peerCorrelationId,
          outbound = None,
          inbound = Some(
            DirectionalSession(
              sessionId = inboundProposal.sessionId,
              direction = SessionDirection.Inbound,
              peer = remotePeer,
              peerCorrelationId = inboundProposal.peerCorrelationId,
              proposal = inboundProposal,
              negotiated = None,
              status = DirectionalSessionStatus.Opening,
              createdAt = createdAt,
              openedAt = None,
            )
          ),
          status = PeerRelationshipStatus.Opening,
          wasBidirectionalOpen = false,
        )
      ),
    )

    val (afterReject, rejection) = engine
      .rejectPreOpenTraffic(inboundProposal.sessionId, PreOpenTrafficKind.ControlChannel)
      .toOption
      .get

    assertEquals(rejection.reason, "preOpenControlTraffic")
    assertEquals(
      afterReject.relationshipWith(remotePeer).flatMap(_.inbound).map(_.status),
      Some(DirectionalSessionStatus.Closed),
    )

  test("handleInboundProposal rejects a mismatched peer correlation when another alive direction already exists"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get
    val ack = SessionNegotiation
      .acknowledge(
        proposal = proposal,
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(30),
      )
      .toOption
      .get
    val opened = afterOutbound.applyHandshakeAck(ack, createdAt.plusSeconds(1)).toOption.get

    val mismatchedInbound = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toOption.get,
      peerCorrelationId = PeerCorrelationId.parse("99999999-9999-4999-8999-999999999999").toOption.get,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val (_, result) = opened.handleInboundProposal(mismatchedInbound, createdAt.plusSeconds(2))
    assertEquals(
      result.asInstanceOf[InboundHandshakeResult.Rejected].rejection.reason,
      "peerCorrelationMismatch",
    )

  test("handleInboundProposal rejects a duplicate inbound direction while one is already alive"):
    val engine = GossipSessionEngine(localPeer, topology)
    val inbound = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toOption.get,
      peerCorrelationId = PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val (afterFirstInbound, firstResult) = engine.handleInboundProposal(inbound, createdAt)
    assert(firstResult.isInstanceOf[InboundHandshakeResult.Accepted])

    val duplicateInbound = inbound.copy(
      sessionId = DirectionalSessionId.parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc").toOption.get
    )

    val (_, result) = afterFirstInbound.handleInboundProposal(duplicateInbound, createdAt.plusSeconds(1))
    assertEquals(
      result.asInstanceOf[InboundHandshakeResult.Rejected].rejection.reason,
      "duplicateInboundDirection",
    )

  test("handleInboundProposal rejects invalid acceptor-side overrides during acknowledge"):
    val engine = GossipSessionEngine(localPeer, topology)
    val inbound = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toOption.get,
      peerCorrelationId = PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val (_, result) = engine.handleInboundProposal(
      inbound,
      createdAt,
      livenessTimeout = Some(Duration.ofSeconds(1)),
    )
    assertEquals(
      result.asInstanceOf[InboundHandshakeResult.Rejected].rejection.reason,
      "invalidNegotiationValue",
    )

  test("startOutbound rejects a duplicate alive outbound direction"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, _) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get

    assertEquals(
      afterOutbound.startOutbound(remotePeer, subscription, createdAt.plusSeconds(1)).left.map(_.reason),
      Left("duplicateOutboundDirection"),
    )

  test("rejectPreOpenTraffic refuses sessions that are no longer opening"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get
    val closed = afterOutbound.closeSession(proposal.sessionId).toOption.get

    assertEquals(
      closed.rejectPreOpenTraffic(proposal.sessionId, PreOpenTrafficKind.EventStream).left.map(_.reason),
      Left("sessionNotOpening"),
    )

  test("opening handshake timeout moves the pending session to dead"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get

    val boundaryExpired = afterOutbound.expireOpeningHandshakes(createdAt.plusSeconds(30))
    assertEquals(
      boundaryExpired.relationshipWith(remotePeer).flatMap(_.outbound).map(_.status),
      Some(DirectionalSessionStatus.Dead),
    )

    val expired = afterOutbound.expireOpeningHandshakes(createdAt.plusSeconds(31))
    assertEquals(
      expired.relationshipWith(remotePeer).flatMap(_.outbound).map(_.sessionId),
      Some(proposal.sessionId),
    )
    assertEquals(
      expired.relationshipWith(remotePeer).flatMap(_.outbound).map(_.status),
      Some(DirectionalSessionStatus.Dead),
    )
    assertEquals(expired.relationshipWith(remotePeer).map(_.status), Some(PeerRelationshipStatus.Dead))

  test("expireOpeningHandshakes also expires manually tracked inbound opening sessions"):
    val inboundProposal = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toOption.get,
      peerCorrelationId = PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val engine = GossipSessionEngine(
      localPeer = localPeer,
      topology = topology,
      relationships = Map(
        remotePeer -> PeerRelationship(
          peer = remotePeer,
          peerCorrelationId = inboundProposal.peerCorrelationId,
          outbound = None,
          inbound = Some(
            DirectionalSession(
              sessionId = inboundProposal.sessionId,
              direction = SessionDirection.Inbound,
              peer = remotePeer,
              peerCorrelationId = inboundProposal.peerCorrelationId,
              proposal = inboundProposal,
              negotiated = None,
              status = DirectionalSessionStatus.Opening,
              createdAt = createdAt,
              openedAt = None,
            )
          ),
          status = PeerRelationshipStatus.Opening,
          wasBidirectionalOpen = false,
        )
      ),
    )

    val expired = engine.expireOpeningHandshakes(createdAt.plusSeconds(30))
    assertEquals(
      expired.relationshipWith(remotePeer).flatMap(_.inbound).map(_.status),
      Some(DirectionalSessionStatus.Dead),
    )

  test("handleInboundProposal can re-associate a dead relationship to a new correlation id"):
    val oldCorrelation = PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get
    val newCorrelation = PeerCorrelationId.parse("22222222-2222-4222-8222-222222222222").toOption.get
    val staleProposal = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toOption.get,
      peerCorrelationId = oldCorrelation,
      initiator = remotePeer,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val engine = GossipSessionEngine(
      localPeer = localPeer,
      topology = topology,
      relationships = Map(
        remotePeer -> PeerRelationship(
          peer = remotePeer,
          peerCorrelationId = oldCorrelation,
          outbound = None,
          inbound = Some(
            DirectionalSession(
              sessionId = staleProposal.sessionId,
              direction = SessionDirection.Inbound,
              peer = remotePeer,
              peerCorrelationId = oldCorrelation,
              proposal = staleProposal,
              negotiated = Some(
                NegotiatedSessionParameters(
                  heartbeatInterval = Duration.ofSeconds(10),
                  livenessTimeout = Duration.ofSeconds(30),
                  maxControlRetryInterval = Duration.ofSeconds(30),
                )
              ),
              status = DirectionalSessionStatus.Closed,
              createdAt = createdAt.minusSeconds(10),
              openedAt = Some(createdAt.minusSeconds(9)),
            )
          ),
          status = PeerRelationshipStatus.Closed,
          wasBidirectionalOpen = true,
        )
      ),
    )

    val newInbound = staleProposal.copy(
      sessionId = DirectionalSessionId.parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc").toOption.get,
      peerCorrelationId = newCorrelation,
    )

    val (afterInbound, result) = engine.handleInboundProposal(newInbound, createdAt.plusSeconds(1))
    assert(result.isInstanceOf[InboundHandshakeResult.Accepted])
    assertEquals(afterInbound.relationshipWith(remotePeer).map(_.peerCorrelationId), Some(newCorrelation))
    assertEquals(
      afterInbound.relationshipWith(remotePeer).flatMap(_.inbound).map(_.status),
      Some(DirectionalSessionStatus.Open),
    )

  test("ack round-trip opens the outbound direction and later dead direction yields half-open relationship"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get

    val (afterInbound, inboundResult) = afterOutbound.handleInboundProposal(
      SessionOpenProposal(
        sessionId = DirectionalSessionId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toOption.get,
        peerCorrelationId = proposal.peerCorrelationId,
        initiator = remotePeer,
        acceptor = localPeer,
        subscriptions = subscription,
        heartbeatInterval = Some(Duration.ofSeconds(10)),
        livenessTimeout = Some(Duration.ofSeconds(30)),
        maxControlRetryInterval = Some(Duration.ofSeconds(30)),
      ),
      createdAt.plusSeconds(1),
    )
    assert(inboundResult.isInstanceOf[InboundHandshakeResult.Accepted])
    val outboundAck = SessionNegotiation
      .acknowledge(
        proposal = proposal,
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(30),
      )
      .toOption
      .get
    val opened = afterInbound.applyHandshakeAck(outboundAck, createdAt.plusSeconds(2)).toOption.get

    assertEquals(opened.relationshipWith(remotePeer).map(_.status), Some(PeerRelationshipStatus.Open))

    val halfOpen = opened.markSessionDead(proposal.sessionId).toOption.get
    assertEquals(halfOpen.relationshipWith(remotePeer).map(_.status), Some(PeerRelationshipStatus.HalfOpen))
    assertEquals(
      halfOpen.relationshipWith(remotePeer).flatMap(_.outbound).map(_.status),
      Some(DirectionalSessionStatus.Dead),
    )

  test("applyHandshakeAck rejects a duplicate ack after the outbound session is already open"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get

    val ack = SessionNegotiation
      .acknowledge(
        proposal = proposal,
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(30),
      )
      .toOption
      .get

    val opened = afterOutbound.applyHandshakeAck(ack, createdAt.plusSeconds(1)).toOption.get
    assertEquals(
      opened.applyHandshakeAck(ack, createdAt.plusSeconds(2)).left.map(_.reason),
      Left("sessionNotOpening"),
    )

  test("public session mutation APIs reject unknown session ids"):
    val engine = GossipSessionEngine(localPeer, topology)
    val unknownSessionId = DirectionalSessionId.parse("ffffffff-ffff-4fff-8fff-ffffffffffff").toOption.get
    val unknownAck = SessionOpenAck(
      sessionId = unknownSessionId,
      peerCorrelationId = PeerCorrelationId.parse("11111111-1111-4111-8111-111111111111").toOption.get,
      initiator = localPeer,
      acceptor = remotePeer,
      subscriptions = subscription,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(30),
      ),
    )

    assertEquals(engine.applyHandshakeAck(unknownAck, createdAt).left.map(_.reason), Left("unknownSession"))
    assertEquals(engine.closeSession(unknownSessionId).left.map(_.reason), Left("unknownSession"))
    assertEquals(engine.markSessionDead(unknownSessionId).left.map(_.reason), Left("unknownSession"))

  test("closeSession closes an opening session and refuses to rewrite a dead session"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get

    val closed = afterOutbound.closeSession(proposal.sessionId).toOption.get
    assertEquals(
      closed.relationshipWith(remotePeer).flatMap(_.outbound).map(_.status),
      Some(DirectionalSessionStatus.Closed),
    )

    val dead = afterOutbound.markSessionDead(proposal.sessionId).toOption.get
    assertEquals(
      dead.closeSession(proposal.sessionId).left.map(_.reason),
      Left("sessionAlreadyDead"),
    )
    assertEquals(
      dead.relationshipWith(remotePeer).flatMap(_.outbound).map(_.status),
      Some(DirectionalSessionStatus.Dead),
    )

  test("startOutbound after a dead relationship issues a fresh peer correlation id"):
    val engine = GossipSessionEngine(localPeer, topology)
    val (afterOutbound, proposal) =
      engine.startOutbound(remotePeer, subscription, createdAt).toOption.get
    val dead = afterOutbound.markSessionDead(proposal.sessionId).toOption.get

    val (_, retryProposal) = dead
      .startOutbound(remotePeer, subscription, createdAt.plusSeconds(1))
      .toOption
      .get

    assert(retryProposal.peerCorrelationId != proposal.peerCorrelationId)

  test("non-neighbor admission is rejected for outbound and inbound session opens"):
    val nonNeighbor = PeerIdentity.unsafe("node-z")
    val engine = GossipSessionEngine(localPeer, topology)

    assert(engine.startOutbound(nonNeighbor, subscription, createdAt).isLeft)

    val inbound = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toOption.get,
      peerCorrelationId = PeerCorrelationId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toOption.get,
      initiator = nonNeighbor,
      acceptor = localPeer,
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val (_, result) = engine.handleInboundProposal(inbound, createdAt)
    assert(result.isInstanceOf[InboundHandshakeResult.Rejected])

  test("handleInboundProposal rejects a proposal addressed to the wrong acceptor"):
    val engine = GossipSessionEngine(localPeer, topology)
    val inbound = SessionOpenProposal(
      sessionId = DirectionalSessionId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toOption.get,
      peerCorrelationId = PeerCorrelationId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toOption.get,
      initiator = remotePeer,
      acceptor = PeerIdentity.unsafe("node-c"),
      subscriptions = subscription,
      heartbeatInterval = Some(Duration.ofSeconds(10)),
      livenessTimeout = Some(Duration.ofSeconds(30)),
      maxControlRetryInterval = Some(Duration.ofSeconds(30)),
    )

    val (_, result) = engine.handleInboundProposal(inbound, createdAt)
    assertEquals(
      result.asInstanceOf[InboundHandshakeResult.Rejected].rejection.reason,
      "wrongAcceptor",
    )
