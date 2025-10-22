package org.sigilaris.core
package crypto

import cats.syntax.eq.*

import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}
import failure.DecodeFailure
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.core.datatype.UInt256

final case class PublicKey(x: UInt256, y: UInt256):
  def toBytes: ByteVector = x.bytes ++ y.bytes
  def toBigInt: BigInt    = BigInt(1, toBytes.toArray)

  override def toString: String = ss"PublicKey(${toBytes.toHex})"

object PublicKey:
  @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
  def fromByteArray(
      array: Array[Byte],
  ): Either[failure.UInt256Failure, PublicKey] =
    if array.length =!= 64 then
      Left(
        failure.UInt256Overflow(ss"Public key array size must be 64, got: ${array.length.toString}"),
      )
    else
      val (xArr, yArr) = array splitAt 32
      for
        x <- UInt256.fromBigIntUnsigned(BigInt(1, xArr))
        y <- UInt256.fromBigIntUnsigned(BigInt(1, yArr))
      yield PublicKey(x, y)

  inline given pubkeyByteEncoder: ByteEncoder[PublicKey] with
    def encode(pubkey: PublicKey): ByteVector = pubkey.toBytes

  given pubkeyByteDecoder: ByteDecoder[PublicKey] =
    ByteDecoder.fromFixedSizeBytes(64)(identity).emap { bytes =>
      fromByteArray(bytes.toArray).left.map(e => DecodeFailure(e.msg))
    }

//  inline given Hash[PublicKey] = Hash.build
