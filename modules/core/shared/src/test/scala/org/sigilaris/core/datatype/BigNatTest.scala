package org.sigilaris.core
package datatype

import codec.byte.ByteDecoder.ops.*
import codec.byte.ByteEncoder.ops.*
import codec.json.JsonDecoder
import codec.json.JsonEncoder.ops.*

import hedgehog.munit.HedgehogSuite
import hedgehog.*

class BigNatTest extends HedgehogSuite:

  val genBignat = Gen
    .bytes(Range.linear(0, 64))
    .map(BigInt(1, _))
    .map(BigNat.unsafeFromBigInt)
    .forAll

  property("roundtrip of bignat byte codec"):
    genBignat.map: bignat =>
      val encoded = bignat.toBytes

      encoded.to[BigNat] match
        case Right(decoded) => decoded ==== bignat
        case _              => Result.failure

  property("roundtrip of bignat json codec"):
    genBignat.map: bignat =>
      val encoded = bignat.toJson

      JsonDecoder[BigNat].decode(encoded) match
        case Right(decoded) => decoded ==== bignat
        case _              => Result.failure
