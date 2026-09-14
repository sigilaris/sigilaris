package org.sigilaris.core
package crypto

import scala.scalajs.js
import scala.util.Try
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Uint8Array

import cats.syntax.either.*

import scodec.bits.ByteVector

import datatype.UInt256
import facade.{BasePoint, EC, JsKeyPair, Keccak256}
import util.SafeStringInterp.*

/** Scala.js implementation of cryptographic operations using elliptic.js and
  * keccak.
  *
  * Provides secp256k1 elliptic curve operations including:
  *   - Keccak-256 hashing via keccak npm package
  *   - Key pair generation and derivation via elliptic.js
  *   - ECDSA signing with Low-S normalization and recovery parameter
  *     computation
  *   - Public key recovery from signatures
  *
  * All operations delegate to JavaScript libraries through Scala.js facades,
  * providing the same API as the JVM implementation.
  *
  * @example
  *   ```scala
  *   // Generate a key pair
  *   val keyPair = CryptoOps.generate()
  *
  *   // Hash and sign
  *   val message = "hello".getBytes
  *   val hash    = CryptoOps.keccak256(message)
  *   val sig     = CryptoOps.sign(keyPair, hash).toOption.get
  *
  *   // Recover public key
  *   val recovered = CryptoOps.recover(sig, hash)
  *   assert(recovered.contains(keyPair.publicKey))
  *   ```
  *
  * @note
  *   This is the Scala.js-specific implementation. Cross-platform code should
  *   use [[CryptoOpsLike]] interface.
  *
  * @see
  *   [[CryptoOpsLike]] for the cross-platform interface
  * @see
  *   [[facade]] for JavaScript library facades
  */
object CryptoOps extends CryptoOpsLike:
  private val CurveOrder =
    BigInt(
      "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
      16,
    )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def keccak256(input: Array[Byte]): Array[Byte] =
    val hexString: String = Keccak256.update(input.toUint8Array).hex()

    ByteVector
      .fromHexDescriptive(hexString)
      .fold(e => throw new Exception(e), _.toArray)

  extension (byteArray: Array[Byte])
    def toUint8Array: Uint8Array =
      Uint8Array.from[Byte](byteArray.toJSArray, _.toShort)

  val ec: EC = EC.secp256k1

  def generate(): KeyPair = ec.genKeyPair().asScala

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def fromPrivate(privateKey: BigInt): KeyPair =
    val priv32 = UInt256
      .fromBigIntUnsigned(privateKey)
      .getOrElse:
        throw new Exception(ss"Invalid private key: ${privateKey.toString(16)}")
      .bytes
    ec.keyFromPrivate(priv32.toArray.toUint8Array).asScala

  extension (jsKeyPair: JsKeyPair)
    @SuppressWarnings(
      Array("org.wartremover.warts.Throw", "org.wartremover.warts.Overloading"),
    )
    def asScala: KeyPair =
      val privHex    = jsKeyPair.getPrivate().toStringBase(16)
      val pBigInt    = BigInt(privHex, 16)
      val p: UInt256 = UInt256
        .fromBigIntUnsigned(pBigInt)
        .getOrElse:
          throw new Exception(ss"Wrong private key: ${privHex}")

      KeyPair(p, jsKeyPair.getPublic().asScala)

  extension (pubKey: BasePoint)
    @SuppressWarnings(
      Array("org.wartremover.warts.Throw", "org.wartremover.warts.Overloading"),
    )
    def asScala: PublicKey =
      PublicKey.fromBasePoint(pubKey)

  def sign(
      keyPair: KeyPair,
      transactionHash: Array[Byte],
  ): Either[failure.SigilarisFailure, Signature] =
    transactionHash.length match
      case 32 =>
        Try(signDigest(keyPair, transactionHash)).toEither.left
          .map(_ => failure.DecodeFailure("Could not sign message hash"))
          .flatMap(identity)
      case _ =>
        Left[failure.SigilarisFailure, Signature](
          failure.DecodeFailure("Signing requires a 32-byte message hash"),
        )

  private def signDigest(
      keyPair: KeyPair,
      transactionHash: Array[Byte],
  ): Either[failure.SigilarisFailure, Signature] =
    val jsSig = keyPair.toJs.sign(transactionHash.toUint8Array)
    val rawS  = BigInt(jsSig.s.toStringBase(16), 16)
    val lowS  = if rawS > CurveOrder / 2 then CurveOrder - rawS else rawS
    // Recover using normalized S so the recovery parity changes with S when needed.
    val sigObj: js.Object = js.Dynamic.literal(
      "r" -> jsSig.r.toStringBase(16),
      "s" -> lowS.toString(16),
    )
    val pub0: PublicKey = ec
      .recoverPubKey(
        transactionHash.toUint8Array,
        sigObj,
        0,
        scala.scalajs.js.undefined,
      )
      .asScala
    val pub1: PublicKey = ec
      .recoverPubKey(
        transactionHash.toUint8Array,
        sigObj,
        1,
        scala.scalajs.js.undefined,
      )
      .asScala
    val recoveryParamEither: Either[failure.SigilarisFailure, Int] =
      if pub0.toBigInt.equals(keyPair.publicKey.toBigInt) then
        0.asRight[failure.SigilarisFailure]
      else if pub1.toBigInt.equals(keyPair.publicKey.toBigInt) then
        1.asRight[failure.SigilarisFailure]
      else
        failure.DecodeFailure("Unable to determine recoveryParam").asLeft[Int]
    for
      recoveryParam <- recoveryParamEither
      v = 27 + recoveryParam
      r <- UInt256
        .fromBigIntUnsigned(BigInt(jsSig.r.toStringBase(16), 16))
      s <- UInt256
        .fromBigIntUnsigned(lowS)
    yield Signature(v, r, s)

  extension (keyPair: KeyPair)
    def toJs: JsKeyPair =
      ec.keyFromPrivate(keyPair.privateKey.bytes.toArray.toUint8Array)

  def recover(
      signature: Signature,
      hashArray: Array[Byte],
  ): Either[failure.SigilarisFailure, PublicKey] =
    Try {
      val jsSig: js.Object = js.Dynamic.literal(
        "r" -> signature.r.toHexLower,
        "s" -> signature.s.toHexLower,
      )
      // Keep elliptic's original recovery-parameter interpretation unchanged.
      val pub = ec
        .recoverPubKey(
          hashArray.toUint8Array,
          jsSig,
          signature.v - 27,
          js.undefined,
        )
        .asScala
      // Reject lazy infinity coordinates within the same typed boundary.
      val _ = pub.toBytes
      pub
    }.toEither.left.map(_ =>
      failure.DecodeFailure("Could not recover public key from signature"),
    )
