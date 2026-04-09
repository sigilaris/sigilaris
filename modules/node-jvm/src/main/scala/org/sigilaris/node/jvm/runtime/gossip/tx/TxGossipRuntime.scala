package org.sigilaris.node.jvm.runtime.gossip.tx

import java.time.{Duration, Instant}

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection.*

/** Outcome of processing a control batch. */
enum ControlBatchOutcome:

  /** The batch was freshly applied. */
  case Applied

  /** The batch was deduplicated via its idempotency key. */
  case Deduplicated

/** Optional overrides for session negotiation parameters.
  *
  * @param heartbeatInterval
  *   optional heartbeat interval override
  * @param livenessTimeout
  *   optional liveness timeout override
  * @param maxControlRetryInterval
  *   optional max control retry interval override
  */
final case class TxSessionNegotiationOverrides(
    heartbeatInterval: Option[Duration],
    livenessTimeout: Option[Duration],
    maxControlRetryInterval: Option[Duration],
)

/** Companion for `TxSessionNegotiationOverrides`. */
object TxSessionNegotiationOverrides:

  /** Default overrides with no values set (uses policy defaults). */
  val default: TxSessionNegotiationOverrides =
    TxSessionNegotiationOverrides(
      heartbeatInterval = None,
      livenessTimeout = None,
      maxControlRetryInterval = None,
    )

/** Result of receiving and applying a batch of inbound events.
  *
  * @tparam A
  *   the artifact payload type
  * @param applied
  *   events that were newly applied
  * @param duplicates
  *   events that were duplicates
  * @param lastCursor
  *   the cursor of the last processed event, if any
  */
final case class TxReceiveEventsResult[A](
    applied: Vector[GossipEvent[A]],
    duplicates: Vector[GossipEvent[A]],
    lastCursor: Option[CursorToken],
)

/** Strategy for selecting which live events to deliver based on peer-declared
  * filters and known ids.
  *
  * @tparam A
  *   the artifact payload type
  */
trait TxCascadeStrategy[A]:

  /** Selects live events from candidates, filtering out known and
    * Bloom-matched artifacts.
    *
    * @param filter
    *   optional Bloom filter from the peer
    * @param exactKnownIds
    *   exact known artifact ids from the peer
    * @param candidates
    *   the candidate events to filter
    * @return
    *   the selected events, or a backfill-unavailable rejection
    */
  def selectLiveEvents(
      filter: Option[GossipFilter.TxBloomFilter],
      exactKnownIds: Set[StableArtifactId],
      candidates: Vector[GossipEvent[A]],
  ): Either[CanonicalRejection.BackfillUnavailable, Vector[GossipEvent[A]]]

/** Companion for `TxCascadeStrategy` providing default implementations. */
object TxCascadeStrategy:

  /** Creates a cascade strategy that requires exact known ids for
    * Bloom-ambiguous events, rejecting with backfill-unavailable otherwise.
    *
    * @tparam A
    *   the artifact payload type
    * @return
    *   the cascade strategy
    */
  def exactKnownOrBackfillUnavailable[A]: TxCascadeStrategy[A] =
    new TxCascadeStrategy[A]:
      override def selectLiveEvents(
          filter: Option[GossipFilter.TxBloomFilter],
          exactKnownIds: Set[StableArtifactId],
          candidates: Vector[GossipEvent[A]],
      ): Either[CanonicalRejection.BackfillUnavailable, Vector[
        GossipEvent[A],
      ]] =
        filter match
          case None =>
            candidates
              .filterNot(event => exactKnownIds.contains(event.id))
              .asRight[CanonicalRejection.BackfillUnavailable]
          case Some(bloomFilter) =>
            val unresolved =
              candidates.filter: event =>
                TxBloomFilterSupport.mightContain(bloomFilter, event.id) &&
                  !exactKnownIds.contains(event.id)
            Either.cond(
              unresolved.isEmpty,
              candidates.filterNot: event =>
                exactKnownIds.contains(event.id) || TxBloomFilterSupport
                  .mightContain(bloomFilter, event.id),
              CanonicalRejection.BackfillUnavailable(
                reason = "txBackfillUnavailable",
                detail = Some(unresolved.map(_.id.toHexLower).mkString(",")),
              ),
            )

/** Effectful runtime coordinating transaction gossip sessions, event
  * production, event consumption, and control batch processing.
  *
  * @tparam F
  *   the effect type
  * @tparam A
  *   the artifact payload type
  */
