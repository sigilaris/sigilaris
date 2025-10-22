package org.sigilaris.core
package crypto

import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference

import org.bouncycastle.math.ec.ECPoint
import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}
import failure.DecodeFailure

sealed trait PublicKey:
  def toBytes: ByteVector
  def x: UInt256BigInt
  def y: UInt256BigInt
  private[crypto] def asECPoint(): ECPoint

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  override final def toString: String = s"PublicKey($toBytes)"

  // Equality and hash are based strictly on the 64-byte x||y SoT
  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  override final def equals(other: Any): Boolean =
    other match
      case that: PublicKey => this.toBytes == that.toBytes
      case _               => false

  override final def hashCode(): Int = toBytes.hashCode

object PublicKey:
  final case class XY(x: UInt256BigInt, y: UInt256BigInt) extends PublicKey:
    private val cachedXY64Ref: AtomicReference[Option[Array[Byte]]] =
      new AtomicReference[Option[Array[Byte]]](None)
    private val cachedPointRef: AtomicReference[Option[ECPoint]] =
      new AtomicReference[Option[ECPoint]](None)
    private val cachedPointNormRef: AtomicReference[Option[ECPoint]] =
      new AtomicReference[Option[ECPoint]](None)

    override def toBytes: ByteVector =
      val arr = xy64Array()
      ByteVector.view(java.util.Arrays.copyOf(arr, arr.length))

    private def xy64Array(): Array[Byte] =
      cachedXY64Ref.get() match
        case Some(arr) => arr
        case None =>
          val combined =
            val xb  = x.toBytes.toArray
            val yb  = y.toBytes.toArray
            val out = new Array[Byte](64)
            System.arraycopy(xb, 0, out, 0, 32)
            System.arraycopy(yb, 0, out, 32, 32)
            out
          if CryptoParams.CachePolicy.enabled then cachedXY64Ref.set(Some(combined))
          combined

    override private[crypto] def asECPoint(): ECPoint =
      cachedPointNormRef.get() match
        case Some(np) => np
        case None =>
          val decoded = cachedPointRef.get() match
            case Some(p) => p
            case None =>
              val point = CryptoParams.curve.getCurve.decodePoint({
                val xy  = xy64Array()
                val enc = new Array[Byte](65)
                enc(0) = 0x04.toByte
                System.arraycopy(xy, 0, enc, 1, 64)
                enc
              })
              if CryptoParams.CachePolicy.enabled then cachedPointRef.set(Some(point))
              point
          val normalized = if decoded.isNormalized then decoded else decoded.normalize()
          if CryptoParams.CachePolicy.enabled then cachedPointNormRef.set(Some(normalized))
          normalized

  final case class Point(p: ECPoint) extends PublicKey:
    private val cachedXY64Ref: AtomicReference[Option[Array[Byte]]] =
      new AtomicReference[Option[Array[Byte]]](None)
    private val cachedXRef: AtomicReference[Option[UInt256BigInt]] =
      new AtomicReference[Option[UInt256BigInt]](None)
    private val cachedYRef: AtomicReference[Option[UInt256BigInt]] =
      new AtomicReference[Option[UInt256BigInt]](None)
    private val cachedNormRef: AtomicReference[Option[ECPoint]] =
      new AtomicReference[Option[ECPoint]](None)

    override private[crypto] def asECPoint(): ECPoint =
      cachedNormRef.get() match
        case Some(np) => np
        case None =>
          val np = if p.isNormalized then p else p.normalize()
          if CryptoParams.CachePolicy.enabled then cachedNormRef.set(Some(np))
          np

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    override def x: UInt256BigInt =
      cachedXRef.get() match
        case Some(v) => v
        case None =>
          val v =
            val np = asECPoint()
            UInt256.fromBigIntegerUnsigned(np.getAffineXCoord.toBigInteger) match
              case Right(u)                         => u
              case Left(UInt256RefineFailure(msg)) =>
                throw new IllegalArgumentException(msg)
          if CryptoParams.CachePolicy.enabled then cachedXRef.set(Some(v))
          v

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    override def y: UInt256BigInt =
      cachedYRef.get() match
        case Some(v) => v
        case None =>
          val v =
            val np = asECPoint()
            UInt256.fromBigIntegerUnsigned(np.getAffineYCoord.toBigInteger) match
              case Right(u)                         => u
              case Left(UInt256RefineFailure(msg)) =>
                throw new IllegalArgumentException(msg)
          if CryptoParams.CachePolicy.enabled then cachedYRef.set(Some(v))
          v

    private def xy64Array(): Array[Byte] =
      cachedXY64Ref.get() match
        case Some(arr) => arr
        case None =>
          val xb  = x.toBytes.toArray
          val yb  = y.toBytes.toArray
          val out = new Array[Byte](64)
          System.arraycopy(xb, 0, out, 0, 32)
          System.arraycopy(yb, 0, out, 32, 32)
          if CryptoParams.CachePolicy.enabled then cachedXY64Ref.set(Some(out))
          out

    override def toBytes: ByteVector =
      val arr = xy64Array()
      ByteVector.view(java.util.Arrays.copyOf(arr, arr.length))

  @SuppressWarnings(
    Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"),
  )
  def fromByteArray(array: Array[Byte]): Either[UInt256RefineFailure, PublicKey] =
    if array.length != 64 then
      Left(UInt256RefineFailure(s"Public key array size are not 64: $array"))
    else
      val (xArr, yArr) = array splitAt 32
      for
        x <- UInt256.fromBigIntegerUnsigned(new BigInteger(1, xArr))
        y <- UInt256.fromBigIntegerUnsigned(new BigInteger(1, yArr))
      yield XY(x, y)

  def fromXY(x: UInt256BigInt, y: UInt256BigInt): PublicKey = XY(x, y)

  def fromECPoint(p: ECPoint): PublicKey = Point(p)

  inline given pubkeyByteEncoder: ByteEncoder[PublicKey] with
    def encode(pubkey: PublicKey): ByteVector = pubkey.toBytes

  given pubkeyByteDecoder: ByteDecoder[PublicKey] =
    ByteDecoder.fromFixedSizeBytes(64)(identity).emap { bytes =>
      fromByteArray(bytes.toArray).left.map(e => DecodeFailure(e.msg))
    }

  inline given Hash[PublicKey] = Hash.build
