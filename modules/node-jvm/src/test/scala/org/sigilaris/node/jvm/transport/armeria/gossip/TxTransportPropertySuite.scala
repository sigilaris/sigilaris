package org.sigilaris.node.jvm.transport.armeria.gossip

import hedgehog.*
import hedgehog.munit.HedgehogSuite
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.gossip.*

final class TxTransportPropertySuite extends HedgehogSuite:

  private given ByteEncoder[String] =
    ByteEncoder[Utf8].contramap(Utf8(_))

  private given ByteDecoder[String] =
    ByteDecoder[Utf8].map(_.asString)

  private final case class Payload(body: String) derives ByteEncoder, ByteDecoder

  private val genTokenHead: Gen[Char] =
    Gen.alphaNum.map(_.toLower)

  private val genTokenTailChar: Gen[Char] =
    Gen.choice1(
      Gen.alphaNum.map(_.toLower),
      Gen.constant('.'),
      Gen.constant('_'),
      Gen.constant('-'),
    )

  private val genLowerToken: Gen[String] =
    for
      head <- genTokenHead
      tail <- Gen.string(genTokenTailChar, Range.linear(0, 15))
    yield s"$head$tail"

  private val genHexLowerChar: Gen[Char] =
    Gen.choice1(
      Gen.digit,
      Gen.element1('a', 'b', 'c', 'd', 'e', 'f'),
    )

  private def genHexString(size: Int): Gen[String] =
    Gen.string(genHexLowerChar, Range.singleton(size))

  private val genUuidV4: Gen[String] =
    for
      a <- genHexString(8)
      b <- genHexString(4)
      c <- genHexString(3)
      d <- Gen.element1('8', '9', 'a', 'b')
      e <- genHexString(3)
      f <- genHexString(12)
    yield s"$a-$b-4$c-$d$e-$f"

  private def genByteVector(range: Range[Int]): Gen[ByteVector] =
    Gen.bytes(range).map(ByteVector.view)

  private val genCursorToken: Gen[CursorToken] =
    for
      payload <- genByteVector(Range.linear(0, 64))
      version <- Gen.int(Range.linear(0, 255))
    yield CursorToken.unsafeIssue(payload, version)

  private val genText: Gen[String] =
    Gen.string(
      Gen.choice1(
        Gen.alphaNum,
        Gen.constant(' '),
        Gen.constant('-'),
        Gen.constant('_'),
        Gen.constant('.'),
      ),
      Range.linear(0, 32),
    )

  private val genOptionalText: Gen[Option[String]] =
    Gen.choice1(
      Gen.constant(None),
      genText.map(Some(_)),
    )

  private val genCanonicalRejection: Gen[CanonicalRejection] =
    for
      reason <- genLowerToken
      detail <- genOptionalText
      rejection <- Gen.choice1(
        Gen.constant(
          CanonicalRejection.HandshakeRejected(reason = reason, detail = detail),
        ),
        Gen.constant(
          CanonicalRejection.ControlBatchRejected(
            reason = reason,
            detail = detail,
          ),
        ),
        Gen.constant(
          CanonicalRejection.ArtifactContractRejected(
            reason = reason,
            detail = detail,
          ),
        ),
        Gen.constant(
          CanonicalRejection.StaleCursor(reason = reason, detail = detail),
        ),
        Gen.constant(
          CanonicalRejection.BackfillUnavailable(
            reason = reason,
            detail = detail,
          ),
        ),
      )
    yield rejection

  private val genEventEnvelope: Gen[EventEnvelopeWire[Payload]] =
    for
      sessionId <- genUuidV4
      chainId <- genLowerToken.map(ChainId.unsafe)
      topic <- genLowerToken.map(GossipTopic.unsafe)
      id <- genByteVector(Range.linear(1, 64)).map(StableArtifactId.unsafeFromBytes)
      cursor <- genCursorToken
      ts <- Gen.long(Range.linear(0L, 1000000000000L))
      payload <- genText.map(Payload(_))
    yield EventEnvelopeWire(
      kind = "event",
      sessionId = sessionId,
      event = Some(
        EventWire(
          chainId = chainId.value,
          topic = topic.value,
          id = id.toHexLower,
          cursor = cursor.toBase64Url,
          ts = ts,
          payload = payload,
        ),
      ),
    )

  private val genKeepAliveEnvelope: Gen[EventEnvelopeWire[Payload]] =
    for
      sessionId <- genUuidV4
      atEpochMs <- Gen.long(Range.linear(0L, 1000000000000L))
    yield EventEnvelopeWire(
      kind = "keepAlive",
      sessionId = sessionId,
      atEpochMs = Some(atEpochMs),
    )

  private val genRejectionEnvelope: Gen[EventEnvelopeWire[Payload]] =
    for
      sessionId <- genUuidV4
      rejection <- genCanonicalRejection
    yield EventEnvelopeWire(
      kind = "rejection",
      sessionId = sessionId,
      rejection = Some(RejectionWire.fromCanonical(rejection)),
    )

  private val genEnvelope: Gen[EventEnvelopeWire[Payload]] =
    Gen.choice1(
      genEventEnvelope,
      genKeepAliveEnvelope,
      genRejectionEnvelope,
    )

  property("RejectionWire conversion round-trips canonical rejections"):
    for rejection <- genCanonicalRejection.forAll
    yield
      Result.assert(
        RejectionWire.toCanonical(RejectionWire.fromCanonical(rejection)) == rejection,
      )

  property("BinaryEventStreamCodec round-trips generated envelope batches"):
    for envelopes <- Gen.list(genEnvelope, Range.linear(0, 8)).map(_.toVector).forAll
    yield
      Result.assert(
        BinaryEventStreamCodec
          .encode(envelopes)
          .flatMap(BinaryEventStreamCodec.decode[Payload]) == Right(envelopes),
      )
