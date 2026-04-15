package org.sigilaris.node.gossip.tx

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.CanonicalRejection.*

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
    override protected val clock: GossipClock[F],
    override protected val source: GossipArtifactSource[F, A],
    override protected val sink: GossipArtifactSink[F, A],
    override protected val topicContracts: GossipTopicContractRegistry[A],
    override protected val stateStore: TxGossipStateStore[F],
    override protected val policy: TxRuntimePolicy,
    override protected val cascadeStrategy: TxCascadeStrategy[A],
) extends TxGossipRuntimeControlOps[F, A]
    with TxGossipRuntimePollingOps[F, A]:
  private def authenticatedPeerMismatch(
      expected: PeerIdentity,
      actual: PeerIdentity,
  ): CanonicalRejection.HandshakeRejected =
    handshakeRejected(
      "authenticatedPeerMismatch",
      "expected=" + expected.value + " actual=" + actual.value,
    )

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
