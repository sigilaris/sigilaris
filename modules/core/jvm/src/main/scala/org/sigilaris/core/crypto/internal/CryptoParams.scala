package org.sigilaris.core
package crypto
package internal

import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger
import java.util.Locale
import java.util.Arrays

/** Shared secp256k1 curve parameters and cryptographic utilities.
  *
  * Provides pre-computed curve parameters, signature normalization, constant-
  * time operations, and caching policy for the JVM crypto implementation.
  *
  * @note
  *   This is an internal module. Public API should use
  *   [[org.sigilaris.core.crypto.CryptoOps]] instead.
  */
object CryptoParams:
  /** Integer converter for elliptic curve point encoding. */
  val x9: X9IntegerConverter = new X9IntegerConverter()

  /** Fixed-point multiplier for efficient scalar multiplication. */
  val fixedPointMultiplier: FixedPointCombMultiplier = new FixedPointCombMultiplier()

  /** secp256k1 curve parameters from BouncyCastle. */
  val curveParams: X9ECParameters = CustomNamedCurves.getByName("secp256k1")

  /** secp256k1 domain parameters (curve, generator, order, cofactor). */
  val curve: ECDomainParameters = new ECDomainParameters(
    curveParams.getCurve,
    curveParams.getG,
    curveParams.getN,
    curveParams.getH,
  )

  /** Half of the secp256k1 curve order (n/2), used for Low-S normalization. */
  val halfCurveOrder: BigInteger = curveParams.getN.shiftRight(1)

  /** Checks if a signature's s-value is in High-S form (s > n/2).
    *
    * @param s
    *   signature s-value
    * @return
    *   true if s > n/2 (High-S), false otherwise
    */
  inline def isHighS(s: BigInteger): Boolean = s.compareTo(halfCurveOrder) > 0

  /** Normalizes a signature's s-value to Low-S form (s ≤ n/2).
    *
    * If s > n/2, returns n - s. Otherwise returns s unchanged. This prevents
    * signature malleability.
    *
    * @param s
    *   signature s-value
    * @return
    *   normalized s-value (s ≤ n/2)
    */
  inline def normalizeS(s: BigInteger): BigInteger =
    if isHighS(s) then curve.getN.subtract(s) else s

  /** Constant-time cryptographic operations to prevent timing attacks. */
  object ConstTime:
    import scala.annotation.tailrec

    /** Constant-time byte array equality comparison.
      *
      * Compares two byte arrays in constant time to prevent timing attacks.
      * Always scans the full length of the longer array.
      *
      * @param a
      *   first byte array
      * @param b
      *   second byte array
      * @return
      *   true if arrays are equal, false otherwise
      *
      * @note
      *   Execution time is independent of array contents, preventing timing
      *   side-channels
      */
    def equalsBytes(a: Array[Byte], b: Array[Byte]): Boolean =
      val aLen = if java.util.Objects.isNull(a) then 0 else a.length
      val bLen = if java.util.Objects.isNull(b) then 0 else b.length
      val max  = if aLen > bLen then aLen else bLen
      @tailrec def loop(i: Int, acc: Int): Int =
        if i >= max then acc
        else
          val av = if i < aLen then a(i) else 0.toByte
          val bv = if i < bLen then b(i) else 0.toByte
          loop(i + 1, acc | ((av ^ bv) & 0xff))
      val diff = loop(0, aLen ^ bLen)
      (diff: Int) match
        case 0 => true
        case _ => false

    /** Securely zeroes out sensitive byte arrays.
      *
      * Overwrites each byte with 0 to clear sensitive data from memory. Use
      * this for private keys, nonces, and other secrets.
      *
      * @param arrays
      *   variable number of byte arrays to zeroize
      *
      * @note
      *   Does not guarantee clearing data from CPU caches or swap
      */
    def zeroize(arrays: Array[Byte]*): Unit =
      import scala.annotation.tailrec
      @tailrec def loop(i: Int): Unit =
        if i >= arrays.length then ()
        else
          val arr = arrays(i)
          if !java.util.Objects.isNull(arr) then Arrays.fill(arr, 0.toByte)
          loop(i + 1)
      loop(0)

  /** Caching policy for elliptic curve point and coordinate computations.
    *
    * Controls whether intermediate cryptographic computations (EC points, x/y
    * coordinates, byte arrays) are cached for performance. Caching can be
    * disabled via system property for debugging or reduced memory footprint.
    */
  object CachePolicy:
    /** System property key for cache control. */
    private val propKey = "sigilaris.crypto.cache"

    /** Whether caching is enabled.
      *
      * Defaults to true. Set system property `sigilaris.crypto.cache=false` to
      * disable.
      *
      * @note
      *   Disabling cache reduces memory usage but increases CPU overhead for
      *   repeated cryptographic operations
      */
    val enabled: Boolean =
      sys.props.get(propKey) match
        case None => true
        case Some(v) =>
          v.toLowerCase(Locale.ROOT) match
            case "false" => false
            case _        => true


