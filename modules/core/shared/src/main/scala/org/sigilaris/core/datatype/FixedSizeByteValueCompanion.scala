package org.sigilaris.core
package datatype

import cats.Eq
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.core.util.SafeStringInterp.*

/** Reusable companion mix-in for opaque values backed by fixed-width bytes.
  *
  * Concrete companions provide the byte width, a human-readable label, and the
  * opaque wrap/unwrap functions. The mix-in then derives the standard
  * constructor, byte codec, and `Eq` surface shared by hash-like identifiers.
  */
trait FixedSizeByteValueCompanion[A]:
  protected def size: Int

  protected def label: String

  protected def wrap(bytes: ByteVector): A

  protected def unwrap(value: A): ByteVector

  def apply(bytes: ByteVector): Either[String, A] =
    Either.cond(
      bytes.size == size.toLong,
      wrap(bytes),
      ss"Invalid ${label} length: expected ${size.toString}, got ${bytes.size.toString}",
    )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(bytes: ByteVector): A =
    apply(bytes) match
      case Right(value) => value
      case Left(err)    => throw new IllegalArgumentException(err)

  extension (value: A)
    def bytes: ByteVector = unwrap(value)

  given fixedSizeByteValueEq: Eq[A] = Eq.fromUniversalEquals

  given fixedSizeByteValueEncoder: ByteEncoder[A] =
    (value: A) => unwrap(value)

  given fixedSizeByteValueDecoder: ByteDecoder[A] = bytes =>
    if bytes.size >= size.toLong then
      val (front, remainder) = bytes.splitAt(size.toLong)
      Right[DecodeFailure, DecodeResult[A]](DecodeResult(wrap(front), remainder))
    else
      Left[DecodeFailure, DecodeResult[A]](
        DecodeFailure(
          ss"Insufficient bytes for ${label}: expected ${size.toString}, got ${bytes.size.toString}",
        ),
      )
