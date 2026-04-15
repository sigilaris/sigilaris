package org.sigilaris.core
package failure

private[failure] object FailureMessageFormat:
  private val MessageSeparator: String = ": "
  private val DetailSeparator: String  = " | "

  def encode(
      errorKey: ErrorKey,
      message: String,
      detail: Option[String],
  ): String =
    render(
      errorKey = errorKey.rendered,
      message = Some(message),
      detail = detail,
    )

  def render(
      errorKey: String,
      message: Option[String],
      detail: Option[String],
  ): String =
    val trimmedKey     = errorKey.trim
    val trimmedMessage = message.map(_.trim).filter(_.nonEmpty)
    val trimmedDetail  = detail.map(_.trim).filter(_.nonEmpty)
    val base =
      trimmedMessage.fold(trimmedKey)(trimmedKey + MessageSeparator + _)
    trimmedDetail.fold(base)(base + DetailSeparator + _)

  def parse(
      rendered: String,
  ): FailureMessageEnvelope =
    // Keep legacy compat by treating the first detail separator as the split
    // point; canonical payloads should not embed the literal separator token.
    val trimmed = rendered.trim
    val messageSeparatorIndex = trimmed.indexOf(MessageSeparator)
    if messageSeparatorIndex >= 0 then
      val key = trimmed.substring(0, messageSeparatorIndex)
      val payload =
        trimmed.substring(messageSeparatorIndex + MessageSeparator.length)
      val detailSeparatorIndex = payload.indexOf(DetailSeparator)
      if detailSeparatorIndex >= 0 then
        FailureMessageEnvelope.normalized(
          errorKey = key,
          message = Some(payload.substring(0, detailSeparatorIndex)),
          detail =
            Some(payload.substring(detailSeparatorIndex + DetailSeparator.length)),
        )
      else
        FailureMessageEnvelope.normalized(
          errorKey = key,
          message = Some(payload),
          detail = None,
        )
    else
      val detailSeparatorIndex = trimmed.indexOf(DetailSeparator)
      if detailSeparatorIndex >= 0 then
        FailureMessageEnvelope.normalized(
          errorKey = trimmed.substring(0, detailSeparatorIndex),
          message = None,
          detail =
            Some(trimmed.substring(detailSeparatorIndex + DetailSeparator.length)),
        )
      else FailureMessageEnvelope.normalized(trimmed, None, None)
