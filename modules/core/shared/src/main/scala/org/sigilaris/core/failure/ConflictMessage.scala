package org.sigilaris.core
package failure

import scala.util.matching.Regex

object ConflictMessage:
  val Prefix: String = "conflict."

  private val KeyPattern: Regex =
    "^conflict\\.[a-z0-9_]+\\.[a-z0-9_]+$".r

  def format(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    FailureMessageFormat.encode(
      errorKey = Prefix + domain + "." + reason,
      fallbackKey = Prefix + "unknown.invalid_error_key",
      message = message,
      detail = detail,
      keyPattern = KeyPattern,
    )
