package org.sigilaris.core
package codec.json

/** Minimal, library-agnostic JSON value ADT.
  *
  * This AST is deliberately small and independent of any backend. All
  * higher-level behaviors (naming policies, discriminator, null/absent, etc.)
  * are handled by encoders/decoders and derivation, not by this ADT.
  */
enum JsonValue:
  case JNull
  case JBool(value: Boolean)
  case JNumber(value: BigDecimal)
  case JString(value: String)
  case JArray(values: Vector[JsonValue])
  case JObject(fields: Map[String, JsonValue])

object JsonValue:
  inline def nullValue: JsonValue = JNull

  def obj(fields: (String, JsonValue)*): JsonValue =
    JObject(fields.toMap)

  def arr(values: JsonValue*): JsonValue =
    JArray(values.toVector)


