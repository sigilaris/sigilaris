package org.sigilaris.core
package codec.json

import munit.FunSuite
import backend.circe.CirceJsonOps

final class CirceJsonOpsSuite extends FunSuite:

  test("print then parse basic values roundtrip"):
    val values = List[JsonValue](
      JsonValue.JNull,
      JsonValue.JBool(true),
      JsonValue.JBool(false),
      JsonValue.JNumber(BigDecimal(123)),
      JsonValue.JString("hello"),
      JsonValue.JArray(Vector(JsonValue.JNumber(1), JsonValue.JNumber(2))),
      JsonValue.JObject(Map("a" -> JsonValue.JString("b"))),
    )
    values.foreach: v =>
      val s = CirceJsonOps.print(v)
      val p = CirceJsonOps.parse(s)
      assertEquals(p, Right(v))

  test("parse invalid JSON returns ParseFailure"):
    val res = CirceJsonOps.parse("{ not-json }")
    res match
      case Left(_: org.sigilaris.core.failure.ParseFailure) => assert(true)
      case other => fail("expected ParseFailure, got: " + other)


