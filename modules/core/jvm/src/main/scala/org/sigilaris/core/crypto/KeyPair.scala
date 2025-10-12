package org.sigilaris.core
package crypto

import org.bouncycastle.crypto.params.ECPrivateKeyParameters

final case class KeyPair(privateKey: UInt256BigInt, publicKey: PublicKey):
  // Cached 32-byte private key SoT
  private val cachedD32Ref: java.util.concurrent.atomic.AtomicReference[Option[Array[Byte]]] =
    new java.util.concurrent.atomic.AtomicReference[Option[Array[Byte]]](None)
  // Cached ECPrivateKeyParameters view (JVM-only)
  private val cachedPrivParamsRef: java.util.concurrent.atomic.AtomicReference[Option[ECPrivateKeyParameters]] =
    new java.util.concurrent.atomic.AtomicReference[Option[ECPrivateKeyParameters]](None)

  private[crypto] def d32Array(): Array[Byte] =
    cachedD32Ref.get() match
      case Some(arr) => arr
      case None =>
        val arr = privateKey.toBytes.toArray
        if CryptoParams.CachePolicy.enabled then cachedD32Ref.set(Some(arr))
        arr

  private[crypto] def privateParams(): ECPrivateKeyParameters =
    cachedPrivParamsRef.get() match
      case Some(p) => p
      case None =>
        val p = new ECPrivateKeyParameters(privateKey.toBigInt.bigInteger, CryptoParams.curve)
        if CryptoParams.CachePolicy.enabled then cachedPrivParamsRef.set(Some(p))
        p

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  override lazy val toString: String =
    s"KeyPair(${privateKey.toBytes.toHex}, $publicKey)"
