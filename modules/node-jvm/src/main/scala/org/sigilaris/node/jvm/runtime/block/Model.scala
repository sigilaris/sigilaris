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

/** Represents a failure encountered during block validation.
  *
  * @param reason
  *   short machine-readable failure reason
  * @param detail
  *   optional human-readable detail message
  */
final case class BlockValidationFailure(
    reason: String,
    detail: Option[String],
)

/** Companion for `BlockValidationFailure`. */
object BlockValidationFailure:

  /** Creates a `BlockValidationFailure` with no detail message.
    *
    * @param reason
    *   short machine-readable failure reason
    * @return
    *   a validation failure with `detail` set to `None`
    */
  def withoutDetail(
      reason: String,
  ): BlockValidationFailure =
    BlockValidationFailure(reason = reason, detail = None)

/** Unique identifier for a block, derived from the block header hash. */
opaque type BlockId = UInt256

/** Companion for `BlockId`. */
object BlockId:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  /** Wraps a `UInt256` as a `BlockId`.
    *
    * @param value
    *   the raw 256-bit value
    * @return
    *   the corresponding block identifier
    */
  def apply(
      value: UInt256,
  ): BlockId = value

  /** Parses a hex string into a `BlockId`.
    *
    * @param value
    *   hex-encoded 256-bit value
    * @return
    *   `Right(blockId)` on success, or `Left(error)` on parse failure
    */
  def fromHex(
      value: String,
  ): Either[String, BlockId] =
    UInt256.fromHex(value).leftMap(_.toString).map(apply)

  extension (blockId: BlockId)
    /** Unwraps the underlying `UInt256`. */
    def toUInt256: UInt256 = blockId

    /** Renders the block identifier as a lowercase hex string. */
    def toHexLower: String = renderHex(blockId)

  given ByteEncoder[BlockId] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[BlockId]          = Eq.by(_.toUInt256)

/** Zero-based height of a block within the chain. */
opaque type BlockHeight = BigNat

/** Companion for `BlockHeight`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object BlockHeight:

  /** Height of the genesis block (zero). */
  val Genesis: BlockHeight = BigNat.Zero

  /** Wraps a `BigNat` as a `BlockHeight`.
    *
    * @param value
    *   the non-negative natural number
    * @return
    *   the corresponding block height
    */
  def apply(
      value: BigNat,
  ): BlockHeight = value

  /** Converts a `Long` to a `BlockHeight`, failing if the value is negative.
    *
    * @param value
    *   non-negative long value
    * @return
    *   `Right(height)` on success, or `Left(error)` if negative
    */
  def fromLong(
      value: Long,
  ): Either[String, BlockHeight] =
    BigNat.fromBigInt(BigInt(value)).leftMap(identity).map(apply)

  /** Converts a `Long` to a `BlockHeight`, throwing on negative input.
    *
    * @param value
    *   non-negative long value
    * @return
    *   the corresponding block height
    * @throws IllegalArgumentException
    *   if the value is negative
    */
  def unsafeFromLong(
      value: Long,
  ): BlockHeight =
    fromLong(value) match
      case Right(height) => height
      case Left(error)   => throw new IllegalArgumentException(error)

  extension (height: BlockHeight)
    /** Unwraps the underlying `BigNat`. */
    def toBigNat: BigNat = height

    /** Renders the block height as a decimal string. */
    def render: String = height.toBigNat.toBigInt.toString

  given ByteEncoder[BlockHeight] = ByteEncoder[BigNat].contramap(_.toBigNat)
  given Eq[BlockHeight]          = Eq.by(_.toBigNat)
  given Ordering[BlockHeight] =
    Ordering.by[BlockHeight, BigNat](_.toBigNat)(using BigNat.bignatOrdering)

/** Cryptographic commitment to the global state at a given block. */
opaque type StateRoot = UInt256

