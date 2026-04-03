package org.sigilaris.core
package datatype

import codec.byte.ByteDecoder.ops.*
import codec.byte.ByteEncoder.ops.*
import codec.json.JsonDecoder
import codec.json.JsonEncoder.ops.*

import hedgehog.munit.HedgehogSuite
import hedgehog.*

class BigNatTest extends HedgehogSuite:

  private val bignatGen = Gen
    .bytes(Range.linear(0, 64))
    .map(BigInt(1, _))
    .map(BigNat.unsafeFromBigInt)

  property("roundtrip of bignat byte codec"):
    bignatGen.forAll.map: bignat =>
      val encoded = bignat.toBytes

      encoded.to[BigNat] match
        case Right(decoded) => decoded ==== bignat
        case _              => Result.failure

  property("roundtrip of bignat json codec"):
    bignatGen.forAll.map: bignat =>
      val encoded = bignat.toJson

      JsonDecoder[BigNat].decode(encoded) match
        case Right(decoded) => decoded ==== bignat
        case _              => Result.failure

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

      Result.all(List(
        if xy <= 0 && yz <= 0 then Result.assert(xz <= 0) else Result.success,
        if xy >= 0 && yz >= 0 then Result.assert(xz >= 0) else Result.success,
      ))
