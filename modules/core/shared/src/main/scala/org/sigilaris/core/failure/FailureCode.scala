package org.sigilaris.core
package failure

import scala.util.matching.Regex

/** Stable, transport-neutral identifier for a failure source. */
final case class FailureCode(value: String):
  require(FailureCode.Pattern.matches(value), "Invalid FailureCode: " + value)

object FailureCode:
  private[failure] val Pattern: Regex =
    "^[a-z0-9]+(?:[._-][a-z0-9]+)*$".r

  val Unknown: FailureCode = FailureCode("core.unknown")
  val Decode: FailureCode  = FailureCode("core.decode")
  val Parse: FailureCode   = FailureCode("core.parse")
  val Trie: FailureCode    = FailureCode("core.trie")
  val Routing: FailureCode = FailureCode("core.routing")
  val Crypto: FailureCode  = FailureCode("core.crypto")

  object UInt256:
    val TooLong: FailureCode       = FailureCode("datatype.uint256.too_long")
    val NegativeValue: FailureCode = FailureCode("datatype.uint256.negative_value")
    val Overflow: FailureCode      = FailureCode("datatype.uint256.overflow")
    val InvalidHex: FailureCode    = FailureCode("datatype.uint256.invalid_hex")

/** External mapping key projected from a [[FailureCode]] plus formatter context.
  */
final case class ErrorKey(rendered: String, code: FailureCode)

object ErrorKey:
  def fromCandidate(
      candidate: String,
      fallback: String,
      code: FailureCode,
      keyPattern: Regex,
  ): ErrorKey =
    val rendered =
      if keyPattern.matches(candidate) then candidate else fallback
    ErrorKey(rendered, code)
