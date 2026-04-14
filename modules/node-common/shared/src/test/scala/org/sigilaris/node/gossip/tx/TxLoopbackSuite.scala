package org.sigilaris.node.gossip.tx

import java.time.{Duration, Instant}

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.gossip.*

final class TxLoopbackSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val subscription =
    SessionSubscription.unsafe(ChainTopic(chainId, GossipTopic.tx))
  private val baseInstant = Instant.parse("2026-04-01T00:00:00Z")

  test(
    "loopback handshake delivers events, resumes from setCursor, and preserves timestamps",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      first <- a.source.append(
        chainId,
        TestTx("tx-1"),
        baseInstant.minusSeconds(5),
      )
      _ <- a.source.append(chainId, TestTx("tx-2"), baseInstant.minusSeconds(4))
      _ <- a.clock.advance(Duration.ofSeconds(1))
      polled1 <- a.runtime.pollEvents(sessionId)
      messages1 = polled1.toOption.get
      received1 <- b.runtime.receiveEvents(sessionId, messages1)
      lastCursor = received1.toOption.get.lastCursor.get
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          Vector(
            ControlOp.SetCursor(
              CompositeCursor(
                Map(ChainTopic(chainId, GossipTopic.tx) -> lastCursor),
              ),
            ),
          ),
        ),
      )
      _ <- a.source.append(chainId, TestTx("tx-3"), baseInstant.plusSeconds(1))
      _ <- a.clock.advance(Duration.ofSeconds(3))
      polled2 <- a.runtime.pollEvents(sessionId)
      messages2 = polled2.toOption.get
      received2 <- b.runtime.receiveEvents(sessionId, messages2)
    yield
      val events1 = extractEvents(messages1)
      val events2 = extractEvents(messages2)
      assertEquals(events1.map(_.payload.body), Vector("tx-1", "tx-2"))
      assertEquals(events1.head.ts, first.ts)
      assertEquals(
        received1.toOption.get.applied.map(_.payload.body),
        Vector("tx-1", "tx-2"),
      )
      assertEquals(events2.map(_.payload.body), Vector("tx-3"))
      assertEquals(
        received2.toOption.get.applied.map(_.payload.body),
        Vector("tx-3"),
      )

  test(
    "loopback surfaces stale cursors and deduplicates control retries within the horizon",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId     = opened.proposal.sessionId
      invalidCursor = CursorToken.issue(ByteVector.fromLong(1L), version = 2)
      batch = controlBatch(
        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        Vector(
          ControlOp.SetCursor(
            CompositeCursor(
              Map(ChainTopic(chainId, GossipTopic.tx) -> invalidCursor),
            ),
          ),
        ),
      )
      first  <- a.runtime.receiveControlBatch(sessionId, batch)
      second <- a.runtime.receiveControlBatch(sessionId, batch)
      polled <- a.runtime.pollEvents(sessionId)
    yield
      assertEquals(first, Right(ControlBatchOutcome.Applied))
      assertEquals(second, Right(ControlBatchOutcome.Deduplicated))
      assertEquals(polled.left.map(_.rejectionClass), Left("staleCursor"))

  test(
    "loopback honors setKnown, explicit requestById fetches, and sink dedupes newer cursors with the same stable id",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      txOther <- a.source.append(
        chainId,
        TestTx("other"),
        baseInstant.minusSeconds(7),
      )
      _ <- a.source.append(chainId, TestTx("same"), baseInstant.minusSeconds(6))
      _ <- a.source.append(chainId, TestTx("same"), baseInstant.minusSeconds(5))
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
          Vector(ControlOp.SetKnownTx(chainId, Vector(txOther.id))),
        ),
      )
      _       <- a.clock.advance(Duration.ofSeconds(1))
      polled1 <- a.runtime.pollEvents(sessionId)
      messages1 = polled1.toOption.get
      received1 <- b.runtime.receiveEvents(sessionId, messages1)
      lastCursor = received1.toOption.get.lastCursor.get
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          Vector(
            ControlOp.SetCursor(
              CompositeCursor(
                Map(ChainTopic(chainId, GossipTopic.tx) -> lastCursor),
              ),
            ),
            ControlOp.RequestByIdTx(chainId, Vector(txOther.id)),
          ),
        ),
      )
      polled2 <- a.runtime.pollEvents(sessionId)
      messages2 = polled2.toOption.get
      received2 <- b.runtime.receiveEvents(sessionId, messages2)
    yield
      assertEquals(
        extractEvents(messages1).map(_.payload.body),
        Vector("same", "same"),
      )
      assertEquals(
        received1.toOption.get.applied.map(_.payload.body),
        Vector("same"),
      )
      assertEquals(
        received1.toOption.get.duplicates.map(_.payload.body),
        Vector("same"),
      )
      assertEquals(
        extractEvents(messages2).map(_.payload.body),
        Vector("other"),
      )
      assertEquals(
        received2.toOption.get.applied.map(_.payload.body),
        Vector("other"),
      )

  test(
    "reverse direction reuses the peer correlation id and half-open relationship is preserved after one side dies",
  ):
    for
      a          <- Harness.create("node-a", "node-b", baseInstant)
      b          <- Harness.create("node-b", "node-a", baseInstant)
      openedAtoB <- openOutbound(a, b)
      proposalBtoAEither <- b.runtime.startOutbound(
        PeerIdentity.unsafe("node-a"),
        subscription,
      )
      proposalBtoA <- IO.fromEither(
        proposalBtoAEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      inboundOnA <- a.runtime.handleInboundProposal(proposalBtoA)
      acceptedOnA <- inboundOnA match
        case accepted: InboundHandshakeResult.Accepted =>
          IO.pure(accepted)
        case rejected: InboundHandshakeResult.Rejected =>
          IO.raiseError(new IllegalStateException(rejected.rejection.reason))
      _ <- b.runtime
        .applyHandshakeAck(acceptedOnA.ack)
        .flatMap: result =>
          IO.fromEither(
            result.leftMap(rejection =>
              new IllegalStateException(rejection.reason),
            ),
          )
      relationshipOpen <- a.runtime.relationshipWith(
        PeerIdentity.unsafe("node-b"),
      )
      _ <- a.runtime
        .markSessionDead(openedAtoB.proposal.sessionId)
        .flatMap: result =>
          IO.fromEither(
            result.leftMap(rejection =>
              new IllegalStateException(rejection.reason),
            ),
          )
      relationshipHalfOpen <- a.runtime.relationshipWith(
        PeerIdentity.unsafe("node-b"),
      )
    yield
      assertEquals(
        proposalBtoA.peerCorrelationId,
        openedAtoB.proposal.peerCorrelationId,
      )
      assertEquals(
        relationshipOpen.map(_.status),
        Some(PeerRelationshipStatus.Open),
      )
      assertEquals(
        relationshipHalfOpen.map(_.status),
        Some(PeerRelationshipStatus.HalfOpen),
      )

  test("config batching applies the maxBatchItems cap and flushInterval timer"):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "12121212-1212-4212-8212-121212121212",
          Vector(
            ControlOp.Config(
              Map(
                SessionConfigKey.TxMaxBatchItems   -> 2L,
                SessionConfigKey.TxFlushIntervalMs -> 5000L,
              ),
            ),
          ),
        ),
      )
      _         <- a.source.append(chainId, TestTx("tx-1"), baseInstant)
      _         <- a.source.append(chainId, TestTx("tx-2"), baseInstant)
      _         <- a.source.append(chainId, TestTx("tx-3"), baseInstant)
      firstPoll <- a.runtime.pollEvents(sessionId)
      firstMessages = firstPoll.toOption.get
      firstReceive <- b.runtime.receiveEvents(sessionId, firstMessages)
      secondCursor = firstReceive.toOption.get.lastCursor.get
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "13131313-1313-4313-8313-131313131313",
          Vector(
            ControlOp.SetCursor(
              CompositeCursor(
                Map(ChainTopic(chainId, GossipTopic.tx) -> secondCursor),
              ),
            ),
            ControlOp.Config(
              Map(
                SessionConfigKey.TxMaxBatchItems   -> 10L,
                SessionConfigKey.TxFlushIntervalMs -> 5000L,
              ),
            ),
          ),
        ),
      )
      _             <- a.source.append(chainId, TestTx("tx-4"), baseInstant)
      immediatePoll <- a.runtime.pollEvents(sessionId)
      _             <- a.clock.advance(Duration.ofSeconds(5))
      delayedPoll   <- a.runtime.pollEvents(sessionId)
    yield
      assertEquals(
        extractEvents(firstMessages).map(_.payload.body),
        Vector("tx-1", "tx-2"),
      )
      assertEquals(immediatePoll, Right(Vector.empty))
      assertEquals(
        extractEvents(delayedPoll.toOption.get).map(_.payload.body),
        Vector("tx-3", "tx-4"),
      )

  test(
    "requestById emission is capped by maxBatchItems and drains across polls",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      tx1 <- a.source.append(
        chainId,
        TestTx("tx-1"),
        baseInstant.minusSeconds(3),
      )
      tx2 <- a.source.append(
        chainId,
        TestTx("tx-2"),
        baseInstant.minusSeconds(2),
      )
      tx3 <- a.source.append(
        chainId,
        TestTx("tx-3"),
        baseInstant.minusSeconds(1),
      )
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "15151515-1515-4515-8515-151515151515",
          Vector(
            ControlOp.Config(Map(SessionConfigKey.TxMaxBatchItems -> 2L)),
            ControlOp.SetKnownTx(chainId, Vector(tx1.id, tx2.id, tx3.id)),
            ControlOp.RequestByIdTx(chainId, Vector(tx1.id, tx2.id, tx3.id)),
          ),
        ),
      )
      firstPoll <- a.runtime.pollEvents(sessionId)
      firstMessages = firstPoll.toOption.get
      _          <- b.runtime.receiveEvents(sessionId, firstMessages)
      secondPoll <- a.runtime.pollEvents(sessionId)
      secondMessages = secondPoll.toOption.get
    yield
      assertEquals(
        extractEvents(firstMessages).map(_.payload.body),
        Vector("tx-1", "tx-2"),
      )
      assertEquals(
        extractEvents(secondMessages).map(_.payload.body),
        Vector("tx-3"),
      )

  test(
    "flushInterval uses producer-local availability time instead of artifact ts",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "18181818-1818-4818-8818-181818181818",
          Vector(
            ControlOp.Config(
              Map(
                SessionConfigKey.TxMaxBatchItems   -> 10L,
                SessionConfigKey.TxFlushIntervalMs -> 5000L,
              ),
            ),
          ),
        ),
      )
      _ <- a.source.append(
        chainId,
        TestTx("old-ts"),
        baseInstant.minus(Duration.ofDays(1)),
      )
      firstImmediate <- a.runtime.pollEvents(sessionId)
      _              <- a.clock.advance(Duration.ofSeconds(5))
      firstDelayed   <- a.runtime.pollEvents(sessionId)
      firstMessages = firstDelayed.toOption.get
      firstReceive <- b.runtime.receiveEvents(sessionId, firstMessages)
      firstCursor = firstReceive.toOption.get.lastCursor.get
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "19191919-1919-4919-8919-191919191919",
          Vector(
            ControlOp.SetCursor(
              CompositeCursor(
                Map(ChainTopic(chainId, GossipTopic.tx) -> firstCursor),
              ),
            ),
          ),
        ),
      )
      _ <- a.source.append(
        chainId,
        TestTx("future-ts"),
        baseInstant.plus(Duration.ofDays(1)),
      )
      secondImmediate <- a.runtime.pollEvents(sessionId)
      _               <- a.clock.advance(Duration.ofSeconds(5))
      secondDelayed   <- a.runtime.pollEvents(sessionId)
    yield
      assertEquals(firstImmediate, Right(Vector.empty))
      assertEquals(
        extractEvents(firstMessages).map(_.payload.body),
        Vector("old-ts"),
      )
      assertEquals(secondImmediate, Right(Vector.empty))
      assertEquals(
        extractEvents(secondDelayed.toOption.get).map(_.payload.body),
        Vector("future-ts"),
      )

  test(
    "explicit requestById delivery does not move the stream cursor backwards",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      oldEvent <- a.source.append(
        chainId,
        TestTx("tx-1"),
        baseInstant.minusSeconds(3),
      )
      _ <- a.source.append(chainId, TestTx("tx-2"), baseInstant.minusSeconds(2))
      _ <- a.clock.advance(Duration.ofSeconds(1))
      initialPoll <- a.runtime.pollEvents(sessionId)
      initialMessages = initialPoll.toOption.get
      initialReceive <- b.runtime.receiveEvents(sessionId, initialMessages)
      latestCursor = initialReceive.toOption.get.lastCursor.get
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "17171717-1717-4717-8717-171717171717",
          Vector(
            ControlOp.SetCursor(
              CompositeCursor(
                Map(ChainTopic(chainId, GossipTopic.tx) -> latestCursor),
              ),
            ),
            ControlOp.RequestByIdTx(chainId, Vector(oldEvent.id)),
          ),
        ),
      )
      requestPoll <- a.runtime.pollEvents(sessionId)
      requestMessages = requestPoll.toOption.get
      nextPoll <- a.runtime.pollEvents(sessionId)
    yield
      assertEquals(
        extractEvents(requestMessages).map(_.payload.body),
        Vector("tx-1"),
      )
      assertEquals(nextPoll, Right(Vector.empty))

  test(
    "nack replays from the requested cursor without advancing the durable checkpoint",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      event1 <- a.source.append(
        chainId,
        TestTx("tx-1"),
        baseInstant.minusSeconds(3),
      )
      _ <- a.source.append(chainId, TestTx("tx-2"), baseInstant.minusSeconds(2))
      _ <- a.source.append(chainId, TestTx("tx-3"), baseInstant.minusSeconds(1))
      _ <- a.clock.advance(Duration.ofSeconds(1))
      initialPoll <- a.runtime.pollEvents(sessionId)
      initialMessages = initialPoll.toOption.get
      initialReceive <- b.runtime.receiveEvents(sessionId, initialMessages)
      latestCursor = initialReceive.toOption.get.lastCursor.get
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "14141414-1414-4414-8414-141414141414",
          Vector(
            ControlOp.SetCursor(
              CompositeCursor(
                Map(ChainTopic(chainId, GossipTopic.tx) -> latestCursor),
              ),
            ),
            ControlOp.Nack(chainId, GossipTopic.tx, Some(event1.cursor)),
          ),
        ),
      )
      replayPoll <- a.runtime.pollEvents(sessionId)
      replayMessages = replayPoll.toOption.get
      replayReceive <- b.runtime.receiveEvents(sessionId, replayMessages)
      nextPoll      <- a.runtime.pollEvents(sessionId)
    yield
      assertEquals(
        extractEvents(replayMessages).map(_.payload.body),
        Vector("tx-2", "tx-3"),
      )
      assertEquals(
        replayReceive.toOption.get.duplicates.map(_.payload.body),
        Vector("tx-2", "tx-3"),
      )
      assertEquals(nextPoll, Right(Vector.empty))

  test(
    "nack with no cursor replays the full chain from the origin immediately",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      _ <- a.source.append(chainId, TestTx("tx-1"), baseInstant.minusSeconds(3))
      _ <- a.source.append(chainId, TestTx("tx-2"), baseInstant.minusSeconds(2))
      _ <- a.clock.advance(Duration.ofSeconds(1))
      initialPoll <- a.runtime.pollEvents(sessionId)
      initialMessages = initialPoll.toOption.get
      initialReceive <- b.runtime.receiveEvents(sessionId, initialMessages)
      latestCursor = initialReceive.toOption.get.lastCursor.get
      _ <- a.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "16161616-1616-4616-8616-161616161616",
          Vector(
            ControlOp.SetCursor(
              CompositeCursor(
                Map(ChainTopic(chainId, GossipTopic.tx) -> latestCursor),
              ),
            ),
            ControlOp.Nack(chainId, GossipTopic.tx, None),
          ),
        ),
      )
      replayPoll <- a.runtime.pollEvents(sessionId)
      replayMessages = replayPoll.toOption.get
    yield assertEquals(
      extractEvents(replayMessages).map(_.payload.body),
      Vector("tx-1", "tx-2"),
    )

  test(
    "receiveEvents does not refresh inbound activity when artifact validation rejects",
  ):
    for
      a      <- Harness.create("node-a", "node-b", baseInstant)
      b      <- Harness.create("node-b", "node-a", baseInstant)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      before <- b.runtime.snapshotState
      _      <- b.clock.advance(Duration.ofSeconds(5))
      rejected <- b.runtime.receiveEvents(
        sessionId,
        Vector(
          EventStreamMessage.Event(
            GossipEvent(
              chainId = chainId,
              topic = GossipTopic.unsafe("audit"),
              id = StableArtifactId.unsafeFromHex("01"),
              cursor = CursorToken.issue(ByteVector.fromLong(1L)),
              ts = baseInstant,
              payload = TestTx("bad"),
            ),
          ),
        ),
      )
      after <- b.runtime.snapshotState
    yield
      assertEquals(
        rejected.left.map(_.rejectionClass),
        Left("artifactContractRejected"),
      )
      assertEquals(
        after.engine.sessionById(sessionId).map(_.lastActivityAt),
        before.engine.sessionById(sessionId).map(_.lastActivityAt),
      )

  private def openOutbound(
      from: Harness,
      to: Harness,
  ): IO[OpenedSession] =
    for
      proposalEither <- from.runtime.startOutbound(
        PeerIdentity.unsafe(to.localNodeId),
        subscription,
      )
      proposal <- IO.fromEither(
        proposalEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      inboundResult <- to.runtime.handleInboundProposal(proposal)
      accepted <- inboundResult match
        case accepted: InboundHandshakeResult.Accepted =>
          IO.pure(accepted)
        case rejected: InboundHandshakeResult.Rejected =>
          IO.raiseError(new IllegalStateException(rejected.rejection.reason))
      ackResult <- from.runtime.applyHandshakeAck(accepted.ack)
      _ <- IO.fromEither(
        ackResult.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
    yield OpenedSession(proposal, accepted.ack)

  private def extractEvents(
      messages: Vector[EventStreamMessage[TestTx]],
  ): Vector[GossipEvent[TestTx]] =
    messages.collect:
      case EventStreamMessage.Event(event) => event

  private def controlBatch(
      idempotencyKey: String,
      ops: Vector[ControlOp],
  ): ControlBatch =
    ControlBatch.create(idempotencyKey, ops).toOption.get

  private final case class OpenedSession(
      proposal: SessionOpenProposal,
      ack: SessionOpenAck,
  )

  private final case class Harness(
      localNodeId: String,
      runtime: TxGossipRuntime[IO, TestTx],
      source: InMemoryTxArtifactSource[IO, TestTx],
      sink: InMemoryTxArtifactSink[IO, TestTx],
      clock: TestClock,
  )

  private object Harness:
    def create(
        localNodeId: String,
        remoteNodeId: String,
        instant: Instant,
    ): IO[Harness] =
      for
        topology <- IO.fromEither(
          StaticPeerTopology
            .parse(
              localNodeIdentity = localNodeId,
              knownPeers = List(remoteNodeId),
              directNeighbors = List(remoteNodeId),
            )
            .leftMap(new IllegalArgumentException(_)),
        )
        registry      = StaticPeerRegistry(topology)
        authenticator = StaticPeerAuthenticator[IO](registry)
        clock <- TestClock.create(instant)
        given GossipClock[IO] = clock
        source <- InMemoryTxArtifactSource.create[IO, TestTx]
        sink   <- InMemoryTxArtifactSink.create[IO, TestTx]
        stateStore <- TxGossipStateStore.inMemory[IO](
          GossipSessionEngine(registry.localPeer, topology),
        )
      yield Harness(
        localNodeId = localNodeId,
        runtime = TxGossipRuntime.default[IO, TestTx](
          peerAuthenticator = authenticator,
          clock = clock,
          source = source,
          sink = sink,
          topicContracts =
            GossipTopicContractRegistry.single(TxTopic.contract[TestTx]),
          stateStore = stateStore,
        ),
        source = source,
        sink = sink,
        clock = clock,
      )

  private final class TestClock private (ref: Ref[IO, Instant])
      extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

    def advance(duration: Duration): IO[Unit] =
      ref.update(_.plus(duration))

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))

  private final case class TestTx(body: String)

  private given ByteEncoder[TestTx] =
    ByteEncoder[Utf8].contramap(tx => Utf8(tx.body))
  private given Hash[TestTx]       = Hash.build
  private given TxIdentity[TestTx] = TxIdentity.fromHash[TestTx]
