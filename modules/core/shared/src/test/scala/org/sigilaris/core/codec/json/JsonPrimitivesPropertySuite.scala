package org.sigilaris.core
package codec.json

import hedgehog.munit.HedgehogSuite
import hedgehog.*
import java.time.Instant
import org.sigilaris.core.codec.CodecLawSupport.JsonLaws

final class JsonPrimitivesPropertySuite extends HedgehogSuite:

  property("Boolean roundtrip"):
    for b <- Gen.boolean.forAll
    yield
      Result.all(List(JsonLaws.roundTrip(b), JsonLaws.deterministicEncoding(b)))

  property("String roundtrip"):
    for s <- Gen.string(Gen.unicode, Range.linear(0, 64)).forAll
    yield
      Result.all(List(JsonLaws.roundTrip(s), JsonLaws.deterministicEncoding(s)))

  property("Int roundtrip"):
    for n <- Gen.int(Range.linearFrom(0, Int.MinValue, Int.MaxValue)).forAll
    yield
      Result.all(List(JsonLaws.roundTrip(n), JsonLaws.deterministicEncoding(n)))

  property("Long roundtrip"):
    for n <- Gen.long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue)).forAll
    yield
      Result.all(List(JsonLaws.roundTrip(n), JsonLaws.deterministicEncoding(n)))

  property("BigInt roundtrip (default writes as string; decoder accepts both)"):
    for bi <- Gen.bytes(Range.linear(1, 64)).map(BigInt(_)).forAll
    yield
      val enc = JsonEncoder[BigInt].encode(bi)
      // default config writes big numbers as strings
      val isStringEncoded = enc match
        case JsonValue.JString(_) => true
        case _                    => false
      Result.all:
        List(
          isStringEncoded ==== true,
          JsonLaws.roundTrip(bi),
          JsonLaws.deterministicEncoding(bi),
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
      Result.all:
        List(
          isStringEncoded ==== true,
          JsonLaws.roundTrip(bd),
          JsonLaws.deterministicEncoding(bd),
        )

  property("Instant roundtrip (millisecond precision)"):
    for epochMilli <- Gen
        .long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))
        .forAll
    yield
      val inst = Instant.ofEpochMilli(epochMilli)
      val truncated = inst.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
      val encoded = JsonEncoder[Instant].encode(inst)
      val decoded = JsonDecoder[Instant].decode(encoded)
      Result.all(
        List(
          decoded ==== Right(truncated),
          JsonLaws.deterministicEncoding(truncated),
        ),
      )
