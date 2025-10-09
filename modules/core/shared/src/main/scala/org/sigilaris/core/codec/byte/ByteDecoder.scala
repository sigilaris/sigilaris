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

final case class DecodeResult[A](value: A, remainder: ByteVector)

trait ByteDecoder[A]:
  def decode(bytes: ByteVector): Either[DecodeFailure, DecodeResult[A]]

  def map[B](f: A => B): ByteDecoder[B] = bytes =>
    decode(bytes).map:
      case DecodeResult(value, remainder) => DecodeResult(f(value), remainder)

  def emap[B](f: A => Either[DecodeFailure, B]): ByteDecoder[B] = bytes =>
    for
      decoded   <- decode(bytes)
      converted <- f(decoded.value)
    yield DecodeResult(converted, decoded.remainder)

  def flatMap[B](f: A => ByteDecoder[B]): ByteDecoder[B] = bytes =>
    decode(bytes).flatMap:
      case DecodeResult(value, remainder) => f(value).decode(remainder)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def widen[AA >: A]: ByteDecoder[AA] = this.asInstanceOf[ByteDecoder[AA]]

object ByteDecoder:
  def apply[A: ByteDecoder]: ByteDecoder[A] = summon

  object ops:
    extension (bytes: ByteVector)
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
        scribe.info(s"Decoder: $decoder")
        scribe.info(s"Bytes to decode: $bytes")
        decoder.decode(bytes) match
          case Left(failure) => failure.asLeft[DecodeResult[A]]
          case Right(DecodeResult(value, remainder)) =>
            scribe.info(s"Decoded: $value")
            loop(rest, remainder, value *: acc)
    loop(elems, bytes, EmptyTuple)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  inline def summonAll[T <: Tuple]: List[ByteDecoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[ByteDecoder[t]] :: summonAll[ts]

  inline given derived[T](using p: Mirror.ProductOf[T]): ByteDecoder[T] =
    lazy val elemInstances: List[ByteDecoder[?]] =
      summonAll[p.MirroredElemTypes]
    decoderProduct(p, elemInstances)

  given unitByteDecoder: ByteDecoder[Unit] = bytes =>
    DecodeResult((), bytes).asRight[DecodeFailure]

  type BigNat = BigInt :| Positive0

  def unsafeFromBigInt(n: BigInt): BigNat = n.refineUnsafe

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

  given bigintByteDecoder: ByteDecoder[BigInt] = ByteDecoder[BigNat].map:
    case x if x % 2 === 0 => x / 2
    case x => (x - 1) / (-2)

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

  given byteDecoder: ByteDecoder[Byte] = fromFixedSizeBytes(1)(_.toByte())

  given longDecoder: ByteDecoder[Long] = fromFixedSizeBytes(8)(_.toLong())

  given instantDecoder: ByteDecoder[Instant] =
    ByteDecoder[Long] `map` Instant.ofEpochMilli
