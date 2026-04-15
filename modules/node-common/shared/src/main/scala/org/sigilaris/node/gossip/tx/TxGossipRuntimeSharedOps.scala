package org.sigilaris.node.gossip.tx

import java.time.Instant

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.CanonicalRejection.*

private[tx] trait TxGossipRuntimeSharedOps[F[_]: Sync, A]:
  protected def clock: GossipClock[F]
  protected def source: GossipArtifactSource[F, A]
  protected def sink: GossipArtifactSink[F, A]
  protected def topicContracts: GossipTopicContractRegistry[A]
  protected def stateStore: TxGossipStateStore[F]
  protected def policy: TxRuntimePolicy
  protected def cascadeStrategy: TxCascadeStrategy[A]

  protected final def expireState(
      state: TxGossipRuntimeState,
      now: Instant,
  ): TxGossipRuntimeState =
    val updatedEngine = state.engine.expireTimedOutSessions(now)
    val liveOutboundSessions = state.outboundSessions.filter:
      case (sessionId, _) =>
        updatedEngine
          .sessionById(sessionId)
          .exists(_.status === DirectionalSessionStatus.Open)
    state.copy(
      engine = updatedEngine,
      outboundSessions = liveOutboundSessions,
    )

  protected final def snapshotAt(
      now: Instant,
  ): F[TxGossipRuntimeState] =
    stateStore.modify: state =>
      val updated = expireState(state, now)
      updated -> updated

  protected final def touchSessionActivity(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
      now: Instant,
  ): Either[CanonicalRejection.HandshakeRejected, TxGossipRuntimeState] =
    state.engine
      .touchSessionActivity(sessionId, now)
      .map(updatedEngine => state.copy(engine = updatedEngine))

  protected final def rejectPreOpenTrafficIfNeeded(
      snapshot: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
      kind: PreOpenTrafficKind,
  ): F[Option[CanonicalRejection.HandshakeRejected]] =
    snapshot.engine.sessionById(sessionId) match
      case Some(session)
          if session.direction === SessionDirection.Outbound &&
            session.status === DirectionalSessionStatus.Opening =>
        stateStore
          .modify: state =>
            state.engine
              .rejectPreOpenTraffic(sessionId, kind)
              .fold(
                error => state -> error.asLeft[HandshakeRejected],
                { case (updatedEngine, rejection) =>
                  state.copy(
                    engine = updatedEngine,
                    outboundSessions = state.outboundSessions - sessionId,
                  ) -> rejection.asRight[HandshakeRejected]
                },
              )
          .map:
            case Right(rejection) =>
              Some(rejection)
            case Left(error) if error.reason === "sessionNotOpening" =>
              None
            case Left(error) =>
              Some(error)
      case _ =>
        none[CanonicalRejection.HandshakeRejected].pure[F]

  protected final def openOutboundProducerSession(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
  ): Either[
    CanonicalRejection.ControlBatchRejected,
    (DirectionalSession, TxProducerSessionState),
  ] =
    for
      session <- state.engine
        .sessionById(sessionId)
        .toRight(controlRejected("unknownSession", sessionId.value))
      _ <- Either.cond(
        session.direction === SessionDirection.Outbound,
        (),
        controlRejected("sessionNotProducer", sessionId.value),
      )
      _ <- Either.cond(
        session.status === DirectionalSessionStatus.Open,
        (),
        controlRejected("sessionNotOpen", sessionId.value),
      )
      sessionState <- state.outboundSessions
        .get(sessionId)
        .toRight:
          controlRejected("unknownSessionState", sessionId.value)
    yield session -> sessionState

  protected final def openOutboundEventSession(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
  ): Either[
    CanonicalRejection.HandshakeRejected,
    (DirectionalSession, TxProducerSessionState),
  ] =
    openOutboundProducerSession(state, sessionId).leftMap: rejection =>
      CanonicalRejection.HandshakeRejected(
        reason = rejection.reason,
        detail = rejection.detail,
      )

  protected final def openInboundConsumerSession(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
  ): Either[CanonicalRejection.HandshakeRejected, DirectionalSession] =
    for
      session <- state.engine
        .sessionById(sessionId)
        .toRight(handshakeRejected("unknownSession", sessionId.value))
      _ <- Either.cond(
        session.direction === SessionDirection.Inbound,
        (),
        handshakeRejected("sessionNotConsumer", sessionId.value),
      )
      _ <- Either.cond(
        session.status === DirectionalSessionStatus.Open,
        (),
        handshakeRejected("sessionNotOpen", sessionId.value),
      )
    yield session

  protected final def appendUnique(
      existing: Vector[StableArtifactId],
      additions: Vector[StableArtifactId],
  ): Vector[StableArtifactId] =
    additions.foldLeft(existing): (acc, id) =>
      if acc.contains(id) then acc else acc :+ id

  protected final def selectArtifacts(
      candidates: Vector[AvailableGossipEvent[A]],
      selectedEvents: Vector[GossipEvent[A]],
  ): Vector[AvailableGossipEvent[A]] =
    val byCursor =
      candidates.iterator
        .map(candidate => candidate.event.cursor -> candidate)
        .toMap
    selectedEvents.flatMap(event => byCursor.get(event.cursor))

  protected final def positiveIntConfig(
      key: String,
      value: Long,
  ): Either[CanonicalRejection.ControlBatchRejected, Int] =
    Either.cond(
      value > 0L && value <= Int.MaxValue.toLong,
      value.toInt,
      controlRejected("invalidConfigValue", ss"${key}=${value.toString}"),
    )

  protected final def controlRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.ControlBatchRejected =
    CanonicalRejection.ControlBatchRejected(
      reason = reason,
      detail = Some(detail),
    )

  protected final def handshakeRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.HandshakeRejected =
    CanonicalRejection.HandshakeRejected(
      reason = reason,
      detail = Some(detail),
    )

  protected final def mergePolledSessionState(
      current: TxProducerSessionState,
      polledFrom: TxProducerSessionState,
      updated: TxProducerSessionState,
  ): TxProducerSessionState =
    val mergedStreamCursor =
      updated.streamCursor.values.foldLeft(current.streamCursor.values):
        (acc, entry) =>
          val (chainTopic, token) = entry
          val previousToken       = polledFrom.streamCursor.tokenFor(chainTopic)
          val currentToken        = current.streamCursor.tokenFor(chainTopic)
          if currentToken === previousToken then acc.updated(chainTopic, token)
          else acc

    val consumedReplayKeys =
      polledFrom.pendingReplay.keySet.diff(updated.pendingReplay.keySet)
    val mergedPendingReplay =
      consumedReplayKeys.foldLeft(current.pendingReplay): (acc, key) =>
        if current.pendingReplay.get(key) === polledFrom.pendingReplay.get(key)
        then acc - key
        else acc

    val mergedPendingRequests =
      polledFrom.pendingRequestByIds.keySet.foldLeft(
        current.pendingRequestByIds,
      ): (acc, chainId) =>
        val before =
          polledFrom.pendingRequestByIds.getOrElse(
            chainId,
            Vector.empty[StableArtifactId],
          )
        val after = updated.pendingRequestByIds.getOrElse(
          chainId,
          Vector.empty[StableArtifactId],
        )
        val servedIds = before.filterNot(after.contains).toSet
        if servedIds.isEmpty then acc
        else
          acc.updatedWith(chainId):
            case None => None
            case Some(existing) =>
              val remaining = existing.filterNot(servedIds.contains)
              remaining.some.filter(_.nonEmpty)

    val mergedPendingScopedRequests =
      polledFrom.pendingRequestScopeIds.keySet.foldLeft(
        current.pendingRequestScopeIds,
      ): (acc, scope) =>
        val before =
          polledFrom.pendingRequestScopeIds.getOrElse(
            scope,
            Vector.empty[StableArtifactId],
          )
        val after =
          updated.pendingRequestScopeIds.getOrElse(
            scope,
            Vector.empty[StableArtifactId],
          )
        val servedIds = before.filterNot(after.contains).toSet
        if servedIds.isEmpty then acc
        else
          acc.updatedWith(scope):
            case None => None
            case Some(existing) =>
              val remaining = existing.filterNot(servedIds.contains)
              remaining.some.filter(_.nonEmpty)

    current.copy(
      streamCursor = CompositeCursor(mergedStreamCursor),
      pendingReplay = mergedPendingReplay,
      pendingRequestByIds = mergedPendingRequests,
      pendingRequestScopeIds = mergedPendingScopedRequests,
    )
