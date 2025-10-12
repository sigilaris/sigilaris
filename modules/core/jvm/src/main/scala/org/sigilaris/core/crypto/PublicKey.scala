package org.sigilaris.core
package crypto

import java.math.BigInteger

import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}
import failure.DecodeFailure
import org.bouncycastle.math.ec.ECPoint


final case class PublicKey(x: UInt256BigInt, y: UInt256BigInt):
  // Cached 64-byte concatenation (x||y) as SoT for fast access in hot paths
  private val cachedXY64Ref: java.util.concurrent.atomic.AtomicReference[Option[Array[Byte]]] =
    new java.util.concurrent.atomic.AtomicReference[Option[Array[Byte]]](None)
  // Cached uncompressed ECPoint view (JVM-only), derived from SoT
  private val cachedPointRef: java.util.concurrent.atomic.AtomicReference[Option[ECPoint]] =
    new java.util.concurrent.atomic.AtomicReference[Option[ECPoint]](None)

  def toBytes: ByteVector =
    val arr = xy64Array()
    // return copy to preserve immutability outside
    ByteVector.view(java.util.Arrays.copyOf(arr, arr.length))

  def toBigInt: BigInt = BigInt(1, xy64Array())

  private def xy64Array(): Array[Byte] =
    cachedXY64Ref.get() match
      case Some(arr) => arr
      case None =>
        val combined =
          val xb = x.toBytes.toArray
          val yb = y.toBytes.toArray
          val out = new Array[Byte](64)
          System.arraycopy(xb, 0, out, 0, 32)
          System.arraycopy(yb, 0, out, 32, 32)
          out
        if CryptoParams.CachePolicy.enabled then cachedXY64Ref.set(Some(combined))
        combined

  // Uncompressed ECPoint view (65 bytes with 0x04 prefix)
  private[crypto] def ecPointUncompressed(): ECPoint =
    cachedPointRef.get() match
      case Some(p) => p
      case None =>
        val point = CryptoParams.curve.getCurve.decodePoint({
          val xy = xy64Array()
          val enc = new Array[Byte](65)
          enc(0) = 0x04.toByte
          System.arraycopy(xy, 0, enc, 1, 64)
          enc
        })
        if CryptoParams.CachePolicy.enabled then cachedPointRef.set(Some(point))
        point


  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  override def toString: String = s"PublicKey($toBytes)"

object PublicKey:
  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"))
  def fromByteArray(
      array: Array[Byte],
  ): Either[UInt256RefineFailure, PublicKey] =
    if array.length != 64 then
      Left(
        UInt256RefineFailure(s"Public key array size are not 64: $array"),
      )
    else
      val (xArr, yArr) = array splitAt 32
      for
        x <- UInt256.fromBigIntegerUnsigned(new BigInteger(1, xArr))
        y <- UInt256.fromBigIntegerUnsigned(new BigInteger(1, yArr))
      yield PublicKey(x, y)

  inline given pubkeyByteEncoder: ByteEncoder[PublicKey] with
    def encode(pubkey: PublicKey): ByteVector = pubkey.toBytes

  given pubkeyByteDecoder: ByteDecoder[PublicKey] =
    ByteDecoder.fromFixedSizeBytes(64)(identity).emap { bytes =>
      fromByteArray(bytes.toArray).left.map(e => DecodeFailure(e.msg))
    }

  inline given Hash[PublicKey] = Hash.build
