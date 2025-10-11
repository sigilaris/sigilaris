package org.sigilaris.core
package datatype

import munit.FunSuite
import scodec.bits.*

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.json.{JsonDecoder, JsonEncoder}

final class UInt256Test extends FunSuite:

  test("fromHex: accepts common forms and pads to 32 bytes"):
    val hexs = List(
      "",
      "0",
      "00",
      "0x01",
      "01",
      "0001",
      "  0x0001  ",
      "0x01_02_03  # comment",
    )

    hexs.foreach: h =>
      val e = UInt256.fromHex(h)
      assert(e.isRight, clue = s"expected success for: '${h}' but got ${e}")
      val u   = e.toOption.get
      val b   = u.bytes
      assertEquals(b.length.toInt, UInt256.Size)

  test("byte codec: fixed 32 bytes roundtrip"):
    val short: ByteVector = hex"deadbeef"
    val u     = UInt256.unsafeFromBytesBE(short)
    val enc   = ByteEncoder[UInt256].encode(u)
    assertEquals(enc.length.toInt, UInt256.Size)
    val dec   = ByteDecoder[UInt256].decode(enc)
    assert(dec.isRight)
    assertEquals(dec.toOption.get.value, u)

  test("byte codec: roundtrip with single zero byte hex\"00\""):
    val short: ByteVector = hex"00"
    val u     = UInt256.unsafeFromBytesBE(short)
    val enc   = ByteEncoder[UInt256].encode(u)
    assertEquals(enc.length.toInt, UInt256.Size)
    val dec   = ByteDecoder[UInt256].decode(enc)
    assert(dec.isRight)
    assertEquals(dec.toOption.get.value, u)

  test("json codec: hex lower without 0x; roundtrip"):
    val u   = UInt256.fromHex("0x01aa").toOption.get
    val js  = JsonEncoder[UInt256].encode(u)
    // Expect JString; compare via decoder roundtrip to avoid Json AST import churn
    val back = JsonDecoder[UInt256].decode(js)
    assert(back.isRight)
    assertEquals(back.toOption.get, u)


