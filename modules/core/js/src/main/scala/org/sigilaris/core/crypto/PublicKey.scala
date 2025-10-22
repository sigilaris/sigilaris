package org.sigilaris.core
package crypto

import cats.syntax.eq.*

import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}
import datatype.UInt256
import failure.{DecodeFailure, UInt256Failure, UInt256Overflow}
import facade.BasePoint
import util.SafeStringInterp.*

sealed trait PublicKey:
  def toBytes: ByteVector
  def x: UInt256
  def y: UInt256
  def toBigInt: BigInt = BigInt(1, toBytes.toArray)

  override def toString: String = ss"PublicKey(${toBytes.toHex})"

  // Equality and hashCode are based strictly on the 64-byte x||y bytes
  override final def equals(other: Any): Boolean =
    other match
      case that: PublicKey => this.toBytes === that.toBytes
      case _               => false

  override final def hashCode(): Int = toBytes.hashCode

object PublicKey:
  final case class XY(x: UInt256, y: UInt256) extends PublicKey:
    private lazy val xyBytes: ByteVector = x.bytes ++ y.bytes
    override def toBytes: ByteVector      = xyBytes

  final case class Point(p: BasePoint) extends PublicKey:
    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    override lazy val x: UInt256 =
      val xHex = p.getX().toStringBase(16)
      UInt256
        .fromBigIntUnsigned(BigInt(xHex, 16))
        .getOrElse(throw new Exception(ss"Wrong public key x: ${xHex}"))

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    override lazy val y: UInt256 =
      val yHex = p.getY().toStringBase(16)
      UInt256
        .fromBigIntUnsigned(BigInt(yHex, 16))
        .getOrElse(throw new Exception(ss"Wrong public key y: ${yHex}"))

    private lazy val xyBytes: ByteVector = x.bytes ++ y.bytes
    override def toBytes: ByteVector      = xyBytes

  @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
  def fromByteArray(
      array: Array[Byte],
  ): Either[UInt256Failure, PublicKey] =
    if array.length =!= 64 then
      Left:
        UInt256Overflow(ss"Public key array size must be 64, got: ${array.length.toString}")
    else
      val (xArr, yArr) = array splitAt 32
      for
        x <- UInt256.fromBigIntUnsigned(BigInt(1, xArr))
        y <- UInt256.fromBigIntUnsigned(BigInt(1, yArr))
      yield XY(x, y)

  def fromXY(x: UInt256, y: UInt256): PublicKey = XY(x, y)

  def fromBasePoint(p: BasePoint): PublicKey = Point(p)

  inline given pubkeyByteEncoder: ByteEncoder[PublicKey] with
    def encode(pubkey: PublicKey): ByteVector = pubkey.toBytes

  given pubkeyByteDecoder: ByteDecoder[PublicKey] =
    ByteDecoder.fromFixedSizeBytes(64)(identity).emap: bytes =>
      fromByteArray(bytes.toArray).left.map(e => DecodeFailure(e.msg))

//  inline given Hash[PublicKey] = Hash.build
