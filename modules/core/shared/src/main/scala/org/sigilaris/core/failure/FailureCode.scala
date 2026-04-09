package org.sigilaris.core
package failure

import scala.util.matching.Regex

/** Stable, transport-neutral identifier for a failure source.
  *
  * Values must match the pattern `[a-z0-9]+([._-][a-z0-9]+)*`.
  *
  * @param value
  *   the dot/dash/underscore-separated identifier string
  */
final case class FailureCode(value: String):
  require(FailureCode.Pattern.matches(value), "Invalid FailureCode: " + value)

/** Predefined [[FailureCode]] constants for core failure categories. */
object FailureCode:
  private[failure] val Pattern: Regex =
    "^[a-z0-9]+(?:[._-][a-z0-9]+)*$".r

  /** Failure code for unclassified errors. */
  val Unknown: FailureCode = FailureCode("core.unknown")

  /** Failure code for decoding errors. */
  val Decode: FailureCode  = FailureCode("core.decode")

  /** Failure code for parsing errors. */
  val Parse: FailureCode   = FailureCode("core.parse")

  /** Failure code for Merkle trie errors. */
  val Trie: FailureCode    = FailureCode("core.trie")

  /** Failure code for transaction routing errors. */
  val Routing: FailureCode = FailureCode("core.routing")

  /** Failure code for cryptographic operation errors. */
  val Crypto: FailureCode  = FailureCode("core.crypto")

  /** Failure codes specific to [[org.sigilaris.core.datatype.UInt256]] operations. */
  object UInt256:
    /** Input byte sequence exceeds the 32-byte limit. */
    val TooLong: FailureCode = FailureCode("datatype.uint256.too_long")

    /** Input value is negative, which is invalid for an unsigned type. */
    val NegativeValue: FailureCode = FailureCode:
      "datatype.uint256.negative_value"

    /** Input value exceeds the 2^256 - 1 upper bound. */
    val Overflow: FailureCode   = FailureCode("datatype.uint256.overflow")

    /** Input hex string contains invalid characters. */
    val InvalidHex: FailureCode = FailureCode("datatype.uint256.invalid_hex")

/** External mapping key projected from a [[FailureCode]] plus formatter
  * context.
  *
  * @param rendered
  *   the validated (or fallback) key string
  * @param code
  *   the associated failure code
  */
final case class ErrorKey(rendered: String, code: FailureCode)

/** Factory for [[ErrorKey]] instances with pattern-based validation. */
object ErrorKey:
  /** Builds an ErrorKey, falling back to a safe default if the candidate
    * does not match the expected pattern.
    *
    * @param candidate
    *   the proposed key string
    * @param fallback
    *   the key string to use if candidate is invalid
    * @param code
    *   the failure code to attach
    * @param keyPattern
    *   regex the candidate must match
    * @return
    *   an ErrorKey with either the candidate or fallback as rendered key
    */
  def fromCandidate(
      candidate: String,
      fallback: String,
      code: FailureCode,
      keyPattern: Regex,
  ): ErrorKey =
    val rendered =
      if keyPattern.matches(candidate) then candidate else fallback
    ErrorKey(rendered, code)