final class TxGossipRuntime[F[_]: Sync, A](
    peerAuthenticator: PeerAuthenticator[F],
    clock: GossipClock[F],
    source: GossipArtifactSource[F, A],
    sink: GossipArtifactSink[F, A],
    topicContracts: GossipTopicContractRegistry[A],
    stateStore: TxGossipStateStore[F],
    policy: TxRuntimePolicy,
    cascadeStrategy: TxCascadeStrategy[A],
):
  private def authenticatedPeerMismatch(
      expected: PeerIdentity,
      actual: PeerIdentity,
  ): CanonicalRejection.HandshakeRejected =
    handshakeRejected(
      "authenticatedPeerMismatch",
      "expected=" + expected.value + " actual=" + actual.value,
    )

  private def expireState(
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

  private def snapshotAt(
      now: Instant,
  ): F[TxGossipRuntimeState] =
    stateStore.modify: state =>
      val updated = expireState(state, now)
      updated -> updated

  private def touchSessionActivity(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
      now: Instant,
  ): Either[CanonicalRejection.HandshakeRejected, TxGossipRuntimeState] =
    state.engine
      .touchSessionActivity(sessionId, now)
      .map(updatedEngine => state.copy(engine = updatedEngine))

  private def rejectPreOpenTrafficIfNeeded(
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

  /** Initiates an outbound session to a peer with default negotiation
    * overrides.
    *
    * @param peer
    *   the target peer
    * @param subscriptions
    *   the chain-topic pairs to subscribe to
    * @return
    *   the proposal, or a rejection
    */
  def startOutbound(
      peer: PeerIdentity,
      subscriptions: SessionSubscription,
  ): F[Either[HandshakeRejected, SessionOpenProposal]] =
    startOutboundConfigured(
      peer,
      subscriptions,
      TxSessionNegotiationOverrides.default,
    )

  /** Initiates an outbound session to a peer with custom negotiation overrides.
    *
    * @param peer
    *   the target peer
    * @param subscriptions
    *   the chain-topic pairs to subscribe to
    * @param negotiationOverrides
    *   the negotiation parameter overrides
    * @return
    *   the proposal, or a rejection
    */
  def startOutboundConfigured(
      peer: PeerIdentity,
      subscriptions: SessionSubscription,
      negotiationOverrides: TxSessionNegotiationOverrides,
  ): F[Either[HandshakeRejected, SessionOpenProposal]] =
    peerAuthenticator
      .authenticate(peer)
      .flatMap:
        case Left(rejection) =>
          rejection.asLeft[SessionOpenProposal].pure[F]
        case Right(_) =>
          clock.now.flatMap: now =>
            stateStore.modify: state =>
              val currentState = expireState(state, now)
              currentState.engine
                .startOutbound(
                  peer = peer,
                  subscriptions = subscriptions,
                  now = now,
                  heartbeatInterval = negotiationOverrides.heartbeatInterval,
                  livenessTimeout = negotiationOverrides.livenessTimeout,
                  maxControlRetryInterval =
                    negotiationOverrides.maxControlRetryInterval,
                )
                .fold(
                  error => currentState -> error.asLeft[SessionOpenProposal],
                  (updatedEngine, proposal) =>
                    currentState
                      .copy(engine = updatedEngine) ->
                      proposal.asRight[HandshakeRejected],
                )

  /** Processes an inbound session open proposal using the initiator as the
    * authenticated peer.
    *
    * @param proposal
    *   the received proposal
    * @return
    *   the handshake result
    */
  def handleInboundProposal(
      proposal: SessionOpenProposal,
  ): F[InboundHandshakeResult] =
    handleInboundProposalFromPeer(
      proposal = proposal,
      authenticatedPeer = proposal.initiator,
    )

  /** Processes an inbound proposal with an externally authenticated peer
    * identity.
    *
    * @param proposal
    *   the received proposal
    * @param authenticatedPeer
    *   the peer identity verified by transport authentication
    * @return
    *   the handshake result
    */
  def handleInboundProposalFromPeer(
      proposal: SessionOpenProposal,
      authenticatedPeer: PeerIdentity,
  ): F[InboundHandshakeResult] =
    handleInboundProposalConfigured(
      proposal,
      authenticatedPeer,
      TxSessionNegotiationOverrides.default,
    )

  /** Processes an inbound proposal with custom negotiation overrides.
    *
    * @param proposal
    *   the received proposal
    * @param authenticatedPeer
    *   the peer identity verified by transport authentication
    * @param negotiationOverrides
    *   the negotiation parameter overrides
    * @return
    *   the handshake result
    */
  def handleInboundProposalConfigured(
      proposal: SessionOpenProposal,
      authenticatedPeer: PeerIdentity,
      negotiationOverrides: TxSessionNegotiationOverrides,
  ): F[InboundHandshakeResult] =
    peerAuthenticator
      .authenticate(authenticatedPeer)
      .flatMap:
        case Left(rejection) =>
          InboundHandshakeResult.Rejected(rejection).pure[F]
        case Right(boundPeer) if boundPeer =!= proposal.initiator =>
          InboundHandshakeResult
            .Rejected(
              authenticatedPeerMismatch(
                expected = proposal.initiator,
                actual = boundPeer,
              ),
            )
            .pure[F]
        case Right(_) =>
          clock.now.flatMap: now =>
            stateStore.modify: state =>
              val currentState = expireState(state, now)
              val (updatedEngine, result) =
                currentState.engine.handleInboundProposal(
                  proposal = proposal,
                  now = now,
                  heartbeatInterval = negotiationOverrides.heartbeatInterval,
                  livenessTimeout = negotiationOverrides.livenessTimeout,
                  maxControlRetryInterval =
                    negotiationOverrides.maxControlRetryInterval,
                )
              val updatedSessions = result match
                case InboundHandshakeResult.Accepted(_, supersededSessionId) =>
                  supersededSessionId.foldLeft(currentState.outboundSessions):
                    case (sessions, sessionId) => sessions - sessionId
                case InboundHandshakeResult.Rejected(_) =>
                  currentState.outboundSessions
              currentState.copy(
                engine = updatedEngine,
                outboundSessions = updatedSessions,
              ) -> result

  private def sessionOwnedByPeer(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
      authenticatedPeer: PeerIdentity,
  ): Either[CanonicalRejection.HandshakeRejected, DirectionalSession] =
    state.engine.sessionById(sessionId) match
      case None =>
        handshakeRejected("unknownSession", sessionId.value)
          .asLeft[DirectionalSession]
      case Some(session) if session.peer =!= authenticatedPeer =>
        authenticatedPeerMismatch(
          expected = session.peer,
          actual = authenticatedPeer,
        ).asLeft[DirectionalSession]
      case Some(session) =>
        session.asRight[CanonicalRejection.HandshakeRejected]

  /** Applies a received handshake ack to complete an outbound session.
    *
    * @param ack
    *   the received acknowledgement
    * @return
    *   unit on success, or a rejection
    */
  def applyHandshakeAck(
      ack: SessionOpenAck,
  ): F[Either[HandshakeRejected, Unit]] =
    clock.now.flatMap: now =>
      stateStore.modify: state =>
        val currentState = expireState(state, now)
        currentState.engine
          .applyHandshakeAck(ack, now)
          .fold(
            error => currentState -> error.asLeft[Unit],
            updatedEngine =>
              updatedEngine.sessionById(ack.sessionId) match
                case None =>
                  currentState ->
                    handshakeRejected("unknownSession", ack.sessionId.value)
                      .asLeft[Unit]
                case Some(session) =>
                  val runtimeSession = TxProducerSessionState(
                    sessionId = session.sessionId,
                    peer = session.peer,
                    peerCorrelationId = session.peerCorrelationId,
                    subscriptions = session.proposal.subscriptions,
                    negotiated = ack.negotiated,
                    batchingConfig = policy.defaultBatchingConfig,
                  )
                  currentState.copy(
                    engine = updatedEngine,
                    outboundSessions = currentState.outboundSessions
                      .updated(session.sessionId, runtimeSession),
                  ) -> ().asRight[HandshakeRejected],
          )

  /** Gracefully closes a session.
    *
    * @param sessionId
    *   the session to close
    * @return
    *   unit on success, or a rejection
    */
  def closeSession(
      sessionId: DirectionalSessionId,
  ): F[Either[HandshakeRejected, Unit]] =
    clock.now.flatMap: now =>
      stateStore.modify: state =>
        val currentState = expireState(state, now)
        currentState.engine
          .closeSession(sessionId)
          .fold(
            error => currentState -> error.asLeft[Unit],
            updatedEngine =>
              currentState.copy(
                engine = updatedEngine,
                outboundSessions = currentState.outboundSessions - sessionId,
              ) -> ().asRight[HandshakeRejected],
          )

  /** Marks a session as terminally dead.
    *
    * @param sessionId
    *   the session to mark dead
    * @return
    *   unit on success, or a rejection
    */
  def markSessionDead(
      sessionId: DirectionalSessionId,
  ): F[Either[HandshakeRejected, Unit]] =
    clock.now.flatMap: now =>
      stateStore.modify: state =>
        val currentState = expireState(state, now)
        currentState.engine
          .markSessionDead(sessionId)
          .fold(
            error => currentState -> error.asLeft[Unit],
            updatedEngine =>
              currentState.copy(
                engine = updatedEngine,
                outboundSessions = currentState.outboundSessions - sessionId,
              ) -> ().asRight[HandshakeRejected],
          )

  /** Authorizes that a session exists and is open, touching its activity
    * timestamp.
    *
    * @param sessionId
    *   the session to authorize
    * @return
    *   the session, or a rejection
    */
  def authorizeOpenSession(
      sessionId: DirectionalSessionId,
  ): F[Either[CanonicalRejection.HandshakeRejected, DirectionalSession]] =
    clock.now.flatMap: now =>
      stateStore.modify: state =>
        val currentState = expireState(state, now)
        currentState.engine.sessionById(sessionId) match
          case None =>
            currentState ->
              handshakeRejected("unknownSession", sessionId.value)
                .asLeft[DirectionalSession]
          case Some(session)
              if session.status =!= DirectionalSessionStatus.Open =>
            currentState ->
              handshakeRejected("sessionNotOpen", sessionId.value)
                .asLeft[DirectionalSession]
          case Some(session) =>
            touchSessionActivity(currentState, sessionId, now).fold(
              rejection => currentState -> rejection.asLeft[DirectionalSession],
              updatedState =>
                updatedState ->
                  session.asRight[CanonicalRejection.HandshakeRejected],
            )

  /** Verifies that a session is owned by the given authenticated peer.
    *
    * @param sessionId
    *   the session to check
    * @param authenticatedPeer
    *   the peer identity to verify ownership against
    * @return
    *   the session, or a rejection
    */
  def authorizeSessionPeer(
      sessionId: DirectionalSessionId,
      authenticatedPeer: PeerIdentity,
  ): F[Either[CanonicalRejection.HandshakeRejected, DirectionalSession]] =
    clock.now.flatMap: now =>
      stateStore.modify: state =>
        val currentState = expireState(state, now)
        currentState ->
          sessionOwnedByPeer(currentState, sessionId, authenticatedPeer)

  /** Authorizes that a session exists, is open, and is owned by the given
    * authenticated peer.
    *
    * @param sessionId
    *   the session to authorize
    * @param authenticatedPeer
    *   the peer identity to verify ownership against
    * @return
    *   the session, or a rejection
    */
  def authorizeOpenSessionForPeer(
      sessionId: DirectionalSessionId,
      authenticatedPeer: PeerIdentity,
  ): F[Either[CanonicalRejection.HandshakeRejected, DirectionalSession]] =
    clock.now.flatMap: now =>
      stateStore.modify: state =>
        val currentState = expireState(state, now)
        sessionOwnedByPeer(currentState, sessionId, authenticatedPeer).fold(
          rejection => currentState -> rejection.asLeft[DirectionalSession],
          session =>
            if session.status =!= DirectionalSessionStatus.Open then
              currentState ->
                handshakeRejected("sessionNotOpen", sessionId.value)
                  .asLeft[DirectionalSession]
            else
              touchSessionActivity(currentState, sessionId, now).fold(
                rejection =>
                  currentState -> rejection.asLeft[DirectionalSession],
                updatedState =>
                  updatedState ->
                    session.asRight[CanonicalRejection.HandshakeRejected],
              ),
        )

  /** Receives and applies a control batch from a consumer on an outbound
    * session.
    *
    * @param sessionId
    *   the session receiving the batch
    * @param batch
    *   the control batch to process
    * @return
    *   the batch outcome, or a rejection
    */
  def receiveControlBatch(
      sessionId: DirectionalSessionId,
      batch: ControlBatch,
  ): F[Either[CanonicalRejection.ControlBatchRejected, ControlBatchOutcome]] =
    for
      now      <- clock.now
      snapshot <- snapshotAt(now)
      preOpenRejection <- rejectPreOpenTrafficIfNeeded(
        snapshot,
        sessionId,
        PreOpenTrafficKind.ControlChannel,
      )
      result <- preOpenRejection match
        case Some(rejection) =>
          controlRejected(
            rejection.reason,
            rejection.detail.getOrElse(sessionId.value),
          )
            .asLeft[ControlBatchOutcome]
            .pure[F]
        case None =>
          prevalidateControlBatch(snapshot, sessionId, batch).flatMap:
            case Left(rejection) =>
              rejection.asLeft[ControlBatchOutcome].pure[F]
            case Right(()) =>
              stateStore.modify: state =>
                // Defensive reaping: state may have changed since snapshotAt(now).
                val currentState = expireState(state, now)
                openOutboundProducerSession(currentState, sessionId).fold(
                  rejection =>
                    currentState -> rejection.asLeft[ControlBatchOutcome],
                  (_, sessionState) =>
                    applyControlBatch(now, batch, sessionState).fold(
                      rejection =>
                        currentState -> rejection.asLeft[ControlBatchOutcome],
                      { case (updatedSession, outcome) =>
                        touchSessionActivity(currentState, sessionId, now).fold(
                          rejection =>
                            currentState ->
                              controlRejected(
                                rejection.reason,
                                rejection.detail.getOrElse(sessionId.value),
                              ).asLeft[ControlBatchOutcome],
                          updatedState =>
                            updatedState.copy(
                              outboundSessions = updatedState.outboundSessions
                                .updated(sessionId, updatedSession),
                            ) -> outcome
                              .asRight[CanonicalRejection.ControlBatchRejected],
                        )
                      },
                    ),
                )
    yield result

  /** Polls available events for an outbound session, batching according to QoS
    * settings.
    *
    * @param sessionId
    *   the outbound session to poll
    * @return
    *   the event stream messages, or a rejection
    */
  def pollEvents(
      sessionId: DirectionalSessionId,
  ): F[Either[CanonicalRejection, Vector[EventStreamMessage[A]]]] =
    for
      now      <- clock.now
      snapshot <- snapshotAt(now)
      preOpenRejection <- rejectPreOpenTrafficIfNeeded(
        snapshot,
        sessionId,
        PreOpenTrafficKind.EventStream,
      )
      result <- preOpenRejection match
        case Some(rejection) =>
          rejection.asLeft[Vector[EventStreamMessage[A]]].pure[F]
        case None =>
          openOutboundEventSession(snapshot, sessionId).fold(
            rejection =>
              rejection.asLeft[Vector[EventStreamMessage[A]]].pure[F],
            (_, sessionState) =>
              pollOpenSession(now, sessionState).flatMap:
                case Left(rejection) =>
                  rejection.asLeft[Vector[EventStreamMessage[A]]].pure[F]
                case Right((updatedSession, emittedEvents)) =>
                  stateStore.modify: state =>
                    // Defensive reaping: state may have changed since snapshotAt(now).
                    val currentState = expireState(state, now)
                    currentState.outboundSessions.get(sessionId) match
                      case None =>
                        currentState ->
                          handshakeRejected("unknownSession", sessionId.value)
                            .asLeft[Vector[EventStreamMessage[A]]]
                      case Some(current) =>
                        touchSessionActivity(currentState, sessionId, now).fold(
                          rejection =>
                            currentState ->
                              rejection.asLeft[Vector[EventStreamMessage[A]]],
                          updatedState =>
                            val mergedSessions =
                              updatedState.outboundSessions.updated(
                                sessionId,
                                mergePolledSessionState(
                                  current,
                                  sessionState,
                                  updatedSession,
                                ),
                              )
                            updatedState.copy(outboundSessions =
                              mergedSessions,
                            ) ->
                              emittedEvents
                                .map(EventStreamMessage.Event(_))
                                .asRight[HandshakeRejected],
                        ),
          )
    yield result

  /** Receives and applies inbound event stream messages on a consumer session.
    *
    * @param sessionId
    *   the inbound session receiving events
    * @param messages
    *   the event stream messages
    * @return
    *   the receive result, or a rejection
    */
  def receiveEvents(
      sessionId: DirectionalSessionId,
      messages: Vector[EventStreamMessage[A]],
  ): F[Either[CanonicalRejection, TxReceiveEventsResult[A]]] =
    clock.now.flatMap: now =>
      snapshotAt(now).flatMap: state =>
        openInboundConsumerSession(state, sessionId).fold(
          rejection => rejection.asLeft[TxReceiveEventsResult[A]].pure[F],
          _ =>
            messages
              .foldLeftM(
                TxReceiveEventsResult[A](
                  applied = Vector.empty[GossipEvent[A]],
                  duplicates = Vector.empty[GossipEvent[A]],
                  lastCursor = None,
                ).asRight[CanonicalRejection],
              ):
                case (left @ Left(_), _) =>
                  left.pure[F]
                case (Right(result), EventStreamMessage.KeepAlive(_, _)) =>
                  result.asRight[CanonicalRejection].pure[F]
                case (_, EventStreamMessage.Rejection(rejection)) =>
                  rejection.asLeft[TxReceiveEventsResult[A]].pure[F]
                case (Right(result), EventStreamMessage.Event(event)) =>
                  validateAndApplyEvent(event).map:
                    case Left(rejection) =>
                      rejection.asLeft[TxReceiveEventsResult[A]]
                    case Right(applyResult) =>
                      if applyResult.duplicate then
                        result
                          .copy(
                            duplicates = result.duplicates :+ event,
                            lastCursor = Some(event.cursor),
                          )
                          .asRight[CanonicalRejection]
                      else
                        result
                          .copy(
                            applied = result.applied :+ event,
                            lastCursor = Some(event.cursor),
                          )
                          .asRight[CanonicalRejection]
              .flatMap:
                case left @ Left(_) =>
                  left.pure[F]
                case right @ Right(_) if messages.isEmpty =>
                  right.pure[F]
                case right @ Right(_) =>
                  stateStore.modify: current =>
                    // Defensive reaping: state may have changed since snapshotAt(now).
                    val currentState = expireState(current, now)
                    touchSessionActivity(currentState, sessionId, now).fold(
                      rejection =>
                        currentState ->
                          rejection.asLeft[TxReceiveEventsResult[A]],
                      updatedState => updatedState -> right,
                    ),
        )

  /** Generates a keep-alive message for an outbound event stream session.
    *
    * @param sessionId
    *   the outbound session
    * @return
    *   the keep-alive message, or a rejection
    */
  def eventKeepAlive(
      sessionId: DirectionalSessionId,
  ): F[Either[CanonicalRejection.HandshakeRejected, EventStreamMessage[A]]] =
    for
      now   <- clock.now
      state <- snapshotAt(now)
      preOpenRejection <- rejectPreOpenTrafficIfNeeded(
        state,
        sessionId,
        PreOpenTrafficKind.EventStream,
      )
      result <- preOpenRejection match
        case Some(rejection) =>
          rejection.asLeft[EventStreamMessage[A]].pure[F]
        case None =>
          openOutboundEventSession(state, sessionId).fold(
            _.asLeft[EventStreamMessage[A]].pure[F],
            _ =>
              stateStore.modify: current =>
                // Defensive reaping: state may have changed since snapshotAt(now).
                val currentState = expireState(current, now)
                touchSessionActivity(currentState, sessionId, now).fold(
                  rejection =>
                    currentState -> rejection.asLeft[EventStreamMessage[A]],
                  updatedState =>
                    updatedState ->
                      EventStreamMessage
                        .KeepAlive(sessionId, now)
                        .asRight[HandshakeRejected],
                ),
          )
    yield result

  /** Generates a keep-alive acknowledgement for an outbound control channel
    * session.
    *
    * @param sessionId
    *   the outbound session
    * @return
    *   the ack message, or a rejection
    */
  def controlKeepAlive(
      sessionId: DirectionalSessionId,
  ): F[Either[CanonicalRejection.ControlBatchRejected, ControlChannelMessage]] =
    for
      now   <- clock.now
      state <- snapshotAt(now)
      preOpenRejection <- rejectPreOpenTrafficIfNeeded(
        state,
        sessionId,
        PreOpenTrafficKind.ControlChannel,
      )
      result <- preOpenRejection match
        case Some(rejection) =>
          controlRejected(
            rejection.reason,
            rejection.detail.getOrElse(sessionId.value),
          )
            .asLeft[ControlChannelMessage]
            .pure[F]
        case None =>
          openOutboundProducerSession(state, sessionId).fold(
            _.asLeft[ControlChannelMessage].pure[F],
            _ =>
              stateStore.modify: current =>
                // Defensive reaping: state may have changed since snapshotAt(now).
                val currentState = expireState(current, now)
                touchSessionActivity(currentState, sessionId, now).fold(
                  rejection =>
                    currentState ->
                      controlRejected(
                        rejection.reason,
                        rejection.detail.getOrElse(sessionId.value),
                      ).asLeft[ControlChannelMessage],
                  updatedState =>
                    updatedState ->
                      ControlChannelMessage
                        .Ack(sessionId, now)
                        .asRight[CanonicalRejection.ControlBatchRejected],
                ),
          )
    yield result

  /** Returns a snapshot of the current runtime state after expiring timed-out
    * sessions.
    *
    * @return
    *   the current runtime state
    */
  def snapshotState: F[TxGossipRuntimeState] =
    clock.now.flatMap(snapshotAt)

  /** Returns the current relationship with the given peer, if any.
    *
    * @param peer
    *   the peer identity
    * @return
    *   the relationship, or None
    */
  def relationshipWith(peer: PeerIdentity): F[Option[PeerRelationship]] =
    snapshotState.map(_.engine.relationshipWith(peer))

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
        batch.ops.foldLeftM(
          ().asRight[CanonicalRejection.ControlBatchRejected],
        ):
          case (Left(rejection), _) =>
            rejection.asLeft[Unit].pure[F]
          case (Right(_), ControlOp.RequestByIdTx(chainId, ids)) =>
            val distinctIds = ids.distinct
            if distinctIds.sizeIs > policy.maxTxRequestIds then
              controlRejected(
                "requestByIdTooLarge",
                ss"max=${policy.maxTxRequestIds.toString} actual=${distinctIds.size.toString}",
              ).asLeft[Unit].pure[F]
            else
              source
                .readByIds(chainId, GossipTopic.tx, distinctIds)
                .map: events =>
                  val foundIds = events.map(_.event.id).toSet
                  val missing  = distinctIds.filterNot(foundIds.contains)
                  Either.cond(
                    missing.isEmpty,
                    (),
                    controlRejected(
                      "unknownRequestedArtifact",
                      missing.map(_.toHexLower).mkString(","),
                    ),
                  )
          case (Right(_), ControlOp.RequestByIdExact(scope, ids)) =>
            openExactKnownContract(scope.topic) match
              case Left(rejection) =>
                rejection.asLeft[Unit].pure[F]
              case Right(contract) =>
                val distinctIds = ids.distinct
                contract.requestByIdLimit match
                  case Some(limit) if distinctIds.sizeIs > limit =>
                    controlRejected(
                      "requestByIdTooLarge",
                      ss"max=${limit.toString} actual=${distinctIds.size.toString}",
                    ).asLeft[Unit].pure[F]
                  case Some(_) =>
                    source
                      .readByIds(scope.chainId, scope.topic, distinctIds)
                      .map: events =>
                        val (wrongWindow, foundIds) =
                          events.foldLeft(
                            (
                              Vector.empty[StableArtifactId],
                              Set.empty[StableArtifactId],
                            ),
                          ):
                            case ((wrongWindowAcc, foundAcc), available) =>
                              contract.exactKnownScopeOf(available.event) match
                                case Right(Some(eventScope))
                                    if eventScope === scope =>
                                  wrongWindowAcc -> (foundAcc + available.event.id)
                                case Right(Some(_)) =>
                                  (wrongWindowAcc :+ available.event.id) -> foundAcc
                                case Right(None) =>
                                  (wrongWindowAcc :+ available.event.id) -> foundAcc
                                case Left(_) =>
                                  (wrongWindowAcc :+ available.event.id) -> foundAcc
                        val missing = distinctIds.filterNot(foundIds.contains)
                        if wrongWindow.nonEmpty then
                          controlRejected(
                            "wrongWindowKey",
                            wrongWindow.map(_.toHexLower).mkString(","),
                          ).asLeft[Unit]
                        else
                          Either.cond(
                            missing.isEmpty,
                            (),
                            controlRejected(
                              "unknownRequestedArtifact",
                              missing.map(_.toHexLower).mkString(","),
                            ),
                          )
                  case None =>
                    controlRejected("unsupportedTopic", scope.topic.value)
                      .asLeft[Unit]
                      .pure[F]
          case (Right(_), _) =>
            ().asRight[CanonicalRejection.ControlBatchRejected].pure[F],
    )

  private def applyControlBatch(
      now: Instant,
      batch: ControlBatch,
      sessionState: TxProducerSessionState,
  ): Either[
    CanonicalRejection.ControlBatchRejected,
    (TxProducerSessionState, ControlBatchOutcome),
  ] =
    val prunedKeys            = pruneIdempotencyKeys(now, sessionState)
    val sessionWithPrunedKeys = sessionState.copy(idempotencyKeys = prunedKeys)
    if prunedKeys.contains(batch.idempotencyKey) then
      (sessionWithPrunedKeys -> ControlBatchOutcome.Deduplicated)
        .asRight[CanonicalRejection.ControlBatchRejected]
    else
      batch.ops
        .foldLeft[Either[
          CanonicalRejection.ControlBatchRejected,
          TxProducerSessionState,
        ]](
          sessionWithPrunedKeys.asRight[CanonicalRejection.ControlBatchRejected],
        ):
          case (Right(current), op) =>
            applyControlOp(current, op)
          case (left, _) =>
            left
        .map: updated =>
          updated.copy(
            idempotencyKeys =
              updated.idempotencyKeys.updated(batch.idempotencyKey, now),
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
              TxBloomFilterSupport
                .validate(bloomFilter, policy)
                .map: validated =>
                  sessionState.copy(filters =
                    sessionState.filters.updated(chainId, validated),
                  )

      case ControlOp.SetKnownTx(chainId, ids) =>
        validateTxSubscription(sessionState, chainId, GossipTopic.tx).flatMap:
          _ =>
            val distinctNewIds = ids.toSet
            val mergedIds = sessionState.exactKnownIds.getOrElse(
              chainId,
              Set.empty[StableArtifactId],
            ) ++ distinctNewIds
            Either.cond(
              distinctNewIds.sizeIs <= policy.maxTxSetKnownEntries &&
                mergedIds.sizeIs <= policy.maxTxSetKnownEntries,
              sessionState.copy(
                exactKnownIds = sessionState.exactKnownIds.updated(
                  chainId,
                  mergedIds,
                ),
              ),
              controlRejected(
                "setKnownTooLarge",
                ss"max=${policy.maxTxSetKnownEntries.toString} actual=${mergedIds.size.toString}",
              ),
            )

      case ControlOp.SetKnownExact(scope, ids) =>
        validateExactKnownSubscription(sessionState, scope).flatMap: contract =>
          val distinctNewIds = ids.toSet
          val existing =
            sessionState.exactKnownScopeIds.getOrElse(
              scope,
              Set.empty[StableArtifactId],
            )
          val mergedIds = existing ++ distinctNewIds
          contract.exactKnownSetLimit match
            case Some(limit) =>
              Either.cond(
                distinctNewIds.sizeIs <= limit && mergedIds.sizeIs <= limit,
                sessionState.copy(
                  exactKnownScopeIds =
                    sessionState.exactKnownScopeIds.updated(scope, mergedIds),
                ),
                controlRejected(
                  "setKnownTooLarge",
                  ss"max=${limit.toString} actual=${mergedIds.size.toString}",
                ),
              )
            case None =>
              controlRejected("unsupportedTopic", scope.topic.value)
                .asLeft[TxProducerSessionState]

      case ControlOp.SetCursor(cursor) =>
        val unsupportedKeys =
          cursor.values.keySet.filterNot(sessionState.subscriptions.contains)
        Either.cond(
          unsupportedKeys.isEmpty,
          sessionState.withProducerState(
            sessionState.producerState.withDurableCursor(cursor),
          ),
          controlRejected(
            "cursorOutOfSubscription",
            unsupportedKeys
              .map(key => ss"${key.chainId.value}:${key.topic.value}")
              .mkString(","),
          ),
        )

      case ControlOp.Nack(chainId, topic, cursor) =>
        validateSubscription(sessionState, chainId, topic).map: _ =>
          sessionState.withProducerState(
            sessionState.producerState
              .withReplay(ChainTopic(chainId, topic), cursor),
          )

      case ControlOp.RequestByIdTx(chainId, ids) =>
        validateTxSubscription(sessionState, chainId, GossipTopic.tx).flatMap:
          _ =>
            val distinctIds = ids.distinct
            Either.cond(
              distinctIds.sizeIs <= policy.maxTxRequestIds,
              sessionState.copy(
                pendingRequestByIds = sessionState.pendingRequestByIds.updated(
                  chainId,
                  appendUnique(
                    sessionState.pendingRequestByIds
                      .getOrElse(chainId, Vector.empty[StableArtifactId]),
                    distinctIds,
                  ),
                ),
              ),
              controlRejected(
                "requestByIdTooLarge",
                ss"max=${policy.maxTxRequestIds.toString} actual=${distinctIds.size.toString}",
              ),
            )

      case ControlOp.RequestByIdExact(scope, ids) =>
        validateExactKnownSubscription(sessionState, scope).flatMap: contract =>
          val distinctIds = ids.distinct
          val nextRetryCount =
            sessionState.requestScopeRetryCounts.getOrElse(scope, 0) + 1
          contract.requestByIdLimit match
            case Some(limit) =>
              if distinctIds.sizeIs > limit then
                controlRejected(
                  "requestByIdTooLarge",
                  ss"max=${limit.toString} actual=${distinctIds.size.toString}",
                ).asLeft[TxProducerSessionState]
              else
                policy.maxExactRequestRetriesPerScope match
                  case Some(retryLimit) if nextRetryCount > retryLimit =>
                    controlRejected(
                      "requestByIdRetryBudgetExceeded",
                      ss"max=${retryLimit.toString} actual=${nextRetryCount.toString} scope=${scope.topic.value}",
                    ).asLeft[TxProducerSessionState]
                  case _ =>
                    sessionState
                      .copy(
                        pendingRequestScopeIds =
                          sessionState.pendingRequestScopeIds.updated(
                            scope,
                            appendUnique(
                              sessionState.pendingRequestScopeIds.getOrElse(
                                scope,
                                Vector.empty[StableArtifactId],
                              ),
                              distinctIds,
                            ),
                          ),
                        requestScopeRetryCounts =
                          sessionState.requestScopeRetryCounts
                            .updated(scope, nextRetryCount),
                      )
                      .asRight[CanonicalRejection.ControlBatchRejected]
            case None =>
              controlRejected("unsupportedTopic", scope.topic.value)
                .asLeft[TxProducerSessionState]

      case ControlOp.Config(values) =>
        values
          .foldLeft[
            Either[CanonicalRejection.ControlBatchRejected, TxBatchingConfig],
          ](
            sessionState.batchingConfig
              .asRight[CanonicalRejection.ControlBatchRejected],
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
  ): F[
    Either[CanonicalRejection, (TxProducerSessionState, Vector[GossipEvent[A]])],
  ] =
    sessionState.subscriptions.values.toVector
      .sortBy: chainTopic =>
        val priority =
          topicContracts
            .contractFor(chainTopic.topic)
            .toOption
            .map(_.deliveryPriority)
            .getOrElse(0)
        (-priority, chainTopic.chainId.value, chainTopic.topic.value)
      .foldLeftM(
        (
          sessionState,
          Vector.empty[GossipEvent[A]],
        ).asRight[CanonicalRejection],
      ):
        case (Left(rejection), _) =>
          rejection
            .asLeft[(TxProducerSessionState, Vector[GossipEvent[A]])]
            .pure[F]
        case (Right((currentState, emitted)), chainTopic) =>
          if chainTopic.topic === GossipTopic.tx then
            pollTxChain(now, currentState, chainTopic).map:
              case Left(rejection) =>
                rejection
                  .asLeft[(TxProducerSessionState, Vector[GossipEvent[A]])]
              case Right((updatedState, chainEvents)) =>
                (updatedState -> (emitted ++ chainEvents))
                  .asRight[CanonicalRejection]
          else
            topicContracts.contractFor(chainTopic.topic) match
              case Left(rejection) =>
                rejection
                  .asLeft[(TxProducerSessionState, Vector[GossipEvent[A]])]
                  .pure[F]
              case Right(contract) =>
                pollExactKnownChain(now, currentState, chainTopic, contract)
                  .map:
                    case Left(rejection) =>
                      rejection
                        .asLeft[
                          (TxProducerSessionState, Vector[GossipEvent[A]]),
                        ]
                    case Right((updatedState, chainEvents)) =>
                      (updatedState -> (emitted ++ chainEvents))
                        .asRight[CanonicalRejection]

  private def pollTxChain(
      now: Instant,
      sessionState: TxProducerSessionState,
      chainTopic: ChainTopic,
  ): F[
    Either[CanonicalRejection, (TxProducerSessionState, Vector[GossipEvent[A]])],
  ] =
    val requestedIds = sessionState.pendingRequestByIds.getOrElse(
      chainTopic.chainId,
      Vector.empty[StableArtifactId],
    )
    val producerState  = sessionState.producerState
    val cursorOverride = producerState.pendingReplay.get(chainTopic)
    val startCursor =
      cursorOverride.getOrElse(producerState.startCursorFor(chainTopic))

    for
      explicitArtifacts <-
        if requestedIds.isEmpty then
          Vector.empty[AvailableGossipEvent[A]].pure[F]
        else
          source.readByIds(chainTopic.chainId, chainTopic.topic, requestedIds)
      afterCursor <- source.readAfter(
        chainTopic.chainId,
        chainTopic.topic,
        startCursor,
      )
    yield afterCursor.flatMap: candidates =>
      val explicitEvents = explicitArtifacts.map(_.event)
      val liveCandidates = candidates.filterNot(candidate =>
        explicitEvents.exists(_.id === candidate.event.id),
      )
      cascadeStrategy
        .selectLiveEvents(
          filter = sessionState.filters.get(chainTopic.chainId),
          exactKnownIds = sessionState.exactKnownIds.getOrElse(
            chainTopic.chainId,
            Set.empty[StableArtifactId],
          ),
          candidates = liveCandidates.map(_.event),
        )
        .map: selectedLiveEvents =>
          val selectedLiveArtifacts =
            selectArtifacts(liveCandidates, selectedLiveEvents)
          val explicitBatch =
            explicitEvents.take(sessionState.batchingConfig.maxBatchItems)
          val remainingCapacity =
            (sessionState.batchingConfig.maxBatchItems - explicitBatch.size)
              .max(0)
          val forceFlush = cursorOverride.nonEmpty
          val liveBatch =
            GossipProducerPolling.batchAvailableEvents(
              now = now,
              candidates = selectedLiveArtifacts,
              qos = sessionState.batchingConfig,
              forceFlush = forceFlush,
              limit = remainingCapacity,
            )
          val emitted           = explicitBatch ++ liveBatch
          val servedExplicitIds = explicitBatch.map(_.id).toSet
          val updatedProducerState =
            producerState
              .advanceStreamCursor(chainTopic, liveBatch)
              .clearReplay(chainTopic)
          val updatedState = sessionState
            .withProducerState(updatedProducerState)
            .copy(
              pendingRequestByIds =
                if servedExplicitIds.isEmpty then
                  sessionState.pendingRequestByIds
                else
                  sessionState.pendingRequestByIds
                    .updatedWith(chainTopic.chainId):
                      case None => None
                      case Some(existing) =>
                        val remaining =
                          existing.filterNot(servedExplicitIds.contains)
                        remaining.some.filter(_.nonEmpty),
            )
          updatedState -> emitted

  private def pollExactKnownChain(
      now: Instant,
      sessionState: TxProducerSessionState,
      chainTopic: ChainTopic,
      contract: GossipTopicContract[A],
  ): F[
    Either[CanonicalRejection, (TxProducerSessionState, Vector[GossipEvent[A]])],
  ] =
    val requestedScopes =
      sessionState.pendingRequestScopeIds.toVector.collect:
        case (scope, ids)
            if scope.chainId === chainTopic.chainId &&
              scope.topic === chainTopic.topic =>
          scope -> ids

    val requestedIds   = requestedScopes.flatMap(_._2).distinct
    val producerState  = sessionState.producerState
    val cursorOverride = producerState.pendingReplay.get(chainTopic)
    val startCursor =
      cursorOverride.getOrElse(producerState.startCursorFor(chainTopic))
    val qos = contract.producerQoS(sessionState.batchingConfig)

    for
      explicitArtifacts <-
        if requestedIds.isEmpty then
          Vector.empty[AvailableGossipEvent[A]].pure[F]
        else
          source.readByIds(chainTopic.chainId, chainTopic.topic, requestedIds)
      afterCursor <- source.readAfter(
        chainTopic.chainId,
        chainTopic.topic,
        startCursor,
      )
    yield
      val explicitResult = explicitArtifacts.traverse: available =>
        contract
          .exactKnownScopeOf(available.event)
          .map(scope => scope -> available)

      afterCursor.flatMap: candidates =>
        explicitResult.flatMap: scopedExplicitArtifacts =>
          val requestedScopeSet = requestedScopes.map(_._1).toSet
          val explicitMatched =
            scopedExplicitArtifacts.collect:
              case (Some(scope), available)
                  if requestedScopeSet.contains(scope) =>
                scope -> available
          val explicitEvents = explicitMatched.map(_._2.event)
          val liveCandidates = candidates.filterNot(candidate =>
            explicitEvents.exists(_.id === candidate.event.id),
          )
          liveCandidates
            .traverse: candidate =>
              contract
                .exactKnownScopeOf(candidate.event)
                .map(scope => scope -> candidate)
            .map: scopedLiveCandidates =>
              val filteredLiveArtifacts =
                scopedLiveCandidates.collect:
                  case (Some(scope), candidate)
                      if !sessionState.exactKnownScopeIds
                        .getOrElse(scope, Set.empty[StableArtifactId])
                        .contains(candidate.event.id) =>
                    candidate
              val explicitBatch = explicitEvents.take(qos.maxBatchItems)
              val remainingCapacity =
                (qos.maxBatchItems - explicitBatch.size).max(0)
              val forceFlush = cursorOverride.nonEmpty
              val liveBatch =
                GossipProducerPolling.batchAvailableEvents(
                  now = now,
                  candidates = filteredLiveArtifacts,
                  qos = qos,
                  forceFlush = forceFlush,
                  limit = remainingCapacity,
                )
              val emitted = explicitBatch ++ liveBatch
              val servedByScope =
                explicitMatched.foldLeft(
                  Map.empty[ExactKnownSetScope, Set[StableArtifactId]],
                ): (acc, entry) =>
                  val (scope, available) = entry
                  acc.updated(
                    scope,
                    acc.getOrElse(scope, Set.empty[StableArtifactId]) +
                      available.event.id,
                  )
              val updatedProducerState =
                producerState
                  .advanceStreamCursor(chainTopic, liveBatch)
                  .clearReplay(chainTopic)
              val updatedState = sessionState
                .withProducerState(updatedProducerState)
                .copy(
                  pendingRequestScopeIds =
                    if servedByScope.isEmpty then
                      sessionState.pendingRequestScopeIds
                    else
                      servedByScope.foldLeft(
                        sessionState.pendingRequestScopeIds,
                      ): (acc, entry) =>
                        val (scope, servedIds) = entry
                        acc.updatedWith(scope):
                          case None => None
                          case Some(existing) =>
                            val remaining =
                              existing.filterNot(servedIds.contains)
                            remaining.some.filter(_.nonEmpty),
                )
              updatedState -> emitted

  private def pruneIdempotencyKeys(
      now: Instant,
      sessionState: TxProducerSessionState,
  ): Map[ControlIdempotencyKey, Instant] =
    val cutoff = now.minus(policy.controlRetryHorizon(sessionState.negotiated))
    sessionState.idempotencyKeys.filterNot((_, appliedAt) =>
      appliedAt.isBefore(cutoff),
    )

  private def validateTxSubscription(
      sessionState: TxProducerSessionState,
      chainId: ChainId,
      topic: GossipTopic,
  ): Either[CanonicalRejection.ControlBatchRejected, Unit] =
    validateSubscription(sessionState, chainId, topic).flatMap: _ =>
      Either.cond(
        topic === GossipTopic.tx,
        (),
        controlRejected(
          "topicOutOfSubscription",
          ss"${chainId.value}:${topic.value}",
        ),
      )

  private def validateSubscription(
      sessionState: TxProducerSessionState,
      chainId: ChainId,
      topic: GossipTopic,
  ): Either[CanonicalRejection.ControlBatchRejected, Unit] =
    Either.cond(
      sessionState.subscriptions.contains(chainId, topic),
      (),
      controlRejected(
        "topicOutOfSubscription",
        ss"${chainId.value}:${topic.value}",
      ),
    )

  private def validateExactKnownSubscription(
      sessionState: TxProducerSessionState,
      scope: ExactKnownSetScope,
  ): Either[CanonicalRejection.ControlBatchRejected, GossipTopicContract[A]] =
    Either
      .cond(
        sessionState.subscriptions.contains(scope.chainId, scope.topic),
        (),
        controlRejected(
          "topicOutOfSubscription",
          ss"${scope.chainId.value}:${scope.topic.value}",
        ),
      )
      .flatMap(_ => openExactKnownContract(scope.topic))

  private def openExactKnownContract(
      topic: GossipTopic,
  ): Either[CanonicalRejection.ControlBatchRejected, GossipTopicContract[A]] =
    topicContracts
      .contractFor(topic)
      .leftMap(rejection =>
        controlRejected(
          rejection.reason,
          rejection.detail.getOrElse(topic.value),
        ),
      )
      .flatMap: contract =>
        Either.cond(
          contract.exactKnownSetLimit.nonEmpty || contract.requestByIdLimit.nonEmpty,
          contract,
          controlRejected("unsupportedTopic", topic.value),
        )

  private def openOutboundProducerSession(
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

  private def openOutboundEventSession(
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

  private def openInboundConsumerSession(
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

  private def appendUnique(
      existing: Vector[StableArtifactId],
      additions: Vector[StableArtifactId],
  ): Vector[StableArtifactId] =
    additions.foldLeft(existing): (acc, id) =>
      if acc.contains(id) then acc else acc :+ id

  private def selectArtifacts(
      candidates: Vector[AvailableGossipEvent[A]],
      selectedEvents: Vector[GossipEvent[A]],
  ): Vector[AvailableGossipEvent[A]] =
    val byCursor =
      candidates.iterator
        .map(candidate => candidate.event.cursor -> candidate)
        .toMap
    selectedEvents.flatMap(event => byCursor.get(event.cursor))

  private def positiveIntConfig(
      key: String,
      value: Long,
  ): Either[CanonicalRejection.ControlBatchRejected, Int] =
    Either.cond(
      value > 0L && value <= Int.MaxValue.toLong,
      value.toInt,
      controlRejected("invalidConfigValue", ss"${key}=${value.toString}"),
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

/** Companion for `TxGossipRuntime` providing factory methods. */
object TxGossipRuntime:

  /** Creates a runtime with the default policy and cascade strategy.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the artifact payload type
    */
  def default[F[_]: Sync, A](
      peerAuthenticator: PeerAuthenticator[F],
      clock: GossipClock[F],
      source: GossipArtifactSource[F, A],
      sink: GossipArtifactSink[F, A],
      topicContracts: GossipTopicContractRegistry[A],
      stateStore: TxGossipStateStore[F],
  ): TxGossipRuntime[F, A] =
    withPolicy(
      peerAuthenticator = peerAuthenticator,
      clock = clock,
      source = source,
      sink = sink,
      topicContracts = topicContracts,
      stateStore = stateStore,
      policy = TxRuntimePolicy(),
    )

  /** Creates a runtime with a custom policy and the default cascade strategy.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the artifact payload type
    */
  def withPolicy[F[_]: Sync, A](
      peerAuthenticator: PeerAuthenticator[F],
      clock: GossipClock[F],
      source: GossipArtifactSource[F, A],
      sink: GossipArtifactSink[F, A],
      topicContracts: GossipTopicContractRegistry[A],
      stateStore: TxGossipStateStore[F],
      policy: TxRuntimePolicy,
  ): TxGossipRuntime[F, A] =
    configured(
      peerAuthenticator = peerAuthenticator,
      clock = clock,
      source = source,
      sink = sink,
      topicContracts = topicContracts,
      stateStore = stateStore,
      policy = policy,
      cascadeStrategy = TxCascadeStrategy.exactKnownOrBackfillUnavailable[A],
    )

  /** Creates a fully configured runtime with custom policy and cascade
    * strategy.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the artifact payload type
    */
  def configured[F[_]: Sync, A](
      peerAuthenticator: PeerAuthenticator[F],
      clock: GossipClock[F],
      source: GossipArtifactSource[F, A],
      sink: GossipArtifactSink[F, A],
      topicContracts: GossipTopicContractRegistry[A],
      stateStore: TxGossipStateStore[F],
      policy: TxRuntimePolicy,
      cascadeStrategy: TxCascadeStrategy[A],
  ): TxGossipRuntime[F, A] =
    new TxGossipRuntime(
      peerAuthenticator = peerAuthenticator,
      clock = clock,
      source = source,
      sink = sink,
      topicContracts = topicContracts,
      stateStore = stateStore,
      policy = policy,
      cascadeStrategy = cascadeStrategy,
    )
