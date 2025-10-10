package org.sigilaris.core
package codec.json

import munit.FunSuite

final class JsonDerivedHelpersSuite extends FunSuite:

  // Simple product type for default config
  case class Book(title: String, pages: Int)

  test("derived: product encode/decode with default config"):
    val cfg   = JsonConfig.default
    val value = Book("FP in Scala", 400)
    val enc   = JsonEncoder.derived[Book]
    val dec   = JsonDecoder.derived[Book]
    val json  = enc.encode(value)
    assertEquals(json, JsonValue.obj(
      "title" -> JsonValue.JString("FP in Scala"),
      "pages" -> JsonValue.JNumber(BigDecimal(400)),
    ))
    val back  = dec.decode(json, cfg)
    assertEquals(back, Right(value))

  // Product type designed to exercise naming policy
  case class Report(pageCount: Int, authorName: String)

  test("derived: product honors SnakeCase naming via configured givens"):
    val cfg  = JsonConfig.default.copy(fieldNaming = FieldNamingPolicy.SnakeCase)
    val encs = JsonEncoder.configured(cfg)
    import encs.given
    val value = Report(12, "Alice")
    val enc   = JsonEncoder.derived[Report]
    val json  = enc.encode(value)
    assertEquals(json, JsonValue.obj(
      "page_count" -> JsonValue.JNumber(BigDecimal(12)),
      "author_name" -> JsonValue.JString("Alice"),
    ))
    val decs = JsonDecoder.configured(cfg)
    import decs.given
    val dec  = JsonDecoder.derived[Report]
    val back = dec.decode(json, cfg)
    assertEquals(back, Right(value))

  // Sum type for discriminator wrapper
  sealed trait Animal
  object Animal:
    case class Dog(name: String) extends Animal
    case class Cat(age: Int) extends Animal

  import Animal.*

  test("derived: sum uses wrapped-by-type-key; roundtrip"):
    val cfg  = JsonConfig.default
    val a: Animal = Dog("Rex")
    val enc  = JsonEncoder.derived[Animal]
    val json = enc.encode(a)
    assertEquals(json, JsonValue.obj(
      "Dog" -> JsonValue.obj("name" -> JsonValue.JString("Rex"))
    ))
    val dec  = JsonDecoder.derived[Animal]
    val back = dec.decode(json, cfg)
    assertEquals(back, Right(a))
