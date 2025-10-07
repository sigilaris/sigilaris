package org.sigilaris.core
package codec.byte

import java.time.Instant

import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror

import cats.syntax.eq.*

import scodec.bits.ByteVector

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

trait ByteEncoder[A]:
  def encode(value: A): ByteVector

  def contramap[B](f: B => A): ByteEncoder[B] = value => encode(f(value))

object ByteEncoder:
  def apply[A: ByteEncoder]: ByteEncoder[A] = summon

  object ops:
    extension [A: ByteEncoder](a: A)
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

  inline given derived[T](using p: Mirror.ProductOf[T]): ByteEncoder[T] =
    lazy val elemInstances: List[ByteEncoder[?]] =
      summonAll[p.MirroredElemTypes]
    encoderProduct(elemInstances)

  given unitByteEncoder: ByteEncoder[Unit] = _ => ByteVector.empty

  given byteEncoder: ByteEncoder[Byte] = ByteVector.fromByte(_)

  given longEncoder: ByteEncoder[Long] = ByteVector.fromLong(_)

  given instantEncoder: ByteEncoder[Instant] =
    ByteVector fromLong _.toEpochMilli

  type BigNat = BigInt :| Positive0

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

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  given bigintByteEncoder: ByteEncoder[BigInt] = ByteEncoder[BigNat].contramap:
    case n if n >= 0 => (n * 2).asInstanceOf[BigNat]
    case n           => (n * (-2) + 1).asInstanceOf[BigNat]
