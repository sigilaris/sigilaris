package org.sigilaris.core
package codec.byte

import java.time.Instant

import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror
import scala.reflect.{ClassTag, classTag}

import cats.syntax.either.*
import cats.syntax.eq.*

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0
import scodec.bits.ByteVector

import failure.DecodeFailure
import util.SafeStringInterp.*

/** Result of a decoding operation.
  *
  * @param value
  *   the decoded value
  * @param remainder
  *   remaining bytes after decoding
  */
final case class DecodeResult[A](value: A, remainder: ByteVector)

/** Type class for decoding byte sequences to Scala values.
  *
  * ByteDecoder is the inverse of ByteEncoder, reconstructing typed values from
  * deterministic byte representations.
  *
  * Decoding returns Either[DecodeFailure, DecodeResult[A]]:
  *   - Left(failure): decoding failed with error message
  *   - Right(result): successfully decoded value with remaining bytes
  *
  * The remainder allows sequential decoding of multiple values from a single
  * byte sequence.
  *
  * @see
  *   [[ByteEncoder]] for the encoding operation
  * @see
  *   [[ByteCodec]] for bidirectional encoding/decoding
  * @see
  *   types.md for detailed decoding rules per type
  */
trait ByteDecoder[A]:
  /** Decodes bytes to a value.
    *
    * @param bytes
    *   the byte sequence to decode
    * @return
    *   Either failure or decoded value with remainder
    */
  def decode(bytes: ByteVector): Either[DecodeFailure, DecodeResult[A]]

  /** Creates a new decoder by transforming the decoded value.
    *
    * @param f
    *   function to transform decoded value
    * @return
    *   new decoder for type B
    */
  def map[B](f: A => B): ByteDecoder[B] = bytes =>
    decode(bytes).map:
      case DecodeResult(value, remainder) => DecodeResult(f(value), remainder)

  /** Creates a new decoder with validation.
    *
    * @param f
    *   function that may fail with DecodeFailure
    * @return
    *   new decoder for type B
    *
    * @example
    * ```scala
    * val positiveDecoder = ByteDecoder[Long].emap { n =>
    *   Either.cond(n > 0, n, DecodeFailure("Must be positive"))
    * }
    * ```
    */
  def emap[B](f: A => Either[DecodeFailure, B]): ByteDecoder[B] = bytes =>
    for
      decoded   <- decode(bytes)
      converted <- f(decoded.value)
    yield DecodeResult(converted, decoded.remainder)

  /** Creates a new decoder by chaining decoders.
    *
    * @param f
    *   function that creates a decoder based on decoded value
    * @return
    *   new decoder for type B
    */
  def flatMap[B](f: A => ByteDecoder[B]): ByteDecoder[B] = bytes =>
    decode(bytes).flatMap:
      case DecodeResult(value, remainder) => f(value).decode(remainder)

  /** Widens the decoder to a supertype. */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def widen[AA >: A]: ByteDecoder[AA] = this.asInstanceOf[ByteDecoder[AA]]

object ByteDecoder:
  /** Summons a ByteDecoder instance for type A. */
  def apply[A: ByteDecoder]: ByteDecoder[A] = summon

  /** Extension methods for decoding bytes to values. */
  object ops:
    extension (bytes: ByteVector)
      /** Decodes bytes to a value, failing if remainder is non-empty.
        *
        * @return
        *   Either failure or fully decoded value
        *
        * @example
        * ```scala
        * import ByteDecoder.ops.*
        * val result: Either[DecodeFailure, Long] = bytes.to[Long]
        * ```
        */
      def to[A: ByteDecoder]: Either[DecodeFailure, A] = for
        result <- ByteDecoder[A].decode(bytes)
        DecodeResult[A](a, r) = result
        _ <- Either.cond(
          r.isEmpty,
          (),
          DecodeFailure(ss"non empty remainder: ${r.toHex}"),
        )
      yield a

  private def decoderProduct[A](
      p: Mirror.ProductOf[A],
      elems: => List[ByteDecoder[?]],
  ): ByteDecoder[A] = (bytes: ByteVector) =>

    def reverse(tuple: Tuple): Tuple =
      @SuppressWarnings(Array("org.wartremover.warts.Any"))
      @annotation.tailrec
      def loop(tuple: Tuple, acc: Tuple): Tuple = tuple match
        case _: EmptyTuple => acc
        case t *: ts       => loop(ts, t *: acc)
      loop(tuple, EmptyTuple)

    @SuppressWarnings(Array("org.wartremover.warts.Any"))
    @annotation.tailrec
    def loop(
        elems: List[ByteDecoder[?]],
        bytes: ByteVector,
        acc: Tuple,
    ): Either[DecodeFailure, DecodeResult[A]] = elems match
      case Nil =>
        (DecodeResult(p.fromProduct(reverse(acc)), bytes))
          .asRight[DecodeFailure]
      case decoder :: rest =>
