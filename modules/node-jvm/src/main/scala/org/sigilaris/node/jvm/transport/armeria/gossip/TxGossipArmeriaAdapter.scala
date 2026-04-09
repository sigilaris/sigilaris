package org.sigilaris.node.jvm.transport.armeria.gossip

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import scala.util.Try

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import scodec.bits.ByteVector
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.*

/** Wire format for a chain-topic subscription pair.
  *
  * @param chainId
  *   string identifier of the chain
  * @param topic
  *   gossip topic name
  */
final case class ChainTopicWire(
    chainId: String,
    topic: String,
)

/** JSON codec instances for `ChainTopicWire`. */
object ChainTopicWire:
  given Decoder[ChainTopicWire] = deriveDecoder
  given Encoder[ChainTopicWire] = deriveEncoder

/** Wire format for a session open proposal sent by the initiating peer.
  *
  * @param sessionId
  *   proposed directional session identifier
  * @param peerCorrelationId
  *   correlation identifier assigned by the proposing peer
  * @param initiator
  *   identity of the session initiator
  * @param acceptor
  *   identity of the session acceptor
  * @param subscriptions
  *   chain-topic pairs the initiator wants to subscribe to
  * @param heartbeatIntervalMs
  *   proposed heartbeat interval in milliseconds
  * @param livenessTimeoutMs
  *   proposed liveness timeout in milliseconds
  * @param maxControlRetryIntervalMs
  *   proposed maximum control retry interval in milliseconds
  */
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

/** JSON codec instances for `SessionOpenProposalWire`. */
object SessionOpenProposalWire:
  given Decoder[SessionOpenProposalWire] = deriveDecoder
  given Encoder[SessionOpenProposalWire] = deriveEncoder

/** Wire format for a session open acknowledgement returned by the accepting peer.
  *
  * @param sessionId
  *   confirmed directional session identifier
  * @param peerCorrelationId
  *   correlation identifier from the original proposal
  * @param initiator
  *   identity of the session initiator
  * @param acceptor
  *   identity of the session acceptor
  * @param subscriptions
  *   negotiated chain-topic subscriptions
  * @param heartbeatIntervalMs
  *   negotiated heartbeat interval in milliseconds
  * @param livenessTimeoutMs
  *   negotiated liveness timeout in milliseconds
  * @param maxControlRetryIntervalMs
  *   negotiated maximum control retry interval in milliseconds
  */
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

/** JSON codec instances for `SessionOpenAckWire`. */
object SessionOpenAckWire:
  given Decoder[SessionOpenAckWire] = deriveDecoder
  given Encoder[SessionOpenAckWire] = deriveEncoder

/** Wire format for a single cursor entry within a composite cursor.
  *
  * @param chainId
  *   chain identifier for this cursor position
  * @param topic
  *   gossip topic for this cursor position
  * @param token
  *   Base64URL-encoded cursor token
  */
final case class CursorEntryWire(
    chainId: String,
    topic: String,
    token: String,
)

/** JSON codec instances for `CursorEntryWire`. */
object CursorEntryWire:
  given Decoder[CursorEntryWire] = deriveDecoder
  given Encoder[CursorEntryWire] = deriveEncoder

/** Wire format for a transaction Bloom filter used in gossip deduplication.
  *
  * @param bitsetBase64Url
  *   Base64URL-encoded bitset bytes
  * @param numHashes
  *   number of hash functions used
  * @param hashFamilyId
  *   identifier of the hash family
  */
final case class TxBloomFilterWire(
    bitsetBase64Url: String,
    numHashes: Int,
    hashFamilyId: String,
)

/** JSON codec instances for `TxBloomFilterWire`. */
object TxBloomFilterWire:
  given Decoder[TxBloomFilterWire] = deriveDecoder
  given Encoder[TxBloomFilterWire] = deriveEncoder

