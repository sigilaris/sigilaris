package org.sigilaris.node.jvm.runtime.block

import java.time.Instant
import java.util.Arrays

import cats.Eq
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}
import org.sigilaris.core.util.SafeStringInterp.*

private object BlockEncodingInstances:
  given [A: ByteEncoder]: ByteEncoder[Vector[A]] =
    ByteEncoder[List[A]].contramap(_.toList)

import BlockEncodingInstances.given

final case class BlockValidationFailure(
    reason: String,
    detail: Option[String],
)

object BlockValidationFailure:
  def withoutDetail(
      reason: String,
  ): BlockValidationFailure =
    BlockValidationFailure(reason = reason, detail = None)

opaque type BlockId = UInt256

object BlockId:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(
      value: UInt256,
  ): BlockId = value

  def fromHex(
      value: String,
  ): Either[String, BlockId] =
    UInt256.fromHex(value).leftMap(_.toString).map(apply)

  extension (blockId: BlockId)
    def toUInt256: UInt256 = blockId
    def toHexLower: String = renderHex(blockId)

  given ByteEncoder[BlockId] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[BlockId]          = Eq.by(_.toUInt256)

opaque type BlockHeight = BigNat

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object BlockHeight:
  val Genesis: BlockHeight = BigNat.Zero

  def apply(
      value: BigNat,
  ): BlockHeight = value

  def fromLong(
      value: Long,
  ): Either[String, BlockHeight] =
    BigNat.fromBigInt(BigInt(value)).leftMap(identity).map(apply)

  def unsafeFromLong(
      value: Long,
  ): BlockHeight =
    fromLong(value) match
      case Right(height) => height
      case Left(error)   => throw new IllegalArgumentException(error)

  extension (height: BlockHeight)
    def toBigNat: BigNat = height
    def render: String   = height.toBigNat.toBigInt.toString

  given ByteEncoder[BlockHeight] = ByteEncoder[BigNat].contramap(_.toBigNat)
  given Eq[BlockHeight]          = Eq.by(_.toBigNat)
  given Ordering[BlockHeight] =
    Ordering.by[BlockHeight, BigNat](_.toBigNat)(using BigNat.bignatOrdering)

opaque type StateRoot = UInt256

object StateRoot:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(
      value: UInt256,
  ): StateRoot = value

  def fromHex(
      value: String,
  ): Either[String, StateRoot] =
    UInt256.fromHex(value).leftMap(_.toString).map(apply)

  extension (stateRoot: StateRoot)
    def toUInt256: UInt256 = stateRoot
    def toHexLower: String = renderHex(stateRoot)

  given ByteEncoder[StateRoot] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[StateRoot]          = Eq.by(_.toUInt256)

opaque type BodyRoot = UInt256

object BodyRoot:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(
      value: UInt256,
  ): BodyRoot = value

  def fromHex(
      value: String,
  ): Either[String, BodyRoot] =
    UInt256.fromHex(value).leftMap(_.toString).map(apply)

  extension (bodyRoot: BodyRoot)
    def toUInt256: UInt256 = bodyRoot
    def toHexLower: String = renderHex(bodyRoot)

  given ByteEncoder[BodyRoot] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[BodyRoot]          = Eq.by(_.toUInt256)

opaque type BlockTimestamp = Long

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object BlockTimestamp:
  val Epoch: BlockTimestamp = 0L

  def fromEpochMillis(
      value: Long,
  ): Either[String, BlockTimestamp] =
    Either.cond(
      value >= 0L,
      value,
      "block timestamp must be non-negative",
    )

  def fromInstant(
      value: Instant,
  ): Either[String, BlockTimestamp] =
    Either
      .catchNonFatal(value.toEpochMilli)
      .leftMap(_.getMessage)
      .flatMap(fromEpochMillis)

  def unsafeFromEpochMillis(
      value: Long,
  ): BlockTimestamp =
    fromEpochMillis(value) match
      case Right(timestamp) => timestamp
      case Left(error)      => throw new IllegalArgumentException(error)

  extension (timestamp: BlockTimestamp)
    def toEpochMillis: Long = timestamp
    def toInstant: Instant  = Instant.ofEpochMilli(timestamp)

  given ByteEncoder[BlockTimestamp] =
    ByteEncoder[Long].contramap(_.toEpochMillis)
  given Eq[BlockTimestamp]       = Eq.by(_.toEpochMillis)
  given Ordering[BlockTimestamp] = Ordering.by(_.toEpochMillis)

