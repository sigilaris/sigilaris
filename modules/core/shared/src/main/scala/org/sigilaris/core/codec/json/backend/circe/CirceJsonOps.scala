package org.sigilaris.core
package codec.json
package backend.circe

import failure.ParseFailure
import io.circe.{Json => CJson}
import io.circe.parser
// no syntax imports needed
import ops.{JsonParser, JsonPrinter}

private[circe] object CirceConversions:
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def toCore(json: CJson): JsonValue =
    if json.isNull then JsonValue.JNull
    else
      json.fold(
        jsonNull = JsonValue.JNull,
        jsonBoolean = b => JsonValue.JBool(b),
        jsonNumber = n => JsonValue.JNumber(n.toBigDecimal.getOrElse(BigDecimal(0))),
        jsonString = s => JsonValue.JString(s),
        jsonArray = arr => JsonValue.JArray(arr.iterator.map(toCore).toVector),
        jsonObject = obj =>
          JsonValue.JObject(obj.toMap.view.mapValues(toCore).toMap),
      )

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def fromCore(j: JsonValue): CJson = j match
    case JsonValue.JNull           => CJson.Null
    case JsonValue.JBool(b)        => CJson.fromBoolean(b)
    case JsonValue.JNumber(n)      => CJson.fromBigDecimal(n)
    case JsonValue.JString(s)      => CJson.fromString(s)
    case JsonValue.JArray(values)  => CJson.fromValues(values.map(fromCore))
    case JsonValue.JObject(fields) =>
      CJson.fromFields(fields.view.mapValues(fromCore))

/** Circe-backed parser and printer that operate on the core [[JsonValue]].
  *
  * Provides JSON parsing and printing using the Circe library while
  * converting to/from the library-agnostic [[JsonValue]] AST.
  */
object CirceJsonOps extends JsonParser, JsonPrinter:
  import CirceConversions.*

  /** Parses a JSON string using Circe and converts to [[JsonValue]].
    *
    * @param input the JSON string to parse
    * @return either a parse failure or the parsed [[JsonValue]]
    */
  def parse(input: String): Either[ParseFailure, JsonValue] =
    parser.parse(input).left.map(err => ParseFailure(err.message)).map(toCore)

  /** Prints a [[JsonValue]] to a compact JSON string using Circe.
    *
    * @param json the JSON value to print
    * @return the compact JSON string (no whitespace)
    */
  def print(json: JsonValue): String =
    fromCore(json).noSpaces
