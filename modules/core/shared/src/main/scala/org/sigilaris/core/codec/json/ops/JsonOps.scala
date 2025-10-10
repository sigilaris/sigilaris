package org.sigilaris.core
package codec.json
package ops

import failure.ParseFailure

/** Pure interfaces for parsing and printing JSON using backend implementations.
  * Backends must not leak their own AST types outside this package.
  */
trait JsonParser:
  def parse(input: String): Either[ParseFailure, JsonValue]

trait JsonPrinter:
  def print(json: JsonValue): String


