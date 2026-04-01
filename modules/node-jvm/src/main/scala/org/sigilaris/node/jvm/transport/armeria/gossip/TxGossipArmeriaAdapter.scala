package org.sigilaris.node.jvm.transport.armeria.gossip

import java.time.Duration
import java.util.Base64
import scala.util.Try

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.Json
import scodec.bits.ByteVector
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.*

final case class ChainTopicWire(
    chainId: String,
    topic: String,
)
object ChainTopicWire:
  given Decoder[ChainTopicWire] = deriveDecoder
  given Encoder[ChainTopicWire] = deriveEncoder

final case class SessionOpenProposalWire(
    sessionId: String,
    peerCorrelationId: String,
    initiator: String,
    acceptor: String,
    subscriptions: Vector[ChainTopicWire],
    heartbeatIntervalMs: Option[Long],
    livenessTimeoutMs: Option[Long],
    maxControlRetryIntervalMs: Option[Long],
)
object SessionOpenProposalWire:
  given Decoder[SessionOpenProposalWire] = deriveDecoder
  given Encoder[SessionOpenProposalWire] = deriveEncoder

final case class SessionOpenAckWire(
    sessionId: String,
    peerCorrelationId: String,
    initiator: String,
    acceptor: String,
    subscriptions: Vector[ChainTopicWire],
    heartbeatIntervalMs: Long,
    livenessTimeoutMs: Long,
    maxControlRetryIntervalMs: Long,
)
object SessionOpenAckWire:
  given Decoder[SessionOpenAckWire] = deriveDecoder
  given Encoder[SessionOpenAckWire] = deriveEncoder

final case class CursorEntryWire(
    chainId: String,
    topic: String,
    token: String,
)
object CursorEntryWire:
  given Decoder[CursorEntryWire] = deriveDecoder
  given Encoder[CursorEntryWire] = deriveEncoder

final case class TxBloomFilterWire(
    bitsetBase64Url: String,
    numHashes: Int,
    hashFamilyId: String,
)
object TxBloomFilterWire:
  given Decoder[TxBloomFilterWire] = deriveDecoder
  given Encoder[TxBloomFilterWire] = deriveEncoder

final case class ControlOpWire(
    kind: String,
    chainId: Option[String] = None,
    topic: Option[String] = None,
    windowKey: Option[String] = None,
    cursor: Option[Vector[CursorEntryWire]] = None,
    cursorToken: Option[String] = None,
    ids: Option[Vector[String]] = None,
    filter: Option[TxBloomFilterWire] = None,
    config: Option[Map[String, Long]] = None,
)
object ControlOpWire:
  given Decoder[ControlOpWire] = deriveDecoder
  given Encoder[ControlOpWire] = deriveEncoder

final case class ControlBatchWire(
    idempotencyKey: String,
    ops: Vector[ControlOpWire],
)
object ControlBatchWire:
  given Decoder[ControlBatchWire] = deriveDecoder
  given Encoder[ControlBatchWire] = deriveEncoder

final case class ControlRequestWire(
    kind: String,
    batch: Option[ControlBatchWire] = None,
)
object ControlRequestWire:
  given Decoder[ControlRequestWire] = deriveDecoder
  given Encoder[ControlRequestWire] = deriveEncoder

final case class EventRequestWire(
    kind: String = "poll",
)
object EventRequestWire:
  given Decoder[EventRequestWire] = deriveDecoder
  given Encoder[EventRequestWire] = deriveEncoder

final case class RejectionWire(
    rejectionClass: String,
    reason: String,
    detail: Option[String],
)
object RejectionWire:
  given Decoder[RejectionWire] = deriveDecoder
  given Encoder[RejectionWire] = deriveEncoder

final case class ControlResponseWire(
    status: String,
    sessionId: Option[String] = None,
    deduplicated: Option[Boolean] = None,
    rejection: Option[RejectionWire] = None,
)
object ControlResponseWire:
  given Decoder[ControlResponseWire] = deriveDecoder
  given Encoder[ControlResponseWire] = deriveEncoder