/** Companion for `StateRoot`. */
object StateRoot:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  /** Wraps a `UInt256` as a `StateRoot`.
    *
    * @param value
    *   the raw 256-bit hash value
    * @return
    *   the corresponding state root
    */
  def apply(
      value: UInt256,
  ): StateRoot = value

  /** Parses a hex string into a `StateRoot`.
    *
    * @param value
    *   hex-encoded 256-bit value
    * @return
    *   `Right(stateRoot)` on success, or `Left(error)` on parse failure
    */
  def fromHex(
      value: String,
  ): Either[String, StateRoot] =
    UInt256.fromHex(value).leftMap(_.toString).map(apply)

  extension (stateRoot: StateRoot)
    /** Unwraps the underlying `UInt256`. */
    def toUInt256: UInt256 = stateRoot

    /** Renders the state root as a lowercase hex string. */
    def toHexLower: String = renderHex(stateRoot)

  given ByteEncoder[StateRoot] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[StateRoot]          = Eq.by(_.toUInt256)

/** Cryptographic commitment to the contents of a block body. */
opaque type BodyRoot = UInt256

/** Companion for `BodyRoot`. */
object BodyRoot:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  /** Wraps a `UInt256` as a `BodyRoot`.
    *
    * @param value
    *   the raw 256-bit hash value
    * @return
    *   the corresponding body root
    */
  def apply(
      value: UInt256,
  ): BodyRoot = value

  /** Parses a hex string into a `BodyRoot`.
    *
    * @param value
    *   hex-encoded 256-bit value
    * @return
    *   `Right(bodyRoot)` on success, or `Left(error)` on parse failure
    */
  def fromHex(
      value: String,
  ): Either[String, BodyRoot] =
    UInt256.fromHex(value).leftMap(_.toString).map(apply)

  extension (bodyRoot: BodyRoot)
    /** Unwraps the underlying `UInt256`. */
    def toUInt256: UInt256 = bodyRoot

    /** Renders the body root as a lowercase hex string. */
    def toHexLower: String = renderHex(bodyRoot)

  given ByteEncoder[BodyRoot] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[BodyRoot]          = Eq.by(_.toUInt256)

/** Millisecond-precision UTC timestamp associated with a block. */
opaque type BlockTimestamp = Long

/** Companion for `BlockTimestamp`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object BlockTimestamp:

  /** The Unix epoch (0 milliseconds) timestamp. */
  val Epoch: BlockTimestamp = 0L

  /** Creates a `BlockTimestamp` from epoch milliseconds, rejecting negative
    * values.
    *
    * @param value
    *   non-negative epoch milliseconds
    * @return
    *   `Right(timestamp)` on success, or `Left(error)` if negative
    */
  def fromEpochMillis(
      value: Long,
  ): Either[String, BlockTimestamp] =
    Either.cond(
      value >= 0L,
      value,
      "block timestamp must be non-negative",
    )

  /** Creates a `BlockTimestamp` from a `java.time.Instant`.
    *
    * @param value
    *   the instant to convert
    * @return
    *   `Right(timestamp)` on success, or `Left(error)` on overflow or negative
    *   epoch
    */
  def fromInstant(
      value: Instant,
  ): Either[String, BlockTimestamp] =
    Either
      .catchNonFatal(value.toEpochMilli)
      .leftMap(_.getMessage)
      .flatMap(fromEpochMillis)

  /** Creates a `BlockTimestamp` from epoch milliseconds, throwing on negative
    * input.
    *
    * @param value
    *   non-negative epoch milliseconds
    * @return
    *   the corresponding block timestamp
    * @throws IllegalArgumentException
    *   if the value is negative
    */
  def unsafeFromEpochMillis(
      value: Long,
  ): BlockTimestamp =
    fromEpochMillis(value) match
      case Right(timestamp) => timestamp
      case Left(error)      => throw new IllegalArgumentException(error)

  extension (timestamp: BlockTimestamp)
    /** Returns the timestamp as epoch milliseconds. */
    def toEpochMillis: Long = timestamp

    /** Converts the timestamp to a `java.time.Instant`. */
    def toInstant: Instant = Instant.ofEpochMilli(timestamp)

  given ByteEncoder[BlockTimestamp] =
    ByteEncoder[Long].contramap(_.toEpochMillis)
  given Eq[BlockTimestamp]       = Eq.by(_.toEpochMillis)
  given Ordering[BlockTimestamp] = Ordering.by(_.toEpochMillis)

/** Content-addressable hash of a single block record. */
opaque type BlockRecordHash = UInt256

