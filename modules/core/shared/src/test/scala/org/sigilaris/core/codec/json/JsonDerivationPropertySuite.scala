package org.sigilaris.core
package codec.json

import hedgehog.munit.HedgehogSuite
import hedgehog.*

final class JsonDerivationPropertySuite extends HedgehogSuite:

  // Product type for tests
  case class Person(name: String, age: Option[Int])

  private val genName: Gen[String] =
    Gen.string(Gen.unicode, Range.linear(0, 32))

  private val genPerson: Gen[Person] =
    for
      n  <- genName
      oi <- Gen.int(Range.linearFrom(0, Int.MinValue, Int.MaxValue)).option
    yield Person(n, oi)

  property("product: encode/decode roundtrip with Identity naming"):
    for p <- genPerson.forAll
    yield
      val json = JsonEncoder[Person].encode(p)
      val back = JsonDecoder[Person].decode(json)
      back ==== Right(p)

  property("product: SnakeCase naming roundtrip via configured givens"):
    val snake = JsonConfig.default.copy(fieldNaming = FieldNamingPolicy.SnakeCase)
    val encs  = JsonEncoder.configured(snake)
    val decs  = JsonDecoder.configured(snake)
    import encs.given
    import decs.given

    for p <- genPerson.forAll
    yield
      val json = summon[JsonEncoder[Person]].encode(p)
      val back = summon[JsonDecoder[Person]].decode(json)
      back ==== Right(p)

  property("product: treat absent as null for missing optional fields"):
    for base <- genPerson.forAll
    yield
      val p0   = base.copy(age = Some(7))
      val json = JsonEncoder[Person].encode(p0)
      // Drop the optional field to simulate absence
      val stripped = json match
        case JsonValue.JObject(fields) => JsonValue.JObject(fields - "age" - "Age" - "age" - "AGE")
        case other                     => other
      val res = JsonDecoder[Person].decode(stripped)
      res ==== Right(p0.copy(age = None))

  // Sum type for tests
  sealed trait Shape
  object Shape:
    case class Circle(r: Int)       extends Shape
    case class Rect(w: Int, h: Int) extends Shape
  import Shape.*

  private val genShape: Gen[Shape] =
    for
      tag <- Gen.int(Range.linear(0, 1))
      s   <-
        if tag == 0 then
          Gen.int(Range.linear(0, 1000)).map(Circle.apply)
        else
          for
            w <- Gen.int(Range.linear(0, 1000))
            h <- Gen.int(Range.linear(0, 1000))
          yield Rect(w, h)
    yield s

  property("sum: wrapped-by-type-key encode; decode roundtrip"):
    for s <- genShape.forAll
    yield
      val json = JsonEncoder[Shape].encode(s)
      val back = JsonDecoder[Shape].decode(json)
      val structuralOk = json match
        case JsonValue.JObject(fields) if fields.size == 1 =>
          fields.head match
            case ("Circle", JsonValue.JObject(body)) =>
              s match
                case Circle(r) => body.get("r").exists(_ == JsonValue.JNumber(BigDecimal(r)))
                case _         => false
            case ("Rect", JsonValue.JObject(body)) =>
              s match
                case Rect(w, h) =>
                  body.get("w").exists(_ == JsonValue.JNumber(BigDecimal(w))) &&
                  body.get("h").exists(_ == JsonValue.JNumber(BigDecimal(h)))
                case _ => false
            case _ => false
        case _ => false
      Result.all(
        List(
          back ==== Right(s),
          structuralOk ==== true,
        ),
      )
