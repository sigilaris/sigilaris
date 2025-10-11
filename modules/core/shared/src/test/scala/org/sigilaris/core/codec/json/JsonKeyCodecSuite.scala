package org.sigilaris.core
package codec.json

import munit.FunSuite
import java.util.UUID

final class JsonKeyCodecSuite extends FunSuite:

  test("JsonKeyCodec base instances encode/decode roundtrip"):
    val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    assertEquals(JsonKeyCodec[String].decodeKey("abc"), Right("abc"))
    assertEquals(JsonKeyCodec[String].encodeKey("abc"), "abc")
    assertEquals(JsonKeyCodec[UUID].decodeKey(uuid.toString), Right(uuid))
    assertEquals(JsonKeyCodec[Int].decodeKey("42"), Right(42))
    assert(JsonKeyCodec[Int].decodeKey("x").isLeft)

  test("JsonKeyCodec.imap maps to new type"):
    case class UserId(value: Int)
    val kc: JsonKeyCodec[UserId] = JsonKeyCodec[Int].imap(UserId.apply, _.value)
    assertEquals(kc.encodeKey(UserId(7)), "7")
    assertEquals(kc.decodeKey("7"), Right(UserId(7)))