/** Companion for `BlockRecordHash`. */
object BlockRecordHash:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  /** Wraps a `UInt256` as a `BlockRecordHash`.
    *
    * @param value
    *   the raw 256-bit hash value
    * @return
    *   the corresponding record hash
    */
  def apply(
      value: UInt256,
  ): BlockRecordHash = value

  /** Parses a hex string into a `BlockRecordHash`.
    *
    * @param value
    *   hex-encoded 256-bit value
    * @return
    *   `Right(hash)` on success, or `Left(error)` on parse failure
    */
  def fromHex(
      value: String,
  ): Either[String, BlockRecordHash] =
    UInt256.fromHex(value).leftMap(_.toString).map(apply)

  /** Computes the canonical hash for a given block record.
    *
    * @tparam TxRef
    *   transaction reference type
    * @tparam ResultRef
    *   result reference type
    * @tparam Event
    *   event type
    * @param record
    *   the block record to hash
    * @return
    *   the computed record hash
    */
  def compute[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      record: BlockRecord[TxRef, ResultRef, Event],
  ): BlockRecordHash =
    BlockRecordHash(BlockCanonicalEncoding.blockRecordHash(record))

  extension (recordHash: BlockRecordHash)
    /** Unwraps the underlying `UInt256`. */
    def toUInt256: UInt256 = recordHash

    /** Renders the record hash as a lowercase hex string. */
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

/** Immutable header of a block containing linkage and commitment data.
  *
  * @param parent
  *   identifier of the parent block, or `None` for the genesis block
  * @param height
  *   zero-based height of this block in the chain
  * @param stateRoot
  *   cryptographic commitment to the post-execution state
  * @param bodyRoot
  *   cryptographic commitment to the block body contents
  * @param timestamp
  *   UTC timestamp in epoch milliseconds when the block was produced
  */
final case class BlockHeader(
    parent: Option[BlockId],
    height: BlockHeight,
    stateRoot: StateRoot,
    bodyRoot: BodyRoot,
    timestamp: BlockTimestamp,
) derives ByteEncoder

/** Companion for `BlockHeader`. */
object BlockHeader:

  /** Returns the raw bytes used as pre-image input for computing the block
    * identifier.
    *
    * @param header
    *   the block header
    * @return
    *   the canonical byte representation before hashing
    */
  def idPreImage(
      header: BlockHeader,
  ): ByteVector =
    BlockCanonicalEncoding.blockHeaderIdPreImage(header)

  /** Computes the unique `BlockId` for the given header.
    *
    * @param header
    *   the block header
    * @return
    *   the deterministic block identifier
    */
  def computeId(
      header: BlockHeader,
  ): BlockId =
    BlockId(BlockCanonicalEncoding.blockHeaderId(header))

/** A single record within a block body, linking a transaction to its result and
  * emitted events.
  *
  * @tparam TxRef
  *   transaction reference type
  * @tparam ResultRef
  *   result reference type
  * @tparam Event
  *   event type
  * @param tx
  *   reference to the originating transaction
  * @param result
  *   optional reference to the execution result
  * @param events
  *   events emitted during execution
  */
final case class BlockRecord[TxRef, ResultRef, Event](
    tx: TxRef,
    result: Option[ResultRef],
    events: Vector[Event],
) derives ByteEncoder

/** The body of a block, containing the set of records included in it.
  *
  * Duplicate `BlockRecordHash` rejection is owned by the canonical
  * validation/root-computation helpers so callers can assemble generic bodies
  * before the consensus boundary validates them.
  *
  * @tparam TxRef
  *   transaction reference type
  * @tparam ResultRef
  *   result reference type
  * @tparam Event
  *   event type
  * @param records
  *   the set of block records comprising this body
  */
final case class BlockBody[TxRef, ResultRef, Event](
    records: Set[BlockRecord[TxRef, ResultRef, Event]],
)

/** Companion for `BlockBody` providing canonical ordering, root computation,
  * and verification.
  */