/** Wire format for a single control operation within a control batch.
  *
  * @param kind
  *   operation kind (e.g. "setFilter", "setCursor", "nack")
  * @param chainId
  *   optional chain identifier for chain-scoped operations
  * @param topic
  *   optional gossip topic for topic-scoped operations
  * @param windowKey
  *   optional hex-encoded topic window key for exact known set operations
  * @param cursor
  *   optional composite cursor entries for setCursor operations
  * @param cursorToken
  *   optional Base64URL-encoded cursor token for nack operations
  * @param ids
  *   optional hex-encoded artifact identifiers
  * @param filter
  *   optional Bloom filter for setFilter operations
  * @param config
  *   optional key-value configuration for config operations
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
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

/** JSON codec instances for `ControlOpWire`. */
object ControlOpWire:
  given Decoder[ControlOpWire] = deriveDecoder
  given Encoder[ControlOpWire] = deriveEncoder

/** Wire format for a batch of control operations with an idempotency key.
  *
  * @param idempotencyKey
  *   unique key for deduplicating repeated batch submissions
  * @param ops
  *   ordered control operations in this batch
  */
final case class ControlBatchWire(
    idempotencyKey: String,
    ops: Vector[ControlOpWire],
)

/** JSON codec instances for `ControlBatchWire`. */
object ControlBatchWire:
  given Decoder[ControlBatchWire] = deriveDecoder
  given Encoder[ControlBatchWire] = deriveEncoder

/** Wire format for a control channel request.
  *
  * @param kind
  *   request kind (e.g. "batch", "controlKeepAlive")
  * @param batch
  *   optional control batch payload when kind is "batch"
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class ControlRequestWire(
    kind: String,
    batch: Option[ControlBatchWire] = None,
)

/** JSON codec instances for `ControlRequestWire`. */
object ControlRequestWire:
  given Decoder[ControlRequestWire] = deriveDecoder
  given Encoder[ControlRequestWire] = deriveEncoder

/** Wire format for an event stream request.
  *
  * @param kind
  *   request kind (e.g. "poll", "eventKeepAlive")
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class EventRequestWire(
    kind: String = "poll",
)

/** JSON codec instances for `EventRequestWire`. */
object EventRequestWire:
  given Decoder[EventRequestWire] = deriveDecoder
  given Encoder[EventRequestWire] = deriveEncoder

/** Wire format for a canonical rejection response.
  *
  * @param rejectionClass
  *   classification of the rejection (e.g. "handshakeRejected", "controlBatchRejected")
  * @param reason
  *   machine-readable reason code
  * @param detail
  *   optional human-readable detail message
  */
final case class RejectionWire(
    rejectionClass: String,
    reason: String,
    detail: Option[String],
)

/** JSON codec instances for `RejectionWire`. */
object RejectionWire:
  given Decoder[RejectionWire] = deriveDecoder
  given Encoder[RejectionWire] = deriveEncoder

/** Wire format for a control channel response.
  *
  * @param status
  *   outcome status (e.g. "applied", "deduplicated", "ack")
  * @param sessionId
  *   optional session identifier echoed back in the response
  * @param deduplicated
  *   optional flag indicating whether the batch was deduplicated
  * @param rejection
  *   optional rejection payload when the control request was rejected
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class ControlResponseWire(
    status: String,
    sessionId: Option[String] = None,
    deduplicated: Option[Boolean] = None,
    rejection: Option[RejectionWire] = None,
)

/** JSON codec instances for `ControlResponseWire`. */
object ControlResponseWire:
  given Decoder[ControlResponseWire] = deriveDecoder
  given Encoder[ControlResponseWire] = deriveEncoder

/** Wire format for a single gossip event carrying an artifact payload.
  *
  * @tparam A
  *   the payload type
  * @param chainId
  *   chain identifier the event belongs to
  * @param topic
  *   gossip topic the event belongs to
  * @param id
  *   hex-encoded stable artifact identifier
  * @param cursor
  *   Base64URL-encoded cursor token marking this event's position
  * @param ts
  *   event timestamp as epoch milliseconds
  * @param payload
  *   the artifact payload
  */
