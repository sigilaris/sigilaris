package org.sigilaris.node.jvm.runtime.gossip.tx

import java.time.{Duration, Instant}

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.syntax.all.*
import scodec.bits.ByteVector
import munit.CatsEffectSuite

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.gossip.*

final class TxControlInterpreterSuite extends CatsEffectSuite:

  private val chainId    = ChainId.unsafe("chain-main")
  private val remotePeer = PeerIdentity.unsafe("node-b")
  private val subscription =
    SessionSubscription.unsafe(ChainTopic(chainId, GossipTopic.tx))
  private val startedAt = Instant.parse("2026-04-01T00:00:00Z")

  test("TxIdentity.fromHash uses the core tx hash bytes as the stable id"):
    IO:
      val tx = TestTx("hello")
      assertEquals(
        TxIdentity.fromHash[TestTx].stableIdOf(tx).bytes,
        tx.toHash.toUInt256.bytes,
      )

  test(
    "control batches apply atomically, deduplicate by idempotency key, and expire the retry horizon",
  ):
    for
      harness   <- Harness.create("node-a", startedAt)
      sessionId <- openOutbound(harness)
      appended  <- harness.source.append(chainId, TestTx("tx-1"), startedAt)
      batch = controlBatch(
        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        Vector(
          ControlOp.SetKnownTx(chainId, Vector(appended.id)),
          ControlOp.Config(
            Map(
              SessionConfigKey.TxMaxBatchItems   -> 1L,
              SessionConfigKey.TxFlushIntervalMs -> 5L,
            ),
          ),
        ),
      )
      first           <- harness.runtime.receiveControlBatch(sessionId, batch)
      stateAfterFirst <- harness.runtime.snapshotState
      second          <- harness.runtime.receiveControlBatch(sessionId, batch)
      _               <- harness.clock.advance(Duration.ofSeconds(20))
      _               <- harness.runtime.controlKeepAlive(sessionId)
      _               <- harness.clock.advance(Duration.ofSeconds(20))
      _               <- harness.runtime.controlKeepAlive(sessionId)
      _               <- harness.clock.advance(Duration.ofSeconds(21))
      third           <- harness.runtime.receiveControlBatch(sessionId, batch)
      stateAfterThird <- harness.runtime.snapshotState
    yield
      assertEquals(first, Right(ControlBatchOutcome.Applied))
      val sessionAfterFirst = stateAfterFirst.outboundSessions(sessionId)
      assertEquals(
        sessionAfterFirst.exactKnownIds.getOrElse(chainId, Set.empty),
        Set(appended.id),
      )
      assertEquals(sessionAfterFirst.batchingConfig.maxBatchItems, 1)
      assertEquals(
        sessionAfterFirst.batchingConfig.flushInterval,
        Duration.ofMillis(5),
      )
      assertEquals(second, Right(ControlBatchOutcome.Deduplicated))
      assertEquals(third, Right(ControlBatchOutcome.Applied))
      assertEquals(
        stateAfterThird.outboundSessions(sessionId).idempotencyKeys.size,
        1,
      )

  test("rejecting a control batch leaves session state unchanged"):
    for
      harness   <- Harness.create("node-a", startedAt)
      sessionId <- openOutbound(harness)
      appended  <- harness.source.append(chainId, TestTx("tx-1"), startedAt)
      before    <- harness.runtime.snapshotState
      batch = controlBatch(
        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        Vector(
          ControlOp.SetKnownTx(chainId, Vector(appended.id)),
          ControlOp.Config(Map(SessionConfigKey.TxMaxBatchItems -> 0L)),
        ),
      )
      result <- harness.runtime.receiveControlBatch(sessionId, batch)
      after  <- harness.runtime.snapshotState
    yield
      assertEquals(result.left.map(_.reason), Left("invalidConfigValue"))
      assertEquals(
        after.outboundSessions(sessionId),
        before.outboundSessions(sessionId),
      )

  test(
    "setFilter replaces prior state and reconnect starts with an empty filter state",
  ):
    val oldFilter =
      TxBloomFilterSupport.build(Vector.empty, bitsetBytes = 4, numHashes = 2)

    for
      harness   <- Harness.create("node-a", startedAt)
      sessionId <- openOutbound(harness)
      firstTx   <- harness.source.append(chainId, TestTx("tx-1"), startedAt)
      secondTx <- harness.source.append(
        chainId,
        TestTx("tx-2"),
        startedAt.plusSeconds(1),
      )
      filter1 = TxBloomFilterSupport.build(
        Vector(firstTx.id),
        bitsetBytes = 8,
        numHashes = 2,
      )
      filter2 = TxBloomFilterSupport.build(
        Vector(secondTx.id),
        bitsetBytes = 8,
        numHashes = 2,
      )
      _ <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
          Vector(ControlOp.SetFilter(chainId, GossipTopic.tx, filter1)),
        ),
      )
      _ <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          Vector(ControlOp.SetFilter(chainId, GossipTopic.tx, filter2)),
        ),
      )
      replaced <- harness.runtime.snapshotState
      _ <- harness.runtime
        .markSessionDead(sessionId)
        .flatMap: result =>
          IO.fromEither(
            result.leftMap(rejection =>
              new IllegalStateException(rejection.reason),
            ),
          )
      newSessionId <- openOutbound(harness)
      reconnected  <- harness.runtime.snapshotState
    yield
      assert(oldFilter != filter1)
      assertEquals(
        replaced.outboundSessions(sessionId).filters.get(chainId),
        Some(filter2),
      )
      assertEquals(
        reconnected.outboundSessions(newSessionId).filters.get(chainId),
        None,
      )

  test(
    "pre-open event traffic rejects with the dedicated reason and closes the opening lineage",
  ):
    for
      harness   <- Harness.create("node-a", startedAt)
      sessionId <- startOutboundOpening(harness)
      polled    <- harness.runtime.pollEvents(sessionId)
      state     <- harness.runtime.snapshotState
    yield
      assertEquals(polled.left.map(_.rejectionClass), Left("handshakeRejected"))
      assertEquals(polled.left.map(_.reason), Left("preOpenEventTraffic"))
      assertEquals(
        state.engine.sessionById(sessionId).map(_.status),
        Some(DirectionalSessionStatus.Closed),
      )

  test(
    "pre-open control traffic rejects with the dedicated reason and closes the opening lineage",
  ):
    for
      harness   <- Harness.create("node-a", startedAt)
      sessionId <- startOutboundOpening(harness)
      result <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "34343434-3434-4434-8434-343434343434",
          Vector(ControlOp.Config(Map(SessionConfigKey.TxMaxBatchItems -> 1L))),
        ),
      )
      state <- harness.runtime.snapshotState
    yield
      assertEquals(result.left.map(_.reason), Left("preOpenControlTraffic"))
      assertEquals(
        state.engine.sessionById(sessionId).map(_.status),
        Some(DirectionalSessionStatus.Closed),
      )

  test("requestById rejects oversize requests and unknown ids"):
    for
      harness   <- Harness.create("node-a", startedAt)
      sessionId <- openOutbound(harness)
      unknownId = StableArtifactId.unsafeFromHex("01")
      oversizeIds =
        (0 until 1025).toVector.map(index =>
          StableArtifactId.unsafeFromHex(f"${index + 1}%02x"),
        )
      oversize <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
          Vector(ControlOp.RequestByIdTx(chainId, oversizeIds)),
        ),
      )
      unknown <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "ffffffff-ffff-4fff-8fff-ffffffffffff",
          Vector(ControlOp.RequestByIdTx(chainId, Vector(unknownId))),
        ),
      )
    yield
      assertEquals(oversize.left.map(_.reason), Left("requestByIdTooLarge"))
      assertEquals(unknown.left.map(_.reason), Left("unknownRequestedArtifact"))

  test("cumulative setKnown entries stay bounded by policy"):
    val known1 =
      StableArtifactId.unsafeFromBytes(ByteVector.fromByte(0x01.toByte))
    val known2 =
      StableArtifactId.unsafeFromBytes(ByteVector.fromByte(0x02.toByte))
    val known3 =
      StableArtifactId.unsafeFromBytes(ByteVector.fromByte(0x03.toByte))

    for
      harness <- Harness.create(
        "node-a",
        startedAt,
        policy = TxRuntimePolicy(maxTxSetKnownEntries = 2),
      )
      sessionId <- openOutbound(harness)
      first <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "11111111-1111-4111-8111-111111111111",
          Vector(ControlOp.SetKnownTx(chainId, Vector(known1))),
        ),
      )
      second <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "22222222-2222-4222-8222-222222222222",
          Vector(ControlOp.SetKnownTx(chainId, Vector(known2, known3))),
        ),
      )
      state <- harness.runtime.snapshotState
    yield
      assertEquals(first, Right(ControlBatchOutcome.Applied))
      assertEquals(second.left.map(_.reason), Left("setKnownTooLarge"))
      assertEquals(
        state
          .outboundSessions(sessionId)
          .exactKnownIds
          .getOrElse(chainId, Set.empty),
        Set(known1),
      )

  test("setKnown counts distinct ids rather than duplicate input entries"):
    val known1 =
      StableArtifactId.unsafeFromBytes(ByteVector.fromByte(0x01.toByte))
    val known2 =
      StableArtifactId.unsafeFromBytes(ByteVector.fromByte(0x02.toByte))

    for
      harness <- Harness.create(
        "node-a",
        startedAt,
        policy = TxRuntimePolicy(maxTxSetKnownEntries = 2),
      )
      sessionId <- openOutbound(harness)
      result <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "23232323-2323-4232-8232-232323232323",
          Vector(ControlOp.SetKnownTx(chainId, Vector(known1, known1, known2))),
        ),
      )
      state <- harness.runtime.snapshotState
    yield
      assertEquals(result, Right(ControlBatchOutcome.Applied))
      assertEquals(
        state
          .outboundSessions(sessionId)
          .exactKnownIds
          .getOrElse(chainId, Set.empty),
        Set(known1, known2),
      )

  test(
    "unresolved bloom positives fall back to explicit backfillUnavailable rejection",
  ):
    for
      harness   <- Harness.create("node-a", startedAt)
      sessionId <- openOutbound(harness)
      event <- harness.source.append(
        chainId,
        TestTx("tx-1"),
        startedAt.minusSeconds(5),
      )
      filter = TxBloomFilterSupport.build(
        Vector(event.id),
        bitsetBytes = 8,
        numHashes = 2,
      )
      _ <- harness.runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "99999999-9999-4999-8999-999999999999",
          Vector(ControlOp.SetFilter(chainId, GossipTopic.tx, filter)),
        ),
      )
      _      <- harness.clock.advance(Duration.ofSeconds(2))
      polled <- harness.runtime.pollEvents(sessionId)
    yield assertEquals(
      polled.left.map(_.rejectionClass),
      Left("backfillUnavailable"),
    )

  test(
    "pollEvents does not overwrite control-state updates committed while polling is in flight",
  ):
    for
      topology <- IO.fromEither(
        StaticPeerTopology
          .parse(
            localNodeIdentity = "node-a",
            knownPeers = List("node-b"),
            directNeighbors = List("node-b"),
          )
          .leftMap(new IllegalArgumentException(_)),
      )
      registry      = StaticPeerRegistry(topology)
      authenticator = StaticPeerAuthenticator[IO](registry)
      clock <- TestClock.create(startedAt)
      given GossipClock[IO] = clock
      delegate <- InMemoryTxArtifactSource.create[IO, TestTx]
      started  <- Deferred[IO, Unit]
      release  <- Deferred[IO, Unit]
      source = BlockingSource(delegate, started, release)
      sink <- InMemoryTxArtifactSink.create[IO, TestTx]
      stateStore <- TxGossipStateStore.inMemory[IO](
        GossipSessionEngine(registry.localPeer, topology),
      )
      runtime = TxGossipRuntime.default[IO, TestTx](
        peerAuthenticator = authenticator,
        clock = clock,
        source = source,
        sink = sink,
        topicContracts =
          GossipTopicContractRegistry.single(TxTopic.contract[TestTx]),
        stateStore = stateStore,
      )
      harness = Harness(runtime, delegate, clock)
      sessionId <- openOutbound(harness)
      _ <- delegate.append(chainId, TestTx("tx-1"), startedAt.minusSeconds(2))
      pollFiber <- runtime.pollEvents(sessionId).start
      _         <- started.get
      batchResult <- runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "33333333-3333-4333-8333-333333333333",
          Vector(
            ControlOp.Config(
              Map(
                SessionConfigKey.TxMaxBatchItems   -> 1L,
                SessionConfigKey.TxFlushIntervalMs -> 17L,
              ),
            ),
          ),
        ),
      )
      _     <- release.complete(())
      _     <- pollFiber.joinWithNever
      state <- runtime.snapshotState
    yield
      assertEquals(batchResult, Right(ControlBatchOutcome.Applied))
      val session = state.outboundSessions(sessionId)
      assertEquals(session.batchingConfig.maxBatchItems, 1)
      assertEquals(session.batchingConfig.flushInterval, Duration.ofMillis(17))

  test(
    "pollEvents returns unknownSession when the session disappears before commit",
  ):
    for
      topology <- IO.fromEither(
        StaticPeerTopology
          .parse(
            localNodeIdentity = "node-a",
            knownPeers = List("node-b"),
            directNeighbors = List("node-b"),
          )
          .leftMap(new IllegalArgumentException(_)),
      )
      registry      = StaticPeerRegistry(topology)
      authenticator = StaticPeerAuthenticator[IO](registry)
      clock <- TestClock.create(startedAt)
      given GossipClock[IO] = clock
      delegate <- InMemoryTxArtifactSource.create[IO, TestTx]
      started  <- Deferred[IO, Unit]
      release  <- Deferred[IO, Unit]
      source = BlockingSource(delegate, started, release)
      sink <- InMemoryTxArtifactSink.create[IO, TestTx]
      stateStore <- TxGossipStateStore.inMemory[IO](
        GossipSessionEngine(registry.localPeer, topology),
      )
      runtime = TxGossipRuntime.default[IO, TestTx](
        peerAuthenticator = authenticator,
        clock = clock,
        source = source,
        sink = sink,
        topicContracts =
          GossipTopicContractRegistry.single(TxTopic.contract[TestTx]),
        stateStore = stateStore,
      )
      harness = Harness(runtime, delegate, clock)
      sessionId <- openOutbound(harness)
      _ <- delegate.append(chainId, TestTx("tx-1"), startedAt.minusSeconds(2))
      pollFiber <- runtime.pollEvents(sessionId).start
      _         <- started.get
      _ <- runtime
        .closeSession(sessionId)
        .flatMap(result =>
          IO.fromEither(
            result.leftMap(r => new IllegalStateException(r.reason)),
          ),
        )
      _          <- release.complete(())
      pollResult <- pollFiber.joinWithNever
    yield
      assertEquals(
        pollResult.left.map(_.rejectionClass),
        Left("handshakeRejected"),
      )
      assertEquals(pollResult.left.map(_.reason), Left("unknownSession"))

  test("concurrent polls do not regress the stream cursor"):
    for
      topology <- IO.fromEither(
        StaticPeerTopology
          .parse(
            localNodeIdentity = "node-a",
            knownPeers = List("node-b"),
            directNeighbors = List("node-b"),
          )
          .leftMap(new IllegalArgumentException(_)),
      )
      registry      = StaticPeerRegistry(topology)
      authenticator = StaticPeerAuthenticator[IO](registry)
      clock <- TestClock.create(startedAt)
      given GossipClock[IO] = clock
      delegate      <- InMemoryTxArtifactSource.create[IO, TestTx]
      firstStarted  <- Deferred[IO, Unit]
      firstRelease  <- Deferred[IO, Unit]
      secondStarted <- Deferred[IO, Unit]
      secondRelease <- Deferred[IO, Unit]
      callCount     <- Ref.of[IO, Int](0)
      source = SequencedBlockingSource(
        delegate,
        firstStarted,
        firstRelease,
        secondStarted,
        secondRelease,
        callCount,
      )
      sink <- InMemoryTxArtifactSink.create[IO, TestTx]
      stateStore <- TxGossipStateStore.inMemory[IO](
        GossipSessionEngine(registry.localPeer, topology),
      )
      runtime = TxGossipRuntime.default[IO, TestTx](
        peerAuthenticator = authenticator,
        clock = clock,
        source = source,
        sink = sink,
        topicContracts =
          GossipTopicContractRegistry.single(TxTopic.contract[TestTx]),
        stateStore = stateStore,
      )
      harness = Harness(runtime, delegate, clock)
      sessionId <- openOutbound(harness)
      _ <- delegate.append(chainId, TestTx("tx-1"), startedAt.minusSeconds(2))
      poll1    <- runtime.pollEvents(sessionId).start
      _        <- firstStarted.get
      poll2    <- runtime.pollEvents(sessionId).start
      _        <- secondStarted.get
      _        <- firstRelease.complete(())
      _        <- poll1.joinWithNever
      _        <- secondRelease.complete(())
      _        <- poll2.joinWithNever
      nextPoll <- runtime.pollEvents(sessionId)
    yield assertEquals(nextPoll, Right(Vector.empty))

  test("event-path session lookup failures use handshake rejection class"):
    for
      harness   <- Harness.create("node-a", startedAt)
      keepAlive <- harness.runtime.eventKeepAlive(DirectionalSessionId.random())
      poll      <- harness.runtime.pollEvents(DirectionalSessionId.random())
    yield
      assertEquals(
        keepAlive.left.map(_.rejectionClass),
        Left("handshakeRejected"),
      )
      assertEquals(keepAlive.left.map(_.reason), Left("unknownSession"))
      assertEquals(poll.left.map(_.rejectionClass), Left("handshakeRejected"))
      assertEquals(poll.left.map(_.reason), Left("unknownSession"))

  test(
    "control keepalive uses control-batch rejection class for unknown sessions",
  ):
    for
      harness <- Harness.create("node-a", startedAt)
      keepAlive <- harness.runtime.controlKeepAlive(
        DirectionalSessionId.random(),
      )
    yield
      assertEquals(
        keepAlive.left.map(_.rejectionClass),
        Left("controlBatchRejected"),
      )
      assertEquals(keepAlive.left.map(_.reason), Left("unknownSession"))

  test(
    "authorizeOpenSession rejects unknown and non-open sessions and touches open-session activity",
  ):
    for
      unknownHarness <- Harness.create("node-a", startedAt)
      openingHarness <- Harness.create("node-a", startedAt)
      openHarness    <- Harness.create("node-a", startedAt)
      unknown <- unknownHarness.runtime.authorizeOpenSession(
        DirectionalSessionId.random(),
      )
      openingSessionId <- startOutboundOpening(openingHarness)
      opening <- openingHarness.runtime.authorizeOpenSession(openingSessionId)
      openSessionId <- openOutbound(openHarness)
      before <- openHarness.runtime.snapshotState.map(
        _.engine.sessionById(openSessionId).map(_.lastActivityAt),
      )
      _          <- openHarness.clock.advance(Duration.ofSeconds(5))
      authorized <- openHarness.runtime.authorizeOpenSession(openSessionId)
      after <- openHarness.runtime.snapshotState.map(
        _.engine.sessionById(openSessionId).map(_.lastActivityAt),
      )
    yield
      assertEquals(unknown.left.map(_.reason), Left("unknownSession"))
      assertEquals(opening.left.map(_.reason), Left("sessionNotOpen"))
      assertEquals(authorized.map(_.sessionId), Right(openSessionId))
      assertEquals(before, Some(startedAt))
      assertEquals(after, Some(startedAt.plusSeconds(5)))

  test(
    "session peer authorization distinguishes ownership from open-session gating",
  ):
    val otherPeer = PeerIdentity.unsafe("node-c")

    for
      openingHarness   <- Harness.create("node-a", startedAt)
      openHarness      <- Harness.create("node-a", startedAt)
      openingSessionId <- startOutboundOpening(openingHarness)
      openingAuthorized <- openingHarness.runtime.authorizeSessionPeer(
        openingSessionId,
        remotePeer,
      )
      openingOpenAuthorized <- openingHarness.runtime
        .authorizeOpenSessionForPeer(
          openingSessionId,
          remotePeer,
        )
      openSessionId <- openOutbound(openHarness)
      mismatchedPeer <- openHarness.runtime.authorizeSessionPeer(
        openSessionId,
        otherPeer,
      )
      authorized <- openHarness.runtime.authorizeOpenSessionForPeer(
        openSessionId,
        remotePeer,
      )
    yield
      assertEquals(openingAuthorized.map(_.sessionId), Right(openingSessionId))
      assertEquals(
        openingOpenAuthorized.left.map(_.reason),
        Left("sessionNotOpen"),
      )
      assertEquals(
        mismatchedPeer.left.map(_.reason),
        Left("authenticatedPeerMismatch"),
      )
      assertEquals(authorized.map(_.sessionId), Right(openSessionId))

  private def openOutbound(
      harness: Harness,
  ): IO[DirectionalSessionId] =
    for
      proposalEither <- harness.runtime.startOutbound(remotePeer, subscription)
      proposal <- IO.fromEither(
        proposalEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      ack <- IO.fromEither(
        SessionNegotiation
          .acknowledge(
            proposal = proposal,
            heartbeatInterval = Duration.ofSeconds(10),
            livenessTimeout = Duration.ofSeconds(30),
            maxControlRetryInterval = Duration.ofSeconds(30),
          )
          .leftMap(rejection => new IllegalStateException(rejection.reason)),
      )
      ackResult <- harness.runtime.applyHandshakeAck(ack)
      _ <- IO.fromEither(
        ackResult.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
    yield proposal.sessionId

  private def startOutboundOpening(
      harness: Harness,
  ): IO[DirectionalSessionId] =
    harness.runtime
      .startOutbound(remotePeer, subscription)
      .flatMap(result =>
        IO.fromEither(
          result.leftMap(rejection =>
            new IllegalStateException(rejection.reason),
          ),
        ),
      )
      .map(_.sessionId)

  private def controlBatch(
      idempotencyKey: String,
      ops: Vector[ControlOp],
  ): ControlBatch =
    ControlBatch.create(idempotencyKey, ops).toOption.get

  private final case class Harness(
      runtime: TxGossipRuntime[IO, TestTx],
      source: InMemoryTxArtifactSource[IO, TestTx],
      clock: TestClock,
  )

  private object Harness:
    def create(
        localNodeId: String,
        instant: Instant,
        policy: TxRuntimePolicy = TxRuntimePolicy(),
    ): IO[Harness] =
      for
        topology <- IO.fromEither(
          StaticPeerTopology
            .parse(
              localNodeIdentity = localNodeId,
              knownPeers = List("node-b"),
              directNeighbors = List("node-b"),
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
        runtime = TxGossipRuntime.withPolicy[IO, TestTx](
          peerAuthenticator = authenticator,
          clock = clock,
          source = source,
          sink = sink,
          topicContracts =
            GossipTopicContractRegistry.single(TxTopic.contract[TestTx]),
          stateStore = stateStore,
          policy = policy,
        ),
        source = source,
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

  private final case class BlockingSource(
      delegate: InMemoryTxArtifactSource[IO, TestTx],
      started: Deferred[IO, Unit],
      release: Deferred[IO, Unit],
  ) extends GossipArtifactSource[IO, TestTx]:
    override def readAfter(
        chainId: ChainId,
        topic: GossipTopic,
        cursor: Option[CursorToken],
    ): IO[Either[CanonicalRejection, Vector[AvailableGossipEvent[TestTx]]]] =
      started.complete(()).attempt *> release.get *> delegate.readAfter(
        chainId,
        topic,
        cursor,
      )

    override def readByIds(
        chainId: ChainId,
        topic: GossipTopic,
        ids: Vector[StableArtifactId],
    ): IO[Vector[AvailableGossipEvent[TestTx]]] =
      delegate.readByIds(chainId, topic, ids)

  private final case class SequencedBlockingSource(
      delegate: InMemoryTxArtifactSource[IO, TestTx],
      firstStarted: Deferred[IO, Unit],
      firstRelease: Deferred[IO, Unit],
      secondStarted: Deferred[IO, Unit],
      secondRelease: Deferred[IO, Unit],
      callCount: Ref[IO, Int],
  ) extends GossipArtifactSource[IO, TestTx]:
    override def readAfter(
        chainId: ChainId,
        topic: GossipTopic,
        cursor: Option[CursorToken],
    ): IO[Either[CanonicalRejection, Vector[AvailableGossipEvent[TestTx]]]] =
      callCount
        .modify(count => (count + 1, count))
        .flatMap:
          case 0 =>
            firstStarted.complete(()).attempt *> firstRelease.get *> delegate
              .readAfter(chainId, topic, cursor)
          case _ =>
            secondStarted.complete(()).attempt *> secondRelease.get *> delegate
              .readAfter(chainId, topic, cursor)

    override def readByIds(
        chainId: ChainId,
        topic: GossipTopic,
        ids: Vector[StableArtifactId],
    ): IO[Vector[AvailableGossipEvent[TestTx]]] =
      delegate.readByIds(chainId, topic, ids)

  private final case class TestTx(body: String)

  private given ByteEncoder[TestTx] =
    ByteEncoder[Utf8].contramap(tx => Utf8(tx.body))
  private given Hash[TestTx]       = Hash.build
  private given TxIdentity[TestTx] = TxIdentity.fromHash[TestTx]
