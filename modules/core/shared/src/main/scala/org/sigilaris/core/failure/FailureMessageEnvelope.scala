package org.sigilaris.core
package failure

/** Parsed or to-be-rendered failure message payload. */
final case class FailureMessageEnvelope(
    errorKey: String,
    message: Option[String],
    detail: Option[String],
):
  /** Renders the envelope using the canonical failure message formatter. */
  def render: String =
    FailureMessageFormat.render(
      errorKey = errorKey,
      message = message,
      detail = detail,
    )

/** Companion for [[FailureMessageEnvelope]]. */
object FailureMessageEnvelope:
  /** Normalizes empty message/detail branches into `None`. */
  def normalized(
      errorKey: String,
      message: Option[String],
      detail: Option[String],
  ): FailureMessageEnvelope =
    FailureMessageEnvelope(
      errorKey = errorKey.trim,
      message = message.map(_.trim).filter(_.nonEmpty),
      detail = detail.map(_.trim).filter(_.nonEmpty),
    )

  /** Parses a rendered failure message back into its structured envelope.
    *
    * The parser keeps backward compatibility with the legacy format and
    * therefore treats the first literal `" | "` after the optional message as
    * the detail boundary. Canonical inputs should avoid embedding that exact
    * separator token inside message/detail payloads.
    */
  def parse(
      rendered: String,
  ): FailureMessageEnvelope =
    FailureMessageFormat.parse(rendered)
