package org.sigilaris.core
package crypto

import java.math.BigInteger
import java.security.{KeyPairGenerator, SecureRandom}
import java.security.spec.ECGenParameterSpec

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.jcajce.provider.asymmetric.ec.{
  BCECPrivateKey,
  BCECPublicKey,
}
import org.bouncycastle.math.ec.{ECAlgorithms, ECPoint}
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve

import datatype.UInt256
import util.SafeStringInterp.*

/** JVM implementation of cryptographic operations using BouncyCastle.
  *
  * Provides secp256k1 elliptic curve operations including:
  *   - Keccak-256 hashing (thread-safe via pool)
  *   - Key pair generation and derivation
  *   - ECDSA signing with deterministic k (RFC 6979) and Low-S normalization
  *   - Public key recovery from signatures
  *
  * All operations use BouncyCastle's secp256k1 implementation with performance
  * optimizations including thread-local digest pooling and optional coordinate
  * caching.
  *
  * @example
  *   ```scala
  *   // Generate a key pair
  *   val keyPair = CryptoOps.generate()
  *
  *   // Hash and sign
  *   val message = "hello".getBytes
  *   val hash = CryptoOps.keccak256(message)
  *   val sig = CryptoOps.sign(keyPair, hash).toOption.get
  *
  *   // Recover public key
  *   val recovered = CryptoOps.recover(sig, hash)
  *   assert(recovered.contains(keyPair.publicKey))
  *   ```
  *
  * @note
  *   This is the JVM-specific implementation. Cross-platform code should use
  *   [[CryptoOpsLike]] interface.
  *
  * @see [[CryptoOpsLike]] for the cross-platform interface
  * @see [[org.sigilaris.core.crypto.internal.CryptoParams]] for curve
  *      parameters
  * @see [[org.sigilaris.core.crypto.internal.KeccakPool]] for hashing
  *      implementation
  */
