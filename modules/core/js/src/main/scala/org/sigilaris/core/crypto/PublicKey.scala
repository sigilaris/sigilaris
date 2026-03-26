package org.sigilaris.core
package crypto

import cats.syntax.eq.*

import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}
import datatype.UInt256
import failure.{DecodeFailure, UInt256Failure, UInt256Overflow}
import facade.BasePoint
import util.SafeStringInterp.*

/** Scala.js implementation of secp256k1 public key.
  *
  * Represents an uncompressed secp256k1 public key (64 bytes = x||y) with two
  * internal representations:
  *   - [[XY]]: stores x and y coordinates directly
  *   - [[Point]]: wraps elliptic.js [[BasePoint]] and lazily computes x/y
  *
  * @note
  *   Equality and hashCode are based solely on the 64-byte x||y
  *   representation, ensuring consistency across both internal forms.
  *
  * @see [[PublicKeyLike]] for the cross-platform interface
  * @see [[facade.BasePoint]] for the elliptic.js point wrapper
  */
sealed trait PublicKey extends PublicKeyLike:
  /** Returns the 64-byte uncompressed representation (x||y). */
  def toBytes: ByteVector

  /** X-coordinate of the elliptic curve point. */
  def x: UInt256

  /** Y-coordinate of the elliptic curve point. */
  def y: UInt256

  /** Converts to BigInt representation (for JS compatibility). */
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

  /** Decodes a public key from a 64-byte array (x||y).
    *
    * @param array
    *   64-byte array: x (32 bytes) || y (32 bytes), big-endian
    * @return
    *   Right([[PublicKey]]) on success, Left(failure) if array length is not 64
    *   or coordinates are invalid
    */
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

  /** Constructs a public key from x and y coordinates.
    *
    * @param x
    *   x-coordinate as [[UInt256]]
    * @param y
    *   y-coordinate as [[UInt256]]
    * @return
    *   public key with [[XY]] representation
    */
  def fromXY(x: UInt256, y: UInt256): PublicKey = XY(x, y)

  /** Constructs a public key from an elliptic.js point.
    *
    * @param p
    *   elliptic.js BasePoint
    * @return
    *   public key with [[Point]] representation
    *
    * @note
    *   Used internally when deriving keys from private keys or recovering from
    *   signatures
    */
  def fromBasePoint(p: BasePoint): PublicKey = Point(p)

  inline given pubkeyByteEncoder: ByteEncoder[PublicKey] with
    def encode(pubkey: PublicKey): ByteVector = pubkey.toBytes

  given pubkeyByteDecoder: ByteDecoder[PublicKey] =
    ByteDecoder.fromFixedSizeBytes(64)(identity).emap: bytes =>
      fromByteArray(bytes.toArray).left.map(e => DecodeFailure(e.msg))

  inline given Hash[PublicKey] = Hash.build
