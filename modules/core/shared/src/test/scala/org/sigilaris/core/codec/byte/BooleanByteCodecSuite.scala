package org.sigilaris.core.codec.byte

import munit.FunSuite
import scodec.bits.ByteVector

final class BooleanByteCodecSuite extends FunSuite:
  test("Boolean decoder rejects empty input and non-canonical byte values"):
    assertEquals(ByteDecoder[Boolean].decode(ByteVector.empty).isLeft, true)
    assertEquals(ByteDecoder[Boolean].decode(ByteVector(0x02.toByte)).isLeft, true)
    assertEquals(ByteDecoder[Boolean].decode(ByteVector(0xff.toByte)).isLeft, true)
