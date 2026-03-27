package org.sigilaris.core
package failure

import scala.util.matching.Regex

object ConflictMessage:
  val Prefix: String = "conflict."

  private val KeyPattern: Regex =
    "^conflict\\.[a-z0-9_]+\\.[a-z0-9_]+$".r

  val DefaultCode: FailureCode = FailureCode("conflict.generic")

  def format(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    formatWithCode(domain, reason, message, detail, DefaultCode)

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
