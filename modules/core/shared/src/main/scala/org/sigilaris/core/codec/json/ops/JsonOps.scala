package org.sigilaris.core
package codec.json
package ops

import failure.ParseFailure

/** Pure interfaces for parsing and printing JSON using backend implementations.
  *
  * These traits define backend-agnostic operations for JSON string parsing
  * and printing. Implementations (e.g., Circe) must not leak their own
  * AST types outside this package.
  *
  * @see [[org.sigilaris.core.codec.json.backend.circe.CirceJsonOps]]
  */
trait JsonParser:
  /** Parses a JSON string into a [[JsonValue]].
    *
    * @param input the JSON string
    * @return either a parse failure or the parsed JSON value
    */
  def parse(input: String): Either[ParseFailure, JsonValue]

/** Printer for converting [[JsonValue]] to JSON strings. */
trait JsonPrinter:
  /** Prints a [[JsonValue]] to a JSON string.
    *
    * @param json the JSON value
    * @return the JSON string representation
    */
  def print(json: JsonValue): String


