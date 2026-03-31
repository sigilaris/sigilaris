package org.sigilaris.node.jvm.runtime.gossip

import java.time.Instant

import org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection.HandshakeRejected

enum SessionDirection:
  case Outbound, Inbound

enum DirectionalSessionStatus:
  case Opening, Open, Closed, Dead

enum PeerRelationshipStatus:
  case Opening, Open, HalfOpen, Closed, Dead

enum PreOpenTrafficKind:
  case EventStream, ControlChannel

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
):
  def isAlive: Boolean =
    status == DirectionalSessionStatus.Open || status == DirectionalSessionStatus.Opening

final case class PeerRelationship(
    peer: PeerIdentity,
    peerCorrelationId: PeerCorrelationId,
    outbound: Option[DirectionalSession],
    inbound: Option[DirectionalSession],
    status: PeerRelationshipStatus,
    wasBidirectionalOpen: Boolean,
):
  def session(sessionId: DirectionalSessionId): Option[DirectionalSession] =
    List(outbound, inbound).flatten.find(_.sessionId == sessionId)

object PeerRelationship:
  def openingWithOutbound(session: DirectionalSession): PeerRelationship =
    PeerRelationship(
      peer = session.peer,
      peerCorrelationId = session.peerCorrelationId,
      outbound = Some(session),
      inbound = None,
      status = PeerRelationshipStatus.Opening,
      wasBidirectionalOpen = false,
    )

  def withInbound(session: DirectionalSession): PeerRelationship =
    PeerRelationship(
      peer = session.peer,
      peerCorrelationId = session.peerCorrelationId,
      outbound = None,
      inbound = Some(session),
      status = PeerRelationshipStatus.Opening,
      wasBidirectionalOpen = false,
    )

sealed trait InboundHandshakeResult

object InboundHandshakeResult:
  final case class Accepted(
      ack: SessionOpenAck,
      supersededSessionId: Option[DirectionalSessionId],
  ) extends InboundHandshakeResult

  final case class Rejected(
      rejection: CanonicalRejection.HandshakeRejected,
  ) extends InboundHandshakeResult

