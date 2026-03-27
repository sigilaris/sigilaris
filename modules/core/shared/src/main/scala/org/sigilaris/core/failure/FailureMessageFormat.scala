package org.sigilaris.core
package failure

private[failure] object FailureMessageFormat:
  private val DetailSeparator: String = "|"

  def encode(
      errorKey: ErrorKey,
      message: String,
      detail: Option[String],
  ): String =
    val trimmedMessage: String = message.trim
    val base: String =
      if trimmedMessage.isEmpty then errorKey.rendered
      else errorKey.rendered + ": " + trimmedMessage
    detail match
      case Some(value) if value.trim.nonEmpty =>
        base + " " + DetailSeparator + " " + value.trim
      case _ =>
        base
