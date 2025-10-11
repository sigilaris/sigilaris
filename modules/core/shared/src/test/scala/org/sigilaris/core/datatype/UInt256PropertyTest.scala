package org.sigilaris.core
package datatype

import hedgehog.munit.HedgehogSuite
import hedgehog.*
import scodec.bits.ByteVector
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder}

final class UInt256PropertyTest extends HedgehogSuite:

  private val genUInt256Bytes: Gen[ByteVector] =
    // generate up to 32 random bytes, allow leading zeros
    Gen.bytes(Range.linear(0, 32)).map(bs => ByteVector.view(bs))

  private val genHex: Gen[String] =
    genUInt256Bytes.map(_.toHex)

  property("ByteCodec[UInt256] roundtrip (fixed 32 bytes)"):
    for
      raw <- genUInt256Bytes.forAll
    yield
      val u = UInt256.fromBytesBE(raw).toOption.get
      val bytes = ByteEncoder[UInt256].encode(u)
      ByteDecoder[UInt256].decode(bytes) match
        case Right(decoded) => decoded.value ==== u
        case _              => Result.failure

  property("JsonCodec[UInt256] roundtrip (hex string)"):
    for
      hex <- genHex.forAll
    yield
      val u = UInt256.fromHex(hex).toOption.get
      val js = JsonEncoder[UInt256].encode(u)
      JsonDecoder[UInt256].decode(js) match
        case Right(decoded) => decoded ==== u
        case _              => Result.failure


