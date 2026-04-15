package org.sigilaris.core.application.support

import org.sigilaris.core.failure.{
  ClientFailureMessage,
  ConflictMessage,
  FailureCode,
}

/** Shared formatter for reducer-facing failure messages. */
object ReducerMessageSupport:
  def invalidRequest(
      module: String,
      code: FailureCode,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    ClientFailureMessage.invalidRequestWithCode(
      module,
      reason,
      message,
      detail,
      code,
    )

  def notFound(
      module: String,
      code: FailureCode,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    ClientFailureMessage.notFoundWithCode(
      module,
      reason,
      message,
      detail,
      code,
    )

  def conflict(
      module: String,
      code: FailureCode,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    ConflictMessage.formatWithCode(module, reason, message, detail, code)
