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

object CryptoParams:
  val x9: X9IntegerConverter = new X9IntegerConverter()
  val fixedPointMultiplier: FixedPointCombMultiplier = new FixedPointCombMultiplier()
  val curveParams: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
  val curve: ECDomainParameters = new ECDomainParameters(
    curveParams.getCurve,
    curveParams.getG,
    curveParams.getN,
    curveParams.getH,
  )
  val halfCurveOrder: BigInteger = curveParams.getN.shiftRight(1)

  inline def isHighS(s: BigInteger): Boolean = s.compareTo(halfCurveOrder) > 0
  inline def normalizeS(s: BigInteger): BigInteger =
    if isHighS(s) then curve.getN.subtract(s) else s

  object ConstTime:
    import scala.annotation.tailrec
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
    def zeroize(arrays: Array[Byte]*): Unit =
      import scala.annotation.tailrec
      @tailrec def loop(i: Int): Unit =
        if i >= arrays.length then ()
        else
          val arr = arrays(i)
          if !java.util.Objects.isNull(arr) then Arrays.fill(arr, 0.toByte)
          loop(i + 1)
      loop(0)

  object CachePolicy:
    private val propKey = "sigilaris.crypto.cache"
    val enabled: Boolean =
      sys.props.get(propKey) match
        case None => true
        case Some(v) =>
          v.toLowerCase(Locale.ROOT) match
            case "false" => false
            case _        => true


