package org.sigilaris.core
package codec.json

import munit.FunSuite
import org.sigilaris.core.failure.DecodeFailure
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

  test("JsonKeyCodec.narrow validates on decode and encodes via base type"):
    final case class PositiveKey(value: Int)
    def fromInt(n: Int): Either[DecodeFailure, PositiveKey] =
      if n > 0 then Right(PositiveKey(n)) else Left(DecodeFailure(s"Not positive: ${n.toString}"))

    val kc: JsonKeyCodec[PositiveKey] = JsonKeyCodec[Int].narrow(fromInt, _.value)

    // encode uses total back-conversion
    assertEquals(kc.encodeKey(PositiveKey(3)), "3")

    // decode validates via `to`
    assertEquals(kc.decodeKey("3"), Right(PositiveKey(3)))
    assert(kc.decodeKey("0").isLeft)
    assert(kc.decodeKey("-1").isLeft)


