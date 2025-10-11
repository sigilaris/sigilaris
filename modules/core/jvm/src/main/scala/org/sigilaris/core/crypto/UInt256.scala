package org.sigilaris.core
package crypto

import scala.util.Try

import io.circe.{Decoder, Encoder}
import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}

type UInt256BigInt = UInt256.Refined[BigInt]

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


    given Ops[BigInt] = new Ops[BigInt]:

      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Any"))
      def from(value: BigInt): Either[UInt256RefineFailure, Refined[BigInt]] =
        Either.cond(
          value >= 0L && value.bitLength <= 256,
          value.asInstanceOf[UInt256BigInt],
          UInt256RefineFailure(
            s"Bigint out of range to be UInt256: $value",
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

  given uint256bigintCirceEncoder: Encoder[UInt256BigInt] =
    Encoder[String].contramap[UInt256BigInt](_.toBytes.toHex)
  given uint256bigintCirceDecoder: Decoder[UInt256BigInt] =
    Decoder.decodeString.emap((str: String) =>
      for
        bigint  <- Try(BigInt(str, 16)).toEither.left.map(_.getMessage)
        refined <- UInt256.from(bigint).left.map(_.msg)
      yield refined,
    )