final case class EventWire[A](
    chainId: String,
    topic: String,
    id: String,
    cursor: String,
    ts: Long,
    payload: A,
)

/** JSON codec instances for `EventWire`. */
object EventWire:
  given [A: Decoder]: Decoder[EventWire[A]] = deriveDecoder
  given [A: Encoder]: Encoder[EventWire[A]] = deriveEncoder

/** Wire format for an event stream envelope, which may carry an event, keep-alive, or rejection.
  *
  * @tparam A
  *   the event payload type
  * @param kind
  *   envelope kind (e.g. "event", "keepAlive", "rejection")
  * @param sessionId
  *   session identifier this envelope belongs to
  * @param atEpochMs
  *   optional epoch milliseconds for keep-alive envelopes
  * @param event
  *   optional event payload when kind is "event"
  * @param rejection
  *   optional rejection payload when kind is "rejection"
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class EventEnvelopeWire[A](
    kind: String,
    sessionId: String,
    atEpochMs: Option[Long] = None,
    event: Option[EventWire[A]] = None,
    rejection: Option[RejectionWire] = None,
)

/** JSON codec instances for `EventEnvelopeWire`. */
object EventEnvelopeWire:
  given [A: Decoder]: Decoder[EventEnvelopeWire[A]] = deriveDecoder
  given [A: Encoder]: Encoder[EventEnvelopeWire[A]] = deriveEncoder

private[gossip] enum BinaryEventEnvelope:
  case Event(
      sessionId: String,
      chainId: ChainId,
      topic: GossipTopic,
      id: StableArtifactId,
      cursor: CursorToken,
      tsEpochMs: Long,
      payloadBytes: ByteVector,
  )
  case KeepAlive(sessionId: String, atEpochMs: Long)
  case Rejection(sessionId: String, rejection: RejectionWire)

/** Binary codec for encoding and decoding streams of event envelopes in a compact frame format.
  *
  * Each frame is length-prefixed and tagged with a version byte and kind byte,
  * enabling efficient binary transport of gossip events over HTTP.
  */
