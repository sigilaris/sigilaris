package org.sigilaris.core
package datatype

import hedgehog.munit.HedgehogSuite
import hedgehog.*
import scodec.bits.ByteVector
import org.sigilaris.core.codec.CodecLawSupport
import org.sigilaris.core.testing.HedgehogGenSupport

final class UInt256PropertyTest extends HedgehogSuite:

  private val genUInt256Bytes: Gen[ByteVector] =
    HedgehogGenSupport.byteVector(Range.linear(0, 32))

  private val genHex: Gen[String] =
    genUInt256Bytes.map(_.toHex)

  property("ByteCodec[UInt256] roundtrip (fixed 32 bytes)"):
    for raw <- genUInt256Bytes.forAll
    yield
      val u     = UInt256.fromBytesBE(raw).toOption.get
      CodecLawSupport.ByteLaws.roundTrip(u)

  property("JsonCodec[UInt256] roundtrip (hex string)"):
    for hex <- genHex.forAll
    yield
      val u  = UInt256.fromHex(hex).toOption.get
      CodecLawSupport.JsonLaws.roundTrip(u)
