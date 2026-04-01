package org.sigilaris.node.jvm.runtime.gossip.tx

import java.time.{Duration, Instant}

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection.*

enum ControlBatchOutcome:
  case Applied, Deduplicated

final case class TxReceiveEventsResult[A](
    applied: Vector[GossipEvent[A]],
    duplicates: Vector[GossipEvent[A]],
    lastCursor: Option[CursorToken],
)

trait TxCascadeStrategy[A]:
  def selectLiveEvents(
      filter: Option[GossipFilter.TxBloomFilter],
      exactKnownIds: Set[StableArtifactId],
      candidates: Vector[GossipEvent[A]],
  ): Either[CanonicalRejection.BackfillUnavailable, Vector[GossipEvent[A]]]

object TxCascadeStrategy:
  def exactKnownOrBackfillUnavailable[A]: TxCascadeStrategy[A] =
    new TxCascadeStrategy[A]:
      override def selectLiveEvents(
          filter: Option[GossipFilter.TxBloomFilter],
          exactKnownIds: Set[StableArtifactId],
          candidates: Vector[GossipEvent[A]],
      ): Either[CanonicalRejection.BackfillUnavailable, Vector[GossipEvent[A]]] =
        filter match
          case None =>
            Right(candidates.filterNot(event => exactKnownIds.contains(event.id)))
          case Some(bloomFilter) =>
            val unresolved =
              candidates.filter: event =>
                TxBloomFilterSupport.mightContain(bloomFilter, event.id) &&
                  !exactKnownIds.contains(event.id)
            Either.cond(
              unresolved.isEmpty,
              candidates.filterNot: event =>
                exactKnownIds.contains(event.id) || TxBloomFilterSupport.mightContain(bloomFilter, event.id),
              CanonicalRejection.BackfillUnavailable(
                reason = "txBackfillUnavailable",
                detail = Some(unresolved.map(_.id.toHexLower).mkString(",")),
              ),
            )

