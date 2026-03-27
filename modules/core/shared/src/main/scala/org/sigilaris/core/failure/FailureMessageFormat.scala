package org.sigilaris.core
package failure

import scala.util.matching.Regex

private[failure] object FailureMessageFormat:
  private val DetailSeparator: String = "|"

  def encode(
      errorKey: String,
      fallbackKey: String,
      message: String,
      detail: Option[String],
      keyPattern: Regex,
  ): String =
    val safeErrorKey: String =
      if keyPattern.matches(errorKey) then errorKey else fallbackKey
    val trimmedMessage: String = message.trim
    val base: String =
      if trimmedMessage.isEmpty then safeErrorKey
      else safeErrorKey + ": " + trimmedMessage
    detail match
      case Some(value) if value.trim.nonEmpty =>
        base + " " + DetailSeparator + " " + value.trim
      case _ =>
        base
