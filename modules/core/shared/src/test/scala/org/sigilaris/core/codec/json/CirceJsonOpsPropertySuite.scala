package org.sigilaris.core
package codec.json

import hedgehog.munit.HedgehogSuite
import hedgehog.*
import backend.circe.CirceJsonOps

final class CirceJsonOpsPropertySuite extends HedgehogSuite:

  private val genJsonValue: Gen[JsonValue] =
    // simple bounded generator to keep strings and arrays small
    def genLeaf: Gen[JsonValue] =
      Gen.choice1(
        Gen.constant(JsonValue.JNull),
        Gen.boolean.map(JsonValue.JBool.apply),
        Gen.int(Range.linear(-1000, 1000)).map(n => JsonValue.JNumber(BigDecimal(n))),
        Gen.string(Gen.unicode, Range.linear(0, 16)).map(JsonValue.JString.apply),
      )

    def genArray(depth: Int): Gen[JsonValue] =
      for
        size <- Gen.int(Range.linear(0, 5))
        els  <- Gen.list(genJson(depth + 1), Range.linear(0, size)).map(_.toVector)
      yield JsonValue.JArray(els)

    def genObject(depth: Int): Gen[JsonValue] =
      val keyGen = Gen.string(Gen.alphaNum, Range.linear(0, 8))
      for
        size <- Gen.int(Range.linear(0, 5))
        fields <- Gen.list(
          for
            k <- keyGen
            v <- genJson(depth + 1)
          yield (k, v),
          Range.linear(0, size),
        )
      yield JsonValue.JObject(fields.toMap)

    def genJson(depth: Int): Gen[JsonValue] =
      if depth >= 3 then genLeaf
      else Gen.choice1(genLeaf, genArray(depth), genObject(depth))

    genJson(0)

  property("Circe print ∘ parse ≡ id on JsonValue"):
    for j <- genJsonValue.forAll
    yield
      val s   = CirceJsonOps.print(j)
      val res = CirceJsonOps.parse(s)
      res ==== Right(j)