object CryptoOps extends CryptoOpsLike:
  // Initialize BouncyCastle provider once at startup
  private val _ = java.security.Security.addProvider(
    new org.bouncycastle.jce.provider.BouncyCastleProvider()
  )
  /** Computes Keccak-256 hash using thread-local digest pool.
    *
    * @param input
    *   message bytes (not modified)
    * @return
    *   32-byte hash (freshly allocated copy)
    *
    * @note
    *   Thread-safe via [[internal.KeccakPool]]
    */
  def keccak256(input: Array[Byte]): Array[Byte] =
    val kecc = internal.KeccakPool.acquire()
    kecc.update(input, 0, input.length)
    kecc.digest()

  // Use shared domain parameters from CryptoParams
  private inline def CurveParams: X9ECParameters = internal.CryptoParams.curveParams
  private inline def Curve: ECDomainParameters   = internal.CryptoParams.curve
  private inline def HalfCurveOrder: BigInteger  = internal.CryptoParams.halfCurveOrder

  /** Non-deterministic source for key generation (JVM default provider). */
  val secureRandom: SecureRandom = new SecureRandom()

  @SuppressWarnings(
    Array("org.wartremover.warts.Throw", "org.wartremover.warts.ToString"),
  )
  /** Generate a new secp256k1 key pair.
    *
    * @return
    *   freshly generated `KeyPair` with 32-byte private key and 64-byte public
    *   key (x||y), big-endian.
    */
  def generate(): KeyPair =
    val gen  = KeyPairGenerator.getInstance("ECDSA", "BC")
    val spec = new ECGenParameterSpec("secp256k1")
    gen.initialize(spec, secureRandom)
    val pair = gen.generateKeyPair
    val maybeKeyPair: Option[KeyPair] =
      (pair.getPrivate, pair.getPublic) match
        case (bcecPrivate: BCECPrivateKey, bcecPublic: BCECPublicKey) =>
          UInt256
            .fromBigIntegerUnsigned(bcecPrivate.getD)
            .toOption
            .map: privateKey =>
              val publicKey = PublicKey.fromECPoint(bcecPublic.getQ)
              KeyPair(privateKey, publicKey)
        case _ => None

    maybeKeyPair.getOrElse:
      throw new Exception(ss"Wrong keypair result: ${pair.toString}")

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  /** Derive public key from a validated 32-byte private key.
    *
    * Pre-validated input assumed; length/endian checks are enforced at
    * boundaries.
    */
  def fromPrivate(privateKey: BigInt): KeyPair =
    val point: ECPoint = internal.CryptoParams.fixedPointMultiplier
      .multiply(Curve.getG, privateKey.bigInteger mod Curve.getN)

    UInt256
      .fromBigIntUnsigned(privateKey)
      .map: private256 =>
        val public = PublicKey.fromECPoint(point)
        KeyPair(private256, public)
      .getOrElse:
        throw new Exception(
          ss"Failed to convert private key to UInt256: ${privateKey.toString}",
        )

  /** ECDSA sign with deterministic-k and Low-S normalization.
    *
    * Ensures `s` is normalized to ≤ N/2 (Low-S) using secp256k1 curve order.
    */
  def sign(
      keyPair: KeyPair,
      transactionHash: Array[Byte],
  ): Either[failure.SigilarisFailure, Signature] =

    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    val privParams = new org.bouncycastle.crypto.params.ECPrivateKeyParameters(
      keyPair.privateKey.toJavaBigIntegerUnsigned,
      internal.CryptoParams.curve,
    )
    signer.init(true, privParams)
    val Array(r, sValue) = signer.generateSignature(transactionHash)
    val sBig: BigInteger =
      if sValue.compareTo(HalfCurveOrder) > 0 then Curve.getN.subtract(sValue)
      else sValue
    for
      r256 <- UInt256.fromBigIntegerUnsigned(r)
      s256 <- UInt256.fromBigIntegerUnsigned(sBig)
      recId <- (0 until 4)
        .find: id =>
          recoverFromSignature(
            id,
            r256.toJavaBigIntegerUnsigned,
            s256.toJavaBigIntegerUnsigned,
            transactionHash,
          ).contains(keyPair.publicKey)
        .toRight:
          failure.DecodeFailure:
            "Could not construct a recoverable key. The credentials might not be valid."
      v = recId + 27
    yield Signature(v, r256, s256)

  /** Recover public key from a (v,r,s) signature and message hash.
    *
    * Accepts signatures with either High-S or Low-S; internally normalizes `s`
    * to Low-S.
    */
  def recover(
      signature: Signature,
      hashArray: Array[Byte],
  ): Either[failure.SigilarisFailure, PublicKey] =
    val header = signature.v & 0xff
    val recId  = header - 27
    recoverFromSignature(
      recId,
      signature.r.toJavaBigIntegerUnsigned,
      signature.s.toJavaBigIntegerUnsigned,
      hashArray,
    ).toRight:
      failure.DecodeFailure:
        "Could not recover public key from signature"

  private def recoverFromSignature(
      recId: Int,
      r: BigInteger,
      s: BigInteger,
      message: Array[Byte],
  ): Option[PublicKey] =

    val n = Curve.getN
    // If we normalize S from High-S to Low-S, the y-parity must flip to keep the same public key.
    val isHighS  = internal.CryptoParams.isHighS(s)
    val recIdAdj = if isHighS then recId ^ 1 else recId
    val x        = r add (n multiply BigInteger.valueOf(recIdAdj.toLong / 2))
    val prime    = SecP256K1Curve.q
    if x.compareTo(prime) >= 0 then None
    else
      val R =
        def decompressKey(xBN: BigInteger, yBit: Boolean): ECPoint =
          val x9 = internal.CryptoParams.x9
          val compEnc: Array[Byte] =
            x9.integerToBytes(xBN, 1 + x9.getByteLength(Curve.getCurve()))
          compEnc(0) = if yBit then 0x03 else 0x02
          Curve.getCurve().decodePoint(compEnc)
        decompressKey(x, ((recIdAdj & 1) == 1))
      if !R.multiply(n).isInfinity() then None
      else
        val e        = new BigInteger(1, message)
        val eInv     = BigInteger.ZERO subtract e mod n
        val rInv     = r modInverse n
        val sNorm    = internal.CryptoParams.normalizeS(s)
        val srInv    = rInv multiply sNorm mod n
        val eInvrInv = rInv multiply eInv mod n
        val q: ECPoint =
          ECAlgorithms.sumOfTwoMultiplies(Curve.getG(), eInvrInv, R, srInv)
        Some(PublicKey.fromECPoint(q))
