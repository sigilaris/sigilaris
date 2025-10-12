package org.sigilaris.core
package crypto

import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.math.ec.FixedPointCombMultiplier

object CryptoParams:
  // Stateless, safe to cache
  val x9: X9IntegerConverter = new X9IntegerConverter()

  // Reusable multiplier for fixed-point operations on secp256k1
  val fixedPointMultiplier: FixedPointCombMultiplier = new FixedPointCombMultiplier()


