package org.sigilaris.core.application.protocol.v2

import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ApplicationInputId,
  ApplicationResultDigest,
  ApplicationStateRoot,
  ExecutionId,
  ExecutionPlanRoot,
  InclusionHeight,
}
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}
import org.sigilaris.core.failure.DecodeFailure

type Hash    = UInt256
type Bytes   = ByteVector
type Text    = Utf8
type InputId = ApplicationInputId
type Height  = InclusionHeight

enum FailureCode:
  case UnsupportedFormat, UnsupportedTuple, InvalidLength, NonCanonicalEncoding
  case TrailingBytes, InvalidIdentifier, DuplicateField, DuplicateIdentity
  case MembershipMismatch, ManifestMismatch, RoleMismatch, AuthorityMismatch
  case PreconditionMismatch, CommitmentMismatch, AccessOmission,
    MissingCertificate
  case UnexpectedCertificate, CertificateMismatch, InvalidSignature,
    UnknownSigner
  case DuplicateSigner, QuorumNotReached, ProofUnavailable, ProofInvalid
  case ClassificationMismatch, ForbiddenOverlap, InvalidDeadline,
    OpeningMismatch
  case ProtocolLimitExceeded

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class CoreFailure(
    code: FailureCode,
    field: Option[Text] = None,
    detail: Option[Text] = None,
):
  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def message: String =
    Vector(
      Some(code.toString),
      field.map(_.asString),
      detail.map(_.asString),
    ).flatten.mkString(": ")

object CoreFailure:
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def at(code: FailureCode, field: String, detail: String = ""): CoreFailure =
    CoreFailure(
      code,
      Some(Utf8(field)),
      Option.when(detail.nonEmpty)(Utf8(detail)),
    )

trait CanonicalCodec[A]:
  def encode(value: A): Either[CoreFailure, Bytes]
  def decode(bytes: Bytes): Either[CoreFailure, A]

