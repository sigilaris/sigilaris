package org.sigilaris.core
package codec.json

import hedgehog.munit.HedgehogSuite
import hedgehog.*
import java.time.Instant

final class JsonPrimitivesPropertySuite extends HedgehogSuite:

  property("Boolean roundtrip"):
    for b <- Gen.boolean.forAll
    yield
      val json = JsonEncoder[Boolean].encode(b)
      val res  = JsonDecoder[Boolean].decode(json, JsonConfig.default)
      res ==== Right(b)

  property("String roundtrip"):
    for s <- Gen.string(Gen.unicode, Range.linear(0, 64)).forAll
    yield
      val json = JsonEncoder[String].encode(s)
      val res  = JsonDecoder[String].decode(json, JsonConfig.default)
      res ==== Right(s)

  property("Int roundtrip"):
    for n <- Gen.int(Range.linearFrom(0, Int.MinValue, Int.MaxValue)).forAll
    yield
      val json = JsonEncoder[Int].encode(n)
      val res  = JsonDecoder[Int].decode(json, JsonConfig.default)
      res ==== Right(n)

  property("Long roundtrip"):
    for n <- Gen.long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue)).forAll
    yield
      val json = JsonEncoder[Long].encode(n)
      val res  = JsonDecoder[Long].decode(json, JsonConfig.default)
      res ==== Right(n)

  property("BigInt roundtrip (default writes as string; decoder accepts both)"):
    for bi <- Gen.bytes(Range.linear(1, 64)).map(BigInt(_)).forAll
    yield
      val enc = JsonEncoder[BigInt].encode(bi)
      // default config writes big numbers as strings
      val isStringEncoded = enc match
        case JsonValue.JString(_) => true
        case _                    => false
      val back = JsonDecoder[BigInt].decode(enc, JsonConfig.default)
      Result.all:
        List(
          isStringEncoded ==== true,
          back ==== Right(bi),
        )

  property("BigDecimal roundtrip (default writes as string; decoder accepts both)"):
    // generate BigDecimal from (unscaled, scale) to avoid Double rounding
    val genBigDecimal =
      for
        unscaled <- Gen.bytes(Range.linear(1, 64)).map(BigInt(_))
        scale    <- Gen.int(Range.linear(0, 8))
      yield BigDecimal(unscaled, scale)

    for bd <- genBigDecimal.forAll
    yield
      val enc = JsonEncoder[BigDecimal].encode(bd)
      val isStringEncoded = enc match
        case JsonValue.JString(_) => true
        case _                    => false
      val back = JsonDecoder[BigDecimal].decode(enc, JsonConfig.default)
      Result.all:
        List(
          isStringEncoded ==== true,
          back ==== Right(bd),
        )

  property("Instant roundtrip (millisecond precision)"):
    for epochMilli <- Gen
        .long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))
        .forAll
    yield
      val inst   = Instant.ofEpochMilli(epochMilli)
      val enc    = JsonEncoder[Instant].encode(inst)
      val decoded = JsonDecoder[Instant].decode(enc, JsonConfig.default)
      decoded ==== Right(inst.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
