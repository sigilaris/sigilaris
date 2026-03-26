package org.sigilaris.core
package codec.byte

import java.time.Instant

import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror

import cats.syntax.eq.*

import scodec.bits.ByteVector

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

/** Type class for encoding Scala values to deterministic byte sequences.
  *
  * ByteEncoder provides type-safe encoding of Scala data structures into
  * ByteVector representations suitable for blockchain applications such as
  * transaction signing, block hashing, and merkle tree construction.
  *
  * The encoding is deterministic: the same value always produces the same byte
  * sequence, ensuring consistency across different platforms and nodes.
  *
  * @see
  *   [[ByteDecoder]] for the inverse operation
  * @see
  *   [[ByteCodec]] for bidirectional encoding/decoding
  * @see
  *   types.md for detailed encoding rules per type
  */
trait ByteEncoder[A]:
  /** Encodes a value to a deterministic byte sequence.
    *
    * @param value
    *   the value to encode
    * @return
    *   the encoded byte sequence
    */
  def encode(value: A): ByteVector

  /** Creates a new encoder by applying a function before encoding.
    *
    * This is the contravariant map operation for encoders.
    *
    * @param f
    *   function to transform input before encoding
    * @return
    *   new encoder for type B
    *
    * @example
    * ```scala
    * case class UserId(value: Long)
    * given ByteEncoder[UserId] = ByteEncoder[Long].contramap(_.value)
    * ```
    */
  def contramap[B](f: B => A): ByteEncoder[B] = value => encode(f(value))

object ByteEncoder:
  /** Summons a ByteEncoder instance for type A. */
  def apply[A: ByteEncoder]: ByteEncoder[A] = summon

  /** Extension methods for encoding values to bytes. */
  object ops:
    extension [A: ByteEncoder](a: A)
      /** Encodes this value to bytes.
        *
        * @example
        * ```scala
        * import ByteEncoder.ops.*
        * val bytes = 42L.toBytes
        * ```
        */
      def toBytes: ByteVector = ByteEncoder[A].encode(a)

  @SuppressWarnings(
    Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Any"),
  )
  private def encoderProduct[A](
      elems: => List[ByteEncoder[?]],
  ): ByteEncoder[A] = (a: A) =>
    a.asInstanceOf[Product]
      .productIterator
      .zip(elems)
      .map { case (aElem, encoder) =>
        encoder.asInstanceOf[ByteEncoder[Any]].encode(aElem)
      }
      .foldLeft(ByteVector.empty)(_ ++ _)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  inline def summonAll[T <: Tuple]: List[ByteEncoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[ByteEncoder[t]] :: summonAll[ts]

  /** Automatic derivation for product types (case classes, tuples).
    *
    * Encodes each field in order and concatenates the results.
    *
    * @see
    *   types.md for product encoding details
    */
  inline given derived[T](using p: Mirror.ProductOf[T]): ByteEncoder[T] =
    lazy val elemInstances: List[ByteEncoder[?]] =
      summonAll[p.MirroredElemTypes]
    encoderProduct(elemInstances)

  /** Encodes Unit as empty byte sequence.
    *
    * @see
    *   types.md for Unit encoding rules
    */
  given unitByteEncoder: ByteEncoder[Unit] = _ => ByteVector.empty

  /** Encodes Byte as single byte. */
  given byteEncoder: ByteEncoder[Byte] = ByteVector.fromByte(_)

  /** Encodes Long as 8-byte big-endian representation. */
  given longEncoder: ByteEncoder[Long] = ByteVector.fromLong(_)

  /** Encodes Instant as epoch milliseconds (Long encoding).
    *
    * @see
    *   types.md for Instant encoding rules
    */
  given instantEncoder: ByteEncoder[Instant] =
    ByteVector fromLong _.toEpochMilli

  /** Natural number type (non-negative BigInt). */
  type BigNat = BigInt :| Positive0

  /** Encodes natural numbers with variable-length encoding.
    *
    * Encoding rules:
    *   - 0x00 ~ 0x80: single byte for values 0-128
    *   - 0x81 ~ 0xf7: [0x80+len][data] for 1-119 byte data
    *   - 0xf8 ~ 0xff: [0xf8+(ll-1)][len][data] for 120+ byte data
    *
    * @see
    *   types.md for complete BigNat encoding specification
    */
  given bignatByteEncoder: ByteEncoder[BigNat] = bignat =>
    val bytes = ByteVector.view(bignat.toByteArray).dropWhile(_ === 0x00.toByte)
    if bytes.isEmpty then ByteVector(0x00.toByte)
    else if bignat <= 0x80 then bytes
    else
      val size = bytes.size
      if size < (0xf8 - 0x80) + 1 then
        ByteVector.fromByte((size + 0x80).toByte) ++ bytes
      else
        val sizeBytes = ByteVector.fromLong(size).dropWhile(_ === 0x00.toByte)
        ByteVector.fromByte(
          (sizeBytes.size + 0xf8 - 1).toByte,
        ) ++ sizeBytes ++ bytes

  /** Encodes signed integers using sign-magnitude encoding.
    *
    * Encoding transformation:
    *   - Positive n: encode (n * 2) as BigNat
    *   - Negative n: encode (n * (-2) + 1) as BigNat
    *
    * This ensures deterministic encoding while preserving sign information.
    *
    * @see
    *   types.md for BigInt encoding examples
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  given bigintByteEncoder: ByteEncoder[BigInt] = ByteEncoder[BigNat].contramap:
    case n if n >= 0 => (n * 2).asInstanceOf[BigNat]
    case n           => (n * (-2) + 1).asInstanceOf[BigNat]

  private def encodeSize(size: Int): ByteVector =
    bignatByteEncoder.encode:
      BigInt(size).refineUnsafe[Positive0]

  /** Encodes List as [size:BigNat][elem1][elem2]...[elemN].
    *
    * Preserves order of elements.
    *
    * @see
    *   types.md for List encoding rules
    */
  given listByteEncoder[A: ByteEncoder]: ByteEncoder[List[A]] =
    (list: List[A]) =>
      list.foldLeft(encodeSize(list.size)):
        case (acc, a) => acc ++ ByteEncoder[A].encode(a)

  /** Encodes Option as zero or one-element List.
    *
    * Encoding:
    *   - None: [0x00]
    *   - Some(x): [0x01][x encoded]
    *
    * @see
    *   types.md for Option encoding rules
    */
  given optionByteEncoder[A: ByteEncoder]: ByteEncoder[Option[A]] =
    listByteEncoder.contramap(_.toList)

  /** Encodes Set with deterministic ordering.
    *
    * Elements are encoded individually, then sorted lexicographically by their
    * byte representation before concatenation. This ensures deterministic
    * encoding regardless of Set iteration order.
    *
    * @see
    *   types.md for Set encoding and sorting rules
    */
  given setByteEncoder[A: ByteEncoder]: ByteEncoder[Set[A]] = (set: Set[A]) =>
    set
      .map(ByteEncoder[A].encode)
      .toList
      .sorted
      .foldLeft(encodeSize(set.size))(_ ++ _)

  /** Encodes Map as deterministically sorted Set of tuples.
    *
    * Converted to Set[(K, V)] and encoded with Set's deterministic ordering.
    *
    * @see
    *   types.md for Map encoding rules
    */
  given mapByteEncoder[K: ByteEncoder, V: ByteEncoder]: ByteEncoder[Map[K, V]] =
    setByteEncoder[(K, V)].contramap(_.toSet)