/** Validation belongs to the selected new contract, not the historical codec.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object CanonicalCodec:
  def derived[A](
      validate: A => Either[CoreFailure, Unit],
  )(using encoder: ByteEncoder[A], decoder: ByteDecoder[A]): CanonicalCodec[A] =
    new CanonicalCodec[A]:
      override def encode(value: A): Either[CoreFailure, Bytes] =
        validate(value).map(_ => encoder.encode(value))

      override def decode(bytes: Bytes): Either[CoreFailure, A] =
        for
          decoded <- decoder
            .decode(bytes)
            .left
            .map(error =>
              CoreFailure.at(
                if error.msg.startsWith("protocol ") then
                  FailureCode.ProtocolLimitExceeded
                else if error.msg.contains("length exceeds") || error.msg
                    .contains("count exceeds")
                then FailureCode.InvalidLength
                else if error.msg.startsWith("unsupported ") then
                  FailureCode.UnsupportedFormat
                else FailureCode.NonCanonicalEncoding,
                "bytes",
                error.msg,
              ),
            )
          _ <- V2Validation.require(
            decoded.remainder.isEmpty,
            FailureCode.TrailingBytes,
            "bytes",
          )
          _ <- validate(decoded.value)
          _ <- V2Validation.require(
            encoder.encode(decoded.value) == bytes,
            FailureCode.NonCanonicalEncoding,
            "bytes",
          )
        yield decoded.value

/** Explicit imports keep these stricter decoders out of published v1 APIs. */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object V2Codecs:
  /** Lengths are machine bounded even though semantic BigNat values are not.
    * Inspect the magnitude width before the generic decoder allocates a BigInt.
    */
  private def boundedNatural(
      bytes: Bytes,
      maximum: Long,
      failure: String,
  ): Either[DecodeFailure, DecodeResult[BigNat]] =
    val maximumWidth   = math.max(1, (BigInt(maximum).bitLength + 7) / 8)
    val excessiveWidth = bytes.headOption.exists: value =>
      val head = value & 0xff
      head > 0xf8 || (head > 0x80 && head - 0x80 > maximumWidth)
    if excessiveWidth then Left(DecodeFailure(failure))
    else
      ByteDecoder[BigNat]
        .decode(bytes)
        .flatMap: decoded =>
          if decoded.value.toBigInt > BigInt(maximum) then
            Left(DecodeFailure(failure))
          else Right(decoded)

  given bytesEncoder: ByteEncoder[Bytes] = value =>
    ByteEncoder[BigNat].encode(BigNat.unsafeFromLong(value.size)) ++ value

  given bytesDecoder: ByteDecoder[Bytes] = bytes =>
    boundedNatural(bytes, Long.MaxValue, "byte length exceeds available bytes")
      .flatMap: decoded =>
        val size = decoded.value.toBigInt
        if !size.isValidLong || size > BigInt(decoded.remainder.size) then
          Left(DecodeFailure("byte length exceeds available bytes"))
        else
          val (value, rest) = decoded.remainder.splitAt(size.toLong)
          Right(DecodeResult(value, rest))

  given textDecoder: ByteDecoder[Text] = bytes =>
    bytesDecoder
      .decode(bytes)
      .flatMap: decoded =>
        decoded.value.decodeUtf8.left
          .map(_ => DecodeFailure("invalid UTF-8"))
          .map(value => DecodeResult(Utf8(value), decoded.remainder))

  given vectorEncoder[A: ByteEncoder]: ByteEncoder[Vector[A]] =
    ByteEncoder[List[A]].contramap(_.toList)

  /** Every encoded V2 vector element consumes at least one byte. */
  given vectorDecoder[A: ByteDecoder]: ByteDecoder[Vector[A]] =
    boundedVectorDecoder[A](Int.MaxValue.toLong)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def boundedVectorDecoder[A: ByteDecoder](
      maxElements: Long,
      maxEncodedBytes: Long = Long.MaxValue,
  ): ByteDecoder[Vector[A]] = bytes =>
    boundedNatural(bytes, maxElements, "protocol vector limit exceeded")
      .flatMap: decoded =>
        val size       = decoded.value.toBigInt
        val prefixSize = bytes.size - decoded.remainder.size
        if size > BigInt(maxElements) || prefixSize > maxEncodedBytes then
          Left(DecodeFailure("protocol vector limit exceeded"))
        else if !size.isValidInt || size > BigInt(decoded.remainder.size) then
          Left(DecodeFailure("element count exceeds available bytes"))
        else
          @annotation.tailrec
          def read(
              remaining: Bytes,
              count: Int,
              values: List[A],
              budget: Long,
          ): Either[DecodeFailure, DecodeResult[Vector[A]]] =
            if count == 0 then
              Right(DecodeResult(values.reverse.toVector, remaining))
            else if budget <= 0L then
              Left(DecodeFailure("protocol vector byte limit exceeded"))
            else
              val bounded = remaining.take(budget)
              ByteDecoder[A].decode(bounded) match
                case Left(error) => Left(error)
                case Right(next) if next.remainder.size >= bounded.size =>
                  Left(DecodeFailure("vector element consumed no bytes"))
                case Right(next) =>
                  val consumed = bounded.size - next.remainder.size
                  read(
                    remaining.drop(consumed),
                    count - 1,
                    next.value :: values,
                    budget - consumed,
                  )
          read(decoded.remainder, size.toInt, Nil, maxEncodedBytes - prefixSize)

  given optionDecoder[A: ByteDecoder]: ByteDecoder[Option[A]] = bytes =>
    ByteDecoder[Byte]
      .decode(bytes)
      .flatMap: tag =>
        tag.value match
          case 0 => Right(DecodeResult(None, tag.remainder))
          case 1 =>
            ByteDecoder[A]
              .decode(tag.remainder)
              .map(value => DecodeResult(Some(value.value), value.remainder))
          case _ =>
            Left(DecodeFailure("option must contain exactly zero or one value"))

  given inputIdDecoder: ByteDecoder[InputId] = bytes =>
    bytesDecoder
      .decode(bytes)
      .flatMap: decoded =>
        if decoded.value.isEmpty || decoded.value.size > 256L then
          Left(DecodeFailure("input identity must contain 1 through 256 bytes"))
        else
          ApplicationInputId
            .fromBytes(decoded.value)
            .left
            .map(DecodeFailure(_))
            .map(value => DecodeResult(value, decoded.remainder))

  given heightDecoder: ByteDecoder[Height] =
    ByteDecoder[BigNat].map(InclusionHeight(_))
  given executionIdDecoder: ByteDecoder[ExecutionId] =
    ByteDecoder[UInt256].map(ExecutionId(_))
  given resultDigestDecoder: ByteDecoder[ApplicationResultDigest] =
    ByteDecoder[UInt256].map(ApplicationResultDigest(_))
  given stateRootDecoder: ByteDecoder[ApplicationStateRoot] =
    ByteDecoder[UInt256].map(ApplicationStateRoot(_))
  given planRootDecoder: ByteDecoder[ExecutionPlanRoot] =
    ByteDecoder[UInt256].map(ExecutionPlanRoot(_))

  def enumDecoder[A](label: String, values: Vector[(Byte, A)]): ByteDecoder[A] =
    ByteDecoder[Byte].emap(tag =>
      values
        .find(_._1 == tag)
        .map(_._2)
        .toRight(DecodeFailure("unsupported " + label + " tag")),
    )

