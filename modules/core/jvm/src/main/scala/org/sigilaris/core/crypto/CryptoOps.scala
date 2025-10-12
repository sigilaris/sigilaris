package org.sigilaris.core
package crypto

import java.math.BigInteger
import java.security.{KeyPairGenerator, SecureRandom}
import java.security.spec.ECGenParameterSpec
import java.util.Arrays

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.jcajce.provider.asymmetric.ec.{
  BCECPrivateKey,
  BCECPublicKey,
}
 
import org.bouncycastle.math.ec.{
  ECAlgorithms,
  ECPoint,
}
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve

object CryptoOps:
  /** Compute Keccak-256 hash.
    *
    * @param input message bytes (immutable by contract)
    * @return 32-byte hash (copy)
    */
  def keccak256(input: Array[Byte]): Array[Byte] =
    val kecc = KeccakPool.acquire()
    kecc.update(input, 0, input.length)
    kecc.digest()

  // Use shared domain parameters from CryptoParams
  private inline def CurveParams: X9ECParameters = CryptoParams.curveParams
  private inline def Curve: ECDomainParameters = CryptoParams.curve
  private inline def HalfCurveOrder: BigInteger = CryptoParams.halfCurveOrder

  /** Non-deterministic source for key generation (JVM default provider). */
  val secureRandom: SecureRandom = new SecureRandom()

  @SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.Any"))
  /** Generate a new secp256k1 key pair.
    *
    * @return freshly generated `KeyPair` with 32-byte private key and 64-byte public key (x||y), big-endian.
    */
  def generate(): KeyPair =
    val gen  = KeyPairGenerator.getInstance("ECDSA", "BC")
    val spec = new ECGenParameterSpec("secp256k1")
    gen.initialize(spec, secureRandom)
    val pair = gen.generateKeyPair
    val maybeKeyPair: Option[KeyPair] =
      (pair.getPrivate, pair.getPublic) match
        case (bcecPrivate: BCECPrivateKey, bcecPublic: BCECPublicKey) =>
          for
            privateKey <- UInt256.fromBigIntegerUnsigned(bcecPrivate.getD).toOption
            publicKey <- PublicKey
              .fromByteArray(bcecPublic.getQ.getEncoded(false).tail)
              .toOption
          yield KeyPair(privateKey, publicKey)
        case _ => None

    maybeKeyPair.getOrElse {
      throw new Exception(s"Wrong keypair result: $pair")
    }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  /** Derive public key from a validated 32-byte private key.
    *
    * Pre-validated input assumed; length/endian checks are enforced at boundaries.
    */
  def fromPrivate(privateKey: BigInt): KeyPair =
    val point: ECPoint = CryptoParams.fixedPointMultiplier
      .multiply(Curve.getG, privateKey.bigInteger mod Curve.getN)
    val encoded: Array[Byte] = point.getEncoded(false)
    val keypairEither: Either[UInt256RefineFailure, KeyPair] = for
      private256 <- UInt256.from(privateKey)
      public <- PublicKey.fromByteArray(
        Arrays.copyOfRange(encoded, 1, encoded.length),
      )
    yield KeyPair(private256, public)

    keypairEither match
      case Right(keypair)                  => keypair
      case Left(UInt256RefineFailure(msg)) => throw new Exception(msg)

  /** ECDSA sign with deterministic-k and Low-S normalization.
    *
    * Ensures `s` is normalized to ≤ N/2 (Low-S) using secp256k1 curve order.
    */
  def sign(
      keyPair: KeyPair,
      transactionHash: Array[Byte],
  ): Either[String, Signature] =

    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    signer.init(true, keyPair.privateParams())
    val Array(r, sValue) = signer.generateSignature(transactionHash)
    val sBig: BigInteger = if sValue.compareTo(HalfCurveOrder) > 0 then Curve.getN.subtract(sValue) else sValue
    for
      r256 <- UInt256.fromBigIntegerUnsigned(r).left.map(_.msg)
      s256 <- UInt256.fromBigIntegerUnsigned(sBig).left.map(_.msg)
      recId <- (0 until 4)
        .find { id =>
          recoverFromSignature(id, r256, s256, transactionHash)
            .contains(keyPair.publicKey)
        }
        .toRight(
          "Could not construct a recoverable key. The credentials might not be valid.",
        )
      v = recId + 27
    yield Signature(v, r256, s256)

  /** Recover public key from a (v,r,s) signature and message hash.
    *
    * Accepts signatures with either High-S or Low-S; internally normalizes `s` to Low-S.
    */
  def recover(
      signature: Signature,
      hashArray: Array[Byte],
  ): Either[String, PublicKey] =
    val header = signature.v & 0xff
    val recId  = header - 27
    recoverFromSignature(recId, signature.r, signature.s, hashArray)
      .toRight("Could not recover public key from signature")

  private def recoverFromSignature(
      recId: Int,
      r: UInt256BigInt,
      s: UInt256BigInt,
      message: Array[Byte],
  ): Option[PublicKey] =

    val n = Curve.getN
    val x = r.bigInteger add (n multiply BigInteger.valueOf(recId.toLong / 2))
    val prime = SecP256K1Curve.q
    if x.compareTo(prime) >= 0 then None
    else
      val R =
        def decompressKey(xBN: BigInteger, yBit: Boolean): ECPoint =
          val x9 = CryptoParams.x9
          val compEnc: Array[Byte] =
            x9.integerToBytes(xBN, 1 + x9.getByteLength(Curve.getCurve()))
          compEnc(0) = if yBit then 0x03 else 0x02
          Curve.getCurve().decodePoint(compEnc)
        decompressKey(x, ((recId & 1) == 1))
      if !R.multiply(n).isInfinity() then None
      else
        val e        = new BigInteger(1, message)
        val eInv     = BigInteger.ZERO subtract e mod n
        val rInv     = r.bigInteger modInverse n
        val sNorm    = CryptoParams.normalizeS(s.bigInteger)
        val srInv    = rInv multiply sNorm mod n
        val eInvrInv = rInv multiply eInv mod n
        val q: ECPoint =
          ECAlgorithms.sumOfTwoMultiplies(Curve.getG(), eInvrInv, R, srInv)
        PublicKey.fromByteArray(q.getEncoded(false).tail).toOption
