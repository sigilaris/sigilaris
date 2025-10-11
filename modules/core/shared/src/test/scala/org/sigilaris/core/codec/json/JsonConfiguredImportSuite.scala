package org.sigilaris.core
package codec.json

import munit.FunSuite
import JsonEncoder.ops.*

final class JsonConfiguredImportSuite extends FunSuite:

  test("configured encoders import givens and encode with custom config"):
    val cfg = JsonConfig(
      fieldNaming = FieldNamingPolicy.Identity,
      dropNullValues = true,
      treatAbsentAsNull = true,
      writeBigIntAsString = false,
      writeBigDecimalAsString = true,
      discriminator = DiscriminatorConfig(TypeNameStrategy.SimpleName),
    )

    val configured = JsonEncoder.configured(cfg)
    import configured.given

    val json = BigInt(42).toJson
    assertEquals(json, JsonValue.JNumber(BigDecimal(42)))

  // Default encoders resolve without imports via JsonEncoder object inheritance
  test("default JsonEncoder instances resolve without imports"):
    val json = 123.toJson
    assertEquals(json, JsonValue.JNumber(BigDecimal(123)))

  test("configured decoders import givens and decode with custom config"):
    val cfg = JsonConfig(
      fieldNaming = FieldNamingPolicy.Identity,
      dropNullValues = true,
      treatAbsentAsNull = true,
      writeBigIntAsString = false,
      writeBigDecimalAsString = true,
      discriminator = DiscriminatorConfig(TypeNameStrategy.SimpleName),
    )

    val configured = JsonDecoder.configured(cfg)
    import configured.given

    val num = JsonValue.JNumber(BigDecimal(123))
    val str = JsonValue.JString("123")

    assertEquals(JsonDecoder[BigInt].decode(num), Right(BigInt(123)))
    assert(JsonDecoder[BigInt].decode(str).isLeft)

  // Default instances resolve without imports
  test("default JsonDecoder instances resolve without imports"):
    val jNum = JsonValue.JNumber(BigDecimal(7))
    assertEquals(JsonDecoder[Int].decode(jNum), Right(7))

    val arr =
      JsonValue.JArray(Vector(JsonValue.JNumber(1), JsonValue.JNumber(2)))
    assertEquals(JsonDecoder[List[Int]].decode(arr), Right(List(1, 2)))

    val biStr = JsonValue.JString("123")
    val biNum = JsonValue.JNumber(BigDecimal(123))
    assertEquals(JsonDecoder[BigInt].decode(biStr), Right(BigInt(123)))
    assertEquals(JsonDecoder[BigInt].decode(biNum), Right(BigInt(123)))
