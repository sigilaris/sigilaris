package org.sigilaris.node.gossip

import java.time.Instant

import cats.Eq
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.CanonicalRejection.HandshakeRejected

/** Direction of a gossip session relative to the local node. */
enum SessionDirection:

  /** The local node initiated this session. */
  case Outbound

  /** A remote peer initiated this session. */
  case Inbound

/** Companion for `SessionDirection`. */
object SessionDirection:
  given Eq[SessionDirection] = Eq.fromUniversalEquals

/** Lifecycle status of a single directional session. */
enum DirectionalSessionStatus:

  /** Handshake in progress, not yet open. */
  case Opening

  /** Session is open and active. */
  case Open

  /** Session has been gracefully closed. */
  case Closed

  /** Session is terminally dead (timed out or failed). */
  case Dead

/** Companion for `DirectionalSessionStatus`. */
object DirectionalSessionStatus:
  given Eq[DirectionalSessionStatus] = Eq.fromUniversalEquals

/** Aggregate status of a bidirectional peer relationship. */
enum PeerRelationshipStatus:

  /** At least one direction is opening, none fully open yet. */
  case Opening

  /** Both directions are open. */
  case Open

  /** Was previously bidirectionally open, but one direction dropped. */
  case HalfOpen

  /** All directions are closed. */
  case Closed

  /** At least one direction is dead. */
  case Dead

/** Companion for `PeerRelationshipStatus`. */
object PeerRelationshipStatus:
  given Eq[PeerRelationshipStatus] = Eq.fromUniversalEquals

/** Kind of traffic that arrived before a session was fully open. */
enum PreOpenTrafficKind:

  /** Traffic on the event stream channel. */
  case EventStream

  /** Traffic on the control channel. */
  case ControlChannel

/** Companion for `PreOpenTrafficKind`. */
object PreOpenTrafficKind:
  given Eq[PreOpenTrafficKind] = Eq.fromUniversalEquals

/** State of a single directional gossip session.
  *
  * @param sessionId
  *   the unique directional session identifier
  * @param direction
  *   whether this session is outbound or inbound
  * @param peer
  *   the remote peer
  * @param peerCorrelationId
  *   the peer correlation id
  * @param proposal
  *   the original session open proposal
  * @param negotiated
  *   the negotiated parameters (present after handshake completes)
  * @param status
  *   the current lifecycle status
  * @param createdAt
  *   when this session was created
  * @param openedAt
  *   when this session became open (if applicable)
  * @param lastActivityAt
  *   the most recent activity timestamp
  */
final case class DirectionalSession(
    sessionId: DirectionalSessionId,
    direction: SessionDirection,
    peer: PeerIdentity,
    peerCorrelationId: PeerCorrelationId,
    proposal: SessionOpenProposal,
    negotiated: Option[NegotiatedSessionParameters],
    status: DirectionalSessionStatus,
    createdAt: Instant,
    openedAt: Option[Instant],
    lastActivityAt: Instant,
):

  /** @return true if the session is in Opening or Open status */
  def isAlive: Boolean =
    status === DirectionalSessionStatus.Open ||
      status === DirectionalSessionStatus.Opening

/** Aggregate state of a bidirectional relationship with a single peer.
  *
  * @param peer
  *   the remote peer identity
  * @param peerCorrelationId
  *   the current correlation id for this relationship
  * @param outbound
  *   the outbound directional session, if any
  * @param inbound
  *   the inbound directional session, if any
  * @param status
  *   the aggregate relationship status
  * @param wasBidirectionalOpen
  *   true if both directions were simultaneously open at any point
  */
final case class PeerRelationship(
    peer: PeerIdentity,
    peerCorrelationId: PeerCorrelationId,
    outbound: Option[DirectionalSession],
    inbound: Option[DirectionalSession],
    status: PeerRelationshipStatus,
    wasBidirectionalOpen: Boolean,
):

  /** Finds a directional session by its id among the outbound and inbound
    * sessions.
    *
    * @param sessionId
    *   the session id to look for
    * @return
    *   the matching session, or None
    */
  def session(sessionId: DirectionalSessionId): Option[DirectionalSession] =
    List(outbound, inbound).flatten.find(_.sessionId === sessionId)