final case class GossipSessionEngine(
    localPeer: PeerIdentity,
    topology: StaticPeerTopology,
    policy: HandshakePolicy = HandshakePolicy.default,
    relationships: Map[PeerIdentity, PeerRelationship] = Map.empty,
):
  def relationshipWith(peer: PeerIdentity): Option[PeerRelationship] =
    relationships.get(peer)

  def sessionById(sessionId: DirectionalSessionId): Option[DirectionalSession] =
    relationships.valuesIterator.flatMap(r => List(r.outbound, r.inbound).flatten).find(_.sessionId == sessionId)

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
      Either.cond(
        !existingOutboundAlive,
        (),
        HandshakeRejected(
          reason = "duplicateOutboundDirection",
          detail = Some(peer.value),
        ),
      ).flatMap: _ =>
        val chosenCorrelationId =
          peerCorrelationId
            .orElse(
              existing
                .filter: relationship =>
                  relationship.status == PeerRelationshipStatus.Opening ||
                    relationship.status == PeerRelationshipStatus.Open ||
                    relationship.status == PeerRelationshipStatus.HalfOpen
                .map(_.peerCorrelationId)
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
        SessionNegotiation.resolveProposal(proposal, policy).map: _ =>
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
            copy(relationships = relationships.updated(peer, updatedRelationship)),
            proposal,
          )

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
          proposal.acceptor == localPeer,
          (),
          HandshakeRejected(
            reason = "wrongAcceptor",
            detail = Some(s"expected=${localPeer.value} actual=${proposal.acceptor.value}"),
          ),
        )
        _ <- ensureDirectNeighbor(proposal.initiator)
        proposed <- SessionNegotiation.resolveProposal(proposal, policy)
      yield
        val ack = SessionNegotiation.acknowledge(
          proposal = proposal,
          heartbeatInterval = heartbeatInterval.getOrElse(proposed.heartbeatInterval),
          livenessTimeout = livenessTimeout.getOrElse(proposed.livenessTimeout),
          maxControlRetryInterval = maxControlRetryInterval.getOrElse(proposed.maxControlRetryInterval),
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
                if relationship.outbound.exists(
                  session =>
                    session.status == DirectionalSessionStatus.Opening &&
                      !relationship.inbound.exists(_.isAlive) &&
                      relationship.peerCorrelationId != proposal.peerCorrelationId,
                ) =>
              val localCorrelation = relationship.peerCorrelationId
              if PeerCorrelationId.lexicographicCompare(proposal.peerCorrelationId, localCorrelation) < 0 then
                val superseded = relationship.outbound.map(_.sessionId)
                val closedOutbound = relationship.outbound.map:
                  _.copy(status = DirectionalSessionStatus.Closed)
                val inbound = buildAcceptedInboundSession(proposal, ack, now)
                val updatedRelationship = recomputeRelationship(
                  relationship.copy(
                    peerCorrelationId = proposal.peerCorrelationId,
                    outbound = closedOutbound,
                    inbound = Some(inbound),
                  )
                )
                copy(relationships = relationships.updated(proposal.initiator, updatedRelationship)) ->
                  InboundHandshakeResult.Accepted(ack, superseded)
              else
                this -> InboundHandshakeResult.Rejected(
                  HandshakeRejected(
                    reason = "simultaneousOpenLost",
                    detail = Some(localCorrelation.value),
                  )
                )
            case Some(relationship) =>
              if relationship.peerCorrelationId != proposal.peerCorrelationId && hasAliveDirection(relationship) then
                this -> InboundHandshakeResult.Rejected(
                  HandshakeRejected(
                    reason = "peerCorrelationMismatch",
                    detail = Some(
                      s"expected=${relationship.peerCorrelationId.value} actual=${proposal.peerCorrelationId.value}"
                    ),
                  )
                )
              else if relationship.inbound.exists(_.isAlive) then
                this -> InboundHandshakeResult.Rejected(
                  HandshakeRejected(
                    reason = "duplicateInboundDirection",
                    detail = Some(proposal.initiator.value),
                  )
                )
              else
                val inbound = buildAcceptedInboundSession(proposal, ack, now)
                val updatedRelationship = recomputeRelationship(
                  relationship.copy(
                    peerCorrelationId = proposal.peerCorrelationId,
                    inbound = Some(inbound),
                  )
                )
                copy(relationships = relationships.updated(proposal.initiator, updatedRelationship)) ->
                  InboundHandshakeResult.Accepted(ack, None)
            case None =>
              val inbound = buildAcceptedInboundSession(proposal, ack, now)
              val relationship = recomputeRelationship(PeerRelationship.withInbound(inbound))
              copy(relationships = relationships.updated(proposal.initiator, relationship)) ->
                InboundHandshakeResult.Accepted(ack, None)
        updatedEngine -> result

  def applyHandshakeAck(
      ack: SessionOpenAck,
      now: Instant,
  ): Either[HandshakeRejected, GossipSessionEngine] =
    val peer = ack.acceptor
    relationships
      .get(peer)
      .toRight(
        HandshakeRejected(
          reason = "unknownSession",
          detail = Some(ack.sessionId.value),
        )
      )
      .flatMap: relationship =>
        relationship.outbound
          .filter(_.sessionId == ack.sessionId)
          .toRight(
            HandshakeRejected(
              reason = "unknownSession",
              detail = Some(ack.sessionId.value),
            )
          )
          .flatMap: outbound =>
            Either.cond(
              outbound.status == DirectionalSessionStatus.Opening,
              (),
              HandshakeRejected(
                reason = "sessionNotOpening",
                detail = Some(outbound.sessionId.value),
              ),
            ).flatMap: _ =>
              SessionNegotiation.validateAck(outbound.proposal, ack, policy).map: negotiated =>
                val updatedOutbound = outbound.copy(
                  peerCorrelationId = ack.peerCorrelationId,
                  negotiated = Some(negotiated),
                  status = DirectionalSessionStatus.Open,
                  openedAt = Some(now),
                )
                val updatedRelationship = recomputeRelationship(
                  relationship.copy(
                    peerCorrelationId = ack.peerCorrelationId,
                    outbound = Some(updatedOutbound),
                  )
                )
                copy(relationships = relationships.updated(peer, updatedRelationship))

  def rejectPreOpenTraffic(
      sessionId: DirectionalSessionId,
      kind: PreOpenTrafficKind,
  ): Either[HandshakeRejected, (GossipSessionEngine, HandshakeRejected)] =
    relationships.find(_._2.session(sessionId).nonEmpty) match
      case None =>
        Left(
          HandshakeRejected(
            reason = "unknownSession",
            detail = Some(sessionId.value),
          )
        )
      case Some((peer, relationship)) =>
        relationship.session(sessionId).toRight(
          HandshakeRejected(
            reason = "unknownSession",
            detail = Some(sessionId.value),
          )
        ).flatMap:
          case session if session.status == DirectionalSessionStatus.Opening =>
            val updatedSession = session.copy(status = DirectionalSessionStatus.Closed)
            val updatedRelationship =
              updatedSession.direction match
                case SessionDirection.Outbound =>
                  recomputeRelationship(relationship.copy(outbound = Some(updatedSession)))
                case SessionDirection.Inbound =>
                  recomputeRelationship(relationship.copy(inbound = Some(updatedSession)))
            val rejection = HandshakeRejected(
              reason = kind match
                case PreOpenTrafficKind.EventStream    => "preOpenEventTraffic"
                case PreOpenTrafficKind.ControlChannel => "preOpenControlTraffic",
              detail = Some(sessionId.value),
            )
            Right(copy(relationships = relationships.updated(peer, updatedRelationship)) -> rejection)
          case session =>
            Left(
              HandshakeRejected(
                reason = "sessionNotOpening",
                detail = Some(session.sessionId.value),
              )
            )

  def closeSession(sessionId: DirectionalSessionId): Either[HandshakeRejected, GossipSessionEngine] =
    updateSession(sessionId):
      session =>
        session.status match
          case DirectionalSessionStatus.Dead =>
            Left(
              HandshakeRejected(
                reason = "sessionAlreadyDead",
                detail = Some(session.sessionId.value),
              )
            )
          case _ =>
            Right(session.copy(status = DirectionalSessionStatus.Closed))

  def markSessionDead(sessionId: DirectionalSessionId): Either[HandshakeRejected, GossipSessionEngine] =
    updateSession(sessionId)(session => Right(session.copy(status = DirectionalSessionStatus.Dead)))

  def expireOpeningHandshakes(now: Instant): GossipSessionEngine =
    relationships.values.foldLeft(this):
      case (engine, relationship) =>
        List(relationship.outbound, relationship.inbound).flatten.foldLeft(engine):
          case (acc, session) =>
            if session.status == DirectionalSessionStatus.Opening &&
                !now.isBefore(session.createdAt.plus(policy.openingHandshakeTimeout))
            then
              acc.markSessionDead(session.sessionId).getOrElse(acc)
            else acc

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
    )

  private def hasAliveDirection(relationship: PeerRelationship): Boolean =
    relationship.outbound.exists(_.isAlive) || relationship.inbound.exists(_.isAlive)

  private def recomputeRelationship(
      relationship: PeerRelationship,
  ): PeerRelationship =
    val outboundAlive = relationship.outbound.exists(_.isAlive)
    val inboundAlive = relationship.inbound.exists(_.isAlive)
    val bothOpen = relationship.outbound.exists(_.status == DirectionalSessionStatus.Open) &&
      relationship.inbound.exists(_.status == DirectionalSessionStatus.Open)
    val wasBidirectionalOpen = relationship.wasBidirectionalOpen || bothOpen
    val nextStatus =
      if bothOpen then PeerRelationshipStatus.Open
      else if outboundAlive || inboundAlive then
        if wasBidirectionalOpen then PeerRelationshipStatus.HalfOpen
        else PeerRelationshipStatus.Opening
      else if relationship.outbound.exists(_.status == DirectionalSessionStatus.Dead) ||
          relationship.inbound.exists(_.status == DirectionalSessionStatus.Dead)
      then PeerRelationshipStatus.Dead
      else PeerRelationshipStatus.Closed

    relationship.copy(
      status = nextStatus,
      wasBidirectionalOpen = wasBidirectionalOpen,
    )

  private def updateSession(
      sessionId: DirectionalSessionId,
  )(
      transform: DirectionalSession => Either[HandshakeRejected, DirectionalSession],
  ): Either[HandshakeRejected, GossipSessionEngine] =
    relationships.find(_._2.session(sessionId).nonEmpty) match
      case None =>
        Left(
          HandshakeRejected(
            reason = "unknownSession",
            detail = Some(sessionId.value),
          )
        )
      case Some((peer, relationship)) =>
        relationship.session(sessionId).toRight(
          HandshakeRejected(
            reason = "unknownSession",
            detail = Some(sessionId.value),
          )
        ).flatMap(transform).map:
          updatedSession =>
            val updatedRelationship =
              updatedSession.direction match
                case SessionDirection.Outbound =>
                  recomputeRelationship(relationship.copy(outbound = Some(updatedSession)))
                case SessionDirection.Inbound =>
                  recomputeRelationship(relationship.copy(inbound = Some(updatedSession)))
            copy(relationships = relationships.updated(peer, updatedRelationship))

  private def ensureDirectNeighbor(peer: PeerIdentity): Either[HandshakeRejected, Unit] =
    Either.cond(
      topology.isDirectNeighbor(peer),
      (),
      HandshakeRejected(
        reason = "nonNeighborPeer",
        detail = Some(peer.value),
      ),
    )

extension [A, B](either: Either[A, B])
  private[gossip] def getOrElse(default: => B): B =
    either match
      case Right(value) => value
      case Left(_)      => default