//        scribe.info(s"Decoder: $decoder")
//        scribe.info(s"Bytes to decode: $bytes")
        decoder.decode(bytes) match
          case Left(failure) => failure.asLeft[DecodeResult[A]]
          case Right(DecodeResult(value, remainder)) =>
//            scribe.info(s"Decoded: $value")
            loop(rest, remainder, value *: acc)
    loop(elems, bytes, EmptyTuple)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  inline def summonAll[T <: Tuple]: List[ByteDecoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[ByteDecoder[t]] :: summonAll[ts]

  /** Automatic derivation for product types (case classes, tuples).
    *
    * Decodes each field in order.
    *
    * @see
    *   types.md for product decoding details
    */
  inline given derived[T](using p: Mirror.ProductOf[T]): ByteDecoder[T] =
    lazy val elemInstances: List[ByteDecoder[?]] =
      summonAll[p.MirroredElemTypes]
    decoderProduct(p, elemInstances)

  /** Decodes Unit (consumes no bytes).
    *
    * @see
    *   types.md for Unit decoding rules
    */
  given unitByteDecoder: ByteDecoder[Unit] = bytes =>
    DecodeResult((), bytes).asRight[DecodeFailure]

  /** Natural number type (non-negative BigInt). */
  type BigNat = BigInt :| Positive0

  /** Unsafe conversion from BigInt to BigNat (internal use). */
  def unsafeFromBigInt(n: BigInt): BigNat = n.refineUnsafe

  /** Decodes natural numbers with variable-length encoding.
    *
    * Decoding rules:
    *   - 0x00 ~ 0x80: value is the byte itself
    *   - 0x81 ~ 0xf7: length = byte - 0x80, read data
    *   - 0xf8 ~ 0xff: read length-of-length, then length, then data
    *
    * @see
    *   types.md for complete BigNat decoding specification
    */
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  given bignatByteDecoder: ByteDecoder[BigNat] = bytes =>
    Either
      .cond(bytes.nonEmpty, bytes, DecodeFailure("Empty bytes"))
      .flatMap: nonEmptyBytes =>
        val head: Int        = nonEmptyBytes.head & 0xff
        val tail: ByteVector = nonEmptyBytes.tail
        if head <= 0x80 then
          DecodeResult(unsafeFromBigInt(BigInt(head)), tail)
            .asRight[DecodeFailure]
        else if head <= 0xf8 then
          val size = head - 0x80
          if tail.size < size then
            DecodeFailure(s"required byte size $size, but $tail")
              .asLeft[DecodeResult[BigNat]]
          else
            val (front, back) = tail.splitAt(size.toLong)
            DecodeResult(unsafeFromBigInt(BigInt(1, front.toArray)), back)
              .asRight[DecodeFailure]
        else
          val sizeOfNumber = head - 0xf8 + 1
          if tail.size < sizeOfNumber then
            DecodeFailure(s"required byte size $sizeOfNumber, but $tail")
              .asLeft[DecodeResult[BigNat]]
          else
            val (sizeBytes, data) = tail.splitAt(sizeOfNumber.toLong)
            val size              = BigInt(1, sizeBytes.toArray).toLong

            if data.size < size then
              DecodeFailure(s"required byte size $size, but $data")
                .asLeft[DecodeResult[BigNat]]
            else
              val (front, back) = data.splitAt(size)
              DecodeResult(unsafeFromBigInt(BigInt(1, front.toArray)), back)
                .asRight[DecodeFailure]

  /** Decodes signed integers using sign-magnitude decoding.
    *
    * Decoding transformation:
    *   - Even x: decode as x / 2
    *   - Odd x: decode as (x - 1) / (-2)
    *
    * This is the inverse of BigInt encoding.
    *
    * @see
    *   types.md for BigInt decoding examples
    */
  given bigintByteDecoder: ByteDecoder[BigInt] = ByteDecoder[BigNat].map:
    case x if x % 2 === 0 => x / 2
    case x => (x - 1) / (-2)

  /** Creates a decoder for fixed-size byte representations.
    *
    * @param size
    *   number of bytes to consume
    * @param f
    *   function to convert bytes to value
    * @return
    *   decoder that reads exactly size bytes
    */
  def fromFixedSizeBytes[T: ClassTag](
      size: Long,
  )(f: ByteVector => T): ByteDecoder[T] = bytes =>
    def failure = DecodeFailure:
      ss"Too short bytes to decode ${classTag[T].toString}; required ${size.toString} bytes, but received ${bytes.size.toString} bytes: ${bytes.toString}"

    Either.cond(
      bytes.size >= size,
      bytes splitAt size match
        case (front, back) => DecodeResult(f(front), back)
      ,
      failure,
    )

  /** Decodes Byte as single byte. */
  given byteDecoder: ByteDecoder[Byte] = fromFixedSizeBytes(1)(_.toByte())

  /** Decodes Long as 8-byte big-endian representation. */
  given longDecoder: ByteDecoder[Long] = fromFixedSizeBytes(8)(_.toLong())

  /** Decodes Instant from epoch milliseconds (Long decoding).
    *
    * @see
    *   types.md for Instant decoding rules
    */
  given instantDecoder: ByteDecoder[Instant] =
    ByteDecoder[Long].map(Instant.ofEpochMilli)

  /** Creates a decoder for List with known size.
    *
    * @param size
    *   number of elements to decode
    * @return
    *   decoder that reads exactly size elements
    */
  def sizedListDecoder[A: ByteDecoder](size: BigNat): ByteDecoder[List[A]] =
    bytes =>
      @annotation.tailrec
      def loop(
          bytes: ByteVector,
          count: BigInt,
          acc: List[A],
      ): Either[DecodeFailure, DecodeResult[List[A]]] =
        if count === BigInt(0) then
          DecodeResult(acc.reverse, bytes).asRight[DecodeFailure]
        else
          ByteDecoder[A].decode(bytes) match
            case Left(failure) =>
              failure.asLeft[DecodeResult[List[A]]]
            case Right(DecodeResult(value, remainder)) =>
              loop(remainder, count - 1, value :: acc)
      loop(bytes, size, Nil)

  /** Creates a decoder that always fails with a message. */
  def failed[A](msg: String): ByteDecoder[A] = _ =>
    DecodeFailure(msg).asLeft[DecodeResult[A]]

  /** Decodes Option as zero or one-element List.
    *
    * Reads size as BigNat, then decodes list, taking head as Option.
    *
    * @see
    *   types.md for Option decoding rules
    */
  given optionByteDecoder[A: ByteDecoder]: ByteDecoder[Option[A]] =
    bignatByteDecoder.flatMap(sizedListDecoder[A]).map(_.headOption)

  /** Decodes Set from deterministically ordered elements.
    *
    * Reads size as BigNat, then decodes list, converting to Set.
    *
    * @see
    *   types.md for Set decoding rules
    */
  given setByteDecoder[A: ByteDecoder]: ByteDecoder[Set[A]] =
    bignatByteDecoder.flatMap(sizedListDecoder[A]).map(_.toSet)

  /** Decodes Map from deterministically ordered tuples.
    *
    * Reads size as BigNat, then decodes list of tuples, converting to Map.
    *
    * @see
    *   types.md for Map decoding rules
    */
  given mapByteDecoder[K: ByteDecoder, V: ByteDecoder]: ByteDecoder[Map[K, V]] =
    bignatByteDecoder.flatMap(sizedListDecoder[(K, V)]).map(_.toMap)