object BinaryEventStreamCodec:
  /** MIME type for binary event stream responses. */
  val MediaType: String       = "application/octet-stream"

  /** Current binary framing protocol version. */
  val CurrentVersion: Byte    = 1.toByte

  /** Maximum allowed size in bytes for a single encoded event frame. */
  val MaxFrameSizeBytes: Long = 16L * 1024L * 1024L

  private val EventTag: Byte     = 1.toByte
  private val KeepAliveTag: Byte = 2.toByte
  private val RejectionTag: Byte = 3.toByte

  private def encodeLengthPrefix(
      size: Long,
  ): ByteVector =
    ByteEncoder[BigNat].encode(BigNat.unsafeFromLong(size))

  private def decodeLengthPrefixedBytes(
      fieldLabel: String,
  ): ByteDecoder[ByteVector] =
    bytes =>
      ByteDecoder[BigNat]
        .decode(bytes)
        .flatMap: sizeResult =>
          val declaredSizeNat = sizeResult.value.toBigInt
          val availableBytes  = sizeResult.remainder.size
          if declaredSizeNat > BigInt(availableBytes) then
            DecodeFailure(
              fieldLabel +
                " truncated: declared=" +
                declaredSizeNat.toString +
                " available=" +
                availableBytes.toString,
            ).asLeft[DecodeResult[ByteVector]]
          else
            val declaredSize  = declaredSizeNat.toLong
            val (front, back) = sizeResult.remainder.splitAt(declaredSize)
            DecodeResult(front, back).asRight[DecodeFailure]

  private given ByteEncoder[String] = ByteEncoder[Utf8].contramap(Utf8(_))
  private given ByteDecoder[String] = ByteDecoder[Utf8].map(_.asString)
  private given ByteEncoder[ByteVector] = bytes =>
    encodeLengthPrefix(bytes.size) ++ bytes
  private given ByteDecoder[ByteVector] =
    decodeLengthPrefixedBytes("byte vector payload")
  private given ByteEncoder[ChainId] =
    ByteEncoder[String].contramap(_.value)
  private given ByteDecoder[ChainId] =
    ByteDecoder[String].emap(value =>
      ChainId.parse(value).leftMap(DecodeFailure(_)),
    )
  private given ByteEncoder[GossipTopic] =
    ByteEncoder[String].contramap(_.value)
  private given ByteDecoder[GossipTopic] =
    ByteDecoder[String].emap(value =>
      GossipTopic.parse(value).leftMap(DecodeFailure(_)),
    )
  private given ByteEncoder[StableArtifactId] =
    ByteEncoder[ByteVector].contramap(_.bytes)
  private given ByteDecoder[StableArtifactId] =
    ByteDecoder[ByteVector].emap(bytes =>
      StableArtifactId.fromBytes(bytes).leftMap(DecodeFailure(_)),
    )
  private given ByteEncoder[CursorToken] =
    ByteEncoder[ByteVector].contramap(_.bytes)
  private given ByteDecoder[CursorToken] =
    ByteDecoder[ByteVector].emap(bytes =>
      CursorToken.fromBytes(bytes).leftMap(DecodeFailure(_)),
    )

  private final case class EventFramePayload(
      sessionId: String,
      chainId: ChainId,
      topic: GossipTopic,
      id: StableArtifactId,
      cursor: CursorToken,
      tsEpochMs: Long,
      payloadBytes: ByteVector,
  ) derives ByteEncoder,
        ByteDecoder

  private final case class KeepAliveFramePayload(
      sessionId: String,
      atEpochMs: Long,
  ) derives ByteEncoder,
        ByteDecoder

  private final case class RejectionFramePayload(
      sessionId: String,
      rejectionClass: String,
      reason: String,
      detail: Option[String],
  ) derives ByteEncoder,
        ByteDecoder

  /** Encodes a vector of event envelopes into a binary frame byte array.
    *
    * @tparam A
    *   the event payload type
    * @param events
    *   event envelopes to encode
    * @return
    *   the encoded byte array, or an error message
    */
  def encode[A: ByteEncoder](
      events: Vector[EventEnvelopeWire[A]],
  ): Either[String, Array[Byte]] =
    events.traverse(toBinaryEnvelope[A]).map(encodeBinary)

  /** Decodes a binary frame byte array into a vector of event envelopes.
    *
    * @tparam A
    *   the event payload type
    * @param bytes
    *   raw bytes to decode
    * @return
    *   the decoded event envelopes, or an error message
    */
  def decode[A: ByteDecoder](
      bytes: Array[Byte],
  ): Either[String, Vector[EventEnvelopeWire[A]]] =
    decodeFrames[A](ByteVector.view(bytes))

  private[gossip] def encodeBinary(
      events: Vector[BinaryEventEnvelope],
  ): Array[Byte] =
    events.iterator.map(encodeFrame).foldLeft(ByteVector.empty)(_ ++ _).toArray

  private def toBinaryEnvelope[A: ByteEncoder](
      envelope: EventEnvelopeWire[A],
  ): Either[String, BinaryEventEnvelope] =
    envelope.kind match
      case "event" =>
        envelope.event
          .toRight("event envelope missing event payload")
          .flatMap: event =>
            for
              chainId <- ChainId.parse(event.chainId)
              topic   <- GossipTopic.parse(event.topic)
              id      <- StableArtifactId.fromHex(event.id)
              cursor  <- CursorToken.decodeBase64Url(event.cursor)
            yield BinaryEventEnvelope.Event(
              sessionId = envelope.sessionId,
              chainId = chainId,
              topic = topic,
              id = id,
              cursor = cursor,
              tsEpochMs = event.ts,
              payloadBytes = ByteEncoder[A].encode(event.payload),
            )
      case "keepAlive" =>
        envelope.atEpochMs
          .toRight("keepAlive envelope missing atEpochMs")
          .map(at => BinaryEventEnvelope.KeepAlive(envelope.sessionId, at))
      case "rejection" =>
        envelope.rejection
          .toRight("rejection envelope missing rejection payload")
          .map(rejection =>
            BinaryEventEnvelope.Rejection(
              sessionId = envelope.sessionId,
              rejection = rejection,
            ),
          )
      case other =>
        ("unknown event envelope kind: " + other).asLeft[BinaryEventEnvelope]

  private def encodeFrame(
      envelope: BinaryEventEnvelope,
  ): ByteVector =
    val body = envelope match
      case BinaryEventEnvelope.Event(
            sessionId,
            chainId,
            topic,
            id,
            cursor,
            tsEpochMs,
            payloadBytes,
          ) =>
        ByteVector.fromByte(CurrentVersion) ++
          ByteVector.fromByte(EventTag) ++
          ByteEncoder[EventFramePayload].encode(
            EventFramePayload(
              sessionId = sessionId,
              chainId = chainId,
              topic = topic,
              id = id,
              cursor = cursor,
              tsEpochMs = tsEpochMs,
              payloadBytes = payloadBytes,
            ),
          )
      case BinaryEventEnvelope.KeepAlive(sessionId, atEpochMs) =>
        ByteVector.fromByte(CurrentVersion) ++
          ByteVector.fromByte(KeepAliveTag) ++
          ByteEncoder[KeepAliveFramePayload].encode(
            KeepAliveFramePayload(
              sessionId = sessionId,
              atEpochMs = atEpochMs,
            ),
          )
      case BinaryEventEnvelope.Rejection(sessionId, rejection) =>
        ByteVector.fromByte(CurrentVersion) ++
          ByteVector.fromByte(RejectionTag) ++
          ByteEncoder[RejectionFramePayload].encode(
            RejectionFramePayload(
              sessionId = sessionId,
              rejectionClass = rejection.rejectionClass,
              reason = rejection.reason,
              detail = rejection.detail,
            ),
          )

    require(
      body.size <= MaxFrameSizeBytes,
      "event frame exceeds max size: actual=" +
        body.size.toString +
        " max=" +
        MaxFrameSizeBytes.toString,
    )
    encodeLengthPrefix(body.size) ++ body

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def decodeFrames[A: ByteDecoder](
      bytes: ByteVector,
  ): Either[String, Vector[EventEnvelopeWire[A]]] =
    def loop(
        remaining: ByteVector,
        acc: Vector[EventEnvelopeWire[A]],
    ): Either[String, Vector[EventEnvelopeWire[A]]] =
      if remaining.isEmpty then acc.asRight[String]
      else
        ByteDecoder[BigNat]
          .decode(remaining)
          .leftMap(_.msg)
          .flatMap: sizeResult =>
            val declaredSizeNat = sizeResult.value.toBigInt
            if declaredSizeNat > BigInt(MaxFrameSizeBytes) then
              (
                "oversize event frame: declared=" +
                  declaredSizeNat.toString +
                  " max=" +
                  MaxFrameSizeBytes.toString
              ).asLeft[Vector[EventEnvelopeWire[A]]]
            else if declaredSizeNat > BigInt(sizeResult.remainder.size) then
              (
                "truncated event frame: declared=" +
                  declaredSizeNat.toString +
                  " available=" +
                  sizeResult.remainder.size.toString
              ).asLeft[Vector[EventEnvelopeWire[A]]]
            else
              val declaredSize = declaredSizeNat.toLong
              val (frameBytes, rest) =
                sizeResult.remainder.splitAt(declaredSize)
              decodeFrame[A](frameBytes).flatMap(frame =>
                loop(rest, acc :+ frame),
              )

    loop(bytes, Vector.empty)

  private def decodeFrame[A: ByteDecoder](
      frameBytes: ByteVector,
  ): Either[String, EventEnvelopeWire[A]] =
    for
      versionResult <- ByteDecoder[Byte].decode(frameBytes).leftMap(_.msg)
      kindResult <- ByteDecoder[Byte]
        .decode(versionResult.remainder)
        .leftMap(_.msg)
      version = versionResult.value.toInt & 0xff
      kind    = kindResult.value
      _ <- Either.cond(
        version == (CurrentVersion.toInt & 0xff),
        (),
        "unknown event envelope version: " + version.toString,
      )
      envelope <- kind match
        case tag if tag == EventTag =>
          decodeEventPayload[A](kindResult.remainder)
        case tag if tag == KeepAliveTag =>
          decodeKeepAlivePayload[A](kindResult.remainder)
        case tag if tag == RejectionTag =>
          decodeRejectionPayload[A](kindResult.remainder)
        case other =>
          (
            "unknown event envelope kind: " + (other.toInt & 0xff).toString
          ).asLeft[EventEnvelopeWire[A]]
    yield envelope

  private def decodeEventPayload[A: ByteDecoder](
      bytes: ByteVector,
  ): Either[String, EventEnvelopeWire[A]] =
    ByteDecoder[EventFramePayload]
      .decode(bytes)
      .leftMap(_.msg)
      .flatMap:
        case DecodeResult(payload, remainder) =>
          for
            _ <- ensureEmptyRemainder("event frame", remainder)
            decodedPayload <- decodeStrict[A](
              "event payload",
              payload.payloadBytes,
            )
          yield EventEnvelopeWire(
            kind = "event",
            sessionId = payload.sessionId,
            event = Some(
              EventWire(
                chainId = payload.chainId.value,
                topic = payload.topic.value,
                id = payload.id.toHexLower,
                cursor = payload.cursor.toBase64Url,
                ts = payload.tsEpochMs,
                payload = decodedPayload,
              ),
            ),
          )

  private def decodeKeepAlivePayload[A](
      bytes: ByteVector,
  ): Either[String, EventEnvelopeWire[A]] =
    ByteDecoder[KeepAliveFramePayload]
      .decode(bytes)
      .leftMap(_.msg)
      .flatMap:
        case DecodeResult(payload, remainder) =>
          ensureEmptyRemainder("keepAlive frame", remainder).map(_ =>
            EventEnvelopeWire(
              kind = "keepAlive",
              sessionId = payload.sessionId,
              atEpochMs = Some(payload.atEpochMs),
            ),
          )

  private def decodeRejectionPayload[A](
      bytes: ByteVector,
  ): Either[String, EventEnvelopeWire[A]] =
    ByteDecoder[RejectionFramePayload]
      .decode(bytes)
      .leftMap(_.msg)
      .flatMap:
        case DecodeResult(payload, remainder) =>
          ensureEmptyRemainder("rejection frame", remainder).map(_ =>
            EventEnvelopeWire(
              kind = "rejection",
              sessionId = payload.sessionId,
              rejection = Some(
                RejectionWire(
                  rejectionClass = payload.rejectionClass,
                  reason = payload.reason,
                  detail = payload.detail,
                ),
              ),
            ),
          )

  private def decodeStrict[A: ByteDecoder](
      label: String,
      bytes: ByteVector,
  ): Either[String, A] =
    ByteDecoder[A]
      .decode(bytes)
      .leftMap(_.msg)
      .flatMap:
        case DecodeResult(value, remainder) =>
          ensureEmptyRemainder(label, remainder).map(_ => value)

  private def ensureEmptyRemainder(
      label: String,
      remainder: ByteVector,
  ): Either[String, Unit] =
    Either.cond(
      remainder.isEmpty,
      (),
      label + " had trailing bytes: " + remainder.size.toString,
    )

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
      rejection = toRejectionWire(rejection),
    )

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