object Commitment:
  def preimage(domain: Text, canonicalPayload: Bytes): Bytes =
    ByteEncoder[Utf8].encode(domain) ++ V2Codecs.bytesEncoder.encode(
      canonicalPayload,
    )

  def hash(domain: Text, canonicalPayload: Bytes): Hash =
    UInt256.unsafeFromBytesBE(
      ByteVector.view(
        CryptoOps.keccak256(preimage(domain, canonicalPayload).toArray),
      ),
    )

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object V2Validation:
  def require(
      condition: Boolean,
      code: FailureCode,
      field: String,
  ): Either[CoreFailure, Unit] =
    Either.cond(condition, (), CoreFailure.at(code, field))

  def all(
      values: Vector[Either[CoreFailure, Unit]],
  ): Either[CoreFailure, Unit] =
    values.foldLeft[Either[CoreFailure, Unit]](Right[CoreFailure, Unit](()))(
      (acc, next) => acc.flatMap(_ => next),
    )

  def identifier(value: Text, field: String): Either[CoreFailure, Unit] =
    require(
      value.asString.nonEmpty && value.asString == value.asString.trim &&
        ByteVector
          .encodeUtf8(value.asString)
          .exists(_.decodeUtf8.contains(value.asString)),
      FailureCode.InvalidIdentifier,
      field,
    )

  def textKey(value: Text): String =
    ByteVector.encodeUtf8(value.asString).fold(_ => "", _.toHex)

  def sortedUnique(
      values: Vector[String],
      field: String,
  ): Either[CoreFailure, Unit] =
    if values.distinct.sizeCompare(values.size) != 0 then
      Left[CoreFailure, Unit](
        CoreFailure.at(FailureCode.DuplicateIdentity, field),
      )
    else
      require(values == values.sorted, FailureCode.NonCanonicalEncoding, field)

  def inputId(value: InputId, field: String): Either[CoreFailure, Unit] =
    require(
      value.bytes.nonEmpty && value.bytes.size <= 256L,
      FailureCode.ProtocolLimitExceeded,
      field,
    )

  def format(
      actual: Long,
      expected: Long,
      field: String,
  ): Either[CoreFailure, Unit] =
    require(actual == expected, FailureCode.UnsupportedFormat, field)
