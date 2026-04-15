package org.sigilaris.core
package failure

import scala.util.matching.Regex

/** Factory for structured client-facing failure messages.
  *
  * Encodes failure information into a standardized string format using
  * [[FailureMessageFormat]], categorized by client failure kind
  * (invalid request, forbidden, not found).
  */
object ClientFailureMessage:
  /** Classification of client failure kinds. */
  enum Kind:
    /** The request was malformed or contained invalid parameters. */
    case InvalidRequest

    /** The caller lacks permission for the requested operation. */
    case Forbidden

    /** The requested resource does not exist. */
    case NotFound

    /** Returns the dot-separated prefix used in error keys.
      *
      * @return
      *   lowercase prefix string (e.g., "invalid_request")
      */
    def prefix: String =
      this match
        case InvalidRequest => "invalid_request"
        case Forbidden      => "forbidden"
        case NotFound       => "not_found"

  private val KeyPattern: Regex =
    "^(invalid_request|forbidden|not_found)\\.[a-z0-9_]+\\.[a-z0-9_]+$".r

  /** Failure code for invalid request errors. */
  val InvalidRequestCode: FailureCode = FailureCode.unsafe(
    "client.invalid_request",
  )

  /** Failure code for forbidden errors. */
  val ForbiddenCode: FailureCode = FailureCode.unsafe("client.forbidden")

  /** Failure code for not-found errors. */
  val NotFoundCode: FailureCode = FailureCode.unsafe("client.not_found")

  /** Formats an invalid-request failure message with the default failure code.
    *
    * @param domain
    *   the error domain (e.g., "account", "transaction")
    * @param reason
    *   the specific reason within the domain
    * @param message
    *   human-readable description
    * @param detail
    *   optional additional detail appended after a separator
    * @return
    *   the encoded failure message string
    */
  def invalidRequest(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    invalidRequestWithCode(domain, reason, message, detail, InvalidRequestCode)

  /** Formats an invalid-request failure message with a custom failure code.
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
  def invalidRequestWithCode(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    envelope(Kind.InvalidRequest, domain, reason, message, detail, code).render

  /** Formats a forbidden failure message with the default failure code.
    *
    * @param domain
    *   the error domain
    * @param reason
    *   the specific reason within the domain
    * @param message
    *   human-readable description
    * @param detail
    *   optional additional detail
    * @return
    *   the encoded failure message string
    */
  def forbidden(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    forbiddenWithCode(domain, reason, message, detail, ForbiddenCode)

  /** Formats a forbidden failure message with a custom failure code.
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
  def forbiddenWithCode(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    envelope(Kind.Forbidden, domain, reason, message, detail, code).render

  /** Formats a not-found failure message with the default failure code.
    *
    * @param domain
    *   the error domain
    * @param reason
    *   the specific reason within the domain
    * @param message
    *   human-readable description
    * @param detail
    *   optional additional detail
    * @return
    *   the encoded failure message string
    */
  def notFound(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
  ): String =
    notFoundWithCode(domain, reason, message, detail, NotFoundCode)

  /** Formats a not-found failure message with a custom failure code.
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
  def notFoundWithCode(
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): String =
    envelope(Kind.NotFound, domain, reason, message, detail, code).render

  /** Builds an [[ErrorKey]] for a client failure, validating the key format.
    *
    * @param kind
    *   the client failure kind
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

  /** Builds the structured envelope for a client failure message. */
  private[failure] def envelope(
      kind: Kind,
      domain: String,
      reason: String,
      message: String,
      detail: Option[String],
      code: FailureCode,
  ): FailureMessageEnvelope =
    FailureMessageEnvelope.normalized(
      errorKey = errorKey(kind, domain, reason, code).rendered,
      message = Some(message),
      detail = detail,
    )
