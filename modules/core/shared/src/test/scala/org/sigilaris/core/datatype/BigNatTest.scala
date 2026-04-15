package org.sigilaris.core
package datatype

import org.sigilaris.core.codec.CodecLawSupport
import org.sigilaris.core.testing.HedgehogGenSupport

import hedgehog.munit.HedgehogSuite
import hedgehog.*

class BigNatTest extends HedgehogSuite:

  private val bignatGen = HedgehogGenSupport.bigNat

  property("roundtrip of bignat byte codec"):
    bignatGen.forAll.map: bignat =>
      CodecLawSupport.ByteLaws.roundTrip(bignat)

  property("roundtrip of bignat json codec"):
    bignatGen.forAll.map: bignat =>
      CodecLawSupport.JsonLaws.roundTrip(bignat)

  property("bignat ordering is reflexive"):
    bignatGen.forAll.map: bignat =>
      BigNat.bignatOrdering.compare(bignat, bignat) ==== 0

  property("bignat ordering is antisymmetric"):
    for
      x <- bignatGen.forAll
      y <- bignatGen.forAll
    yield
      val forward = Integer.signum(BigNat.bignatOrdering.compare(x, y))
      val reverse = Integer.signum(BigNat.bignatOrdering.compare(y, x))
      forward ==== -reverse

  property("bignat ordering is transitive"):
    for
      x <- bignatGen.forAll
      y <- bignatGen.forAll
      z <- bignatGen.forAll
    yield
      val xy = BigNat.bignatOrdering.compare(x, y)
      val yz = BigNat.bignatOrdering.compare(y, z)
      val xz = BigNat.bignatOrdering.compare(x, z)

      Result.all(
        List(
          if xy <= 0 && yz <= 0 then Result.assert(xz <= 0) else Result.success,
          if xy >= 0 && yz >= 0 then Result.assert(xz >= 0) else Result.success,
        ),
      )

  property("divide rejects zero divisors instead of throwing"):
    bignatGen.forAll.map: bignat =>
      BigNat.divide(bignat, BigNat.Zero).isLeft ==== true

  property("divideBy remains total for non-zero divisors"):
    for
      dividend <- bignatGen.forAll
      divisor <- HedgehogGenSupport.nonZeroBigNat.forAll
    yield
      BigNat.divide(dividend, divisor.toBigNat) match
        case Right(result) =>
          result ==== BigNat.divideBy(dividend, divisor)
        case Left(_) =>
          Result.failure

  property("NonZeroBigNat codec surface round-trips"):
    HedgehogGenSupport.nonZeroBigNat.forAll.map: value =>
      Result.all(
        List(
          CodecLawSupport.ByteLaws.roundTrip(value),
          CodecLawSupport.JsonLaws.roundTrip(value),
        ),
      )
