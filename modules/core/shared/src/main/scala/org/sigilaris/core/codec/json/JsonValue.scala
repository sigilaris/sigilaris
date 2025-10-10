package org.sigilaris.core
package codec.json

/** Minimal, library-agnostic JSON value ADT.
  *
  * This AST is deliberately small and independent of any backend. All
  * higher-level behaviors (naming policies, discriminator, null/absent, etc.)
  * are handled by encoders/decoders and derivation, not by this ADT.
  *
  * The six cases cover all JSON types:
  * - `JNull`: JSON null
  * - `JBool`: JSON boolean
  * - `JNumber`: JSON number (stored as BigDecimal for precision)
  * - `JString`: JSON string
  * - `JArray`: JSON array
  * - `JObject`: JSON object (field map)
  */
enum JsonValue:
  /** JSON null value. */
  case JNull

  /** JSON boolean value.
    *
    * @param value the boolean
    */
  case JBool(value: Boolean)

  /** JSON number value.
    *
    * @param value the numeric value (BigDecimal for arbitrary precision)
    */
  case JNumber(value: BigDecimal)

  /** JSON string value.
    *
    * @param value the string content
    */
  case JString(value: String)

  /** JSON array value.
    *
    * @param values the array elements
    */
  case JArray(values: Vector[JsonValue])

  /** JSON object value.
    *
    * @param fields the object fields (key-value pairs)
    */
  case JObject(fields: Map[String, JsonValue])

object JsonValue:
  /** Convenience alias for JNull. */
  inline def nullValue: JsonValue = JNull

  /** Constructs a JSON object from field pairs.
    *
    * ```scala
    * val person = JsonValue.obj(
    *   "name" -> JString("Alice"),
    *   "age"  -> JNumber(30)
    * )
    * ```
    */
  def obj(fields: (String, JsonValue)*): JsonValue =
    JObject(fields.toMap)

  /** Constructs a JSON array from values.
    *
    * ```scala
    * val numbers = JsonValue.arr(JNumber(1), JNumber(2), JNumber(3))
    * ```
    */
  def arr(values: JsonValue*): JsonValue =
    JArray(values.toVector)


