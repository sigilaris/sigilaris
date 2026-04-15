package org.sigilaris.core
package failure

/** Structured diagnostic payload shared by failure and rejection surfaces. */
final case class FailureDiagnostic(
    reason: String,
    detail: Option[String],
)

/** Companion for [[FailureDiagnostic]]. */
object FailureDiagnostic:
  /** Creates a diagnostic with no detail. */
  def withoutDetail(
      reason: String,
  ): FailureDiagnostic =
    FailureDiagnostic(reason = reason, detail = None)

/** Closed family describing which subsystem emitted a structured diagnostic. */
enum FailureDiagnosticFamily:
  case FootprintDerivation
  case GossipHandshakeRejected
  case GossipStaleCursor
  case GossipControlBatchRejected
  case GossipArtifactContractRejected
  case GossipBackfillUnavailable
  case HotStuffValidation
  case HotStuffPolicyViolation
  case SnapshotSync

/** Shared surface for failures that expose machine-readable reason/detail data. */
trait StructuredFailureDiagnostic:
  def diagnosticFamily: FailureDiagnosticFamily
  def reason: String
  def detail: Option[String]

  final def diagnostic: FailureDiagnostic =
    FailureDiagnostic(reason = reason, detail = detail)
