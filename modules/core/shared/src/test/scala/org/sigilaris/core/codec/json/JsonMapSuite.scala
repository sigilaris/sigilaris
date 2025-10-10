package org.sigilaris.core
package codec.json

import munit.FunSuite
import JsonEncoder.ops.*
import java.util.UUID

final class JsonMapSuite extends FunSuite:

  test("encode Map[Int, String] via JsonKeyCodec[Int]"):
    val m   = Map(1 -> "a", 2 -> "b")
    val json = JsonEncoder[Map[Int, String]].encode(m)
    assertEquals(json, JsonValue.obj("1" -> JsonValue.JString("a"), "2" -> JsonValue.JString("b")))

  test("decode Map[Int, String] and fail on bad key"):
    val cfg  = JsonConfig.default
    val json = JsonValue.obj("x" -> JsonValue.JString("a"))
    val res  = JsonDecoder[Map[Int, String]].decode(json, cfg)
    assert(res.isLeft)

  test("decode Map[Int, String] and fail on duplicate normalized keys"):
    val cfg  = JsonConfig.default
    val json = JsonValue.obj("1" -> JsonValue.JString("a"), "01" -> JsonValue.JString("b"))
    val res  = JsonDecoder[Map[Int, String]].decode(json, cfg)
    assert(res.isLeft)

  test("encode/decode Map[String, Int] remains supported via JsonKeyCodec[String]"):
    val cfg = JsonConfig.default
    val m   = Map("a" -> 1, "b" -> 2)
    val json = m.toJson
    assertEquals(json, JsonValue.obj("a" -> JsonValue.JNumber(BigDecimal(1)), "b" -> JsonValue.JNumber(BigDecimal(2))))
    val round = JsonDecoder[Map[String, Int]].decode(json, cfg)
    assertEquals(round, Right(m))

  test("encode/decode Map[UUID, Int]"):
    val cfg = JsonConfig.default
    val k1  = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val k2  = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val m   = Map(k1 -> 1, k2 -> 2)
    val json = JsonEncoder[Map[UUID, Int]].encode(m)
    val decoded = JsonDecoder[Map[UUID, Int]].decode(json, cfg)
    assertEquals(decoded, Right(m))
