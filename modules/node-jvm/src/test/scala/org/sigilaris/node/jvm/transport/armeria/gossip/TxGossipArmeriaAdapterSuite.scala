package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import java.util.Base64

import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.*
import org.sigilaris.node.jvm.transport.armeria.{ArmeriaServer, ArmeriaServerConfig}

final class TxGossipArmeriaAdapterSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val subscription = SessionSubscription.unsafe(ChainTopic(chainId, GossipTopic.tx))
  private val baseInstant = Instant.parse("2026-04-01T00:00:00Z")

  test("session-open endpoint accepts a direct neighbor and returns a handshake ack"):
    Harness.resource(baseInstant).use: harness =>
      for
        response <- harness.postJson(
          "/gossip/session/open",
          SessionOpenProposalWire(
            sessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            peerCorrelationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            initiator = "node-b",
            acceptor = "node-a",
            subscriptions = Vector(ChainTopicWire(chainId.value, GossipTopic.tx.value)),
            heartbeatIntervalMs = None,
            livenessTimeoutMs = None,
            maxControlRetryIntervalMs = None,
          ).asJson.noSpaces,
        )
        ack <- IO.fromEither(decode[SessionOpenAckWire](response.body).leftMap(new IllegalStateException(_)))
      yield
        assertEquals(response.status, 200)
        assertEquals(ack.acceptor, "node-a")
        assertEquals(ack.initiator, "node-b")
        assertEquals(ack.sessionId, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

  test("session-open endpoint rejects non-neighbor peers with handshakeRejected"):
    Harness.resource(baseInstant).use: harness =>
      for
        response <- harness.postJson(
          "/gossip/session/open",
          SessionOpenProposalWire(
            sessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            peerCorrelationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            initiator = "node-z",
            acceptor = "node-a",
            subscriptions = Vector(ChainTopicWire(chainId.value, GossipTopic.tx.value)),
            heartbeatIntervalMs = None,
            livenessTimeoutMs = None,
            maxControlRetryIntervalMs = None,
          ).asJson.noSpaces,
        )
        rejection <- IO.fromEither(decode[RejectionWire](response.body).leftMap(new IllegalStateException(_)))
      yield
        assertEquals(response.status, 400)
        assertEquals(rejection.rejectionClass, "handshakeRejected")
        assertEquals(rejection.reason, "nonNeighborPeer")

  test("session-open endpoint rejects empty subscriptions"):
    Harness.resource(baseInstant).use: harness =>
      for
        response <- harness.postJson(
          "/gossip/session/open",
          SessionOpenProposalWire(
            sessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            peerCorrelationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            initiator = "node-b",
            acceptor = "node-a",
            subscriptions = Vector.empty,
            heartbeatIntervalMs = None,
            livenessTimeoutMs = None,
            maxControlRetryIntervalMs = None,
          ).asJson.noSpaces,
        )
        rejection <- IO.fromEither(decode[RejectionWire](response.body).leftMap(new IllegalStateException(_)))
      yield
        assertEquals(response.status, 400)
        assertEquals(rejection.rejectionClass, "handshakeRejected")
        assertEquals(rejection.reason, "invalidSubscription")

  test("event/control endpoints serve keepalive, requestById, and wrong-channel rejection"):
    Harness.resource(baseInstant).use: harness =>
      for
        sessionId <- harness.openOutboundLocally
        keepAliveResponse <- harness.postJson(
          s"/gossip/events/${sessionId.value}",
          EventRequestWire("poll").asJson.noSpaces,
        )
        keepAliveLines <- decodeNdjson[EventEnvelopeWire[TestTx]](keepAliveResponse.body)
        controlAckResponse <- harness.postJson(
          s"/gossip/control/${sessionId.value}",
          ControlRequestWire("controlKeepAlive").asJson.noSpaces,
        )
        controlAck <- IO.fromEither(decode[ControlResponseWire](controlAckResponse.body).leftMap(new IllegalStateException(_)))
        tx1 <- harness.source.append(chainId, TestTx("tx-1"), baseInstant.minusSeconds(2))
        tx2 <- harness.source.append(chainId, TestTx("tx-2"), baseInstant.minusSeconds(1))
        controlBatch = ControlRequestWire(
          kind = "batch",
          batch = Some(
            ControlBatchWire(
              idempotencyKey = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
              ops = Vector(
                ControlOpWire(
                  kind = "setKnown.tx",
                  chainId = Some(chainId.value),
                  ids = Some(Vector(tx1.id.toHexLower, tx2.id.toHexLower)),
                ),
                ControlOpWire(
                  kind = "requestById.tx",
                  chainId = Some(chainId.value),
                  ids = Some(Vector(tx1.id.toHexLower)),
                ),
              ),
            )
          ),
        )
        controlBatchResponse <- harness.postJson(
          s"/gossip/control/${sessionId.value}",
          controlBatch.asJson.noSpaces,
        )
        controlBatchAck <- IO.fromEither(decode[ControlResponseWire](controlBatchResponse.body).leftMap(new IllegalStateException(_)))
        requestedResponse <- harness.postJson(
          s"/gossip/events/${sessionId.value}",
          EventRequestWire("poll").asJson.noSpaces,
        )
        requestedLines <- decodeNdjson[EventEnvelopeWire[TestTx]](requestedResponse.body)
        wrongControlResponse <- harness.postJson(
          s"/gossip/control/${sessionId.value}",
          ControlRequestWire("eventKeepAlive").asJson.noSpaces,
        )
        wrongControlRejection <- IO.fromEither(decode[RejectionWire](wrongControlResponse.body).leftMap(new IllegalStateException(_)))
        wrongEventResponse <- harness.postJson(
          s"/gossip/events/${sessionId.value}",
          EventRequestWire("controlKeepAlive").asJson.noSpaces,
        )
        wrongEventLines <- decodeNdjson[EventEnvelopeWire[TestTx]](wrongEventResponse.body)
      yield
        assertEquals(keepAliveResponse.status, 200)
        assertEquals(keepAliveLines.map(_.kind), Vector("keepAlive"))
        assertEquals(controlAckResponse.status, 200)
        assertEquals(controlAck.status, "ack")
        assertEquals(controlBatchResponse.status, 200)
        assertEquals(controlBatchAck.status, "applied")
        assertEquals(requestedLines.flatMap(_.event).map(_.payload.body), Vector("tx-1"))
        assertEquals(wrongControlResponse.status, 400)
        assertEquals(wrongControlRejection.reason, "wrongChannelMessageKind")
        assertEquals(wrongEventLines.map(_.kind), Vector("rejection"))
        assertEquals(wrongEventLines.flatMap(_.rejection).map(_.reason), Vector("wrongChannelMessageKind"))

  test("invalid sessionId path params project handshake rejection on each endpoint family"):
    Harness.resource(baseInstant).use: harness =>
      for
        eventResponse <- harness.postJson(
          "/gossip/events/not-a-session-id",
          EventRequestWire("poll").asJson.noSpaces,
        )
        eventLines <- decodeNdjson[EventEnvelopeWire[TestTx]](eventResponse.body)
        controlResponse <- harness.postJson(
          "/gossip/control/not-a-session-id",
          ControlRequestWire("controlKeepAlive").asJson.noSpaces,
        )
        controlRejection <- IO.fromEither(decode[RejectionWire](controlResponse.body).leftMap(new IllegalStateException(_)))
        disconnectResponse <- harness.postNoBody("/gossip/session/not-a-session-id/disconnect")
        disconnectRejection <- IO.fromEither(
          decode[RejectionWire](disconnectResponse.body).leftMap(new IllegalStateException(_))
        )
      yield
        assertEquals(eventResponse.status, 200)
        assertEquals(eventLines.map(_.kind), Vector("rejection"))
        assertEquals(eventLines.flatMap(_.rejection).map(_.rejectionClass), Vector("handshakeRejected"))
        assertEquals(eventLines.flatMap(_.rejection).map(_.reason), Vector("invalidSessionId"))
        assertEquals(controlResponse.status, 400)
        assertEquals(controlRejection.rejectionClass, "handshakeRejected")
        assertEquals(controlRejection.reason, "invalidSessionId")
        assertEquals(disconnectResponse.status, 400)
        assertEquals(disconnectRejection.rejectionClass, "handshakeRejected")
        assertEquals(disconnectRejection.reason, "invalidSessionId")

  test("event endpoint returns HTTP 200 with an in-stream rejection for malformed request bodies"):
    Harness.resource(baseInstant).use: harness =>
      for
        sessionId <- harness.openOutboundLocally
        response <- harness.postJson(
          s"/gossip/events/${sessionId.value}",
          "{not-json}",
        )
        lines <- decodeNdjson[EventEnvelopeWire[TestTx]](response.body)
      yield
        assertEquals(response.status, 200)
        assertEquals(lines.map(_.kind), Vector("rejection"))
        assertEquals(lines.flatMap(_.rejection).map(_.rejectionClass), Vector("handshakeRejected"))
        assertEquals(lines.flatMap(_.rejection).map(_.reason), Vector("invalidEventRequest"))

  test("control endpoint returns a control-batch rejection for malformed request bodies"):
    Harness.resource(baseInstant).use: harness =>
      for
        sessionId <- harness.openOutboundLocally
        response <- harness.postJson(
          s"/gossip/control/${sessionId.value}",
          "{not-json}",
        )
        rejection <- IO.fromEither(decode[RejectionWire](response.body).leftMap(new IllegalStateException(_)))
      yield
        assertEquals(response.status, 400)
        assertEquals(rejection.rejectionClass, "controlBatchRejected")
        assertEquals(rejection.reason, "invalidControlRequest")

  test("control endpoint accepts setFilter base64url and returns deduplicated on repeated idempotency key"):
    Harness.resource(baseInstant).use: harness =>
      for
        sessionId <- harness.openOutboundLocally
        response1 <- harness.postJson(
          s"/gossip/control/${sessionId.value}",
          ControlRequestWire(
            kind = "batch",
            batch = Some(
              ControlBatchWire(
                idempotencyKey = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                ops = Vector(
                  ControlOpWire(
                    kind = "setFilter",
                    chainId = Some(chainId.value),
                    topic = Some(GossipTopic.tx.value),
                    filter = Some(
                      TxBloomFilterWire(
                        bitsetBase64Url =
                          Base64.getUrlEncoder.withoutPadding().encodeToString(Array(0xfb.toByte)),
                        numHashes = 1,
                        hashFamilyId = TxBloomFilterSupport.SupportedHashFamilyId,
                      )
                    ),
                  )
                ),
              )
            ),
          ).asJson.noSpaces,
        )
        body1 <- IO.fromEither(decode[ControlResponseWire](response1.body).leftMap(new IllegalStateException(_)))
        response2 <- harness.postJson(
          s"/gossip/control/${sessionId.value}",
          ControlRequestWire(
            kind = "batch",
            batch = Some(
              ControlBatchWire(
                idempotencyKey = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                ops = Vector(
                  ControlOpWire(
                    kind = "setFilter",
                    chainId = Some(chainId.value),
                    topic = Some(GossipTopic.tx.value),
                    filter = Some(
                      TxBloomFilterWire(
                        bitsetBase64Url =
                          Base64.getUrlEncoder.withoutPadding().encodeToString(Array(0xfb.toByte)),
                        numHashes = 1,
                        hashFamilyId = TxBloomFilterSupport.SupportedHashFamilyId,
                      )
                    ),
                  )
                ),
              )
            ),
          ).asJson.noSpaces,
        )
        body2 <- IO.fromEither(decode[ControlResponseWire](response2.body).leftMap(new IllegalStateException(_)))
      yield
        assertEquals(response1.status, 200)
        assertEquals(body1.status, "applied")
        assertEquals(response2.status, 200)
        assertEquals(body2.status, "deduplicated")
        assertEquals(body2.deduplicated, Some(true))

  test("control endpoint accepts setCursor, nack, and config ops over HTTP"):
    Harness.resource(baseInstant).use: harness =>
      for
        sessionId <- harness.openOutboundLocally
        tx1 <- harness.source.append(chainId, TestTx("tx-1"), baseInstant.minusSeconds(2))
        tx2 <- harness.source.append(chainId, TestTx("tx-2"), baseInstant.minusSeconds(1))
        _ <- harness.clock.advance(Duration.ofSeconds(1))
        pollResponse <- harness.postJson(
          s"/gossip/events/${sessionId.value}",
          EventRequestWire("poll").asJson.noSpaces,
        )
        polled <- decodeNdjson[EventEnvelopeWire[TestTx]](pollResponse.body)
        replayCursor = polled.flatMap(_.event).head.cursor
        latestCursor = polled.flatMap(_.event).last.cursor
        response <- harness.postJson(
          s"/gossip/control/${sessionId.value}",
          ControlRequestWire(
            kind = "batch",
            batch = Some(
              ControlBatchWire(
                idempotencyKey = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                ops = Vector(
                  ControlOpWire(
                    kind = "setCursor",
                    cursor = Some(
                      Vector(
                        CursorEntryWire(
                          chainId = chainId.value,
                          topic = GossipTopic.tx.value,
                          token = latestCursor,
                        )
                      )
                    ),
                  ),
                  ControlOpWire(
                    kind = "nack",
                    chainId = Some(chainId.value),
                    topic = Some(GossipTopic.tx.value),
                    cursorToken = Some(replayCursor),
                  ),
                  ControlOpWire(
                    kind = "config",
                    config = Some(
                      Map(
                        SessionConfigKey.TxMaxBatchItems.wireName -> 1L,
                        SessionConfigKey.TxFlushIntervalMs.wireName -> 5L,
                      )
                    ),
                  ),
                ),
              )
            ),
          ).asJson.noSpaces,
        )
        body <- IO.fromEither(decode[ControlResponseWire](response.body).leftMap(new IllegalStateException(_)))
        replayResponse <- harness.postJson(
          s"/gossip/events/${sessionId.value}",
          EventRequestWire("poll").asJson.noSpaces,
        )
        replayed <- decodeNdjson[EventEnvelopeWire[TestTx]](replayResponse.body)
      yield
        assertEquals(response.status, 200)
        assertEquals(body.status, "applied")
        assertEquals(replayed.flatMap(_.event).map(_.payload.body), Vector("tx-2"))

  test("control endpoint rejects unknown control op kinds"):
    Harness.resource(baseInstant).use: harness =>
      for
        sessionId <- harness.openOutboundLocally
        response <- harness.postJson(
          s"/gossip/control/${sessionId.value}",
          ControlRequestWire(
            kind = "batch",
            batch = Some(
              ControlBatchWire(
                idempotencyKey = "abababab-abab-4aba-8aba-abababababab",
                ops = Vector(ControlOpWire(kind = "unknown")),
              )
            ),
          ).asJson.noSpaces,
        )
        rejection <- IO.fromEither(decode[RejectionWire](response.body).leftMap(new IllegalStateException(_)))
      yield
        assertEquals(response.status, 400)
        assertEquals(rejection.rejectionClass, "controlBatchRejected")
        assertEquals(rejection.reason, "unknownControlOpKind")
        assertEquals(rejection.detail, Some("unknown"))

  test("disconnect endpoint marks the session dead"):
    Harness.resource(baseInstant).use: harness =>
      for
        sessionId <- harness.openOutboundLocally
        response <- harness.postNoBody(s"/gossip/session/${sessionId.value}/disconnect")
        state <- harness.runtime.snapshotState
      yield
        assertEquals(response.status, 200)
        assertEquals(state.engine.sessionById(sessionId).map(_.status), Some(DirectionalSessionStatus.Dead))

  test("half-open recovery reuses the existing peer correlation id and keeps the surviving direction live"):
    MeshHarness.resource(baseInstant).use: mesh =>
      for
        aToB <- openOutboundViaHttp(mesh.a, mesh.b)
        bToA <- openOutboundViaHttp(mesh.b, mesh.a)
        _ <- mesh.a.postNoBody(s"/gossip/session/${aToB.proposal.sessionId.value}/disconnect")
        _ <- mesh.b.postNoBody(s"/gossip/session/${aToB.proposal.sessionId.value}/disconnect")
        relationshipHalfOpenA <- mesh.a.runtime.relationshipWith(PeerIdentity.unsafe(mesh.b.localNodeId))
        relationshipHalfOpenB <- mesh.b.runtime.relationshipWith(PeerIdentity.unsafe(mesh.a.localNodeId))
        recoveryProposalEither <- mesh.a.runtime.startOutbound(PeerIdentity.unsafe(mesh.b.localNodeId), subscription)
        recoveryProposal <- IO.fromEither(
          recoveryProposalEither.leftMap(rejection => new IllegalStateException(rejection.reason))
        )
        _ <- mesh.b.source.append(chainId, TestTx("surviving"), baseInstant.minusSeconds(1))
        _ <- mesh.b.clock.advance(Duration.ofSeconds(1))
        survivingPoll <- mesh.b.postJson(
          s"/gossip/events/${bToA.proposal.sessionId.value}",
          EventRequestWire("poll").asJson.noSpaces,
        )
        survivingLines <- decodeNdjson[EventEnvelopeWire[TestTx]](survivingPoll.body)
        survivingControl <- mesh.b.postJson(
          s"/gossip/control/${bToA.proposal.sessionId.value}",
          ControlRequestWire("controlKeepAlive").asJson.noSpaces,
        )
        survivingAck <- IO.fromEither(
          decode[ControlResponseWire](survivingControl.body).leftMap(new IllegalStateException(_))
        )
        recoveryResponse <- mesh.b.postJson(
          "/gossip/session/open",
          toProposalWire(recoveryProposal).asJson.noSpaces,
        )
        recoveryAckWire <- IO.fromEither(
          decode[SessionOpenAckWire](recoveryResponse.body).leftMap(new IllegalStateException(_))
        )
        recoveryAck <- IO.fromEither(toAck(recoveryAckWire).leftMap(new IllegalArgumentException(_)))
        applyResult <- mesh.a.runtime.applyHandshakeAck(recoveryAck)
        _ <- IO.fromEither(applyResult.leftMap(rejection => new IllegalStateException(rejection.reason)))
        relationshipOpenA <- mesh.a.runtime.relationshipWith(PeerIdentity.unsafe(mesh.b.localNodeId))
        relationshipOpenB <- mesh.b.runtime.relationshipWith(PeerIdentity.unsafe(mesh.a.localNodeId))
      yield
        assertEquals(relationshipHalfOpenA.map(_.status), Some(PeerRelationshipStatus.HalfOpen))
        assertEquals(relationshipHalfOpenB.map(_.status), Some(PeerRelationshipStatus.HalfOpen))
        assertEquals(recoveryProposal.peerCorrelationId, aToB.proposal.peerCorrelationId)
        assert(recoveryProposal.sessionId != aToB.proposal.sessionId)
        assertEquals(survivingPoll.status, 200)
        assertEquals(survivingLines.flatMap(_.event).map(_.payload.body), Vector("surviving"))
        assertEquals(survivingControl.status, 200)
        assertEquals(survivingAck.status, "ack")
        assertEquals(relationshipOpenA.map(_.status), Some(PeerRelationshipStatus.Open))
        assertEquals(relationshipOpenB.map(_.status), Some(PeerRelationshipStatus.Open))

  test("reconnect starts with an empty filter state and does not carry over prior setFilter state"):
    Harness.resource(baseInstant).use: harness =>
      for
        session1 <- harness.openOutboundLocally
        known <- harness.source.append(chainId, TestTx("known"), baseInstant.minusSeconds(2))
        filter = TxBloomFilterSupport.build(Vector(known.id), bitsetBytes = 8, numHashes = 2)
        filterResponse <- harness.postJson(
          s"/gossip/control/${session1.value}",
          ControlRequestWire(
            kind = "batch",
            batch = Some(
              ControlBatchWire(
                idempotencyKey = "45454545-4545-4454-8454-454545454545",
                ops = Vector(
                  ControlOpWire(
                    kind = "setFilter",
                    chainId = Some(chainId.value),
                    topic = Some(GossipTopic.tx.value),
                    filter = Some(
                      TxBloomFilterWire(
                        bitsetBase64Url = Base64.getUrlEncoder.withoutPadding().encodeToString(filter.bitset.toArray),
                        numHashes = filter.numHashes,
                        hashFamilyId = filter.hashFamilyId,
                      )
                    ),
                  )
                ),
              )
            ),
          ).asJson.noSpaces,
        )
        _ <- IO.fromEither(decode[ControlResponseWire](filterResponse.body).leftMap(new IllegalStateException(_)))
        filteredState <- harness.runtime.snapshotState
        _ <- harness.postNoBody(s"/gossip/session/${session1.value}/disconnect")
        session2 <- harness.openOutboundLocally
        _ <- harness.clock.advance(Duration.ofSeconds(1))
        pollResponse <- harness.postJson(
          s"/gossip/events/${session2.value}",
          EventRequestWire("poll").asJson.noSpaces,
        )
        pollLines <- decodeNdjson[EventEnvelopeWire[TestTx]](pollResponse.body)
        state <- harness.runtime.snapshotState
      yield
        assertEquals(filterResponse.status, 200)
        assertEquals(pollResponse.status, 200)
        assertEquals(pollLines.flatMap(_.event).map(_.payload.body), Vector("known"))
        assertEquals(filteredState.outboundSessions(session1).filters.get(chainId), Some(filter))
        assertEquals(state.engine.sessionById(session1), None)
        assertEquals(state.outboundSessions.get(session1), None)
        assertEquals(state.outboundSessions(session2).filters.get(chainId), None)

  test("opening timeout and open-session idle timeout move sessions to dead without manual disconnect"):
    (
      for
        openingHarness <- Harness.resource(baseInstant)
        openHarness <- Harness.resource(baseInstant)
      yield (openingHarness, openHarness)
    ).use: (openingHarness, openHarness) =>
      for
        openingSessionId <- openingHarness.startOutboundOpening
        openSessionId <- openHarness.openOutboundLocally
        _ <- openingHarness.clock.advance(Duration.ofSeconds(31))
        _ <- openHarness.clock.advance(Duration.ofSeconds(31))
        timedOutState <- openingHarness.runtime.snapshotState
        idleState <- openHarness.runtime.snapshotState
        idlePoll <- openHarness.postJson(
          s"/gossip/events/${openSessionId.value}",
          EventRequestWire("poll").asJson.noSpaces,
        )
        idleLines <- decodeNdjson[EventEnvelopeWire[TestTx]](idlePoll.body)
      yield
        assertEquals(
          timedOutState.engine.sessionById(openingSessionId).map(_.status),
          Some(DirectionalSessionStatus.Dead),
        )
        assertEquals(
          idleState.engine.sessionById(openSessionId).map(_.status),
          Some(DirectionalSessionStatus.Dead),
        )
        assertEquals(idlePoll.status, 200)
        assertEquals(idleLines.map(_.kind), Vector("rejection"))
        assertEquals(idleLines.flatMap(_.rejection).map(_.reason), Vector("sessionNotOpen"))

  test("pre-open event and control traffic reject and close the opening lineage over HTTP"):
    Harness.resource(baseInstant).use: harness =>
      for
        openingEventSession <- harness.startOutboundOpening
        eventResponse <- harness.postJson(
          s"/gossip/events/${openingEventSession.value}",
          EventRequestWire("poll").asJson.noSpaces,
        )
        eventLines <- decodeNdjson[EventEnvelopeWire[TestTx]](eventResponse.body)
        stateAfterEvent <- harness.runtime.snapshotState
        openingControlSession <- harness.startOutboundOpening
        controlResponse <- harness.postJson(
          s"/gossip/control/${openingControlSession.value}",
          ControlRequestWire("controlKeepAlive").asJson.noSpaces,
        )
        controlRejection <- IO.fromEither(
          decode[RejectionWire](controlResponse.body).leftMap(new IllegalStateException(_))
        )
        stateAfterControl <- harness.runtime.snapshotState
      yield
        assertEquals(eventResponse.status, 200)
        assertEquals(eventLines.map(_.kind), Vector("rejection"))
        assertEquals(eventLines.flatMap(_.rejection).map(_.reason), Vector("preOpenEventTraffic"))
        assertEquals(
          stateAfterEvent.engine.sessionById(openingEventSession).map(_.status),
          Some(DirectionalSessionStatus.Closed),
        )
        assertEquals(controlResponse.status, 400)
        assertEquals(controlRejection.reason, "preOpenControlTraffic")
        assertEquals(
          stateAfterControl.engine.sessionById(openingControlSession).map(_.status),
          Some(DirectionalSessionStatus.Closed),
        )

  private def decodeNdjson[A: Decoder](body: String): IO[Vector[A]] =
    body.linesIterator.toVector.filter(_.nonEmpty).traverse: line =>
      IO.fromEither(decode[A](line).leftMap(new IllegalStateException(_)))

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

  private def openOutboundViaHttp(
      from: Harness,
      to: Harness,
  ): IO[OpenedSession] =
    for
      proposalEither <- from.runtime.startOutbound(PeerIdentity.unsafe(to.localNodeId), subscription)
      proposal <- IO.fromEither(proposalEither.leftMap(rejection => new IllegalStateException(rejection.reason)))
      response <- to.postJson("/gossip/session/open", toProposalWire(proposal).asJson.noSpaces)
      ackWire <- IO.fromEither(decode[SessionOpenAckWire](response.body).leftMap(new IllegalStateException(_)))
      ack <- IO.fromEither(toAck(ackWire).leftMap(new IllegalArgumentException(_)))
      applyResult <- from.runtime.applyHandshakeAck(ack)
      _ <- IO.fromEither(applyResult.leftMap(rejection => new IllegalStateException(rejection.reason)))
    yield OpenedSession(proposal, ack)

  private final case class Response(
      status: Int,
      body: String,
  )

  private final case class OpenedSession(
      proposal: SessionOpenProposal,
      ack: SessionOpenAck,
  )

  private final case class Harness(
      localNodeId: String,
      remoteNodeId: String,
      runtime: TxGossipRuntime[IO, TestTx],
      source: InMemoryTxArtifactSource[IO, TestTx],
      clock: TestClock,
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

    def openOutboundLocally: IO[DirectionalSessionId] =
      openOutboundLocally()

    def openOutboundLocally(
        heartbeatInterval: Duration = Duration.ofSeconds(10),
        livenessTimeout: Duration = Duration.ofSeconds(30),
        maxControlRetryInterval: Duration = Duration.ofSeconds(30),
    ): IO[DirectionalSessionId] =
      for
        proposalEither <- runtime.startOutbound(PeerIdentity.unsafe(remoteNodeId), subscription)
        proposal <- IO.fromEither(proposalEither.leftMap(rejection => new IllegalStateException(rejection.reason)))
        ack <- IO.fromEither(
          SessionNegotiation
            .acknowledge(
              proposal = proposal,
              heartbeatInterval = heartbeatInterval,
              livenessTimeout = livenessTimeout,
              maxControlRetryInterval = maxControlRetryInterval,
            )
            .leftMap(rejection => new IllegalStateException(rejection.reason))
        )
        applied <- runtime.applyHandshakeAck(ack)
        _ <- IO.fromEither(applied.leftMap(rejection => new IllegalStateException(rejection.reason)))
      yield proposal.sessionId

    def startOutboundOpening: IO[DirectionalSessionId] =
      runtime
        .startOutbound(PeerIdentity.unsafe(remoteNodeId), subscription)
        .flatMap(result => IO.fromEither(result.leftMap(rejection => new IllegalStateException(rejection.reason))))
        .map(_.sessionId)

  private final case class MeshHarness(
      a: Harness,
      b: Harness,
  )

  private object MeshHarness:
    def resource(start: Instant): Resource[IO, MeshHarness] =
      for
        a <- Harness.resource(start, localNodeId = "node-a", remoteNodeId = "node-b")
        b <- Harness.resource(start, localNodeId = "node-b", remoteNodeId = "node-a")
      yield MeshHarness(a = a, b = b)

  private object Harness:
    def resource(
        start: Instant,
        localNodeId: String = "node-a",
        remoteNodeId: String = "node-b",
    ): Resource[IO, Harness] =
      for
        topology <- Resource.eval(
          IO.fromEither(
            StaticPeerTopology
              .parse(
                localNodeIdentity = localNodeId,
                knownPeers = List(remoteNodeId),
                directNeighbors = List(remoteNodeId),
              )
              .leftMap(new IllegalArgumentException(_))
          )
        )
        registry = StaticPeerRegistry(topology)
        authenticator = StaticPeerAuthenticator[IO](registry)
        clock <- Resource.eval(TestClock.create(start))
        given GossipClock[IO] = clock
        source <- Resource.eval(InMemoryTxArtifactSource.create[IO, TestTx])
        sink <- Resource.eval(InMemoryTxArtifactSink.create[IO, TestTx])
        stateStore <- Resource.eval(TxGossipStateStore.inMemory[IO](GossipSessionEngine(registry.localPeer, topology)))
        runtime = TxGossipRuntime.default[IO, TestTx](
          peerAuthenticator = authenticator,
          clock = clock,
          source = source,
          sink = sink,
          topicContracts = GossipTopicContractRegistry.single(TxTopic.contract[TestTx]),
          stateStore = stateStore,
        )
        server <- ArmeriaServer.resource[IO](
          ArmeriaServerConfig(port = 0),
          TxGossipArmeriaAdapter.endpoints[IO, TestTx](runtime),
        )
      yield Harness(
        localNodeId = localNodeId,
        remoteNodeId = remoteNodeId,
        runtime = runtime,
        source = source,
        clock = clock,
        baseUri = s"http://127.0.0.1:${server.activeLocalPort()}",
      )

  private final class TestClock private (ref: Ref[IO, Instant]) extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

    def advance(duration: Duration): IO[Unit] =
      ref.update(_.plus(duration))

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))

  private final case class TestTx(body: String)
  private object TestTx:
    given Encoder[TestTx] = deriveEncoder
    given Decoder[TestTx] = deriveDecoder

  private given ByteEncoder[TestTx] = ByteEncoder[Utf8].contramap(tx => Utf8(tx.body))
  private given Hash[TestTx] = Hash.build
  private given TxIdentity[TestTx] = TxIdentity.fromHash[TestTx]
