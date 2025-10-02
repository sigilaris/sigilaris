package org.sigilaris.core
package codec.byte

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
          Right[DecodeFailure, DecodeResult[BigNat]]:
            DecodeResult(unsafeFromBigInt(BigInt(head)), tail)
        else if head <= 0xf8 then
          val size = head - 0x80
          if tail.size < size then
            Left[DecodeFailure, DecodeResult[BigNat]]:
              DecodeFailure:
                s"required byte size $size, but $tail"
          else
            val (front, back) = tail.splitAt(size.toLong)
            Right[DecodeFailure, DecodeResult[BigNat]]:
              DecodeResult(unsafeFromBigInt(BigInt(1, front.toArray)), back)
        else
          val sizeOfNumber = head - 0xf8 + 1
          if tail.size < sizeOfNumber then
            Left[DecodeFailure, DecodeResult[BigNat]]:
              DecodeFailure:
                s"required byte size $sizeOfNumber, but $tail"
          else
            val (sizeBytes, data) = tail.splitAt(sizeOfNumber.toLong)
            val size              = BigInt(1, sizeBytes.toArray).toLong

            if data.size < size then
              Left[DecodeFailure, DecodeResult[BigNat]]:
                DecodeFailure:
                  s"required byte size $size, but $data"
            else
              val (front, back) = data.splitAt(size)
              Right[DecodeFailure, DecodeResult[BigNat]]:
                DecodeResult(unsafeFromBigInt(BigInt(1, front.toArray)), back)

  given bigintByteDecoder: ByteDecoder[BigInt] = ByteDecoder[BigNat].map:
    case x if x % 2 === 0 => x / 2
    case x => (x - 1) / (-2)
