package org.sigilaris.node.gossip

import org.sigilaris.core.failure.{
  FailureDiagnosticFamily,
  StructuredFailureDiagnostic,
}
import org.sigilaris.core.util.SafeStringInterp.*

/** Base trait for all canonical rejection types in the gossip protocol. */
sealed trait CanonicalRejection extends StructuredFailureDiagnostic:

  /** @return the classification of this rejection */
  def rejectionClass: String

  /** @return the machine-readable reason code */
  def reason: String

  /** @return optional human-readable detail */
  def detail: Option[String]

/** Companion for `CanonicalRejection` defining concrete rejection subtypes.
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object CanonicalRejection:

  /** Rejection during session handshake.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class HandshakeRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val diagnosticFamily: FailureDiagnosticFamily =
      FailureDiagnosticFamily.GossipHandshakeRejected
    override val rejectionClass: String = "handshakeRejected"

  /** Rejection when a cursor token is no longer valid.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class StaleCursor(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val diagnosticFamily: FailureDiagnosticFamily =
      FailureDiagnosticFamily.GossipStaleCursor
    override val rejectionClass: String = "staleCursor"

  /** Rejection when a control batch cannot be processed.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class ControlBatchRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val diagnosticFamily: FailureDiagnosticFamily =
      FailureDiagnosticFamily.GossipControlBatchRejected
    override val rejectionClass: String = "controlBatchRejected"

  /** Rejection when an artifact fails contract validation.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class ArtifactContractRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val diagnosticFamily: FailureDiagnosticFamily =
      FailureDiagnosticFamily.GossipArtifactContractRejected
    override val rejectionClass: String = "artifactContractRejected"

  /** Rejection when backfill data is not available.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class BackfillUnavailable(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val diagnosticFamily: FailureDiagnosticFamily =
      FailureDiagnosticFamily.GossipBackfillUnavailable
    override val rejectionClass: String = "backfillUnavailable"

/** Validation utilities for gossip protocol field values. */
object GossipFieldValidation:
  private val LowerAsciiToken = "^[a-z0-9][a-z0-9._-]*$".r
  private val LowerAsciiTopic = "^[a-z0-9][a-z0-9._-]*$".r
  private val CanonicalUuidV4 =
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$".r
  private val UuidLike =
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  /** Validates that a value is a non-empty lowercase ASCII token.
    *
    * @param kind
    *   the field name, used in error messages
    * @param value
    *   the value to validate
    * @return
    *   the value or an error message
    */
  def validateLowerAsciiToken(
      kind: String,
      value: String,
  ): Either[String, String] =
    Either.cond(
      LowerAsciiToken.matches(value),
      value,
      ss"${kind} must be a non-empty lowercase ASCII token",
    )

  /** Validates that a value is a valid gossip topic string.
    *
    * @param value
    *   the topic string to validate
    * @return
    *   the value or an error message
    */
  def validateTopic(value: String): Either[String, String] =
    Either.cond(
      LowerAsciiTopic.matches(value),
      value,
      "topic must be a non-empty lowercase ASCII token",
    )

  /** Validates that a value is a canonical lowercase UUIDv4 string.
    *
    * @param kind
    *   the field name, used in error messages
    * @param value
    *   the UUID string to validate; uppercase forms are rejected to keep the
    *   wire representation canonical
    * @return
    *   the validated UUID string or an error message
    */
  def validateUuidV4(kind: String, value: String): Either[String, String] =
    if CanonicalUuidV4.matches(value) then Right[String, String](value)
    else if UuidLike.matches(value) then
      Left[String, String](
        ss"${kind} must be a lowercase canonical UUIDv4 string",
      )
    else Left[String, String](ss"${kind} must be a UUIDv4 string")
