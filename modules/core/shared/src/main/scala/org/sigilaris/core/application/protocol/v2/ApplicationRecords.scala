package org.sigilaris.core.application.protocol.v2

import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.{
  ExecutionId,
  NormalizedApplicationResult,
}
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}

import V2Codecs.given

final case class AppliedEntry(
    executionId: ExecutionId,
    resultDigest: Hash,
    normalizedResult: Bytes,
    ownerDigest: Hash,
    lastInclusionHeight: Height,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object AppliedEntry:
  given ByteEncoder[AppliedEntry]         = ByteEncoder.derived
  given ByteDecoder[AppliedEntry]         = ByteDecoder.derived
  val codec: CanonicalCodec[AppliedEntry] = CanonicalCodec.derived(validate)
  def validate(value: AppliedEntry): Either[CoreFailure, Unit] =
    RecordValidation.check(
      NormalizedApplicationResult
        .fromBytes(value.normalizedResult)
        .digest
        .toUInt256 == value.resultDigest,
      "applied.resultDigest",
    )

final case class ApplicationBatch(
    format: Long,
    context: DomainContext,
    parentBlockId: Hash,
    candidateHeight: Height,
    priorStateRoot: Hash,
    nextStateRoot: Hash,
    planRoot: Hash,
    bodyRoot: Hash,
    entries: Vector[AppliedEntry],
    statePayloadDigest: Hash,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ApplicationBatch:
  given ByteEncoder[ApplicationBatch]         = ByteEncoder.derived
  given ByteDecoder[ApplicationBatch]         = ByteDecoder.derived
  val codec: CanonicalCodec[ApplicationBatch] = CanonicalCodec.derived(validate)
  def validate(value: ApplicationBatch): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 2L, "batch.format")
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.all(value.entries.map(AppliedEntry.validate))
      _ <- RecordValidation.check(
        value.entries.nonEmpty || value.priorStateRoot == value.nextStateRoot,
        "batch.emptyStateRoot",
      )
      _ <- RecordValidation.check(
        value.entries
          .map(_.executionId)
          .distinct
          .sizeCompare(value.entries.size) == 0 && value.entries
          .map(_.ownerDigest)
          .distinct
          .sizeCompare(value.entries.size) == 0,
        "batch.entries",
      )
      _ <- V2Validation.require(
        value.entries.forall(entry =>
          value.candidateHeight.toBigNat.toBigInt <= entry.lastInclusionHeight.toBigNat.toBigInt,
        ),
        FailureCode.InvalidDeadline,
        "batch.deadline",
      )
    yield ()
  def digest(value: ApplicationBatch): Either[CoreFailure, Hash] =
    RecordValidation.digest("sigilaris.application.batch.v2", codec, value)

final case class PreparedApplication(
    schema: Long,
    batchDigest: Hash,
    batch: ApplicationBatch,
    preparedStateInventory: Hash,
    preparedAtSequence: Long,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object PreparedApplication:
  given ByteEncoder[PreparedApplication]         = ByteEncoder.derived
  given ByteDecoder[PreparedApplication]         = ByteDecoder.derived
  val codec: CanonicalCodec[PreparedApplication] =
    CanonicalCodec.derived(validate)
  def validate(value: PreparedApplication): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- RecordValidation.sequence(
        value.preparedAtSequence,
        "application.preparedAtSequence",
      )
      expected <- ApplicationBatch.digest(value.batch)
      _        <- RecordValidation.check(
        value.batchDigest == expected,
        "application.batchDigest",
      )
    yield ()

final case class ApplicationDecision(
    schema: Long,
    batchDigest: Hash,
    blockId: Hash,
    decisionSequence: Long,
    terminalOwners: Vector[Hash],
)
object ApplicationDecision:
  given ByteEncoder[ApplicationDecision]         = ByteEncoder.derived
  given ByteDecoder[ApplicationDecision]         = ByteDecoder.derived
  val codec: CanonicalCodec[ApplicationDecision] =
    CanonicalCodec.derived(validate)
  def validate(value: ApplicationDecision): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- RecordValidation.sequence(
        value.decisionSequence,
        "application.decisionSequence",
      )
      _ <- RecordValidation.check(
        value.terminalOwners.distinct
          .sizeCompare(value.terminalOwners.size) == 0,
        "application.terminalOwners",
      )
    yield ()

final case class AppliedIndexRecord(
    schema: Long,
    context: DomainContext,
    executionId: ExecutionId,
    batchDigest: Hash,
    blockId: Hash,
    candidateHeight: Height,
    resultDigest: Hash,
)
object AppliedIndexRecord:
  given ByteEncoder[AppliedIndexRecord]         = ByteEncoder.derived
  given ByteDecoder[AppliedIndexRecord]         = ByteDecoder.derived
  val codec: CanonicalCodec[AppliedIndexRecord] =
    CanonicalCodec.derived(validate)
  def validate(value: AppliedIndexRecord): Either[CoreFailure, Unit] =
    RecordValidation
      .schema(value.schema)
      .flatMap(_ => DomainContext.validateActive(value.context))

/** The nested schema-3 record is intentionally Bytes in the frozen wire schema.
  * Its complete semantic verification belongs to the node's exact verifier.
  */
final case class ExactRegistration(
    schema: Long,
    context: DomainContext,
    nodePipelineId: Text,
    canonicalRecord: Bytes,
    bindingDigest: Hash,
    requestDigest: Hash,
    lastInclusionHeight: Height,
)
object ExactRegistration:
  given ByteEncoder[ExactRegistration]         = ByteEncoder.derived
  given ByteDecoder[ExactRegistration]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExactRegistration] =
    CanonicalCodec.derived(validate)
  def validate(value: ExactRegistration): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.identifier(value.nodePipelineId, "exact.nodePipelineId")
      _ <- RecordValidation.check(
        value.canonicalRecord.nonEmpty,
        "exact.canonicalRecord",
      )
    yield ()
  def digest(value: ExactRegistration): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.exact-registration.v2",
      codec,
      value,
    )

final case class ExactRecordUpdate(
    schema: Long,
    context: DomainContext,
    nodePipelineId: Text,
    bindingDigest: Hash,
    priorRecordDigest: Hash,
    canonicalNextRecord: Bytes,
    evidenceDigest: Hash,
)
object ExactRecordUpdate:
  given ByteEncoder[ExactRecordUpdate]         = ByteEncoder.derived
  given ByteDecoder[ExactRecordUpdate]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExactRecordUpdate] =
    CanonicalCodec.derived(validate)
  def validate(value: ExactRecordUpdate): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.identifier(value.nodePipelineId, "exact.nodePipelineId")
      _ <- RecordValidation.check(
        value.canonicalNextRecord.nonEmpty,
        "exact.canonicalNextRecord",
      )
    yield ()
  def digest(value: ExactRecordUpdate): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.exact-record-update.v2",
      codec,
      value,
    )
  def key(value: ExactRecordUpdate): Either[CoreFailure, Bytes] =
    for
      context <- DomainContext.codec.encode(value.context)
      text    <- ByteVector
        .encodeUtf8(value.nodePipelineId.asString)
        .left
        .map(_ =>
          CoreFailure.at(FailureCode.InvalidIdentifier, "exact.nodePipelineId"),
        )
    yield context ++ text
