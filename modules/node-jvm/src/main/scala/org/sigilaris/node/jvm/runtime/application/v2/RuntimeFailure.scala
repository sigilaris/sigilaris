package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT

import org.sigilaris.core.application.protocol.v2.CoreFailure

type Result[F[_], A] = EitherT[F, V2RuntimeFailure, A]

enum RuntimeFailureCode:
  case InvalidRequest, Conflict, AlreadyApplied, AlreadyTerminal
  case DeadlineMismatch, DomainMismatch, ProofUnavailable, ProofInvalid
  case CapacityUnavailable, SignerFailure, StorageUnknown, RecoveryRequired
  case IncompleteWitness, IncompleteIndex, JournalCorrupt
  case UnsupportedUpgrade, EvidenceMissing, EvidenceContradictory
  case UnsafeBoundary, EvidenceBaselineLoss, StartupIdentityMismatch
  case ProtocolLimitExceeded, NonCanonicalEncoding, CommitmentMismatch,
    InvalidSignature

/** An error never grants permission to discard a claim or publish a signature.
  */
final case class V2RuntimeFailure(code: RuntimeFailureCode, detail: String):
  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def message: String = code.toString + ": " + detail

object V2RuntimeFailure:
  def at(code: RuntimeFailureCode, detail: String): V2RuntimeFailure =
    V2RuntimeFailure(code, detail)

  def fromCore(value: CoreFailure): V2RuntimeFailure =
    import org.sigilaris.core.application.protocol.v2.FailureCode
    val code = value.code match
      case FailureCode.ProofUnavailable => RuntimeFailureCode.ProofUnavailable
      case FailureCode.ProofInvalid     => RuntimeFailureCode.ProofInvalid
      case FailureCode.ProtocolLimitExceeded =>
        RuntimeFailureCode.ProtocolLimitExceeded
      case FailureCode.InvalidSignature   => RuntimeFailureCode.InvalidSignature
      case FailureCode.InvalidDeadline    => RuntimeFailureCode.DeadlineMismatch
      case FailureCode.CommitmentMismatch =>
        RuntimeFailureCode.CommitmentMismatch
      case FailureCode.NonCanonicalEncoding | FailureCode.TrailingBytes =>
        RuntimeFailureCode.NonCanonicalEncoding
      case _ => RuntimeFailureCode.InvalidRequest
    V2RuntimeFailure(code, value.message)

private[v2] object RuntimeCheck:
  def require(
      condition: Boolean,
      code: RuntimeFailureCode,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    Either.cond(condition, (), V2RuntimeFailure(code, detail))

  def core[A](value: Either[CoreFailure, A]): Either[V2RuntimeFailure, A] =
    value.left.map(V2RuntimeFailure.fromCore)
