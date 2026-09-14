package org.sigilaris.core.application.protocol.v2

import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.datatype.{BigNat, Utf8}

import V2Codecs.given

enum WitnessAccess(val tag: Byte):
  case Read  extends WitnessAccess(1.toByte)
  case Write extends WitnessAccess(2.toByte)

object WitnessAccess:
  val all: Vector[WitnessAccess]   = Vector(Read, Write)
  given ByteEncoder[WitnessAccess] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[WitnessAccess] =
    V2Codecs.enumDecoder("witness access", all.map(value => value.tag -> value))
  val codec: CanonicalCodec[WitnessAccess] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class WitnessEntry(identity: InputId, access: WitnessAccess)

object WitnessEntry:
  given ByteEncoder[WitnessEntry]         = ByteEncoder.derived
  given ByteDecoder[WitnessEntry]         = ByteDecoder.derived
  val codec: CanonicalCodec[WitnessEntry] = CanonicalCodec.derived(value =>
    V2Validation.inputId(value.identity, "witness.identity"),
  )

final case class ReservationWitness(format: Long, entries: Vector[WitnessEntry])

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ReservationWitness:
  given ByteEncoder[ReservationWitness] = ByteEncoder.derived
  given ByteDecoder[ReservationWitness] = bytes =>
    for
      format  <- ByteDecoder[Long].decode(bytes)
      entries <- V2Codecs
        .boundedVectorDecoder[WitnessEntry](
          ProtocolLimits.V2.maxIdentities,
          ProtocolLimits.V2.maxWitnessBytes - 8L,
        )
        .decode(format.remainder)
    yield DecodeResult(
      ReservationWitness(format.value, entries.value),
      entries.remainder,
    )
  val codec: CanonicalCodec[ReservationWitness] =
    CanonicalCodec.derived(validate)

  private def lengthSize(value: Long): Long =
    ByteEncoder[BigNat].encode(BigNat.unsafeFromLong(value)).size

  def validate(value: ReservationWitness): Either[CoreFailure, Unit] =
    val encodedSize = 8L + lengthSize(value.entries.size.toLong) +
      value.entries.foldLeft(0L)((sum, entry) =>
        sum + lengthSize(
          entry.identity.bytes.size,
        ) + entry.identity.bytes.size + 1L,
      )
    for
      _ <- V2Validation.format(value.format, 1L, "witness.format")
      _ <- V2Validation.require(
        value.entries.size <= ProtocolLimits.V2.maxIdentities,
        FailureCode.ProtocolLimitExceeded,
        "witness.identities",
      )
      _ <- V2Validation.all(
        value.entries.map(entry =>
          V2Validation.inputId(entry.identity, "witness.identity"),
        ),
      )
      _ <- V2Validation.sortedUnique(
        value.entries.map(_.identity.toHex),
        "witness.entries",
      )
      _ <- V2Validation.require(
        encodedSize <= ProtocolLimits.V2.maxWitnessBytes,
        FailureCode.ProtocolLimitExceeded,
        "witness.bytes",
      )
    yield ()

  def fromFootprint(value: Footprint): Either[CoreFailure, ReservationWitness] =
    for
      _ <- Footprint.validate(value)
      entries = value.reads.map(WitnessEntry(_, WitnessAccess.Read)) ++
        value.writes.map(WitnessEntry(_, WitnessAccess.Write))
      witness = ReservationWitness(1L, entries.sortBy(_.identity.toHex))
      _ <- validate(witness)
    yield witness

  def digest(value: ReservationWitness): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment.hash(Utf8("sigilaris.application.reservation.witness.v1"), _),
      )

  def chunks(
      value: ReservationWitness,
      limits: ProtocolLimits,
  ): Either[CoreFailure, Vector[WitnessChunk]] =
    for
      _     <- ProtocolLimits.validate(limits)
      bytes <- codec.encode(value)
      id = Commitment.hash(
        Utf8("sigilaris.application.reservation.witness.v1"),
        bytes,
      )
      count = ((bytes.size - 1L) / limits.chunkBytes) + 1L
      _ <- V2Validation.require(
        count <= limits.maxChunks,
        FailureCode.ProtocolLimitExceeded,
        "witness.chunks",
      )
    yield Vector.tabulate(count.toInt)(index =>
      WitnessChunk(
        1L,
        id,
        index.toLong,
        count,
        bytes.slice(
          index.toLong * limits.chunkBytes,
          (index.toLong + 1L) * limits.chunkBytes,
        ),
      ),
    )

  def reassemble(
      chunks: Vector[WitnessChunk],
      limits: ProtocolLimits,
  ): Either[CoreFailure, ReservationWitness] =
    for
      _ <- ProtocolLimits.validate(limits)
      _ <- V2Validation.require(
        chunks.nonEmpty && chunks.size <= limits.maxChunks,
        FailureCode.ProtocolLimitExceeded,
        "chunks.count",
      )
      _ <- V2Validation.all(chunks.map(WitnessChunk.validate))
      _ <- V2Validation.require(
        chunks.zipWithIndex.forall((chunk, index) =>
          chunk.index == index.toLong && chunk.count == chunks.size.toLong,
        ),
        FailureCode.NonCanonicalEncoding,
        "chunks.order/count",
      )
      _ <- V2Validation.require(
        chunks.map(_.witnessDigest).distinct.sizeCompare(1) == 0,
        FailureCode.CommitmentMismatch,
        "chunks.witnessDigest",
      )
      totalSize = chunks.foldLeft(0L)((sum, chunk) => sum + chunk.bytes.size)
      _ <- V2Validation.require(
        totalSize <= limits.maxWitnessBytes,
        FailureCode.ProtocolLimitExceeded,
        "witness.bytes",
      )
      bytes = chunks.foldLeft(ByteVector.empty)((all, chunk) =>
        all ++ chunk.bytes,
      )
      expected = Commitment.hash(
        Utf8("sigilaris.application.reservation.witness.v1"),
        bytes,
      )
      _ <- V2Validation.require(
        chunks.forall(_.witnessDigest == expected),
        FailureCode.CommitmentMismatch,
        "witness.digest",
      )
      witness <- codec.decode(bytes)
    yield witness

final case class WitnessChunk(
    format: Long,
    witnessDigest: Hash,
    index: Long,
    count: Long,
    bytes: Bytes,
)

object WitnessChunk:
  given ByteEncoder[WitnessChunk]         = ByteEncoder.derived
  given ByteDecoder[WitnessChunk]         = ByteDecoder.derived
  val codec: CanonicalCodec[WitnessChunk] = CanonicalCodec.derived(validate)

  def validate(value: WitnessChunk): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 1L, "chunk.format")
      _ <- V2Validation.require(
        value.count > 0L && value.count <= ProtocolLimits.V2.maxChunks && value.index >= 0L && value.index < value.count,
        FailureCode.ProtocolLimitExceeded,
        "chunk.index/count",
      )
      _ <- V2Validation.require(
        value.bytes.nonEmpty && value.bytes.size <= ProtocolLimits.V2.chunkBytes,
        FailureCode.ProtocolLimitExceeded,
        "chunk.bytes",
      )
      _ <- V2Validation.require(
        value.index + 1L == value.count || value.bytes.size == ProtocolLimits.V2.chunkBytes,
        FailureCode.NonCanonicalEncoding,
        "chunk.segmentation",
      )
    yield ()

  def digest(value: WitnessChunk): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment.hash(Utf8("sigilaris.application.reservation.chunk.v1"), _),
      )
