package org.sigilaris.core.application.support

import scodec.bits.ByteVector
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.BigNat

import scala.compiletime.{constValue, erasedValue}

/** Length-prefix encoding for a non-negative integer.
  *
  * Encodes the length as a BigNat using the standard BigNat byte encoder.
  *
  * @param n
  *   the length to encode (must be non-negative)
  * @return
  *   the byte representation
  */
inline def lenBytes(n: Int): ByteVector =
  BigNat.unsafeFromBigInt(BigInt(n)).toBytes

/** Encode a string segment with length-prefix and null terminator.
  *
  * Format: length(segment_bytes) ++ segment_bytes ++ 0x00
  *
  * The length-prefix ensures we can read back the exact segment, and the null
  * terminator provides a clear boundary for debugging and visual inspection.
  *
  * This encoding is prefix-free: no encoded segment can be a prefix of another.
  *
  * @tparam S
  *   the string literal type
  * @return
  *   the encoded byte vector
  */
inline def encodeSegment[S <: String]: ByteVector =
  val str   = constValue[S]
  val bytes = str.getBytes("UTF-8")
  lenBytes(bytes.length) ++ ByteVector.view(bytes) ++ ByteVector(0x00)

/** Encode a path (tuple of string segments) into a byte vector.
  *
  * Format: length(num_segments) ++ segment1 ++ segment2 ++ ...
  *
  * The path-level length header ensures that shorter paths cannot be prefixes
  * of longer paths, even when segments are empty. This is critical for the
  * prefix-free guarantee across all table prefixes.
  *
  * @tparam Path
  *   the path tuple type
  * @return
  *   the encoded path
  */
inline def encodePath[Path <: Tuple]: ByteVector =
  val segCount = constValue[Tuple.Size[Path]]
  val header   = lenBytes(segCount)
  encodePathAcc[Path](header)

/** Internal helper for encoding paths with accumulator.
  *
  * @tparam Path
  *   the path tuple type
  * @param acc
  *   the accumulator for encoded bytes
  * @return
  *   the encoded path
  */
@SuppressWarnings(Array("org.wartremover.warts.Recursion"))
private inline def encodePathAcc[Path <: Tuple](acc: ByteVector): ByteVector =
  inline erasedValue[Path] match
    case _: EmptyTuple => acc
    case _: (h *: t) =>
      val segment: ByteVector = encodeSegment[h & String]
      val newAcc: ByteVector  = acc ++ segment
      encodePathAcc[t](newAcc)

/** Compute the full table prefix for a table at the given path.
  *
  * The prefix is: encodePath(Path) ++ encodeSegment(Name)
  *
  * This is the byte prefix that will be prepended to all keys stored in this
  * table.
  *
  * @tparam Path
  *   the mount path tuple
  * @tparam Name
  *   the table name
  * @return
  *   the full table prefix
  */
inline def tablePrefix[Path <: Tuple, Name <: String]: ByteVector =
  val pathBytes: ByteVector = encodePath[Path]
  val nameBytes: ByteVector = encodeSegment[Name]
  val result: ByteVector    = pathBytes ++ nameBytes
  result

/** Runtime version of lenBytes.
  *
  * Encodes the length as a BigNat using the standard BigNat byte encoder.
  *
  * @param n
  *   the length to encode (must be non-negative)
  * @return
  *   the byte representation
  */
def lenBytesRuntime(n: Int): ByteVector =
  BigNat.unsafeFromBigInt(BigInt(n)).toBytes

/** Runtime encoder for string segments.
  *
  * Encodes a string at runtime using the same format as encodeSegment.
  *
  * @param s
  *   the string to encode
  * @return
  *   the encoded byte vector
  */
def encodeSegmentRuntime(s: String): ByteVector =
  val bytes = s.getBytes("UTF-8")
  lenBytesRuntime(bytes.length) ++ ByteVector.view(bytes) ++ ByteVector(0x00)

/** Typeclass for encoding paths at runtime.
  *
  * This allows encoding paths when the path type is known at compile time but
  * we need to compute the bytes at runtime (e.g., in typeclass derivation).
  *
  * @tparam Path
  *   the path tuple type
  */
trait PathEncoder[Path <: Tuple]:
  /** Encode the path to bytes.
    *
    * @return
    *   the encoded path bytes
    */
  def encode: ByteVector

/** Helper to collect path segments as a list for encoding. */
trait SegmentCollector[T <: Tuple]:
  def collect: List[String]

object SegmentCollector:
  given emptyCollector: SegmentCollector[EmptyTuple] with
    def collect: List[String] = Nil

  given consCollector[H <: String, T <: Tuple](using
      ev: ValueOf[H],
      tailCollector: SegmentCollector[T],
  ): SegmentCollector[H *: T] with
    def collect: List[String] = ev.value :: tailCollector.collect

object PathEncoder:
  /** Base case: empty path encodes to length header (0) only. */
  given empty: PathEncoder[EmptyTuple] with
    def encode: ByteVector = lenBytesRuntime(0)

  /** Inductive case: encode with path-level length header. */
  given cons[H <: String, T <: Tuple](using
      collector: SegmentCollector[H *: T],
  ): PathEncoder[H *: T] with
    def encode: ByteVector =
      val segments = collector.collect
      encodePathRuntime(segments)

/** Compute the table prefix at runtime using PathEncoder.
  *
  * This is a runtime version of tablePrefix that works with abstract type
  * parameters.
  *
  * @tparam Path
  *   the mount path tuple
  * @param tableName
  *   the table name
  * @param pathEncoder
  *   the path encoder (automatically derived)
  * @return
  *   the full table prefix
  */
def tablePrefixRuntime[Path <: Tuple](tableName: String)(using
    pathEncoder: PathEncoder[Path],
): ByteVector =
  val pathBytes = pathEncoder.encode
  val nameBytes = encodeSegmentRuntime(tableName)
  pathBytes ++ nameBytes

/** Runtime encoding for a path given as a List[String].
  *
  * Format: length(num_segments) ++ segment1 ++ segment2 ++ ...
  *
  * The path-level length header ensures prefix-freedom across paths of
  * different lengths.
  *
  * @param path
  *   the path segments as a list
  * @return
  *   the encoded path bytes
  */
def encodePathRuntime(path: List[String]): ByteVector =
  val header = lenBytesRuntime(path.length)
  path.foldLeft(header)((acc, segment) => acc ++ encodeSegmentRuntime(segment))

/** Runtime computation of table prefix from a dynamic path list.
  *
  * This is useful for property testing where both paths and table names are
  * generated dynamically.
  *
  * @param path
  *   the path segments as a list
  * @param tableName
  *   the table name
  * @return
  *   the full table prefix
  */
def tablePrefixRuntimeFromList(path: List[String], tableName: String): ByteVector =
  encodePathRuntime(path) ++ encodeSegmentRuntime(tableName)