object BlockBody:

  /** Sorts the body's records by their canonical hash and rejects duplicates.
    *
    * @tparam TxRef
    *   transaction reference type
    * @tparam ResultRef
    *   result reference type
    * @tparam Event
    *   event type
    * @param body
    *   the block body to canonicalise
    * @return
    *   sorted hash/record pairs, or a validation failure on duplicate hashes
    */
  def canonicalEntries[
      TxRef: ByteEncoder,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      body: BlockBody[TxRef, ResultRef, Event],
  ): Either[BlockValidationFailure, Vector[
    (BlockRecordHash, BlockRecord[TxRef, ResultRef, Event]),
  ]] =
    val sortedEntries =
      body.records.toVector
        .map: record =>
          BlockRecordHash.compute(record) -> record
        .sortBy(_._1)

    sortedEntries
      .sliding(2)
      .collectFirst:
        case Vector((recordHash, _), (nextHash, _))
            if recordHash === nextHash =>
          recordHash
      .fold(
        sortedEntries.asRight[BlockValidationFailure],
      ): duplicate =>
        BlockValidationFailure(
          reason = "duplicateRecordHash",
          detail = Some(duplicate.toHexLower),
        ).asLeft[Vector[
          (BlockRecordHash, BlockRecord[TxRef, ResultRef, Event]),
        ]]

  /** Computes the raw bytes used as pre-image input for the body root hash.
    *
    * @tparam TxRef
    *   transaction reference type
    * @tparam ResultRef
    *   result reference type
    * @tparam Event
    *   event type
    * @param body
    *   the block body
    * @return
    *   the canonical byte representation, or a validation failure
    */
  def bodyRootPreImage[
      TxRef: ByteEncoder,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      body: BlockBody[TxRef, ResultRef, Event],
  ): Either[BlockValidationFailure, ByteVector] =
    canonicalEntries(body).map: entries =>
      BlockCanonicalEncoding.bodyRootPreImage(entries)

  /** Computes the `BodyRoot` hash for a block body.
    *
    * @tparam TxRef
    *   transaction reference type
    * @tparam ResultRef
    *   result reference type
    * @tparam Event
    *   event type
    * @param body
    *   the block body
    * @return
    *   the computed body root, or a validation failure
    */
  def computeBodyRoot[
      TxRef: ByteEncoder,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      body: BlockBody[TxRef, ResultRef, Event],
  ): Either[BlockValidationFailure, BodyRoot] =
    bodyRootPreImage(body).map: bytes =>
      BodyRoot(BlockCanonicalEncoding.bodyRoot(bytes))

  /** Verifies that a block body matches the expected `BodyRoot`.
    *
    * @tparam TxRef
    *   transaction reference type
    * @tparam ResultRef
    *   result reference type
    * @tparam Event
    *   event type
    * @param body
    *   the block body to verify
    * @param expectedRoot
    *   the expected body root commitment
    * @return
    *   `Right(())` if the roots match, or a validation failure describing the
    *   mismatch
    */
  def verifyBodyRoot[
      TxRef: ByteEncoder,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      body: BlockBody[TxRef, ResultRef, Event],
      expectedRoot: BodyRoot,
  ): Either[BlockValidationFailure, Unit] =
    computeBodyRoot(body).flatMap: (actualRoot: BodyRoot) =>
      val expectedHex: String    = expectedRoot.toHexLower
      val actualHex: String      = actualRoot.toHexLower
      val mismatchDetail: String = ss"expected=$expectedHex actual=$actualHex"
      Either.cond(
        actualRoot === expectedRoot,
        (),
        BlockValidationFailure(
          reason = "bodyRootMismatch",
          detail = Some(mismatchDetail),
        ),
      )

/** Combined view of a block header and its body.
  *
  * @tparam TxRef
  *   transaction reference type
  * @tparam ResultRef
  *   result reference type
  * @tparam Event
  *   event type
  * @param header
  *   the block header
  * @param body
  *   the block body
  */
final case class BlockView[TxRef, ResultRef, Event](
    header: BlockHeader,
    body: BlockBody[TxRef, ResultRef, Event],
)

/** Companion for `BlockView`. */
object BlockView:
  /** Validates that the body root in the header matches the actual body
    * contents.
    *
    * This check is intentionally limited to the header/body commitment
    * contract. Header-level progress rules such as genesis-parent shape or
    * consensus-owned height linkage belong to the enclosing validation path.
    *
    * @tparam TxRef
    *   transaction reference type
    * @tparam ResultRef
    *   result reference type
    * @tparam Event
    *   event type
    * @param view
    *   the block view to validate
    * @return
    *   `Right(())` if valid, or a `BlockValidationFailure` describing the
    *   mismatch
    */
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

  def blockRecordHash[
      TxRef: ByteEncoder,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
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
  def bodyRootPreImage[
      TxRef: ByteEncoder,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
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
