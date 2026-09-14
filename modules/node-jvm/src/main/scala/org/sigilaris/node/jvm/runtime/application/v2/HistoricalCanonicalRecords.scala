package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  FinalizedAnchorSuggestion,
  Proposal,
  QuorumCertificate,
}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given

/** Complete application-owned original bytes. The selected historical adapter
  * must decode its original plan/source/state-proof language and re-execute it;
  * these bytes have no authority merely because this outer codec is canonical.
  */
final case class HistoricalExecutionMaterial(
    planBytes: Bytes,
    sourceAndStateProof: Bytes,
    canonicalStatePayload: Bytes,
    normalizedResults: Vector[Bytes],
)
object HistoricalExecutionMaterial:
  import V2Codecs.given
  given ByteEncoder[HistoricalExecutionMaterial]         = ByteEncoder.derived
  given ByteDecoder[HistoricalExecutionMaterial]         = ByteDecoder.derived
  val codec: CanonicalCodec[HistoricalExecutionMaterial] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

/** Canonical application of an OLD-profile block only after its real finality.
  * This never becomes a target-context ApplicationBatch. Installed P remains a
  * speculative working parent until these records actually advance F to P.
  */
final case class HistoricalCanonicalAdvance(
    format: Long,
    handoverDigest: Hash,
    context: DomainContext,
    parentBlockId: Hash,
    blockId: Hash,
    height: Height,
    priorStateRoot: Hash,
    nextStateRoot: Hash,
    profileDigest: Hash,
    proposal: Proposal,
    certificate: QuorumCertificate,
    finalized: FinalizedAnchorSuggestion,
    material: HistoricalExecutionMaterial,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object HistoricalCanonicalAdvance:
  import V2Codecs.given
  given ByteEncoder[HistoricalCanonicalAdvance]         = ByteEncoder.derived
  given ByteDecoder[HistoricalCanonicalAdvance]         = ByteDecoder.derived
  val codec: CanonicalCodec[HistoricalCanonicalAdvance] =
    CanonicalCodec.derived(validate)
  def validate(value: HistoricalCanonicalAdvance): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(
        value.format,
        1L,
        "historicalCanonicalAdvance.format",
      )
      _ <- DomainContext.validate(value.context)
      _ <- V2Validation.require(
        value.proposal.targetBlockId.toUInt256 == value.blockId && value.proposal.block.parent
          .exists(_.toUInt256 == value.parentBlockId) &&
          value.proposal.block.height.toBigNat == value.height.toBigNat && value.proposal.window.height == value.proposal.block.height &&
          value.proposal.block.stateRoot.toUInt256 == value.nextStateRoot && value.proposal.window.chainId.value == value.context.chainId.asString &&
          value.proposal.window.validatorSetHash.toUInt256 == value.context.validatorSetHash,
        FailureCode.MembershipMismatch,
        "historicalCanonicalAdvance.proposal",
      )
      _ <- V2Validation.require(
        value.certificate.subject.window == value.proposal.window && value.certificate.subject.proposalId == value.proposal.proposalId &&
          value.certificate.subject.blockId == value.proposal.targetBlockId && value.finalized.proposal == value.proposal,
        FailureCode.CertificateMismatch,
        "historicalCanonicalAdvance.finality",
      )
    yield ()
  def digest(value: HistoricalCanonicalAdvance): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment.hash(
          Utf8("sigilaris.application.historical-canonical.advance.v1"),
          _,
        ),
      )

enum HistoricalCanonicalStatus(val tag: Byte):
  case Prepared  extends HistoricalCanonicalStatus(1.toByte)
  case Committed extends HistoricalCanonicalStatus(2.toByte)
object HistoricalCanonicalStatus:
  given ByteEncoder[HistoricalCanonicalStatus] =
    ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[HistoricalCanonicalStatus] = V2Codecs.enumDecoder(
    "historical canonical status",
    values.toVector.map(value => value.tag -> value),
  )

/** Sequence starts at 1, initial previousDigest is zero. A Prepared/Committed
  * pair has identical sequence, previous digest, advance and advance digest.
  * The next pair references the COMMITTED record digest. Recovery must verify
  * every actual historical advance before completing a valid Prepared tail.
  */
final case class HistoricalCanonicalRecord(
    format: Long,
    sequence: Long,
    previousDigest: Hash,
    advance: HistoricalCanonicalAdvance,
    advanceDigest: Hash,
    status: HistoricalCanonicalStatus,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object HistoricalCanonicalRecord:
  given ByteEncoder[HistoricalCanonicalRecord]         = ByteEncoder.derived
  given ByteDecoder[HistoricalCanonicalRecord]         = ByteDecoder.derived
  val codec: CanonicalCodec[HistoricalCanonicalRecord] =
    CanonicalCodec.derived(validate)
  def validate(value: HistoricalCanonicalRecord): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(
        value.format,
        1L,
        "historicalCanonicalRecord.format",
      )
      _ <- V2Validation.require(
        value.sequence > 0L,
        FailureCode.InvalidLength,
        "historicalCanonicalRecord.sequence",
      )
      digest <- HistoricalCanonicalAdvance.digest(value.advance)
      _      <- V2Validation.require(
        digest == value.advanceDigest,
        FailureCode.CommitmentMismatch,
        "historicalCanonicalRecord.advanceDigest",
      )
    yield ()
  def digest(value: HistoricalCanonicalRecord): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment
          .hash(Utf8("sigilaris.application.historical-canonical.record.v1"), _),
      )
