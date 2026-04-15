package org.sigilaris.core
package failure

import scala.util.matching.Regex
import cats.Eq

/** Stable, transport-neutral identifier for a failure source.
  *
  * Values must match the pattern `[a-z0-9]+([._-][a-z0-9]+)*`.
  *
  * @param value
  *   the dot/dash/underscore-separated identifier string
  */
opaque type FailureCode = String

/** Predefined [[FailureCode]] constants for core failure categories. */
object FailureCode:
  private[failure] val Pattern: Regex =
    "^[a-z0-9]+(?:[._-][a-z0-9]+)*$".r

  /** Parses a machine-readable failure code.
    *
    * @param value
    *   candidate failure code
    * @return
    *   validated code, or an error message
    */
  def apply(value: String): Either[String, FailureCode] =
    Either.cond(
      Pattern.matches(value),
      value,
      "Invalid FailureCode: " + value,
    )

  /** Unsafely constructs a failure code from a known-valid constant.
    *
    * @throws IllegalArgumentException
    *   if the value is not a valid machine-readable failure code
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(value: String): FailureCode =
    apply(value) match
      case Right(code) => code
      case Left(err)   => throw new IllegalArgumentException(err)

  extension (code: FailureCode)
    def value: String = code

  given failureCodeEq: Eq[FailureCode] = Eq.fromUniversalEquals

  /** Failure code for unclassified errors. */
  val Unknown: FailureCode = unsafe("core.unknown")

  /** Failure code for decoding errors. */
  val Decode: FailureCode  = unsafe("core.decode")

  /** Failure code for parsing errors. */
  val Parse: FailureCode   = unsafe("core.parse")

  /** Failure code for Merkle trie errors. */
  val Trie: FailureCode    = unsafe("core.trie")

  /** Failure code for transaction routing errors. */
  val Routing: FailureCode = unsafe("core.routing")

  /** Failure code for cryptographic operation errors. */
  val Crypto: FailureCode  = unsafe("core.crypto")

  /** Failure codes specific to [[org.sigilaris.core.datatype.UInt256]] operations. */
  object UInt256:
    /** Input byte sequence exceeds the 32-byte limit. */
    val TooLong: FailureCode = unsafe("datatype.uint256.too_long")

    /** Input value is negative, which is invalid for an unsigned type. */
    val NegativeValue: FailureCode = unsafe:
      "datatype.uint256.negative_value"

    /** Input value exceeds the 2^256 - 1 upper bound. */
    val Overflow: FailureCode   = unsafe("datatype.uint256.overflow")

    /** Input hex string contains invalid characters. */
    val InvalidHex: FailureCode = unsafe("datatype.uint256.invalid_hex")

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
