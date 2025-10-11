package org.sigilaris.core
package datatype

import hedgehog.munit.HedgehogSuite
import hedgehog.*

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}

final class Utf8Test extends HedgehogSuite:

  private val genUtf8String: Gen[String] =
    val ascii   = Gen.char(' ', '~')
    val unicode = Gen.int(Range.linear(0x00A0, 0x07FF)).map(_.toChar)
    val chGen   = Gen.choice1(ascii, unicode)
    Gen.string(chGen, Range.linear(0, 64))

  property("ByteCodec[Utf8] roundtrip"):
    for s <- genUtf8String.forAll
    yield
      val u       = Utf8(s)
      val encoded = ByteEncoder[Utf8].encode(u)
      ByteDecoder[Utf8].decode(encoded) match
        case Right(DecodeResult(v, rem)) =>
          Result.all(List(v.asString ==== s, Result.assert(rem.isEmpty)))
        case _ => Result.failure

  property("JsonCodec[Utf8] roundtrip"):
    for s <- genUtf8String.forAll
    yield
      val u  = Utf8(s)
      val js = JsonEncoder[Utf8].encode(u)
      JsonDecoder[Utf8].decode(js) match
        case Right(decoded) => decoded.asString ==== s
        case _              => Result.failure

  property("JsonKeyCodec[Utf8] roundtrip"):
    for s <- genUtf8String.forAll
    yield
      val u   = Utf8(s)
      val enc = summon[JsonKeyCodec[Utf8]].encodeKey(u)
      summon[JsonKeyCodec[Utf8]].decodeKey(enc) match
        case Right(decoded) => decoded.asString ==== s
        case _              => Result.failure


