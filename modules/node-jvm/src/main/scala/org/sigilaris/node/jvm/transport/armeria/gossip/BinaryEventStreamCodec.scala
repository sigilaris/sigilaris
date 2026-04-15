package org.sigilaris.node.jvm.transport.armeria.gossip

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.gossip.*
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