/** Companion for `PeerRelationship` providing factory methods. */
object PeerRelationship:

  /** Creates a new relationship with an outbound session in Opening status.
    *
    * @param session
    *   the outbound directional session
    * @return
    *   the new relationship
    */
  def openingWithOutbound(session: DirectionalSession): PeerRelationship =
    PeerRelationship(
      peer = session.peer,
      peerCorrelationId = session.peerCorrelationId,
      outbound = Some(session),
      inbound = None,
      status = PeerRelationshipStatus.Opening,
      wasBidirectionalOpen = false,
    )

  /** Creates a new relationship with an inbound session.
    *
    * @param session
    *   the inbound directional session
    * @return
    *   the new relationship
    */
  def withInbound(session: DirectionalSession): PeerRelationship =
    PeerRelationship(
      peer = session.peer,
      peerCorrelationId = session.peerCorrelationId,
      outbound = None,
      inbound = Some(session),
      status = PeerRelationshipStatus.Opening,
      wasBidirectionalOpen = false,
    )

/** Result of processing an inbound session open proposal. */
sealed trait InboundHandshakeResult

/** Companion for `InboundHandshakeResult` defining the result subtypes. */
object InboundHandshakeResult:

  /** The proposal was accepted.
    *
    * @param ack
    *   the generated acknowledgement
    * @param supersededSessionId
    *   if a simultaneous-open was resolved, the id of the superseded outbound
    *   session
    */
  final case class Accepted(
      ack: SessionOpenAck,
      supersededSessionId: Option[DirectionalSessionId],
  ) extends InboundHandshakeResult

  /** The proposal was rejected.
    *
    * @param rejection
    *   the rejection reason
    */
  final case class Rejected(
      rejection: CanonicalRejection.HandshakeRejected,
  ) extends InboundHandshakeResult

@SuppressWarnings(
  Array(
    "org.wartremover.warts.DefaultArguments",
  ),
)
/** Pure, immutable state machine managing gossip session lifecycles for a local
  * peer.
  *
  * @param localPeer
  *   the identity of the local node
  * @param topology
  *   the static peer topology
  * @param policy
  *   the handshake timing policy
  * @param relationships
  *   the current peer relationships
  */
