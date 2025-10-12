package org.sigilaris.core
package crypto

import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger
import java.util.Locale

object CryptoParams:
  // Stateless, safe to cache
  val x9: X9IntegerConverter = new X9IntegerConverter()

  // Reusable multiplier for fixed-point operations on secp256k1
  val fixedPointMultiplier: FixedPointCombMultiplier = new FixedPointCombMultiplier()

  // Domain parameters for secp256k1 (safe to share)
  val curveParams: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
  val curve: ECDomainParameters = new ECDomainParameters(
    curveParams.getCurve,
    curveParams.getG,
    curveParams.getN,
    curveParams.getH,
  )
  val halfCurveOrder: BigInteger = curveParams.getN.shiftRight(1)

  object CachePolicy:
    private val propKey = "sigilaris.crypto.cache"
    // enabled when prop is missing or not explicitly set to "false"
    val enabled: Boolean =
      sys.props.get(propKey) match
        case None => true
        case Some(v) =>
          v.toLowerCase(Locale.ROOT) match
            case "false" => false
            case _        => true


