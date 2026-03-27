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

  val InvalidRequestCode: FailureCode = FailureCode("client.invalid_request")
  val ForbiddenCode: FailureCode      = FailureCode("client.forbidden")
  val NotFoundCode: FailureCode       = FailureCode("client.not_found")

  def invalidRequest(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    invalidRequestWithCode(domain, reason, message, detail, InvalidRequestCode)

  def invalidRequestWithCode(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    encode(Kind.InvalidRequest, domain, reason, message, detail, code)

  def forbidden(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    forbiddenWithCode(domain, reason, message, detail, ForbiddenCode)

  def forbiddenWithCode(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    encode(Kind.Forbidden, domain, reason, message, detail, code)

  def notFound(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    notFoundWithCode(domain, reason, message, detail, NotFoundCode)

  def notFoundWithCode(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    encode(Kind.NotFound, domain, reason, message, detail, code)

  def errorKey(
      kind: Kind,
      domain: String,
      reason: String,
      code: FailureCode,
  ): ErrorKey =
    ErrorKey.fromCandidate(
      candidate = kind.prefix + "." + domain + "." + reason,
      fallback = kind.prefix + ".unknown.invalid_error_key",
      code = code,
      keyPattern = KeyPattern,
    )

  private def encode(
      kind: Kind,
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    FailureMessageFormat.encode(
      errorKey = errorKey(kind, domain, reason, code),
      message = message,
      detail = detail,
    )
