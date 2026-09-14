package org.sigilaris.conformance

import org.sigilaris.core.crypto.{CryptoOps, Signature}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.failure.DecodeFailure

/** M3 signing boundaries and fixed RFC 6979/secp256k1 compatibility vectors.
  * The literals were calculated with an independent Python HMAC-SHA256 and
  * affine curve reference, without either production crypto dependency.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.Any",
  ),
)
object CryptoDependencyConformance:
  private val order = BigInt(
    "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
    16,
  )
  private val key                          = CryptoOps.fromPrivate(BigInt(1))
  private def uint(value: BigInt): UInt256 =
    UInt256.unsafeFromBigIntUnsigned(value)
  private def hex(value: String): UInt256 = UInt256.fromHex(value).toOption.get

  private val vectors = Vector(
    (
      BigInt(0),
      28,
      "a0b37f8fba683cc68f6574cd43b39f0343a50008bf6ccea9d13231d9e7e2e1e4",
      "11edc8d307254296264aebfc3dc76cd8b668373a072fd64665b50000e9fcce52",
    ),
    (
      order - 1,
      27,
      "0315f68838865553c4bd6fab5d27c7ebc72b94f1e2939a4f34d6954f998ef2d3",
      "2464db52707d432135c48d6f37d18ee645d7a893a5505350e57b04300098ebe9",
    ),
    (
      order,
      28,
      "a0b37f8fba683cc68f6574cd43b39f0343a50008bf6ccea9d13231d9e7e2e1e4",
      "11edc8d307254296264aebfc3dc76cd8b668373a072fd64665b50000e9fcce52",
    ),
    (
      order + 1,
      28,
      "6673ffad2147741f04772b6f921f0ba6af0c1e77fc439e65c36dedf4092e8898",
      "4c1a971652e0ada880120ef8025e709fff2080c4a39aae068d12eed009b68c89",
    ),
    (
      (BigInt(1) << 256) - 1,
      27,
      "7cb38cc5712e9e11a767615f6080dbc111c9cdd613eb98999fd92a86bafd4540",
      "7923ca1f4d03471d2866f776ef8a6d3cac099b427331aeb245aa9dafeddcf115",
    ),
    // This digest produces an RFC 6979 nonce whose first octet is zero.
    (
      BigInt(197),
      27,
      "c1a80ae733c3a2eeaf8c0f1c568da615580941e71da476bdfbbd6c2f666dee2c",
      "4371e9be994cb7a032aaed78b67daded67de08c621a201a07d4aff87749b59dc",
    ),
  )

  def signatures(): Unit =
    vectors.foreach { (digest, recovery, r, s) =>
      val hash     = uint(digest).bytes.toArray
      val expected = Signature(recovery, hex(r), hex(s))
      assert(CryptoOps.sign(key, hash) == Right(expected))
      assert(CryptoOps.sign(key, hash) == Right(expected))
      assert(CryptoOps.recover(expected, hash) == Right(key.publicKey))
      val high = expected.copy(
        v = 27 + ((recovery - 27) ^ 1),
        s = uint(order - BigInt(1, expected.s.bytes.toArray)),
      )
      assert(CryptoOps.recover(high, hash) == Right(key.publicKey))
    }

  def signingBoundary(): Unit =
    // Deliberately inconsistent pair: private scalar zero, public key for one.
    // This checks typed API failure, not uniform backend key rejection. The JS
    // backend can produce a signature; CryptoOps rejects its recovered-key
    // mismatch. The JVM backend can reject the private scalar earlier.
    val inconsistentKey = key.copy(privateKey = uint(BigInt(0)))
    Vector(0, 1, 31, 33, 64).foreach { length =>
      val hash     = Array.fill[Byte](length)(0)
      val expected =
        Left(DecodeFailure("Signing requires a 32-byte message hash"))
      assert(CryptoOps.sign(key, hash) == expected)
      // Hash-length validation precedes either backend or key-pair checks.
      assert(CryptoOps.sign(inconsistentKey, hash) == expected)
    }
    assert(
      CryptoOps.sign(inconsistentKey, uint(BigInt(1)).bytes.toArray).isLeft,
    )
    Vector(BigInt(0), order).foreach { r =>
      assert(
        CryptoOps
          .recover(
            Signature(27, uint(r), uint(BigInt(1))),
            uint(BigInt(1)).bytes.toArray,
          )
          .isLeft,
      )
    }

  def run(): Unit =
    signatures()
    signingBoundary()
    println(
      "CryptoDependencyConformance PASS: 6 fixed signatures, high-S recovery, leading-zero nonce and typed signing boundaries",
    )
