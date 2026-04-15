package org.sigilaris.core
package failure

import scala.util.matching.Regex

/** Factory for structured conflict failure messages.
  *
  * Encodes conflict information (e.g., optimistic concurrency violations)
  * into a standardized string format using [[FailureMessageFormat]].
  */
object ConflictMessage:
  /** Prefix prepended to all conflict error keys. */
  val Prefix: String = "conflict."

  private val KeyPattern: Regex =
    "^conflict\\.[a-z0-9_]+\\.[a-z0-9_]+$".r

  /** Default failure code for generic conflict errors. */
  val DefaultCode: FailureCode = FailureCode.unsafe("conflict.generic")

  /** Formats a conflict failure message with the default failure code.
    *
    * @param domain
    *   the error domain (e.g., "state", "version")
    * @param reason
    *   the specific reason within the domain
    * @param message
    *   human-readable description
    * @param detail
    *   optional additional detail appended after a separator
    * @return
    *   the encoded failure message string
    */
  def format(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    formatWithCode(domain, reason, message, detail, DefaultCode)

  /** Formats a conflict failure message with a custom failure code.
    *
    * @param domain
    *   the error domain
    * @param reason
    *   the specific reason within the domain
    * @param message
    *   human-readable description
    * @param detail
    *   optional additional detail
    * @param code
    *   the failure code to attach
    * @return
    *   the encoded failure message string
    */
  def formatWithCode(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    FailureMessageFormat.encode(
      errorKey = errorKey(domain, reason, code),
      message = message,
      detail = detail,
    )

  /** Builds an [[ErrorKey]] for a conflict failure, validating the key format.
    *
    * @param domain
    *   the error domain
    * @param reason
    *   the specific reason
    * @param code
    *   the failure code to attach
    * @return
    *   an ErrorKey with the validated or fallback key
    */
  def errorKey(
      domain: String,
      reason: String,
      code: FailureCode,
  ): ErrorKey =
    ErrorKey.fromCandidate(
      candidate = Prefix + domain + "." + reason,
      fallback = Prefix + "unknown.invalid_error_key",
      code = code,
      keyPattern = KeyPattern,
    )
