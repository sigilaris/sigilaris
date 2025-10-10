package org.sigilaris.core
package codec.json

import hedgehog.munit.HedgehogSuite
import hedgehog.*

final class JsonCollectionsPropertySuite extends HedgehogSuite:

  property("List[Int] roundtrip"):
    for xs <- Gen.list(Gen.int(Range.linearFrom(0, Int.MinValue, Int.MaxValue)), Range.linear(0, 30)).forAll
    yield
      val json = JsonEncoder[List[Int]].encode(xs)
      val res  = JsonDecoder[List[Int]].decode(json, JsonConfig.default)
      res ==== Right(xs)

  property("Vector[String] roundtrip"):
    val genStr = Gen.string(Gen.unicode, Range.linear(0, 16))
    for xs <- Gen.list(genStr, Range.linear(0, 20)).map(_.toVector).forAll
    yield
      val json = JsonEncoder[Vector[String]].encode(xs)
      val res  = JsonDecoder[Vector[String]].decode(json, JsonConfig.default)
      res ==== Right(xs)

  property("Option[Int] roundtrip (None encoded as null; decoder treats null)"):
    for oi <- Gen.int(Range.linearFrom(0, Int.MinValue, Int.MaxValue)).option.forAll
    yield
      val json = JsonEncoder[Option[Int]].encode(oi)
      val res  = JsonDecoder[Option[Int]].decode(json, JsonConfig.default)
      res ==== Right(oi)

  property("Map[Int, Int] roundtrip via JsonKeyCodec[Int]"):
    val pairGen = for
      k <- Gen.int(Range.linearFrom(0, Int.MinValue, Int.MaxValue))
      v <- Gen.int(Range.linearFrom(0, Int.MinValue, Int.MaxValue))
    yield (k, v)

    for m <- Gen.list(pairGen, Range.linear(0, 20)).map(_.toMap).forAll
    yield
      val json = JsonEncoder[Map[Int, Int]].encode(m)
      val res  = JsonDecoder[Map[Int, Int]].decode(json, JsonConfig.default)
      res ==== Right(m)

  property("Map[String, Int] roundtrip via JsonKeyCodec[String]"):
    val keyGen = Gen.string(Gen.alphaNum, Range.linear(0, 8))
    val pairGen = for
      k <- keyGen
      v <- Gen.int(Range.linear(0, 1000))
    yield (k, v)

    for m <- Gen.list(pairGen, Range.linear(0, 20)).map(_.toMap).forAll
    yield
      val json = JsonEncoder[Map[String, Int]].encode(m)
      val res  = JsonDecoder[Map[String, Int]].decode(json, JsonConfig.default)
      res ==== Right(m)
