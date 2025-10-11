package org.sigilaris.core
package codec.json

import munit.FunSuite

final class JsonDerivationSuite extends FunSuite:

  // Product derivation
  case class Person(name: String, age: Option[Int])

  test("product: encode/decode roundtrip with Identity naming and Option field"):
    val p    = Person("Ann", Some(42))
    val json = JsonEncoder[Person].encode(p)
    val back = JsonDecoder[Person].decode(json)
    assertEquals(back, Right(p))

  test("product: drop null fields on encode when dropNullValues=true"):
    val json = JsonEncoder[Person].encode(Person("Ann", None))
    // age should be omitted when None because it encodes to JNull
    assertEquals(
      json,
      JsonValue.obj("name" -> JsonValue.JString("Ann")),
    )

  test("product: field naming SnakeCase applied on encode/decode"):
    val snake =
      JsonConfig.default.copy(fieldNaming = FieldNamingPolicy.SnakeCase)
    val p    = Person("Bob", Some(7))
    val encs = JsonEncoder.configured(snake)
    import encs.given
    val json = summon[JsonEncoder[Person]].encode(p)
    assertEquals(
      json,
      JsonValue.obj(
        "name" -> JsonValue.JString("Bob"),
        "age"  -> JsonValue.JNumber(BigDecimal(7)),
      ),
    )
    val decs = JsonDecoder.configured(snake)
    import decs.given
    val back = summon[JsonDecoder[Person]].decode(json)
    assertEquals(back, Right(p))

  test("product: treat absent as null on decode for Option field"):
    val json = JsonValue.obj("name" -> JsonValue.JString("Zed"))
    val res  = JsonDecoder[Person].decode(json)
    assertEquals(res, Right(Person("Zed", None)))

  // Coproduct derivation
  sealed trait Shape
  object Shape:
    case class Circle(r: Int)       extends Shape
    case class Rect(w: Int, h: Int) extends Shape

  import Shape.*

  test("sum: encode uses wrapped-by-type-key with SimpleName; decode roundtrip"):
    val s: Shape = Circle(3)
    val json     = JsonEncoder[Shape].encode(s)
    assertEquals(
      json,
      JsonValue.obj(
        "Circle" -> JsonValue.obj(
          "r" -> JsonValue.JNumber(BigDecimal(3)),
        ),
      ),
    )
    val back = JsonDecoder[Shape].decode(json)
    assertEquals(back, Right(s))

  test("sum: unknown subtype key fails"):
    val bad = JsonValue.obj("Triangle" -> JsonValue.JObject(Map.empty))
    val res = JsonDecoder[Shape].decode(bad)
    assert(res.isLeft)
