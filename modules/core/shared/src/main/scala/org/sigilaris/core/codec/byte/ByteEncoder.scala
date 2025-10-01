package org.sigilaris.core
package codec.byte

import cats.syntax.eq.*

import scodec.bits.ByteVector

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

trait ByteEncoder[A]:
  def encode(value: A): ByteVector

  def contramap[B](f: B => A): ByteEncoder[B] = value => encode(f(value))

object ByteEncoder:
  def apply[A: ByteEncoder]: ByteEncoder[A] = summon

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
