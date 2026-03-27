package org.sigilaris.core
package failure

import scala.util.matching.Regex

object ClientFailureMessage:
  enum Kind:
    case InvalidRequest
    case Forbidden
    case NotFound

    def prefix: String =
      this match
        case InvalidRequest => "invalid_request"
        case Forbidden      => "forbidden"
        case NotFound       => "not_found"

  private val KeyPattern: Regex =
    "^(invalid_request|forbidden|not_found)\\.[a-z0-9_]+\\.[a-z0-9_]+$".r

  def invalidRequest(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    encode(Kind.InvalidRequest, domain, reason, message, detail)

  def forbidden(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    encode(Kind.Forbidden, domain, reason, message, detail)

  def notFound(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    encode(Kind.NotFound, domain, reason, message, detail)

  private def encode(
      kind: Kind,
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    val errorKey: String = kind.prefix + "." + domain + "." + reason
    FailureMessageFormat.encode(
      errorKey = errorKey,
      fallbackKey = kind.prefix + ".unknown.invalid_error_key",
      message = message,
      detail = detail,
      keyPattern = KeyPattern,
    )