opaque type BlockRecordHash = UInt256

object BlockRecordHash:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(
      value: UInt256,
  ): BlockRecordHash = value

  def fromHex(
      value: String,
  ): Either[String, BlockRecordHash] =
    UInt256.fromHex(value).leftMap(_.toString).map(apply)

  def compute[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      record: BlockRecord[TxRef, ResultRef, Event],
  ): BlockRecordHash =
    BlockRecordHash(BlockCanonicalEncoding.blockRecordHash(record))

  extension (recordHash: BlockRecordHash)
    def toUInt256: UInt256 = recordHash
    def toHexLower: String = renderHex(recordHash)

  given ByteEncoder[BlockRecordHash] =
    ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[BlockRecordHash] = Eq.by(_.toUInt256)
  given Ordering[BlockRecordHash] with
    override def compare(
        left: BlockRecordHash,
        right: BlockRecordHash,
    ): Int =
      Arrays.compareUnsigned(
        left.toUInt256.bytes.toArray,
        right.toUInt256.bytes.toArray,
      )

final case class BlockHeader(
    parent: Option[BlockId],
    height: BlockHeight,
    stateRoot: StateRoot,
    bodyRoot: BodyRoot,
    timestamp: BlockTimestamp,
) derives ByteEncoder

object BlockHeader:
  def idPreImage(
      header: BlockHeader,
  ): ByteVector =
    BlockCanonicalEncoding.blockHeaderIdPreImage(header)

  def computeId(
      header: BlockHeader,
  ): BlockId =
    BlockId(BlockCanonicalEncoding.blockHeaderId(header))

final case class BlockRecord[TxRef, ResultRef, Event](
    tx: TxRef,
    result: Option[ResultRef],
    events: Vector[Event],
) derives ByteEncoder

// `BlockBody` is the raw application-facing membership surface. Duplicate
// `BlockRecordHash` rejection is intentionally owned by the canonical
// validation/root-computation helpers so callers can assemble generic bodies
// before the consensus boundary validates them. Structurally identical records
// may still be merged by the runtime `Set`; the canonical helpers are
// responsible for rejecting distinct surviving members that share the same
// `BlockRecordHash`.
final case class BlockBody[TxRef, ResultRef, Event](
    records: Set[BlockRecord[TxRef, ResultRef, Event]],
)

object BlockBody:
  def canonicalEntries[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      body: BlockBody[TxRef, ResultRef, Event],
  ): Either[BlockValidationFailure, Vector[
    (BlockRecordHash, BlockRecord[TxRef, ResultRef, Event]),
  ]] =
    val sortedEntries =
      body.records.toVector.map: record =>
        BlockRecordHash.compute(record) -> record
      .sortBy(_._1)

    sortedEntries
      .sliding(2)
      .collectFirst:
        case Vector((recordHash, _), (nextHash, _)) if recordHash === nextHash =>
          recordHash
      .fold(
        sortedEntries.asRight[BlockValidationFailure],
      ): duplicate =>
        BlockValidationFailure(
          reason = "duplicateRecordHash",
          detail = Some(duplicate.toHexLower),
        ).asLeft[Vector[(BlockRecordHash, BlockRecord[TxRef, ResultRef, Event])]]

  def bodyRootPreImage[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      body: BlockBody[TxRef, ResultRef, Event],
  ): Either[BlockValidationFailure, ByteVector] =
    canonicalEntries(body).map: entries =>
      BlockCanonicalEncoding.bodyRootPreImage(entries)

  def computeBodyRoot[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      body: BlockBody[TxRef, ResultRef, Event],
  ): Either[BlockValidationFailure, BodyRoot] =
    bodyRootPreImage(body).map: bytes =>
      BodyRoot(BlockCanonicalEncoding.bodyRoot(bytes))

  def verifyBodyRoot[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      body: BlockBody[TxRef, ResultRef, Event],
      expectedRoot: BodyRoot,
  ): Either[BlockValidationFailure, Unit] =
    computeBodyRoot(body).flatMap: (actualRoot: BodyRoot) =>
      val expectedHex: String = expectedRoot.toHexLower
      val actualHex: String   = actualRoot.toHexLower
      val mismatchDetail: String = ss"expected=$expectedHex actual=$actualHex"
      Either.cond(
        actualRoot === expectedRoot,
        (),
        BlockValidationFailure(
          reason = "bodyRootMismatch",
          detail = Some(mismatchDetail),
        ),
      )

