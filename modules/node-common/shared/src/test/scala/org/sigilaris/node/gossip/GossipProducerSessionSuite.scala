package org.sigilaris.node.gossip

import java.time.{Duration, Instant}

import munit.FunSuite
import scodec.bits.ByteVector

final class GossipProducerSessionSuite extends FunSuite:

  private val remotePeer = PeerIdentity.unsafe("node-b")
  private val chainId    = ChainId.unsafe("chain-main")
  private val auditTopic = GossipTopic.unsafe("audit")
  private val startedAt  = Instant.parse("2026-04-01T00:00:00Z")

  private final case class StubQoS(
      maxBatchItems: Int,
      flushInterval: Duration,
  ) extends GossipProducerQoS

  test(
    "topic-neutral producer session state and polling can be reused by a non-tx topic stub",
  ):
    val chainTopic = ChainTopic(chainId, auditTopic)
    val session = GossipProducerSessionState(
      sessionId = DirectionalSessionId.random(),
      peer = remotePeer,
      peerCorrelationId = PeerCorrelationId.random(),
      subscriptions = SessionSubscription.unsafe(chainTopic),
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofSeconds(10),
        livenessTimeout = Duration.ofSeconds(30),
        maxControlRetryInterval = Duration.ofSeconds(30),
      ),
    )
    val cursor1         = CursorToken.unsafeIssue(ByteVector.fromLong(1L))
    val cursor2         = CursorToken.unsafeIssue(ByteVector.fromLong(2L))
    val replayRequested = session.withReplay(chainTopic, Some(cursor1))
    val advanced =
      replayRequested
        .advanceStreamCursor(
          chainTopic,
          Vector(
            GossipEvent(
              chainId,
              auditTopic,
              StableArtifactId.unsafeFromHex("01"),
              cursor2,
              startedAt,
              "audit-2",
            ),
          ),
        )
        .clearReplay(chainTopic)
    val polled =
      GossipProducerPolling.batchAvailableEvents(
        now = startedAt.plusSeconds(5),
        candidates = Vector(
          AvailableGossipEvent(
            event = GossipEvent(
              chainId = chainId,
              topic = auditTopic,
              id = StableArtifactId.unsafeFromHex("01"),
              cursor = cursor1,
              ts = startedAt.minusSeconds(60),
              payload = "audit-1",
            ),
            availableAt = startedAt,
          ),
        ),
        qos = StubQoS(maxBatchItems = 4, flushInterval = Duration.ofSeconds(5)),
        forceFlush = false,
        limit = 4,
      )

    assertEquals(replayRequested.startCursorFor(chainTopic), Some(cursor1))
    assertEquals(advanced.streamCursor.tokenFor(chainTopic), Some(cursor2))
    assertEquals(advanced.pendingReplay.get(chainTopic), None)
    assertEquals(polled.map(_.payload), Vector("audit-1"))