final class TxGossipRuntime[F[_]: Sync, A](
    peerAuthenticator: PeerAuthenticator[F],
    clock: GossipClock[F],
    source: GossipArtifactSource[F, A],
    sink: GossipArtifactSink[F, A],
    topicContracts: GossipTopicContractRegistry[A],
    stateStore: TxGossipStateStore[F],
    policy: TxRuntimePolicy = TxRuntimePolicy(),
    cascadeStrategy: TxCascadeStrategy[A] = TxCascadeStrategy.exactKnownOrBackfillUnavailable[A],
):
  def startOutbound(
      peer: PeerIdentity,
      subscriptions: SessionSubscription,
      heartbeatInterval: Option[Duration] = None,
      livenessTimeout: Option[Duration] = None,
      maxControlRetryInterval: Option[Duration] = None,
  ): F[Either[HandshakeRejected, SessionOpenProposal]] =
    peerAuthenticator.authenticate(peer).flatMap:
      case Left(rejection) =>
        rejection.asLeft[SessionOpenProposal].pure[F]
      case Right(_) =>
        clock.now.flatMap: now =>
          stateStore.modify: state =>
            state.engine
              .startOutbound(
                peer = peer,
                subscriptions = subscriptions,
                now = now,
                heartbeatInterval = heartbeatInterval,
                livenessTimeout = livenessTimeout,
                maxControlRetryInterval = maxControlRetryInterval,
              )
              .fold(
                error => state -> Left(error),
                (updatedEngine, proposal) =>
                  state.copy(engine = updatedEngine) -> Right(proposal),
              )

  def handleInboundProposal(
      proposal: SessionOpenProposal,
      heartbeatInterval: Option[Duration] = None,
      livenessTimeout: Option[Duration] = None,
      maxControlRetryInterval: Option[Duration] = None,
  ): F[InboundHandshakeResult] =
    peerAuthenticator.authenticate(proposal.initiator).flatMap:
      case Left(rejection) =>
        InboundHandshakeResult.Rejected(rejection).pure[F]
      case Right(_) =>
        clock.now.flatMap: now =>
          stateStore.modify: state =>
            val (updatedEngine, result) =
              state.engine.handleInboundProposal(
                proposal = proposal,
                now = now,
                heartbeatInterval = heartbeatInterval,
                livenessTimeout = livenessTimeout,
                maxControlRetryInterval = maxControlRetryInterval,
              )
            val updatedSessions = result match
              case InboundHandshakeResult.Accepted(_, supersededSessionId) =>
                supersededSessionId.foldLeft(state.outboundSessions):
                  case (sessions, sessionId) => sessions - sessionId
              case InboundHandshakeResult.Rejected(_) =>
                state.outboundSessions
            state.copy(
              engine = updatedEngine,
              outboundSessions = updatedSessions,
            ) -> result

  def applyHandshakeAck(
      ack: SessionOpenAck,
  ): F[Either[HandshakeRejected, Unit]] =
    clock.now.flatMap: now =>
      stateStore.modify: state =>
        state.engine.applyHandshakeAck(ack, now).fold(
          error => state -> Left(error),
          updatedEngine =>
            updatedEngine.sessionById(ack.sessionId) match
              case None =>
                state -> Left(handshakeRejected("unknownSession", ack.sessionId.value))
              case Some(session) =>
                val runtimeSession = TxProducerSessionState(
                  sessionId = session.sessionId,
                  peer = session.peer,
                  peerCorrelationId = session.peerCorrelationId,
                  subscriptions = session.proposal.subscriptions,
                  negotiated = ack.negotiated,
                  batchingConfig = policy.defaultBatchingConfig,
                )
                state.copy(
                  engine = updatedEngine,
                  outboundSessions = state.outboundSessions.updated(session.sessionId, runtimeSession),
                ) -> Right(())
        )

  def closeSession(
      sessionId: DirectionalSessionId,
  ): F[Either[HandshakeRejected, Unit]] =
    stateStore.modify: state =>
      state.engine.closeSession(sessionId).fold(
        error => state -> Left(error),
        updatedEngine =>
          state.copy(
            engine = updatedEngine,
            outboundSessions = state.outboundSessions - sessionId,
          ) -> Right(())
      )

  def markSessionDead(
      sessionId: DirectionalSessionId,
  ): F[Either[HandshakeRejected, Unit]] =
    stateStore.modify: state =>
      state.engine.markSessionDead(sessionId).fold(
        error => state -> Left(error),
        updatedEngine =>
          state.copy(
            engine = updatedEngine,
            outboundSessions = state.outboundSessions - sessionId,
          ) -> Right(())
      )

  def receiveControlBatch(
      sessionId: DirectionalSessionId,
      batch: ControlBatch,
  ): F[Either[CanonicalRejection.ControlBatchRejected, ControlBatchOutcome]] =
    for
      now <- clock.now
      snapshot <- stateStore.get
      validation <- prevalidateControlBatch(snapshot, sessionId, batch)
      result <- validation match
        case Left(rejection) =>
          rejection.asLeft[ControlBatchOutcome].pure[F]
        case Right(()) =>
          stateStore.modify: state =>
            openOutboundProducerSession(state, sessionId).fold(
              rejection => state -> Left(rejection),
              (_, sessionState) =>
                applyControlBatch(now, batch, sessionState).fold(
                  rejection => state -> Left(rejection),
                  {
                    case (updatedSession, outcome) =>
                      state.copy(
                        outboundSessions = state.outboundSessions.updated(sessionId, updatedSession)
                      ) -> Right(outcome)
                  },
                ),
            )
    yield result

  def pollEvents(
      sessionId: DirectionalSessionId,
  ): F[Either[CanonicalRejection, Vector[EventStreamMessage[A]]]] =
    for
      now <- clock.now
      snapshot <- stateStore.get
      result <- openOutboundEventSession(snapshot, sessionId).fold(
        rejection => rejection.asLeft[Vector[EventStreamMessage[A]]].pure[F],
        (_, sessionState) =>
          pollOpenSession(now, sessionState).flatMap:
            case Left(rejection) =>
              rejection.asLeft[Vector[EventStreamMessage[A]]].pure[F]
            case Right((updatedSession, emittedEvents)) =>
              stateStore.modify: state =>
                state.outboundSessions.get(sessionId) match
                  case None =>
                    state -> Left(handshakeRejected("unknownSession", sessionId.value))
                  case Some(current) =>
                    val mergedSessions =
                      state.outboundSessions.updated(
                        sessionId,
                        mergePolledSessionState(current, sessionState, updatedSession)
                      )
                    state.copy(outboundSessions = mergedSessions) ->
                      Right(emittedEvents.map(EventStreamMessage.Event(_)))
      )
    yield result

  def receiveEvents(
      sessionId: DirectionalSessionId,
      messages: Vector[EventStreamMessage[A]],
  ): F[Either[CanonicalRejection, TxReceiveEventsResult[A]]] =
    stateStore.get.flatMap: state =>
      openInboundConsumerSession(state, sessionId).fold(
        rejection => rejection.asLeft[TxReceiveEventsResult[A]].pure[F],
        _ =>
          messages.foldLeftM(
            TxReceiveEventsResult[A](
              applied = Vector.empty,
              duplicates = Vector.empty,
              lastCursor = None,
            ).asRight[CanonicalRejection]
          ):
            case (left @ Left(_), _) =>
              left.pure[F]
            case (Right(result), EventStreamMessage.KeepAlive(_, _)) =>
              Right(result).pure[F]
            case (_, EventStreamMessage.Rejection(rejection)) =>
              rejection.asLeft[TxReceiveEventsResult[A]].pure[F]
            case (Right(result), EventStreamMessage.Event(event)) =>
              validateAndApplyEvent(event).map:
                case Left(rejection) =>
                  Left(rejection)
                case Right(applyResult) =>
                  if applyResult.duplicate then
                    Right(
                      result.copy(
                        duplicates = result.duplicates :+ event,
                        lastCursor = Some(event.cursor),
                      )
                    )
                  else
                    Right(
                      result.copy(
                        applied = result.applied :+ event,
                        lastCursor = Some(event.cursor),
                      )
                    )
      )

  def eventKeepAlive(
      sessionId: DirectionalSessionId,
  ): F[Either[CanonicalRejection.HandshakeRejected, EventStreamMessage[A]]] =
    for
      now <- clock.now
      state <- stateStore.get
    yield openOutboundEventSession(state, sessionId).fold(
      _.asLeft[EventStreamMessage[A]],
      _ => Right(EventStreamMessage.KeepAlive(sessionId, now)),
    )

  def controlKeepAlive(
      sessionId: DirectionalSessionId,
  ): F[Either[CanonicalRejection.ControlBatchRejected, ControlChannelMessage]] =
    for
      now <- clock.now
      state <- stateStore.get
    yield openOutboundProducerSession(state, sessionId).map(_ => ControlChannelMessage.Ack(sessionId, now))

  def snapshotState: F[TxGossipRuntimeState] =
    stateStore.get

  def relationshipWith(peer: PeerIdentity): F[Option[PeerRelationship]] =
    stateStore.get.map(_.engine.relationshipWith(peer))

  private def validateAndApplyEvent(
      event: GossipEvent[A],
  ): F[Either[CanonicalRejection, ArtifactApplyResult]] =
    topicContracts.contractFor(event.topic) match
      case Left(rejection) =>
        rejection.asLeft[ArtifactApplyResult].pure[F]
      case Right(contract) =>
        contract.validateArtifact(event) match
          case Left(rejection) =>
            rejection.asLeft[ArtifactApplyResult].pure[F]
          case Right(_) =>
            sink.applyEvent(event).map(_.leftMap(identity[CanonicalRejection]))

  private def prevalidateControlBatch(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
      batch: ControlBatch,
  ): F[Either[CanonicalRejection.ControlBatchRejected, Unit]] =
    openOutboundProducerSession(state, sessionId).fold(
      rejection => rejection.asLeft[Unit].pure[F],
      (_, _) =>
        batch.ops.foldLeftM(().asRight[CanonicalRejection.ControlBatchRejected]):
          case (Left(rejection), _) =>
            rejection.asLeft[Unit].pure[F]
          case (Right(_), ControlOp.RequestByIdTx(chainId, ids)) =>
            val distinctIds = ids.distinct
            if distinctIds.size > policy.maxTxRequestIds then
              controlRejected(
                "requestByIdTooLarge",
                s"max=${policy.maxTxRequestIds} actual=${distinctIds.size}",
              ).asLeft[Unit].pure[F]
            else
              source.readByIds(chainId, GossipTopic.tx, distinctIds).map: events =>
                val foundIds = events.map(_.id).toSet
                val missing = distinctIds.filterNot(foundIds.contains)
                Either.cond(
                  missing.isEmpty,
                  (),
                  controlRejected(
                    "unknownRequestedArtifact",
                    missing.map(_.toHexLower).mkString(","),
                  ),
                )
          case (Right(_), _) =>
            Right(()).pure[F]
    )

  private def applyControlBatch(
      now: Instant,
      batch: ControlBatch,
      sessionState: TxProducerSessionState,
  ): Either[CanonicalRejection.ControlBatchRejected, (TxProducerSessionState, ControlBatchOutcome)] =
    val prunedKeys = pruneIdempotencyKeys(now, sessionState)
    val sessionWithPrunedKeys = sessionState.copy(idempotencyKeys = prunedKeys)
    if prunedKeys.contains(batch.idempotencyKey) then
      Right(sessionWithPrunedKeys -> ControlBatchOutcome.Deduplicated)
    else
      batch.ops
        .foldLeft[Either[CanonicalRejection.ControlBatchRejected, TxProducerSessionState]](
          Right(sessionWithPrunedKeys)
        ):
          case (Right(current), op) =>
            applyControlOp(current, op)
          case (left, _) =>
            left
        .map: updated =>
          updated.copy(
            idempotencyKeys = updated.idempotencyKeys.updated(batch.idempotencyKey, now)
          ) -> ControlBatchOutcome.Applied

  private def applyControlOp(
      sessionState: TxProducerSessionState,
      op: ControlOp,
  ): Either[CanonicalRejection.ControlBatchRejected, TxProducerSessionState] =
    op match
      case ControlOp.SetFilter(chainId, topic, filter) =>
        validateTxSubscription(sessionState, chainId, topic).flatMap: _ =>
          filter match
            case bloomFilter: GossipFilter.TxBloomFilter =>
              TxBloomFilterSupport.validate(bloomFilter, policy).map: validated =>
                sessionState.copy(filters = sessionState.filters.updated(chainId, validated))

      case ControlOp.SetKnownTx(chainId, ids) =>
        validateTxSubscription(sessionState, chainId, GossipTopic.tx).flatMap: _ =>
          val distinctNewIds = ids.toSet
          val mergedIds = sessionState.exactKnownIds.getOrElse(chainId, Set.empty) ++ distinctNewIds
          Either.cond(
            distinctNewIds.size <= policy.maxTxSetKnownEntries && mergedIds.size <= policy.maxTxSetKnownEntries,
            sessionState.copy(
              exactKnownIds = sessionState.exactKnownIds.updated(
                chainId,
                mergedIds,
              )
            ),
            controlRejected(
              "setKnownTooLarge",
              s"max=${policy.maxTxSetKnownEntries} actual=${mergedIds.size}",
            ),
          )

      case ControlOp.SetCursor(cursor) =>
        val unsupportedKeys =
          cursor.values.keySet.filterNot(sessionState.subscriptions.contains)
        Either.cond(
          unsupportedKeys.isEmpty,
          sessionState.copy(
            durableCursor = CompositeCursor(sessionState.durableCursor.values ++ cursor.values)
          ),
          controlRejected(
            "cursorOutOfSubscription",
            unsupportedKeys.map(key => s"${key.chainId.value}:${key.topic.value}").mkString(","),
          ),
        )

      case ControlOp.Nack(chainId, topic, cursor) =>
        validateTxSubscription(sessionState, chainId, topic).map: _ =>
          sessionState.copy(
            pendingReplay = sessionState.pendingReplay.updated(ChainTopic(chainId, topic), cursor)
          )

      case ControlOp.RequestByIdTx(chainId, ids) =>
        validateTxSubscription(sessionState, chainId, GossipTopic.tx).flatMap: _ =>
          val distinctIds = ids.distinct
          Either.cond(
            distinctIds.size <= policy.maxTxRequestIds,
            sessionState.copy(
              pendingRequestByIds = sessionState.pendingRequestByIds.updated(
                chainId,
                appendUnique(sessionState.pendingRequestByIds.getOrElse(chainId, Vector.empty), distinctIds),
              )
            ),
            controlRejected(
              "requestByIdTooLarge",
              s"max=${policy.maxTxRequestIds} actual=${distinctIds.size}",
            ),
          )

      case ControlOp.Config(values) =>
        values.foldLeft[Either[CanonicalRejection.ControlBatchRejected, TxBatchingConfig]](
          Right(sessionState.batchingConfig)
        ):
          case (Right(config), (SessionConfigKey.TxMaxBatchItems, value)) =>
            positiveIntConfig("tx.maxBatchItems", value).map: parsed =>
              config.copy(maxBatchItems = parsed)
          case (Right(config), (SessionConfigKey.TxFlushIntervalMs, value)) =>
            positiveIntConfig("tx.flushIntervalMs", value).map: parsed =>
              config.copy(flushInterval = Duration.ofMillis(parsed.toLong))
          // SessionConfigKey is a closed enum in the shipped baseline.
          case (left @ Left(_), _) =>
            left
        .map: batchingConfig =>
          sessionState.copy(batchingConfig = batchingConfig)

  private def pollOpenSession(
      now: Instant,
      sessionState: TxProducerSessionState,
  ): F[Either[CanonicalRejection, (TxProducerSessionState, Vector[GossipEvent[A]])]] =
    sessionState.subscriptions.values.toVector
      .filter(_.topic == GossipTopic.tx)
      .sortBy(chainTopic => chainTopic.chainId.value)
      .foldLeftM(
        (
          sessionState,
          Vector.empty[GossipEvent[A]],
        ).asRight[CanonicalRejection]
      ):
        case (Left(rejection), _) =>
          Left(rejection).pure[F]
        case (Right((currentState, emitted)), chainTopic) =>
          pollChain(now, currentState, chainTopic).map:
            case Left(rejection) =>
              Left(rejection)
            case Right((updatedState, chainEvents)) =>
              Right(updatedState -> (emitted ++ chainEvents))

  private def pollChain(
      now: Instant,
      sessionState: TxProducerSessionState,
      chainTopic: ChainTopic,
  ): F[Either[CanonicalRejection, (TxProducerSessionState, Vector[GossipEvent[A]])]] =
    val requestedIds = sessionState.pendingRequestByIds.getOrElse(chainTopic.chainId, Vector.empty)
    val cursorOverride = sessionState.pendingReplay.get(chainTopic)
    val startCursor =
      cursorOverride.getOrElse(
        sessionState.streamCursor
          .tokenFor(chainTopic)
          .orElse(sessionState.durableCursor.tokenFor(chainTopic))
      )

    for
      explicitEvents <-
        if requestedIds.isEmpty then Vector.empty[GossipEvent[A]].pure[F]
        else source.readByIds(chainTopic.chainId, chainTopic.topic, requestedIds)
      afterCursor <- source.readAfter(chainTopic.chainId, chainTopic.topic, startCursor)
    yield afterCursor.flatMap: candidates =>
      cascadeStrategy
        .selectLiveEvents(
          filter = sessionState.filters.get(chainTopic.chainId),
          exactKnownIds = sessionState.exactKnownIds.getOrElse(chainTopic.chainId, Set.empty),
          candidates = candidates.filterNot(event => explicitEvents.exists(_.id == event.id)),
        )
        .map: liveCandidates =>
          val explicitBatch = explicitEvents.take(sessionState.batchingConfig.maxBatchItems)
          val remainingCapacity = (sessionState.batchingConfig.maxBatchItems - explicitBatch.size).max(0)
          val forceFlush = cursorOverride.nonEmpty
          val liveBatch =
            if remainingCapacity <= 0 then Vector.empty
            else batchLiveEvents(now, liveCandidates, sessionState.batchingConfig, forceFlush, remainingCapacity)
          val emitted = explicitBatch ++ liveBatch
          val updatedStreamCursor =
            if liveBatch.isEmpty then sessionState.streamCursor
            else
              CompositeCursor(
                sessionState.streamCursor.values.updated(chainTopic, liveBatch.last.cursor)
              )
          val servedExplicitIds = explicitBatch.map(_.id).toSet
          val updatedState = sessionState.copy(
            streamCursor = updatedStreamCursor,
            pendingReplay = sessionState.pendingReplay - chainTopic,
            pendingRequestByIds =
              if servedExplicitIds.isEmpty then sessionState.pendingRequestByIds
              else sessionState.pendingRequestByIds.updatedWith(chainTopic.chainId):
                case None => None
                case Some(existing) =>
                  val remaining = existing.filterNot(servedExplicitIds.contains)
                  remaining.some.filter(_.nonEmpty)
          )
          updatedState -> emitted

  private def batchLiveEvents(
      now: Instant,
      candidates: Vector[GossipEvent[A]],
      batchingConfig: TxBatchingConfig,
      forceFlush: Boolean,
      limit: Int,
  ): Vector[GossipEvent[A]] =
    val threshold = batchingConfig.maxBatchItems.min(limit)
    if candidates.isEmpty then Vector.empty
    else if forceFlush || candidates.size >= threshold then
      candidates.take(threshold)
    else if !now.isBefore(candidates.head.ts.plus(batchingConfig.flushInterval)) then
      candidates.take(threshold)
    else Vector.empty

  private def pruneIdempotencyKeys(
      now: Instant,
      sessionState: TxProducerSessionState,
  ): Map[ControlIdempotencyKey, Instant] =
    val cutoff = now.minus(policy.controlRetryHorizon(sessionState.negotiated))
    sessionState.idempotencyKeys.filterNot((_, appliedAt) => appliedAt.isBefore(cutoff))

  private def validateTxSubscription(
      sessionState: TxProducerSessionState,
      chainId: ChainId,
      topic: GossipTopic,
  ): Either[CanonicalRejection.ControlBatchRejected, Unit] =
    Either.cond(
      sessionState.subscriptions.contains(chainId, topic) && topic == GossipTopic.tx,
      (),
      controlRejected(
        "topicOutOfSubscription",
        s"${chainId.value}:${topic.value}",
      ),
    )

  private def openOutboundProducerSession(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
  ): Either[CanonicalRejection.ControlBatchRejected, (DirectionalSession, TxProducerSessionState)] =
    for
      session <- state.engine.sessionById(sessionId).toRight(controlRejected("unknownSession", sessionId.value))
      _ <- Either.cond(
        session.direction == SessionDirection.Outbound,
        (),
        controlRejected("sessionNotProducer", sessionId.value),
      )
      _ <- Either.cond(
        session.status == DirectionalSessionStatus.Open,
        (),
        controlRejected("sessionNotOpen", sessionId.value),
      )
      sessionState <- state.outboundSessions.get(sessionId).toRight(
        controlRejected("unknownSessionState", sessionId.value)
      )
    yield session -> sessionState

  private def openOutboundEventSession(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
  ): Either[CanonicalRejection.HandshakeRejected, (DirectionalSession, TxProducerSessionState)] =
    openOutboundProducerSession(state, sessionId).leftMap: rejection =>
      CanonicalRejection.HandshakeRejected(
        reason = rejection.reason,
        detail = rejection.detail,
      )

  private def openInboundConsumerSession(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
  ): Either[CanonicalRejection.HandshakeRejected, DirectionalSession] =
    for
      session <- state.engine.sessionById(sessionId).toRight(handshakeRejected("unknownSession", sessionId.value))
      _ <- Either.cond(
        session.direction == SessionDirection.Inbound,
        (),
        handshakeRejected("sessionNotConsumer", sessionId.value),
      )
      _ <- Either.cond(
        session.status == DirectionalSessionStatus.Open,
        (),
        handshakeRejected("sessionNotOpen", sessionId.value),
      )
    yield session

  private def appendUnique(
      existing: Vector[StableArtifactId],
      additions: Vector[StableArtifactId],
  ): Vector[StableArtifactId] =
    additions.foldLeft(existing): (acc, id) =>
      if acc.contains(id) then acc else acc :+ id

  private def positiveIntConfig(
      key: String,
      value: Long,
  ): Either[CanonicalRejection.ControlBatchRejected, Int] =
    Either.cond(
      value > 0L && value <= Int.MaxValue.toLong,
      value.toInt,
      controlRejected("invalidConfigValue", s"$key=$value"),
    )

  private def controlRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.ControlBatchRejected =
    CanonicalRejection.ControlBatchRejected(
      reason = reason,
      detail = Some(detail),
    )

  private def handshakeRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.HandshakeRejected =
    CanonicalRejection.HandshakeRejected(
      reason = reason,
      detail = Some(detail),
    )

  private def mergePolledSessionState(
      current: TxProducerSessionState,
      polledFrom: TxProducerSessionState,
      updated: TxProducerSessionState,
  ): TxProducerSessionState =
    val mergedStreamCursor =
      updated.streamCursor.values.foldLeft(current.streamCursor.values): (acc, entry) =>
        val (chainTopic, token) = entry
        val previousToken = polledFrom.streamCursor.tokenFor(chainTopic)
        val currentToken = current.streamCursor.tokenFor(chainTopic)
        if currentToken == previousToken then acc.updated(chainTopic, token)
        else acc

    val consumedReplayKeys = polledFrom.pendingReplay.keySet.diff(updated.pendingReplay.keySet)
    val mergedPendingReplay =
      consumedReplayKeys.foldLeft(current.pendingReplay): (acc, key) =>
        if current.pendingReplay.get(key) == polledFrom.pendingReplay.get(key) then acc - key
        else acc

    val mergedPendingRequests =
      polledFrom.pendingRequestByIds.keySet.foldLeft(current.pendingRequestByIds): (acc, chainId) =>
        val before = polledFrom.pendingRequestByIds.getOrElse(chainId, Vector.empty)
        val after = updated.pendingRequestByIds.getOrElse(chainId, Vector.empty)
        val servedIds = before.filterNot(after.contains).toSet
        if servedIds.isEmpty then acc
        else
          acc.updatedWith(chainId):
            case None => None
            case Some(existing) =>
              val remaining = existing.filterNot(servedIds.contains)
              remaining.some.filter(_.nonEmpty)

    current.copy(
      streamCursor = CompositeCursor(mergedStreamCursor),
      pendingReplay = mergedPendingReplay,
      pendingRequestByIds = mergedPendingRequests,
    )
