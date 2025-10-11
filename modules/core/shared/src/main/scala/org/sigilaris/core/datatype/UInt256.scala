package org.sigilaris.core.datatype

import scodec.bits.ByteVector
import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder}
import cats.syntax.either.*
import org.sigilaris.core.failure.{DecodeFailure, UInt256Failure, UInt256InvalidHex, UInt256NegativeValue, UInt256Overflow, UInt256TooLong}

opaque type UInt256 = ByteVector

object UInt256:
  /** Fixed size in bytes for UInt256. */
  val Size: Int = 32

  /** Creates from big-endian bytes, left-padding with zeros if shorter. Fails
    * if longer than 32 bytes.
    */
  def fromBytesBE(bytes: ByteVector): Either[UInt256Failure, UInt256] =
    Either.cond(
      bytes.length <= Size,
      leftPadTo32(bytes),
      UInt256TooLong(bytes.length, Size),
    )

  /** Unsafe constructor: throws if bytes are longer than 32. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeFromBytesBE(bytes: ByteVector): UInt256 =
    fromBytesBE(bytes) match
      case Right(v) => v
      case Left(e)  => throw new IllegalArgumentException(e)

  /** Constructs from an unsigned BigInt (0 ≤ n < 2^256). */
  def fromBigIntUnsigned(n: BigInt): Either[UInt256Failure, UInt256] =
    for
      nonNeg <- Either.cond(n.signum >= 0, n, UInt256NegativeValue)
      arr = nonNeg.toByteArray.dropWhile(_ == 0.toByte)
      value <- Either.cond(
        arr.length <= Size,
        leftPadTo32(ByteVector.view(arr)),
        UInt256Overflow("BigInt does not fit into 256 bits"),
      )
    yield value

  /** Creates from a hex string. Delegates parsing to ByteVector.fromHex which
    * accepts optional 0x prefix, whitespace, and underscores. Empty string
    * means zero.
    */
  def fromHex(hex: String): Either[UInt256Failure, UInt256] =
    for
      parsed <- ByteVector.fromHexDescriptive(hex.trim).leftMap(msg => UInt256InvalidHex(msg))
      value <- Either.cond(
        parsed.length <= Size,
        leftPadTo32(parsed),
        UInt256TooLong(parsed.length, Size),
      )
    yield value

  /** Unsafe: constructs from an unsigned BigInt, throws on overflow/negative.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeFromBigIntUnsigned(n: BigInt): UInt256 =
    fromBigIntUnsigned(n) match
      case Right(v) => v
      case Left(e)  => throw new IllegalArgumentException(e)

  /** Returns the underlying 32-byte big-endian representation. */
  extension (u: UInt256)
    inline def bytes: ByteVector = u

    /** Interprets as unsigned BigInt. */
    def toBigIntUnsigned: BigInt = BigInt(1, u.toArray)

    /** Lowercase hex string prefixed without 0x. */
    def toHexLower: String = u.toHex

  private def leftPadTo32(bv: ByteVector): UInt256 =
    if bv.length >= Size then bv.takeRight(Size)
    else ByteVector.fill(Size - bv.length)(0.toByte) ++ bv

  given Eq[UInt256] = Eq.fromUniversalEquals

  // Byte codec instances (fixed 32 bytes, big-endian)
  given uint256ByteEncoder: ByteEncoder[UInt256] = (u: UInt256) => u

  given uint256ByteDecoder: ByteDecoder[UInt256] = bytes =>
    ByteDecoder
      .fromFixedSizeBytes[ByteVector](Size)(identity)
      .map[UInt256](bv => (bv: UInt256))
      .decode(bytes)

  // JSON codec instances (hex string without 0x prefix, lowercase)
  given uint256JsonEncoder: JsonEncoder[UInt256] =
    JsonEncoder.stringEncoder.contramap(_.toHexLower)

  given uint256JsonDecoder: JsonDecoder[UInt256] =
    JsonDecoder.stringDecoder.emap: s =>
      fromHex(s).leftMap(e => DecodeFailure(e.msg))
