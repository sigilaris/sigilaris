package org.sigilaris.node.jvm.transport.armeria.gossip

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import scala.util.Try

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.syntax.*
import scodec.bits.ByteVector
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.*
/** Server-side Armeria/Tapir adapter for transaction gossip protocol endpoints.
  *
  * Exposes session open, event stream polling, control batch, and disconnect
  * endpoints with transport authentication.
  */
@SuppressWarnings(Array("org.wartremover.warts.Any"))
object TxGossipArmeriaAdapter:
  /** Creates the list of Tapir server endpoints for the transaction gossip protocol.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the gossip artifact type
    * @param runtime
    *   transaction gossip runtime handling session and message logic
    * @param transportAuth
    *   transport authentication for verifying peer requests
    * @return
    *   list of server endpoints for session open, events, control, and disconnect
    */
  def endpoints[F[_]: Async, A: ByteEncoder](
      runtime: TxGossipRuntime[F, A],
      transportAuth: StaticPeerTransportAuth,
  ): List[ServerEndpoint[Any, F]] =
    List(
      sessionOpenEndpoint(runtime, transportAuth),
      eventStreamEndpoint(runtime, transportAuth),
      controlEndpoint(runtime, transportAuth),
      disconnectEndpoint(runtime, transportAuth),
    )

  private def sessionOpenEndpoint[F[_]: Async, A](
      runtime: TxGossipRuntime[F, A],
      transportAuth: StaticPeerTransportAuth,
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "session" / "open")
      .in(
        header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName),
      )
      .in(header[Option[String]](GossipTransportAuth.TransportProofHeaderName))
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: (authenticatedPeerRaw, transportProofRaw, raw) =>
        authenticateRequest(
          transportAuth = transportAuth,
          authenticatedPeerRaw = authenticatedPeerRaw,
          transportProofRaw = transportProofRaw,
          requestPath = "/gossip/session/open",
          requestBody = raw,
        ) match
          case Left(rejection) =>
            Async[F].pure(renderRejection(rejection).asLeft[String])
          case Right(authenticatedPeer) =>
            decodeOrHandshakeReject[SessionOpenProposalWire](
              raw,
              "invalidSessionOpenProposal",
            ) match
              case Left(rendered) =>
                Async[F].pure(rendered.asLeft[String])
              case Right(wire) =>
                toProposal(wire) match
                  case Left(rejection) =>
                    Async[F].pure(renderRejection(rejection).asLeft[String])
                  case Right(proposal) =>
                    runtime
                      .handleInboundProposalFromPeer(
                        proposal = proposal,
                        authenticatedPeer = authenticatedPeer,
                      )
                      .map:
                        case accepted: InboundHandshakeResult.Accepted =>
                          toAckWire(accepted.ack).asJson.noSpaces
                            .asRight[String]
                        case rejected: InboundHandshakeResult.Rejected =>
                          renderRejection(rejected.rejection).asLeft[String]

  private def eventStreamEndpoint[F[_]: Async, A: ByteEncoder](
      runtime: TxGossipRuntime[F, A],
      transportAuth: StaticPeerTransportAuth,
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "events" / path[String]("sessionId"))
      .in(
        header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName),
      )
      .in(header[Option[String]](GossipTransportAuth.TransportProofHeaderName))
      .in(stringBody)
      .out(byteArrayBody)
      .serverLogicSuccess:
        (sessionIdRaw, authenticatedPeerRaw, transportProofRaw, raw) =>
          handleEventRequest(
            runtime = runtime,
            transportAuth = transportAuth,
            authenticatedPeerRaw = authenticatedPeerRaw,
            transportProofRaw = transportProofRaw,
            sessionIdRaw = sessionIdRaw,
            raw = raw,
          )

  private def controlEndpoint[F[_]: Async, A](
      runtime: TxGossipRuntime[F, A],
      transportAuth: StaticPeerTransportAuth,
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "control" / path[String]("sessionId"))
      .in(
        header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName),
      )
      .in(header[Option[String]](GossipTransportAuth.TransportProofHeaderName))
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic:
        (sessionIdRaw, authenticatedPeerRaw, transportProofRaw, raw) =>
          handleControlRequest(
            runtime = runtime,
            transportAuth = transportAuth,
            authenticatedPeerRaw = authenticatedPeerRaw,
            transportProofRaw = transportProofRaw,
            sessionIdRaw = sessionIdRaw,
            raw = raw,
          )

  private def disconnectEndpoint[F[_]: Async, A](
      runtime: TxGossipRuntime[F, A],
      transportAuth: StaticPeerTransportAuth,
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "session" / path[String]("sessionId") / "disconnect")
      .in(
        header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName),
      )
      .in(header[Option[String]](GossipTransportAuth.TransportProofHeaderName))
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: (sessionIdRaw, authenticatedPeerRaw, transportProofRaw) =>
        DirectionalSessionId.parse(sessionIdRaw) match
          case Left(error) =>
            Async[F].pure(
              renderRejection(handshakeRejected("invalidSessionId", error))
                .asLeft[String],
            )
          case Right(sessionId) =>
            authenticateRequest(
              transportAuth = transportAuth,
              authenticatedPeerRaw = authenticatedPeerRaw,
              transportProofRaw = transportProofRaw,
              requestPath = s"/gossip/session/${sessionIdRaw}/disconnect",
              requestBody = "",
            ) match
              case Left(rejection) =>
                Async[F].pure(renderRejection(rejection).asLeft[String])
              case Right(authenticatedPeer) =>
                runtime
                  .authorizeSessionPeer(sessionId, authenticatedPeer)
                  .flatMap:
                    case Left(rejection) =>
                      renderRejection(rejection).asLeft[String].pure[F]
                    case Right(_) =>
                      runtime
                        .markSessionDead(sessionId)
                        .map(_.leftMap(renderRejection).map(_ => "ok"))

  private def handleEventRequest[F[_]: Async, A: ByteEncoder](
      runtime: TxGossipRuntime[F, A],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeerRaw: Option[String],
      transportProofRaw: Option[String],
      sessionIdRaw: String,
      raw: String,
  ): F[Array[Byte]] =
    DirectionalSessionId.parse(sessionIdRaw) match
      case Left(error) =>
        (BinaryEventStreamCodec.encodeBinary:
          Vector(
            eventRejection(
              sessionIdRaw,
              handshakeRejected("invalidSessionId", error),
            ),
          )
        ).pure[F]
      case Right(sessionId) =>
        authenticateRequest(
          transportAuth = transportAuth,
          authenticatedPeerRaw = authenticatedPeerRaw,
          transportProofRaw = transportProofRaw,
          requestPath = s"/gossip/events/${sessionIdRaw}",
          requestBody = raw,
        ) match
          case Left(rendered) =>
            (BinaryEventStreamCodec.encodeBinary:
              Vector(eventRejection(sessionId.value, rendered))
            ).pure[F]
          case Right(authenticatedPeer) =>
            runtime
              .authorizeSessionPeer(sessionId, authenticatedPeer)
              .flatMap:
                case Left(rejection) =>
                  (BinaryEventStreamCodec.encodeBinary:
                    Vector(eventRejection(sessionId.value, rejection))
                  ).pure[F]
                case Right(_) =>
                  decodeOrRejectionEvent[EventRequestWire](
                    sessionId,
                    raw,
                    "invalidEventRequest",
                  ) match
                    case Left(rendered) =>
                      Async[F].pure(rendered)
                    case Right(request) =>
                      request.kind match
                        case "poll" =>
                          runtime
                            .pollEvents(sessionId)
                            .flatMap:
                              case Left(rejection) =>
                                (BinaryEventStreamCodec.encodeBinary:
                                  Vector(eventRejection(sessionId.value, rejection))
                                ).pure[F]
                              case Right(messages) if messages.nonEmpty =>
                                (BinaryEventStreamCodec.encodeBinary:
                                  messages
                                    .map(toBinaryEventEnvelope(sessionId, _))
                                ).pure[F]
                              case Right(_) =>
                                runtime
                                  .eventKeepAlive(sessionId)
                                  .map:
                                    case Left(rejection) =>
                                      BinaryEventStreamCodec.encodeBinary:
                                        Vector(
                                          eventRejection(sessionId.value, rejection),
                                        )
                                    case Right(message) =>
                                      BinaryEventStreamCodec.encodeBinary:
                                        Vector(toBinaryEventEnvelope(sessionId, message))
                        case "eventKeepAlive" =>
                          runtime
                            .eventKeepAlive(sessionId)
                            .map:
                              case Left(rejection) =>
                                BinaryEventStreamCodec.encodeBinary:
                                  Vector(eventRejection(sessionId.value, rejection))
                              case Right(message) =>
                                BinaryEventStreamCodec.encodeBinary:
                                  Vector(toBinaryEventEnvelope(sessionId, message))
                        case "controlKeepAlive" =>
                          (BinaryEventStreamCodec.encodeBinary:
                            Vector(
                              eventRejection(
                                sessionId.value,
                                controlRejected(
                                  "wrongChannelMessageKind",
                                  "controlKeepAlive",
                                ),
                              ),
                            )
                          ).pure[F]
                        case other =>
                          (BinaryEventStreamCodec.encodeBinary:
                            Vector(
                              eventRejection(
                                sessionId.value,
                                handshakeRejected(
                                  "unknownEventRequestKind",
                                  other,
                                ),
                              ),
                            )
                          ).pure[F]

  private def handleControlRequest[F[_]: Async, A](
      runtime: TxGossipRuntime[F, A],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeerRaw: Option[String],
      transportProofRaw: Option[String],
      sessionIdRaw: String,
      raw: String,
  ): F[Either[String, String]] =
    DirectionalSessionId.parse(sessionIdRaw) match
      case Left(error) =>
        renderRejection(handshakeRejected("invalidSessionId", error))
          .asLeft[String]
          .pure[F]
      case Right(sessionId) =>
        authenticateRequest(
          transportAuth = transportAuth,
          authenticatedPeerRaw = authenticatedPeerRaw,
          transportProofRaw = transportProofRaw,
          requestPath = s"/gossip/control/${sessionIdRaw}",
          requestBody = raw,
        ) match
          case Left(rendered) =>
            Async[F].pure(renderRejection(rendered).asLeft[String])
          case Right(authenticatedPeer) =>
            runtime
              .authorizeSessionPeer(sessionId, authenticatedPeer)
              .flatMap:
                case Left(rejection) =>
                  renderRejection(rejection).asLeft[String].pure[F]
                case Right(_) =>
                  decodeOrControlReject[ControlRequestWire](
                    raw,
                    "invalidControlRequest",
                  ) match
                    case Left(rendered) =>
                      Async[F].pure(rendered.asLeft[String])
                    case Right(request) =>
                      request.kind match
                        case "batch" =>
                          request.batch match
                            case None =>
                              renderRejection(
                                controlRejected("missingControlBatch", "batch"),
                              ).asLeft[String].pure[F]
                            case Some(batchWire) =>
                              toControlBatch(batchWire) match
                                case Left(rejection) =>
                                  renderRejection(rejection)
                                    .asLeft[String]
                                    .pure[F]
                                case Right(batch) =>
                                  runtime
                                    .receiveControlBatch(sessionId, batch)
                                    .map:
                                      case Left(rejection) =>
                                        renderRejection(rejection)
                                          .asLeft[String]
                                      case Right(ControlBatchOutcome.Applied) =>
                                        ControlResponseWire(
                                          status = "applied",
                                          sessionId = Some(sessionId.value),
                                          deduplicated = Some(false),
                                        ).asJson.noSpaces.asRight[String]
                                      case Right(
                                            ControlBatchOutcome.Deduplicated,
                                          ) =>
                                        ControlResponseWire(
                                          status = "deduplicated",
                                          sessionId = Some(sessionId.value),
                                          deduplicated = Some(true),
                                        ).asJson.noSpaces.asRight[String]
                        case "controlKeepAlive" =>
                          runtime
                            .controlKeepAlive(sessionId)
                            .map:
                              case Left(rejection) =>
                                renderRejection(rejection).asLeft[String]
                              case Right(
                                    ControlChannelMessage.Ack(ackSessionId, _),
                                  ) =>
                                ControlResponseWire(
                                  status = "ack",
                                  sessionId = Some(ackSessionId.value),
                                ).asJson.noSpaces.asRight[String]
                              case Right(_) =>
                                ControlResponseWire(
                                  status = "ack",
                                  sessionId = Some(sessionId.value),
                                ).asJson.noSpaces.asRight[String]
                        case "eventKeepAlive" =>
                          Async[F].pure(
                            renderRejection(
                              controlRejected(
                                "wrongChannelMessageKind",
                                "eventKeepAlive",
                              ),
                            ).asLeft[String],
                          )
                        case other =>
                          Async[F].pure(
                            renderRejection(
                              controlRejected(
                                "unknownControlRequestKind",
                                other,
                              ),
                            ).asLeft[String],
                          )

  private def decodeOrHandshakeReject[A: Decoder](
      raw: String,
      reason: String,
  ): Either[String, A] =
    decode[A](raw).leftMap(error =>
      renderRejection(handshakeRejected(reason, error.getMessage)),
    )

  private def decodeOrControlReject[A: Decoder](
      raw: String,
      reason: String,
  ): Either[String, A] =
    decode[A](raw).leftMap(error =>
      renderRejection(controlRejected(reason, error.getMessage)),
    )

  private def decodeOrRejectionEvent[B: Decoder](
      sessionId: DirectionalSessionId,
      raw: String,
      reason: String,
  ): Either[Array[Byte], B] =
    decode[B](raw).leftMap: error =>
      BinaryEventStreamCodec.encodeBinary:
        Vector(
          eventRejection(
            sessionId.value,
            handshakeRejected(reason, error.getMessage),
          ),
        )

  private def authenticateRequest(
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeerRaw: Option[String],
      transportProofRaw: Option[String],
      requestPath: String,
      requestBody: String,
  ): Either[CanonicalRejection.HandshakeRejected, PeerIdentity] =
    GossipTransportAuth.authenticateRequest(
      transportAuth = transportAuth,
      authenticatedPeerRaw = authenticatedPeerRaw,
      transportProofRaw = transportProofRaw,
      httpMethod = "POST",
      requestPath = requestPath,
      requestBodyBytes = requestBody.getBytes(StandardCharsets.UTF_8),
    )

  private def toProposal(
      wire: SessionOpenProposalWire,
  ): Either[CanonicalRejection.HandshakeRejected, SessionOpenProposal] =
    for
      sessionId <- DirectionalSessionId
        .parse(wire.sessionId)
        .leftMap(handshakeRejected("invalidSessionId", _))
      correlationId <- PeerCorrelationId
        .parse(wire.peerCorrelationId)
        .leftMap(
          handshakeRejected("invalidPeerCorrelationId", _),
        )
      initiator <- PeerIdentity
        .parse(wire.initiator)
        .leftMap(handshakeRejected("invalidInitiator", _))
      acceptor <- PeerIdentity
        .parse(wire.acceptor)
        .leftMap(handshakeRejected("invalidAcceptor", _))
      subscriptions <- wire.subscriptions
        .traverse(toChainTopic)
        .flatMap: values =>
          SessionSubscription
            .fromSet(values.toSet)
            .leftMap(handshakeRejected("invalidSubscription", _))
    yield SessionOpenProposal(
      sessionId = sessionId,
      peerCorrelationId = correlationId,
      initiator = initiator,
      acceptor = acceptor,
      subscriptions = subscriptions,
      heartbeatInterval = wire.heartbeatIntervalMs.map(Duration.ofMillis),
      livenessTimeout = wire.livenessTimeoutMs.map(Duration.ofMillis),
      maxControlRetryInterval =
        wire.maxControlRetryIntervalMs.map(Duration.ofMillis),
    )

  private def toAckWire(
      ack: SessionOpenAck,
  ): SessionOpenAckWire =
    SessionOpenAckWire(
      sessionId = ack.sessionId.value,
      peerCorrelationId = ack.peerCorrelationId.value,
      initiator = ack.initiator.value,
      acceptor = ack.acceptor.value,
      subscriptions = ack.subscriptions.values.toVector.map(toChainTopicWire),
      heartbeatIntervalMs = ack.negotiated.heartbeatInterval.toMillis,
      livenessTimeoutMs = ack.negotiated.livenessTimeout.toMillis,
      maxControlRetryIntervalMs =
        ack.negotiated.maxControlRetryInterval.toMillis,
    )

  private def toControlBatch(
      wire: ControlBatchWire,
  ): Either[CanonicalRejection.ControlBatchRejected, ControlBatch] =
    wire.ops
      .traverse(toControlOp)
      .flatMap: ops =>
        ControlBatch.create(
          idempotencyKey = wire.idempotencyKey,
          ops = ops,
        )

  private def toControlOp(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, ControlOp] =
    ControlOpKind
      .parse(wire.kind)
      .flatMap:
        case ControlOpKind.SetFilter =>
          for
            chainId <- requiredChainId(wire)
            topic   <- requiredTopic(wire)
            filter  <- requiredFilter(wire)
          yield ControlOp.SetFilter(chainId, topic, filter)
        case ControlOpKind.SetKnownTx =>
          requiredChainId(wire).flatMap: chainId =>
            requiredIds(wire).map(ids => ControlOp.SetKnownTx(chainId, ids))
        case ControlOpKind.SetKnownExact =>
          requiredExactKnownScope(wire).flatMap: scope =>
            requiredIds(wire).map(ids => ControlOp.SetKnownExact(scope, ids))
        case ControlOpKind.SetCursor =>
          requiredCursorEntries(wire).flatMap(entries =>
            toCompositeCursor(entries).map(ControlOp.SetCursor(_)),
          )
        case ControlOpKind.Nack =>
          for
            chainId     <- requiredChainId(wire)
            topic       <- requiredTopic(wire)
            cursorToken <- optionalCursorToken(wire)
          yield ControlOp.Nack(chainId, topic, cursorToken)
        case ControlOpKind.RequestByIdTx =>
          requiredChainId(wire).flatMap: chainId =>
            requiredIds(wire).map(ids => ControlOp.RequestByIdTx(chainId, ids))
        case ControlOpKind.RequestByIdExact =>
          requiredExactKnownScope(wire).flatMap: scope =>
            requiredIds(wire).map(ids => ControlOp.RequestByIdExact(scope, ids))
        case ControlOpKind.Config =>
          wire.config
            .toRight(controlRejected("missingConfigValues", "config"))
            .flatMap: config =>
              config.toVector
                .traverse:
                  case (key, value) =>
                    SessionConfigKey.parse(key).map(_ -> value)
                .map(entries => ControlOp.Config(entries.toMap))

  private def toCompositeCursor(
      entries: Vector[CursorEntryWire],
  ): Either[CanonicalRejection.ControlBatchRejected, CompositeCursor] =
    entries
      .traverse: entry =>
        for
          chainId <- ChainId
            .parse(entry.chainId)
            .leftMap(controlRejected("invalidChainId", _))
          topic <- GossipTopic
            .parse(entry.topic)
            .leftMap(controlRejected("invalidTopic", _))
          token <- CursorToken
            .decodeBase64Url(entry.token)
            .leftMap(controlRejected("invalidCursor", _))
        yield ChainTopic(chainId, topic) -> token
      .map(entries => CompositeCursor(entries.toMap))

  private def requiredChainId(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, ChainId] =
    wire.chainId
      .toRight(controlRejected("missingChainId", wire.kind))
      .flatMap: value =>
        ChainId.parse(value).leftMap(controlRejected("invalidChainId", _))

  private def requiredTopic(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, GossipTopic] =
    wire.topic
      .toRight(controlRejected("missingTopic", wire.kind))
      .flatMap: value =>
        GossipTopic.parse(value).leftMap(controlRejected("invalidTopic", _))

  private def requiredIds(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, Vector[StableArtifactId]] =
    wire.ids
      .toRight(controlRejected("missingIds", wire.kind))
      .flatMap: values =>
        values.traverse(value =>
          StableArtifactId
            .fromHex(value)
            .leftMap(controlRejected("invalidStableId", _)),
        )

  private def requiredExactKnownScope(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, ExactKnownSetScope] =
    for
      chainId <- requiredChainId(wire)
      topic   <- requiredTopic(wire)
      windowKey <- wire.windowKey
        .toRight(controlRejected("missingWindowKey", wire.kind))
        .flatMap(value =>
          TopicWindowKey
            .fromHex(value)
            .leftMap(controlRejected("invalidWindowKey", _)),
        )
    yield ExactKnownSetScope(chainId, topic, windowKey)

  private def requiredCursorEntries(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, Vector[CursorEntryWire]] =
    wire.cursor.toRight(controlRejected("missingCursor", wire.kind))

  private def optionalCursorToken(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, Option[CursorToken]] =
    wire.cursorToken.traverse(value =>
      CursorToken
        .decodeBase64Url(value)
        .leftMap(controlRejected("invalidCursor", _)),
    )

  private def requiredFilter(
      wire: ControlOpWire,
  ): Either[
    CanonicalRejection.ControlBatchRejected,
    GossipFilter.TxBloomFilter,
  ] =
    wire.filter
      .toRight(controlRejected("missingFilter", wire.kind))
      .flatMap: filter =>
        Try(
          ByteVector.view(Base64.getUrlDecoder.decode(filter.bitsetBase64Url)),
        ).toEither
          .leftMap(_ =>
            controlRejected("invalidFilterBitset", filter.bitsetBase64Url),
          )
          .map: bytes =>
            GossipFilter.TxBloomFilter(
              bitset = bytes,
              numHashes = filter.numHashes,
              hashFamilyId = filter.hashFamilyId,
            )

  private def toChainTopic(
      wire: ChainTopicWire,
  ): Either[CanonicalRejection.HandshakeRejected, ChainTopic] =
    for
      chainId <- ChainId
        .parse(wire.chainId)
        .leftMap(handshakeRejected("invalidChainId", _))
      topic <- GossipTopic
        .parse(wire.topic)
        .leftMap(handshakeRejected("invalidTopic", _))
    yield ChainTopic(chainId, topic)

  private def toChainTopicWire(
      chainTopic: ChainTopic,
  ): ChainTopicWire =
    ChainTopicWire(
      chainId = chainTopic.chainId.value,
      topic = chainTopic.topic.value,
    )

  private def toBinaryEventEnvelope[A: ByteEncoder](
      sessionId: DirectionalSessionId,
      message: EventStreamMessage[A],
  ): BinaryEventEnvelope =
    message match
      case EventStreamMessage.Event(event) =>
        BinaryEventEnvelope.Event(
          sessionId = sessionId.value,
          chainId = event.chainId,
          topic = event.topic,
          id = event.id,
          cursor = event.cursor,
          tsEpochMs = event.ts.toEpochMilli,
          payloadBytes = ByteEncoder[A].encode(event.payload),
        )
      case EventStreamMessage.KeepAlive(sessionId, at) =>
        BinaryEventEnvelope.KeepAlive(
          sessionId = sessionId.value,
          atEpochMs = at.toEpochMilli,
        )
      case EventStreamMessage.Rejection(rejection) =>
        eventRejection(sessionId.value, rejection)

  private def eventRejection(
      sessionId: String,
      rejection: CanonicalRejection,
  ): BinaryEventEnvelope =
    BinaryEventEnvelope.Rejection(
      sessionId = sessionId,
      rejection = RejectionWire.fromCanonical(rejection),
    )

  private def renderRejection(
      rejection: CanonicalRejection,
  ): String =
    RejectionWire.fromCanonical(rejection).asJson.noSpaces

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
