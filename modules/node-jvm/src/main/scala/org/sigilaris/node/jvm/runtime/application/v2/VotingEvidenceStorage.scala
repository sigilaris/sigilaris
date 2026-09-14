package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.Proposal

import V2Codecs.given

private[v2] final case class RetainedLockRequest(
    subject: LockSubject,
    descriptor: InputDescriptor,
    signedTransaction: Bytes,
    proofs: Vector[ResolutionEvidence],
)
private[v2] object RetainedLockRequest:
  given ByteEncoder[RetainedLockRequest]         = ByteEncoder.derived
  given ByteDecoder[RetainedLockRequest]         = ByteDecoder.derived
  val codec: CanonicalCodec[RetainedLockRequest] =
    CanonicalCodec.derived(value =>
      for
        _ <- LockSubject.codec.encode(value.subject)
        _ <- InputDescriptor.codec.encode(value.descriptor)
        _ <- V2Validation.all(
          value.proofs.map(ResolutionEvidence.codec.encode(_).map(_ => ())),
        )
      yield (),
    )

private[v2] final case class RetainedEffectRequest(
    subject: EffectSubject,
    owner: Owner,
    witness: ReservationWitness,
)
private[v2] object RetainedEffectRequest:
  given ByteEncoder[RetainedEffectRequest]         = ByteEncoder.derived
  given ByteDecoder[RetainedEffectRequest]         = ByteDecoder.derived
  val codec: CanonicalCodec[RetainedEffectRequest] =
    CanonicalCodec.derived(value =>
      for
        _ <- EffectSubject.codec.encode(value.subject)
        _ <- Owner.codec.encode(value.owner)
        _ <- ReservationWitness.codec.encode(value.witness)
      yield (),
    )

private[v2] final case class RetainedConsensusRequest(
    proposal: Bytes,
    plan: ExecutionPlan,
)
private[v2] object RetainedConsensusRequest:
  given ByteEncoder[RetainedConsensusRequest]         = ByteEncoder.derived
  given ByteDecoder[RetainedConsensusRequest]         = ByteDecoder.derived
  val codec: CanonicalCodec[RetainedConsensusRequest] = CanonicalCodec.derived(
    value => ExecutionPlan.codec.encode(value.plan).map(_ => ()),
  )

private[v2] final case class VotingEvidence(
    format: Long,
    kind: Long,
    payload: Bytes,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
private[v2] object VotingEvidence:
  given ByteEncoder[VotingEvidence]         = ByteEncoder.derived
  given ByteDecoder[VotingEvidence]         = ByteDecoder.derived
  val codec: CanonicalCodec[VotingEvidence] = CanonicalCodec.derived(value =>
    V2Validation.require(
      value.format == 2L && Set(1L, 2L, 3L).contains(value.kind),
      FailureCode.UnsupportedFormat,
      "votingEvidence",
    ),
  )

/** The store forces this immutable material under its intent digest before
  * appending the intent. A decoded envelope is never a verified capability.
  */
private[v2] object VotingEvidenceStorage:
  val Namespace: Text = Utf8("voting-evidence")

  def encodeLock(request: VerifiedLockRequest): Either[CoreFailure, Bytes] =
    RetainedLockRequest.codec
      .encode(
        RetainedLockRequest(
          request.subject,
          request.descriptor,
          request.signedTransaction,
          request.proofs,
        ),
      )
      .flatMap(payload =>
        VotingEvidence.codec.encode(VotingEvidence(2L, 1L, payload)),
      )

  def encodeEffect(request: VerifiedEffectRequest): Either[CoreFailure, Bytes] =
    RetainedEffectRequest.codec
      .encode(
        RetainedEffectRequest(
          request.subject,
          request.owner,
          request.witness,
        ),
      )
      .flatMap(payload =>
        VotingEvidence.codec.encode(VotingEvidence(2L, 2L, payload)),
      )

  def encodeConsensus(
      request: VerifiedConsensusProposal,
  ): Either[CoreFailure, Bytes] =
    RetainedConsensusRequest.codec
      .encode(
        RetainedConsensusRequest(
          ByteEncoder[Proposal].encode(request.proposal),
          request.plan,
        ),
      )
      .flatMap(payload =>
        VotingEvidence.codec.encode(VotingEvidence(2L, 3L, payload)),
      )