final case class GossipSessionEngine(
    localPeer: PeerIdentity,
    topology: StaticPeerTopology,
    policy: HandshakePolicy = HandshakePolicy.default,
    relationships: Map[PeerIdentity, PeerRelationship] = Map.empty,
):

  /** Returns the relationship with the given peer, if one exists.
    *
    * @param peer
    *   the peer identity
    * @return
    *   the relationship, or None
    */
  def relationshipWith(peer: PeerIdentity): Option[PeerRelationship] =
    relationships.get(peer)

  /** Looks up a directional session by id across all relationships.
    *
    * @param sessionId
    *   the directional session identifier
    * @return
    *   the session, or None
    */
  def sessionById(sessionId: DirectionalSessionId): Option[DirectionalSession] =
    relationships.valuesIterator
      .flatMap(r => List(r.outbound, r.inbound).flatten)
      .find(_.sessionId === sessionId)

  /** Initiates an outbound session to a direct neighbor peer.
    *
    * @param peer
    *   the target peer
    * @param subscriptions
    *   the chain-topic pairs to subscribe to
    * @param now
    *   the current timestamp
    * @param peerCorrelationId
    *   optional override for the correlation id
    * @param heartbeatInterval
    *   optional proposed heartbeat interval
    * @param livenessTimeout
    *   optional proposed liveness timeout
    * @param maxControlRetryInterval
    *   optional proposed max control retry interval
    * @return
    *   the updated engine and proposal, or a rejection
    */
  def startOutbound(
      peer: PeerIdentity,
      subscriptions: SessionSubscription,
      now: Instant,
      peerCorrelationId: Option[PeerCorrelationId] = None,
      heartbeatInterval: Option[java.time.Duration] = None,
      livenessTimeout: Option[java.time.Duration] = None,
      maxControlRetryInterval: Option[java.time.Duration] = None,
  ): Either[HandshakeRejected, (GossipSessionEngine, SessionOpenProposal)] =
    ensureDirectNeighbor(peer).flatMap: _ =>
      val existing = relationships.get(peer)
      val existingOutboundAlive =
        existing.flatMap(_.outbound).exists(_.isAlive)
      Either
        .cond(
          !existingOutboundAlive,
          (),
          HandshakeRejected(
            reason = "duplicateOutboundDirection",
            detail = Some(peer.value),
          ),
        )
        .flatMap: _ =>
          val chosenCorrelationId =
            peerCorrelationId
              .orElse(
                existing
                  .filter: relationship =>
                    relationship.status === PeerRelationshipStatus.Opening ||
                      relationship.status === PeerRelationshipStatus.Open ||
                      relationship.status === PeerRelationshipStatus.HalfOpen
                  .map(_.peerCorrelationId),
              )
              .getOrElse(PeerCorrelationId.random())
          val proposal = SessionOpenProposal(
            sessionId = DirectionalSessionId.random(),
            peerCorrelationId = chosenCorrelationId,
            initiator = localPeer,
            acceptor = peer,
            subscriptions = subscriptions,
            heartbeatInterval = heartbeatInterval,
            livenessTimeout = livenessTimeout,
            maxControlRetryInterval = maxControlRetryInterval,
          )
          SessionNegotiation
            .resolveProposal(proposal, policy)
            .map: _ =>
              val outbound = DirectionalSession(
                sessionId = proposal.sessionId,
                direction = SessionDirection.Outbound,
                peer = peer,
                peerCorrelationId = chosenCorrelationId,
                proposal = proposal,
                negotiated = None,
                status = DirectionalSessionStatus.Opening,
                createdAt = now,
                openedAt = None,
                lastActivityAt = now,
              )
              val updatedRelationship =
                recomputeRelationship(
                  existing match
                    case Some(relationship) =>
                      relationship.copy(
                        peerCorrelationId = chosenCorrelationId,
                        outbound = Some(outbound),
                      )
                    case None =>
                      PeerRelationship.openingWithOutbound(outbound),
                )
              (
                copy(relationships =
                  relationships.updated(peer, updatedRelationship),
                ),
                proposal,
              )

  /** Processes an inbound session open proposal, handling simultaneous-open
    * resolution.
    *
    * @param proposal
    *   the received proposal
    * @param now
    *   the current timestamp
    * @param heartbeatInterval
    *   optional override for the heartbeat interval in the ack
    * @param livenessTimeout
    *   optional override for the liveness timeout in the ack
    * @param maxControlRetryInterval
    *   optional override for the max control retry interval in the ack
    * @return
    *   the updated engine and handshake result
    */
  def handleInboundProposal(
      proposal: SessionOpenProposal,
      now: Instant,
      heartbeatInterval: Option[java.time.Duration] = None,
      livenessTimeout: Option[java.time.Duration] = None,
      maxControlRetryInterval: Option[java.time.Duration] = None,
  ): (GossipSessionEngine, InboundHandshakeResult) =
    val decision =
      for
        _ <- Either.cond(
          proposal.acceptor === localPeer,
          (),
          HandshakeRejected(
            reason = "wrongAcceptor",
            detail = Some(
              ss"expected=${localPeer.value} actual=${proposal.acceptor.value}",
            ),
          ),
        )
        _        <- ensureDirectNeighbor(proposal.initiator)
        proposed <- SessionNegotiation.resolveProposal(proposal, policy)
      yield
        val ack = SessionNegotiation.acknowledge(
          proposal = proposal,
          heartbeatInterval =
            heartbeatInterval.getOrElse(proposed.heartbeatInterval),
          livenessTimeout = livenessTimeout.getOrElse(proposed.livenessTimeout),
          maxControlRetryInterval =
            maxControlRetryInterval.getOrElse(proposed.maxControlRetryInterval),
          policy = policy,
        )
        (proposed, ack)

    decision match
      case Left(rejection) =>
        this -> InboundHandshakeResult.Rejected(rejection)
      case Right((_, Left(rejection))) =>
        this -> InboundHandshakeResult.Rejected(rejection)
      case Right((_, Right(ack))) =>
        val existing = relationships.get(proposal.initiator)
        val (updatedEngine, result) =
          existing match
            case Some(relationship)
                if relationship.outbound.exists(session =>
                  session.status === DirectionalSessionStatus.Opening &&
                    !relationship.inbound.exists(_.isAlive) &&
                    relationship.peerCorrelationId =!= proposal.peerCorrelationId,
                ) =>
              val localCorrelation = relationship.peerCorrelationId
              if PeerCorrelationId.lexicographicCompare(
                  proposal.peerCorrelationId,
                  localCorrelation,
                ) < 0
              then
                val superseded = relationship.outbound.map(_.sessionId)
                val closedOutbound = relationship.outbound.map:
                  _.copy(status = DirectionalSessionStatus.Closed)
                val inbound = buildAcceptedInboundSession(proposal, ack, now)
                val updatedRelationship = recomputeRelationship(
                  relationship.copy(
                    peerCorrelationId = proposal.peerCorrelationId,
                    outbound = closedOutbound,
                    inbound = Some(inbound),
                  ),
                )
                copy(relationships =
                  relationships
                    .updated(proposal.initiator, updatedRelationship),
                ) ->
                  InboundHandshakeResult.Accepted(ack, superseded)
              else
                this -> InboundHandshakeResult.Rejected(
                  HandshakeRejected(
                    reason = "simultaneousOpenLost",
                    detail = Some(localCorrelation.value),
                  ),
                )
            case Some(relationship) =>
              if relationship.peerCorrelationId =!= proposal.peerCorrelationId && hasAliveDirection(
                  relationship,
                )
              then
                this -> InboundHandshakeResult.Rejected(
                  HandshakeRejected(
                    reason = "peerCorrelationMismatch",
                    detail = Some(
                      ss"expected=${relationship.peerCorrelationId.value} actual=${proposal.peerCorrelationId.value}",
                    ),
                  ),
                )
              else if relationship.inbound.exists(_.isAlive) then
                this -> InboundHandshakeResult.Rejected(
                  HandshakeRejected(
                    reason = "duplicateInboundDirection",
                    detail = Some(proposal.initiator.value),
                  ),
                )
              else
                val inbound = buildAcceptedInboundSession(proposal, ack, now)
                val updatedRelationship = recomputeRelationship(
                  relationship.copy(
                    peerCorrelationId = proposal.peerCorrelationId,
                    inbound = Some(inbound),
                  ),
                )
                copy(relationships =
                  relationships
                    .updated(proposal.initiator, updatedRelationship),
                ) ->
                  InboundHandshakeResult.Accepted(ack, None)
            case None =>
              val inbound = buildAcceptedInboundSession(proposal, ack, now)
              val relationship = recomputeRelationship(
                PeerRelationship.withInbound(inbound),
              )
              copy(relationships =
                relationships.updated(proposal.initiator, relationship),
              ) ->
                InboundHandshakeResult.Accepted(ack, None)
        updatedEngine -> result

  /** Applies a received handshake ack to complete an outbound session open.
    *
    * @param ack
    *   the received acknowledgement
    * @param now
    *   the current timestamp
    * @return
    *   the updated engine, or a rejection
    */
  def applyHandshakeAck(
      ack: SessionOpenAck,
      now: Instant,
  ): Either[HandshakeRejected, GossipSessionEngine] =
    val peer = ack.acceptor
    relationships
      .get(peer)
      .toRight:
        HandshakeRejected(
          reason = "unknownSession",
          detail = Some(ack.sessionId.value),
        )
      .flatMap: relationship =>
        relationship.outbound
          .filter(_.sessionId === ack.sessionId)
          .toRight:
            HandshakeRejected(
              reason = "unknownSession",
              detail = Some(ack.sessionId.value),
            )
          .flatMap: outbound =>
            Either
              .cond(
                outbound.status === DirectionalSessionStatus.Opening,
                (),
                HandshakeRejected(
                  reason = "sessionNotOpening",
                  detail = Some(outbound.sessionId.value),
                ),
              )
              .flatMap: _ =>
                SessionNegotiation
                  .validateAck(outbound.proposal, ack, policy)
                  .map: negotiated =>
                    val updatedOutbound = outbound.copy(
                      peerCorrelationId = ack.peerCorrelationId,
                      negotiated = Some(negotiated),
                      status = DirectionalSessionStatus.Open,
                      openedAt = Some(now),
                      lastActivityAt = now,
                    )
                    val updatedRelationship = recomputeRelationship(
                      relationship.copy(
                        peerCorrelationId = ack.peerCorrelationId,
                        outbound = Some(updatedOutbound),
                      ),
                    )
                    copy(relationships =
                      relationships.updated(peer, updatedRelationship),
                    )

  /** Rejects traffic that arrives on a session still in Opening status, closing
    * the session.
    *
    * @param sessionId
    *   the session that received pre-open traffic
    * @param kind
    *   the kind of traffic received
    * @return
    *   the updated engine and rejection to send, or a rejection if the session
    *   is not found
    */
  def rejectPreOpenTraffic(
      sessionId: DirectionalSessionId,
      kind: PreOpenTrafficKind,
  ): Either[HandshakeRejected, (GossipSessionEngine, HandshakeRejected)] =
    relationships.find(_._2.session(sessionId).nonEmpty) match
      case None =>
        HandshakeRejected(
          reason = "unknownSession",
          detail = Some(sessionId.value),
        ).asLeft[(GossipSessionEngine, HandshakeRejected)]
      case Some((peer, relationship)) =>
        relationship
          .session(sessionId)
          .toRight:
            HandshakeRejected(
              reason = "unknownSession",
              detail = Some(sessionId.value),
            )
          .flatMap:
            case session
                if session.status === DirectionalSessionStatus.Opening =>
              val updatedSession =
                session.copy(status = DirectionalSessionStatus.Closed)
              val updatedRelationship =
                updatedSession.direction match
                  case SessionDirection.Outbound =>
                    recomputeRelationship(
                      relationship.copy(outbound = Some(updatedSession)),
                    )
                  case SessionDirection.Inbound =>
                    recomputeRelationship(
                      relationship.copy(inbound = Some(updatedSession)),
                    )
              val rejection = HandshakeRejected(
                reason = kind match
                  case PreOpenTrafficKind.EventStream => "preOpenEventTraffic"
                  case PreOpenTrafficKind.ControlChannel =>
                    "preOpenControlTraffic"
                ,
                detail = Some(sessionId.value),
              )
              (
                copy(relationships =
                  relationships.updated(peer, updatedRelationship),
                ) -> rejection
              ).asRight[HandshakeRejected]
            case session =>
              HandshakeRejected(
                reason = "sessionNotOpening",
                detail = Some(session.sessionId.value),
              ).asLeft[(GossipSessionEngine, HandshakeRejected)]

  /** Gracefully closes a session.
    *
    * @param sessionId
    *   the session to close
    * @return
    *   the updated engine, or a rejection if the session is already dead
    */
  def closeSession(
      sessionId: DirectionalSessionId,
  ): Either[HandshakeRejected, GossipSessionEngine] =
    updateSession(sessionId): session =>
      session.status match
        case DirectionalSessionStatus.Dead =>
          HandshakeRejected(
            reason = "sessionAlreadyDead",
            detail = Some(session.sessionId.value),
          ).asLeft[DirectionalSession]
        case _ =>
          session
            .copy(status = DirectionalSessionStatus.Closed)
            .asRight[HandshakeRejected]

  /** Marks a session as terminally dead.
    *
    * @param sessionId
    *   the session to mark dead
    * @return
    *   the updated engine, or a rejection if the session is not found
    */
  def markSessionDead(
      sessionId: DirectionalSessionId,
  ): Either[HandshakeRejected, GossipSessionEngine] =
    updateSession(sessionId)(session =>
      session
        .copy(status = DirectionalSessionStatus.Dead)
        .asRight[HandshakeRejected],
    )

  /** Updates the last-activity timestamp for an open session.
    *
    * @param sessionId
    *   the session to touch
    * @param now
    *   the current timestamp
    * @return
    *   the updated engine, or a rejection if the session is not open
    */
  def touchSessionActivity(
      sessionId: DirectionalSessionId,
      now: Instant,
  ): Either[HandshakeRejected, GossipSessionEngine] =
    updateSession(sessionId): session =>
      Either.cond(
        session.status === DirectionalSessionStatus.Open,
        session.copy(lastActivityAt = now),
        HandshakeRejected(
          reason = "sessionNotOpen",
          detail = Some(session.sessionId.value),
        ),
      )

  /** Expires sessions that have been in Opening state beyond the handshake
    * timeout.
    *
    * @param now
    *   the current timestamp
    * @return
    *   the updated engine with expired sessions marked dead
    */
  def expireOpeningHandshakes(now: Instant): GossipSessionEngine =
    expireTimedOutSessions(now)

  /** Expires all sessions that have exceeded their handshake timeout or
    * liveness timeout.
    *
    * @param now
    *   the current timestamp
    * @return
    *   the updated engine with timed-out sessions marked dead
    */
  def expireTimedOutSessions(now: Instant): GossipSessionEngine =
    relationships.values.foldLeft(this):
      case (engine, relationship) =>
        List(relationship.outbound, relationship.inbound).flatten
          .foldLeft(engine):
            case (acc, session) =>
              session.status match
                case DirectionalSessionStatus.Opening
                    if !now.isBefore(
                      session.createdAt.plus(policy.openingHandshakeTimeout),
                    ) =>
                  acc.markSessionDead(session.sessionId) match
                    case Right(updatedEngine) => updatedEngine
                    case Left(_)              => acc
                case DirectionalSessionStatus.Open
                    if session.negotiated.exists(params =>
                      !now.isBefore(
                        session.lastActivityAt.plus(params.livenessTimeout),
                      ),
                    ) =>
                  acc.markSessionDead(session.sessionId) match
                    case Right(updatedEngine) => updatedEngine
                    case Left(_)              => acc
                case _ =>
                  acc

  private def buildAcceptedInboundSession(
      proposal: SessionOpenProposal,
      ack: SessionOpenAck,
      now: Instant,
  ): DirectionalSession =
    DirectionalSession(
      sessionId = proposal.sessionId,
      direction = SessionDirection.Inbound,
      peer = proposal.initiator,
      peerCorrelationId = proposal.peerCorrelationId,
      proposal = proposal,
      negotiated = Some(ack.negotiated),
      status = DirectionalSessionStatus.Open,
      createdAt = now,
      openedAt = Some(now),
      lastActivityAt = now,
    )

  private def hasAliveDirection(relationship: PeerRelationship): Boolean =
    relationship.outbound.exists(_.isAlive) || relationship.inbound.exists(
      _.isAlive,
    )

  private def recomputeRelationship(
      relationship: PeerRelationship,
  ): PeerRelationship =
    val outboundAlive = relationship.outbound.exists(_.isAlive)
    val inboundAlive  = relationship.inbound.exists(_.isAlive)
    val bothOpen =
      relationship.outbound.exists(
        _.status === DirectionalSessionStatus.Open,
      ) &&
        relationship.inbound.exists(_.status === DirectionalSessionStatus.Open)
    val wasBidirectionalOpen = relationship.wasBidirectionalOpen || bothOpen
    val nextStatus =
      if bothOpen then PeerRelationshipStatus.Open
      else if outboundAlive || inboundAlive then
        if wasBidirectionalOpen then PeerRelationshipStatus.HalfOpen
        else PeerRelationshipStatus.Opening
      else if relationship.outbound.exists(
          _.status === DirectionalSessionStatus.Dead,
        ) ||
        relationship.inbound.exists(_.status === DirectionalSessionStatus.Dead)
      then PeerRelationshipStatus.Dead
      else PeerRelationshipStatus.Closed

    relationship.copy(
      status = nextStatus,
      wasBidirectionalOpen = wasBidirectionalOpen,
    )

  private def updateSession(
      sessionId: DirectionalSessionId,
  )(
      transform: DirectionalSession => Either[
        HandshakeRejected,
        DirectionalSession,
      ],
  ): Either[HandshakeRejected, GossipSessionEngine] =
    relationships.find(_._2.session(sessionId).nonEmpty) match
      case None =>
        HandshakeRejected(
          reason = "unknownSession",
          detail = Some(sessionId.value),
        ).asLeft[GossipSessionEngine]
      case Some((peer, relationship)) =>
        relationship
          .session(sessionId)
          .toRight:
            HandshakeRejected(
              reason = "unknownSession",
              detail = Some(sessionId.value),
            )
          .flatMap(transform)
          .map: updatedSession =>
            val updatedRelationship =
              updatedSession.direction match
                case SessionDirection.Outbound =>
                  recomputeRelationship(
                    relationship.copy(outbound = Some(updatedSession)),
                  )
                case SessionDirection.Inbound =>
                  recomputeRelationship(
                    relationship.copy(inbound = Some(updatedSession)),
                  )
            copy(relationships =
              relationships.updated(peer, updatedRelationship),
            )

  private def ensureDirectNeighbor(
      peer: PeerIdentity,
  ): Either[HandshakeRejected, Unit] =
    Either.cond(
      topology.isDirectNeighbor(peer),
      (),
      HandshakeRejected(
        reason = "nonNeighborPeer",
        detail = Some(peer.value),
      ),
    )
