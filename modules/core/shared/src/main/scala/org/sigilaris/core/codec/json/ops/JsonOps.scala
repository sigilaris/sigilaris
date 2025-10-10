package org.sigilaris.core
package codec.json
package ops

import failure.DecodeFailure

/** Pure interfaces for parsing and printing JSON using backend implementations.
  * Backends must not leak their own AST types outside this package.
  */
trait JsonParser:
  def parse(input: String): Either[DecodeFailure, JsonValue]

trait JsonPrinter:
  def print(json: JsonValue): String


