package org.sigilaris.core
package crypto

import scala.util.Try

import cats.syntax.eq.given

import io.circe.{Decoder, Encoder}
import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.util.SafeStringInterp.*

type UInt256Bytes  = UInt256.Refined[ByteVector]
type UInt256BigInt = UInt256.Refined[BigInt]

final case class UInt256RefineFailure(msg: String)

object UInt256:
  trait Refine[A]

  type Refined[A] = A & Refine[A]

  def from[A: Ops](value: A): Either[UInt256RefineFailure, Refined[A]] =
    Ops[A].from(value)

  extension [A: Ops](value: Refined[A])
    def toBytes: ByteVector = Ops[A].toBytes(value)
    def toBigInt: BigInt    = Ops[A].toBigInt(value)

  trait Ops[A]:
    def from(value: A): Either[UInt256RefineFailure, Refined[A]]
    def toBytes(value: A): ByteVector
    def toBigInt(value: A): BigInt

  object Ops:
    def apply[A: Ops]: Ops[A] = summon

    given Ops[ByteVector] = new Ops[ByteVector]:

      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
      def from(
          value: ByteVector,
      ): Either[UInt256RefineFailure, Refined[ByteVector]] =
        Either.cond(
          value.size === 32L,
          value.asInstanceOf[UInt256Bytes],
          UInt256RefineFailure(
            ss"Incorrect sized bytes to be UInt256: ${value.toHex}",
          ),
        )
      def toBytes(value: ByteVector): ByteVector = value
      def toBigInt(value: ByteVector): BigInt    = BigInt(1, value.toArray)

    given Ops[BigInt] = new Ops[BigInt]:

      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
      def from(value: BigInt): Either[UInt256RefineFailure, Refined[BigInt]] =
        Either.cond(
          value >= 0L && value.bitLength <= 256,
          value.asInstanceOf[UInt256BigInt],
          UInt256RefineFailure(
            ss"Bigint out of range to be UInt256: ${value.toString}",
          ),
        )
      def toBytes(value: BigInt): ByteVector =
        ByteVector.view(value.toByteArray).takeRight(32L).padLeft(32L)
      def toBigInt(value: BigInt): BigInt = value
  end Ops

  given uint256ByteEncoder[A: Ops]: ByteEncoder[Refined[A]] = _.toBytes
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Nothing"))
  given uint256bytesByteDecoder: ByteDecoder[Refined[ByteVector]] =
    ByteDecoder.fromFixedSizeBytes(32)(_.asInstanceOf[Refined[ByteVector]])
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Nothing"))
  given uint256bigintByteDecoder: ByteDecoder[Refined[BigInt]] =
    ByteDecoder[Refined[ByteVector]].map(_.toBigInt.asInstanceOf[Refined[BigInt]])

  given uint256bytesCirceEncoder: Encoder[UInt256Bytes] =
    Encoder[String].contramap[UInt256Bytes](_.toBytes.toHex)
  given uint256bytesCirceDecoder: Decoder[UInt256Bytes] =
    Decoder.decodeString.emap((str: String) =>
      for
        bytes   <- ByteVector.fromHexDescriptive(str)
        refined <- UInt256.from(bytes).left.map(_.msg)
      yield refined,
    )

  given uint256bigintCirceEncoder: Encoder[UInt256BigInt] =
    Encoder[String].contramap[UInt256BigInt](_.toBytes.toHex)
  given uint256bigintCirceDecoder: Decoder[UInt256BigInt] =
    Decoder.decodeString.emap((str: String) =>
      for
        bigint  <- Try(BigInt(str, 16)).toEither.left.map(_.getMessage)
        refined <- UInt256.from(bigint).left.map(_.msg)
      yield refined,
    )

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  val EmptyBytes: UInt256Bytes = UInt256.from(ByteVector.low(32)).toOption.get