package org.sigilaris.core
package merkle

import cats.Eq
import cats.syntax.either.*
import cats.syntax.eq.given

import io.github.iltotore.iron.{:|, assume, refineEither, refineUnsafe}
import io.github.iltotore.iron.constraint.collection.Length
import io.github.iltotore.iron.constraint.numeric.Multiple

import scodec.bits.{BitVector, ByteVector}

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.byte.ByteEncoder.ops.*
import datatype.BigNat
import failure.DecodeFailure
import util.iron.given

/** Represents a bit vector aligned to 4-bit boundaries.
  *
  * Used to represent key paths in Merkle Tries, where each nibble
  * has a value in the range 0-15.
  *
  * @example
  * ```scala
  * val bytes = ByteVector(0x12, 0x34)
  * val nibbles = bytes.toNibbles
  * nibbles.hex // "1234"
  * nibbles.nibbleSize // 4
  * ```
  *
  * @note The bit size must always be a multiple of 4.
  */
opaque type Nibbles = BitVector :| Nibbles.NibbleCond

/** Companion object for Nibbles providing creation and manipulation methods. */
object Nibbles:
  type NibbleCond = Length[Multiple[4L]]

  /** Empty Nibbles value. */
  val empty: Nibbles = BitVector.empty.assumeNibbles

  /** Combines multiple Nibbles into one.
    *
    * @param nibbles sequence of Nibbles to combine
    * @return combined Nibbles
    */
  def combine(nibbles: Nibbles*): Nibbles =
    nibbles.foldLeft(BitVector.empty)(_ ++ _.value).assumeNibbles

  extension (nibbles: Nibbles)
    /** Returns the underlying BitVector value. */
    def value: BitVector  = nibbles

    /** Converts to ByteVector. */
    def bytes: ByteVector = nibbles.bytes

    /** Returns the number of nibbles (bit size / 4). */
    def nibbleSize: Long  = nibbles.size / 4L

    /** Splits the first nibble and the remainder.
      *
      * @return first nibble value (0-15) and remaining Nibbles, or None if empty
      */
    def unCons: Option[(Int, Nibbles)] =
      if nibbles.isEmpty then None
      else
        val head = nibbles.value.take(4).toInt(signed = false)
        val tail = nibbles.value.drop(4).assumeNibbles
        Some((head, tail))

    /** Removes the given prefix if present.
      *
      * @param prefix the prefix to remove
      * @return remainder after removing prefix, or None if prefix not found
      */
    def stripPrefix(prefix: Nibbles): Option[Nibbles] =
      if nibbles.startsWith(prefix) then
        Some(nibbles.drop(prefix.size).assumeNibbles)
      else None

    /** Compares two Nibbles lexicographically.
      *
      * @param that the Nibbles to compare with
      * @return negative (this < that), 0 (equal), positive (this > that)
      */
    def compareTo(that: Nibbles): Int =
      val thisBytes = nibbles.bytes
      val thatBytes = that.bytes
      val minSize   = thisBytes.size min thatBytes.size

      (0L `until` minSize)
        .find: i =>
          thisBytes.get(i) =!= thatBytes.get(i)
        .fold(thisBytes.size compareTo thatBytes.size): i =>
          (thisBytes.get(i) & 0xff) compare (thatBytes.get(i) & 0xff)

    /** Checks if this Nibbles is lexicographically less than or equal to that. */
    def <=(that: Nibbles): Boolean = compareTo(that) <= 0

    /** Checks if this Nibbles is lexicographically less than that. */
    def <(that: Nibbles): Boolean  = compareTo(that) < 0

    /** Checks if this Nibbles is lexicographically greater than or equal to that. */
    def >=(that: Nibbles): Boolean = compareTo(that) >= 0

    /** Checks if this Nibbles is lexicographically greater than that. */
    def >(that: Nibbles): Boolean  = compareTo(that) > 0

    /** Converts to hexadecimal string. */
    def hex: String = value.toHex

  extension (bitVector: BitVector)
    /** Converts BitVector to Nibbles with validation.
      *
      * @return Right(Nibbles) on success, Left(error) on failure
      */
    def refineToNibble: Either[String, Nibbles] =
      bitVector.refineEither[Length[Multiple[4L]]]

    /** Converts BitVector to Nibbles without validation.
      *
      * @note Throws runtime error if the constraint is not satisfied.
      */
    def assumeNibbles: Nibbles = bitVector.assume[Nibbles.NibbleCond]

  extension (byteVector: ByteVector)
    /** Converts ByteVector to Nibbles.
      *
      * @note ByteVector always has a multiple of 8 bits, so conversion is safe.
      */
    def toNibbles: Nibbles = byteVector.bits.refineUnsafe[Length[Multiple[4L]]]

  /** Encodes Nibbles to bytes.
    *
    * Encoding format: nibble count (BigNat) + nibble bytes
    */
  given nibblesByteEncoder: ByteEncoder[Nibbles] = (nibbles: Nibbles) =>
    BigNat.unsafeFromLong(nibbles.size / 4).toBytes ++ nibbles.bytes

  /** Decodes Nibbles from bytes.
    *
    * Decoding format: nibble count (BigNat) + nibble bytes
    */
  given nibblesByteDecoder: ByteDecoder[Nibbles] =
    ByteDecoder[BigNat].flatMap: nibbleSize =>
      val nibbleSizeLong = nibbleSize.toBigInt.toLong
      ByteDecoder
        .fromFixedSizeBytes((nibbleSizeLong + 1) / 2): nibbleBytes =>
          val bitsSize = nibbleSizeLong * 4
          val padSize  = bitsSize - nibbleBytes.size * 8
          val nibbleBits =
            if padSize > 0 then nibbleBytes.bits.padLeft(padSize)
            else nibbleBytes.bits
          nibbleBits.take(bitsSize)
        .emap(_.refineToNibble.leftMap(DecodeFailure(_)))

  /** Cats Eq instance for Nibbles equality comparison. */
  given nibblesEq: Eq[Nibbles] = Eq.fromUniversalEquals