final case class BlockView[TxRef, ResultRef, Event](
    header: BlockHeader,
    body: BlockBody[TxRef, ResultRef, Event],
)

object BlockView:
  // This check is intentionally limited to the header/body commitment contract.
  // Header-level progress rules such as genesis-parent shape or consensus-owned
  // height linkage belong to the enclosing validation path, not the generic
  // application-neutral block view surface.
  def validate[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      view: BlockView[TxRef, ResultRef, Event],
  ): Either[BlockValidationFailure, Unit] =
    BlockBody.verifyBodyRoot(view.body, view.header.bodyRoot)

private object BlockCanonicalEncoding:
  private val BlockHeaderIdDomain: Utf8 =
    Utf8("sigilaris.block.header.id.v1")
  private val BlockRecordHashDomain: Utf8 =
    Utf8("sigilaris.block.record.hash.v1")
  private val BlockBodyRootDomain: Utf8 =
    Utf8("sigilaris.block.body.root.v1")

  private final case class BlockHeaderIdInput(
      domain: Utf8,
      header: BlockHeader,
  ) derives ByteEncoder

  private final case class BlockRecordHashInput[TxRef, ResultRef, Event](
      domain: Utf8,
      record: BlockRecord[TxRef, ResultRef, Event],
  ) derives ByteEncoder

  private final case class BlockBodyRootInput[TxRef, ResultRef, Event](
      domain: Utf8,
      records: Vector[BlockRecord[TxRef, ResultRef, Event]],
  ) derives ByteEncoder

  def blockHeaderId(
      header: BlockHeader,
  ): UInt256 =
    hashBytes(blockHeaderIdPreImage(header))

  def blockHeaderIdPreImage(
      header: BlockHeader,
  ): ByteVector =
    BlockHeaderIdInput(
      domain = BlockHeaderIdDomain,
      header = header,
    ).toBytes

  def blockRecordHash[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      record: BlockRecord[TxRef, ResultRef, Event],
  ): UInt256 =
    hashEncoded(
      BlockRecordHashInput(
        domain = BlockRecordHashDomain,
        record = record,
      ),
    )

  // `BlockRecordHash` values decide the canonical ordering, but the body-root
  // commitment is over the sorted full records rather than the hashes
  // themselves so receivers can recompute the same commitment directly from a
  // fetched body without introducing a separate "hash list" body format.
  // Ordering/hash-scheme changes are versioned through the body root domain
  // string.
  def bodyRootPreImage[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      entries: Vector[(BlockRecordHash, BlockRecord[TxRef, ResultRef, Event])],
  ): ByteVector =
    BlockBodyRootInput(
      domain = BlockBodyRootDomain,
      records = entries.map(_._2),
    ).toBytes

  def bodyRoot(
      canonicalBytes: ByteVector,
  ): UInt256 =
    hashBytes(canonicalBytes)

  private def hashEncoded[A: ByteEncoder](
      value: A,
  ): UInt256 =
    hashBytes(value.toBytes)

  private def hashBytes(
      bytes: ByteVector,
  ): UInt256 =
    UInt256.unsafeFromBytesBE:
      ByteVector.view(CryptoOps.keccak256(bytes.toArray))