final case class EventWire[A](
    chainId: String,
    topic: String,
    id: String,
    cursor: String,
    ts: Long,
    payload: A,
)
object EventWire:
  given [A: Decoder]: Decoder[EventWire[A]] = deriveDecoder
  given [A: Encoder]: Encoder[EventWire[A]] = deriveEncoder

final case class EventEnvelopeWire[A](
    kind: String,
    sessionId: String,
    atEpochMs: Option[Long] = None,
    event: Option[EventWire[A]] = None,
    rejection: Option[RejectionWire] = None,
)
object EventEnvelopeWire:
  given [A: Decoder]: Decoder[EventEnvelopeWire[A]] = deriveDecoder
  given [A: Encoder]: Encoder[EventEnvelopeWire[A]] = deriveEncoder

object TxGossipArmeriaAdapter:
  def endpoints[F[_]: Async, A: Encoder](
      runtime: TxGossipRuntime[F, A],
  ): List[ServerEndpoint[Any, F]] =
    List(
      sessionOpenEndpoint(runtime),
      eventStreamEndpoint(runtime),
      controlEndpoint(runtime),
      disconnectEndpoint(runtime),
    )

  private def sessionOpenEndpoint[F[_]: Async, A](
      runtime: TxGossipRuntime[F, A],
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "session" / "open")
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: raw =>
        decodeOrHandshakeReject[SessionOpenProposalWire](raw, "invalidSessionOpenProposal") match
          case Left(rendered) =>
            Async[F].pure(rendered.asLeft[String])
          case Right(wire) =>
            toProposal(wire) match
              case Left(rejection) =>
                Async[F].pure(renderRejection(rejection).asLeft[String])
              case Right(proposal) =>
                runtime.handleInboundProposal(proposal).map:
                  case accepted: InboundHandshakeResult.Accepted =>
                    toAckWire(accepted.ack).asJson.noSpaces.asRight[String]
                  case rejected: InboundHandshakeResult.Rejected =>
                    renderRejection(rejected.rejection).asLeft[String]

  private def eventStreamEndpoint[F[_]: Async, A: Encoder](
      runtime: TxGossipRuntime[F, A],
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "events" / path[String]("sessionId"))
      .in(stringBody)
      .out(stringBody)
      .serverLogicSuccess: (sessionIdRaw, raw) =>
        handleEventRequest(runtime, sessionIdRaw, raw)

  private def controlEndpoint[F[_]: Async, A](
      runtime: TxGossipRuntime[F, A],
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "control" / path[String]("sessionId"))
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: (sessionIdRaw, raw) =>
        handleControlRequest(runtime, sessionIdRaw, raw)

  private def disconnectEndpoint[F[_]: Async, A](
      runtime: TxGossipRuntime[F, A],
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "session" / path[String]("sessionId") / "disconnect")
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: sessionIdRaw =>
        DirectionalSessionId.parse(sessionIdRaw) match
          case Left(error) =>
            Async[F].pure(renderRejection(handshakeRejected("invalidSessionId", error)).asLeft[String])
          case Right(sessionId) =>
            runtime.markSessionDead(sessionId).map(_.leftMap(renderRejection).map(_ => "ok"))

  private def handleEventRequest[F[_]: Async, A: Encoder](
      runtime: TxGossipRuntime[F, A],
      sessionIdRaw: String,
      raw: String,
  ): F[String] =
    DirectionalSessionId.parse(sessionIdRaw) match
      case Left(error) =>
        renderEventStream(Vector(eventRejection(sessionIdRaw, handshakeRejected("invalidSessionId", error)))).pure[F]
      case Right(sessionId) =>
        decodeOrRejectionEvent[EventRequestWire](sessionId, raw, "invalidEventRequest") match
          case Left(rendered) =>
            Async[F].pure(rendered)
          case Right(request) =>
            request.kind match
              case "poll" =>
                runtime.pollEvents(sessionId).flatMap:
                  case Left(rejection) =>
                    renderEventStream(Vector(eventRejection(sessionId.value, rejection))).pure[F]
                  case Right(messages) if messages.nonEmpty =>
                    renderEventStream(messages.map(toEventEnvelope(sessionId, _))).pure[F]
                  case Right(_) =>
                    runtime.eventKeepAlive(sessionId).map:
                      case Left(rejection) =>
                        renderEventStream(Vector(eventRejection(sessionId.value, rejection)))
                      case Right(message) =>
                        renderEventStream(Vector(toEventEnvelope(sessionId, message)))
              case "eventKeepAlive" =>
                runtime.eventKeepAlive(sessionId).map:
                  case Left(rejection) =>
                    renderEventStream(Vector(eventRejection(sessionId.value, rejection)))
                  case Right(message) =>
                    renderEventStream(Vector(toEventEnvelope(sessionId, message)))
              case "controlKeepAlive" =>
                renderEventStream(
                  Vector(
                    eventRejection(
                      sessionId.value,
                      controlRejected("wrongChannelMessageKind", "controlKeepAlive"),
                    )
                  )
                ).pure[F]
              case other =>
                renderEventStream(
                  Vector(
                    eventRejection(
                      sessionId.value,
                      handshakeRejected("unknownEventRequestKind", other),
                    )
                  )
                ).pure[F]

  private def handleControlRequest[F[_]: Async, A](
      runtime: TxGossipRuntime[F, A],
      sessionIdRaw: String,
      raw: String,
  ): F[Either[String, String]] =
    DirectionalSessionId.parse(sessionIdRaw) match
      case Left(error) =>
        renderRejection(handshakeRejected("invalidSessionId", error)).asLeft[String].pure[F]
      case Right(sessionId) =>
        decodeOrControlReject[ControlRequestWire](raw, "invalidControlRequest") match
          case Left(rendered) =>
            Async[F].pure(rendered.asLeft[String])
          case Right(request) =>
            request.kind match
              case "batch" =>
                request.batch match
                  case None =>
                    renderRejection(controlRejected("missingControlBatch", "batch")).asLeft[String].pure[F]
                  case Some(batchWire) =>
                    toControlBatch(batchWire) match
                      case Left(rejection) =>
                        renderRejection(rejection).asLeft[String].pure[F]
                      case Right(batch) =>
                        runtime.receiveControlBatch(sessionId, batch).map:
                          case Left(rejection) =>
                            renderRejection(rejection).asLeft[String]
                          case Right(ControlBatchOutcome.Applied) =>
                            ControlResponseWire(
                              status = "applied",
                              sessionId = Some(sessionId.value),
                              deduplicated = Some(false),
                            ).asJson.noSpaces.asRight[String]
                          case Right(ControlBatchOutcome.Deduplicated) =>
                            ControlResponseWire(
                              status = "deduplicated",
                              sessionId = Some(sessionId.value),
                              deduplicated = Some(true),
                            ).asJson.noSpaces.asRight[String]
              case "controlKeepAlive" =>
                runtime.controlKeepAlive(sessionId).map:
                  case Left(rejection) =>
                    renderRejection(rejection).asLeft[String]
                  case Right(ControlChannelMessage.Ack(ackSessionId, _)) =>
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
                Async[F].pure(renderRejection(controlRejected("wrongChannelMessageKind", "eventKeepAlive")).asLeft[String])
              case other =>
                Async[F].pure(renderRejection(controlRejected("unknownControlRequestKind", other)).asLeft[String])

  private def decodeOrHandshakeReject[A: Decoder](
      raw: String,
      reason: String,
  ): Either[String, A] =
    decode[A](raw).leftMap(error => renderRejection(handshakeRejected(reason, error.getMessage)))

  private def decodeOrControlReject[A: Decoder](
      raw: String,
      reason: String,
  ): Either[String, A] =
    decode[A](raw).leftMap(error => renderRejection(controlRejected(reason, error.getMessage)))

  private def decodeOrRejectionEvent[B: Decoder](
      sessionId: DirectionalSessionId,
      raw: String,
      reason: String,
  ): Either[String, B] =
    decode[B](raw).leftMap: error =>
      renderEventStream[Json](
        Vector(eventRejection[Json](sessionId.value, handshakeRejected(reason, error.getMessage)))
      )

  private def toProposal(
      wire: SessionOpenProposalWire,
  ): Either[CanonicalRejection.HandshakeRejected, SessionOpenProposal] =
    for
      sessionId <- DirectionalSessionId.parse(wire.sessionId).leftMap(handshakeRejected("invalidSessionId", _))
      correlationId <- PeerCorrelationId.parse(wire.peerCorrelationId).leftMap(
        handshakeRejected("invalidPeerCorrelationId", _)
      )
      initiator <- PeerIdentity.parse(wire.initiator).leftMap(handshakeRejected("invalidInitiator", _))
      acceptor <- PeerIdentity.parse(wire.acceptor).leftMap(handshakeRejected("invalidAcceptor", _))
      subscriptions <- wire.subscriptions.traverse(toChainTopic).flatMap: values =>
        SessionSubscription.fromSet(values.toSet).leftMap(handshakeRejected("invalidSubscription", _))
    yield SessionOpenProposal(
      sessionId = sessionId,
      peerCorrelationId = correlationId,
      initiator = initiator,
      acceptor = acceptor,
      subscriptions = subscriptions,
      heartbeatInterval = wire.heartbeatIntervalMs.map(Duration.ofMillis),
      livenessTimeout = wire.livenessTimeoutMs.map(Duration.ofMillis),
      maxControlRetryInterval = wire.maxControlRetryIntervalMs.map(Duration.ofMillis),
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
      maxControlRetryIntervalMs = ack.negotiated.maxControlRetryInterval.toMillis,
    )

  private def toControlBatch(
      wire: ControlBatchWire,
  ): Either[CanonicalRejection.ControlBatchRejected, ControlBatch] =
    wire.ops.traverse(toControlOp).flatMap: ops =>
      ControlBatch.create(
        idempotencyKey = wire.idempotencyKey,
        ops = ops,
      )

  private def toControlOp(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, ControlOp] =
    ControlOpKind.parse(wire.kind).flatMap:
      case ControlOpKind.SetFilter =>
        for
          chainId <- requiredChainId(wire)
          topic <- requiredTopic(wire)
          filter <- requiredFilter(wire)
        yield ControlOp.SetFilter(chainId, topic, filter)
      case ControlOpKind.SetKnownTx =>
        requiredChainId(wire).flatMap: chainId =>
          requiredIds(wire).map(ids => ControlOp.SetKnownTx(chainId, ids))
      case ControlOpKind.SetKnownExact =>
        requiredExactKnownScope(wire).flatMap: scope =>
          requiredIds(wire).map(ids => ControlOp.SetKnownExact(scope, ids))
      case ControlOpKind.SetCursor =>
        requiredCursorEntries(wire).flatMap(entries => toCompositeCursor(entries).map(ControlOp.SetCursor(_)))
      case ControlOpKind.Nack =>
        for
          chainId <- requiredChainId(wire)
          topic <- requiredTopic(wire)
          cursorToken <- optionalCursorToken(wire)
        yield ControlOp.Nack(chainId, topic, cursorToken)
      case ControlOpKind.RequestByIdTx =>
        requiredChainId(wire).flatMap: chainId =>
          requiredIds(wire).map(ids => ControlOp.RequestByIdTx(chainId, ids))
      case ControlOpKind.RequestByIdExact =>
        requiredExactKnownScope(wire).flatMap: scope =>
          requiredIds(wire).map(ids => ControlOp.RequestByIdExact(scope, ids))
      case ControlOpKind.Config =>
        wire.config.toRight(controlRejected("missingConfigValues", "config")).flatMap: config =>
          config.toVector.traverse:
            case (key, value) =>
              SessionConfigKey.parse(key).map(_ -> value)
          .map(entries => ControlOp.Config(entries.toMap))

  private def toCompositeCursor(
      entries: Vector[CursorEntryWire],
  ): Either[CanonicalRejection.ControlBatchRejected, CompositeCursor] =
    entries
      .traverse: entry =>
        for
          chainId <- ChainId.parse(entry.chainId).leftMap(controlRejected("invalidChainId", _))
          topic <- GossipTopic.parse(entry.topic).leftMap(controlRejected("invalidTopic", _))
          token <- CursorToken.decodeBase64Url(entry.token).leftMap(controlRejected("invalidCursor", _))
        yield ChainTopic(chainId, topic) -> token
      .map(entries => CompositeCursor(entries.toMap))

  private def requiredChainId(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, ChainId] =
    wire.chainId.toRight(controlRejected("missingChainId", wire.kind)).flatMap: value =>
      ChainId.parse(value).leftMap(controlRejected("invalidChainId", _))

  private def requiredTopic(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, GossipTopic] =
    wire.topic.toRight(controlRejected("missingTopic", wire.kind)).flatMap: value =>
      GossipTopic.parse(value).leftMap(controlRejected("invalidTopic", _))

  private def requiredIds(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, Vector[StableArtifactId]] =
    wire.ids.toRight(controlRejected("missingIds", wire.kind)).flatMap: values =>
      values.traverse(value => StableArtifactId.fromHex(value).leftMap(controlRejected("invalidStableId", _)))

  private def requiredExactKnownScope(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, ExactKnownSetScope] =
    for
      chainId <- requiredChainId(wire)
      topic <- requiredTopic(wire)
      windowKey <- wire.windowKey
        .toRight(controlRejected("missingWindowKey", wire.kind))
        .flatMap(value => TopicWindowKey.fromHex(value).leftMap(controlRejected("invalidWindowKey", _)))
    yield ExactKnownSetScope(chainId, topic, windowKey)

  private def requiredCursorEntries(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, Vector[CursorEntryWire]] =
    wire.cursor.toRight(controlRejected("missingCursor", wire.kind))

  private def optionalCursorToken(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, Option[CursorToken]] =
    wire.cursorToken.traverse(value => CursorToken.decodeBase64Url(value).leftMap(controlRejected("invalidCursor", _)))

  private def requiredFilter(
      wire: ControlOpWire,
  ): Either[CanonicalRejection.ControlBatchRejected, GossipFilter.TxBloomFilter] =
    wire.filter.toRight(controlRejected("missingFilter", wire.kind)).flatMap: filter =>
      Try(ByteVector.view(Base64.getUrlDecoder.decode(filter.bitsetBase64Url))).toEither
        .leftMap(_ => controlRejected("invalidFilterBitset", filter.bitsetBase64Url))
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
      chainId <- ChainId.parse(wire.chainId).leftMap(handshakeRejected("invalidChainId", _))
      topic <- GossipTopic.parse(wire.topic).leftMap(handshakeRejected("invalidTopic", _))
    yield ChainTopic(chainId, topic)

  private def toChainTopicWire(
      chainTopic: ChainTopic,
  ): ChainTopicWire =
    ChainTopicWire(
      chainId = chainTopic.chainId.value,
      topic = chainTopic.topic.value,
    )

  private def toEventEnvelope[A](
      sessionId: DirectionalSessionId,
      message: EventStreamMessage[A],
  ): EventEnvelopeWire[A] =
    message match
      case EventStreamMessage.Event(event) =>
        EventEnvelopeWire(
          kind = "event",
          sessionId = sessionId.value,
          event = Some(
            EventWire(
              chainId = event.chainId.value,
              topic = event.topic.value,
              id = event.id.toHexLower,
              cursor = event.cursor.toBase64Url,
              ts = event.ts.toEpochMilli,
              payload = event.payload,
            )
          ),
        )
      case EventStreamMessage.KeepAlive(sessionId, at) =>
        EventEnvelopeWire(
          kind = "keepAlive",
          sessionId = sessionId.value,
          atEpochMs = Some(at.toEpochMilli),
        )
      case EventStreamMessage.Rejection(rejection) =>
        eventRejection(sessionId.value, rejection)

  private def eventRejection[A](
      sessionId: String,
      rejection: CanonicalRejection,
  ): EventEnvelopeWire[A] =
    EventEnvelopeWire(
      kind = "rejection",
      sessionId = sessionId,
      rejection = Some(toRejectionWire(rejection)),
    )

  private def renderEventStream[A: Encoder](
      events: Vector[EventEnvelopeWire[A]],
  ): String =
    events.map(_.asJson.noSpaces).mkString("", "\n", "\n")

  private def renderRejection(
      rejection: CanonicalRejection,
  ): String =
    toRejectionWire(rejection).asJson.noSpaces

  private def toRejectionWire(
      rejection: CanonicalRejection,
  ): RejectionWire =
    RejectionWire(
      rejectionClass = rejection.rejectionClass,
      reason = rejection.reason,
      detail = rejection.detail,
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
