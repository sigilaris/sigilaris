package org.sigilaris.node.txpipeline.v2

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8

import V2Codecs.given

enum ExactStageLifecycle(val tag: Byte):
  case Accepted         extends ExactStageLifecycle(1.toByte)
  case LockCertified    extends ExactStageLifecycle(2.toByte)
  case Reserved         extends ExactStageLifecycle(3.toByte)
  case Included         extends ExactStageLifecycle(4.toByte)
  case Finalized        extends ExactStageLifecycle(5.toByte)
  case Materialized     extends ExactStageLifecycle(6.toByte)
  case ExpiredUnapplied extends ExactStageLifecycle(7.toByte)
  case Failed           extends ExactStageLifecycle(8.toByte)

object ExactStageLifecycle:
  val all: Vector[ExactStageLifecycle] = Vector(
    Accepted,
    LockCertified,
    Reserved,
    Included,
    Finalized,
    Materialized,
    ExpiredUnapplied,
    Failed,
  )
  given ByteEncoder[ExactStageLifecycle] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ExactStageLifecycle] = V2Codecs.enumDecoder(
    "exact lifecycle",
    all.map(value => value.tag -> value),
  )
  val codec: CanonicalCodec[ExactStageLifecycle] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class ExactStageRecord(
    executionId: ExecutionId,
    sourceKind: Byte,
    lifecycle: ExactStageLifecycle,
    inputLockCertificateId: Option[Hash],
    effectCertificateId: Option[Hash],
    firstApplicationBlock: Option[Hash],
    firstApplicationHeight: Option[Height],
    resultDigest: Option[Hash],
    terminalEvidenceDigest: Option[Hash],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactStageRecord:
  given ByteEncoder[ExactStageRecord]         = ByteEncoder.derived
  given ByteDecoder[ExactStageRecord]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExactStageRecord] = CanonicalCodec.derived(validate)

  /** Presence is checked per state, never by comparing enum tag numbers. */
  def validate(value: ExactStageRecord): Either[CoreFailure, Unit] =
    val application = Vector(
      value.firstApplicationBlock.nonEmpty,
      value.firstApplicationHeight.nonEmpty,
      value.resultDigest.nonEmpty,
    )
    for
      _ <- ExactValidation.source(value.sourceKind)
      _ <- ExactValidation.check(
        application.forall(identity) || application.forall(present => !present),
        "exactStage.applicationClosure",
      )
      _ <- ExactValidation.check(
        value.sourceKind != 1.toByte || value.effectCertificateId.isEmpty,
        "exactStage.consensusEffect",
      )
      _ <- ExactValidation.check(
        value.effectCertificateId.isEmpty || value.inputLockCertificateId.nonEmpty,
        "exactStage.effectLock",
      )
      _ <- V2Validation.all(
        value.firstApplicationHeight.toList.toVector.map(height =>
          V2Validation.require(
            height.toBigNat.toBigInt > 0,
            FailureCode.InvalidDeadline,
            "exactStage.applicationHeight",
          ),
        ),
      )
      _ <- value.lifecycle match
        case ExactStageLifecycle.Accepted =>
          ExactValidation.check(
            value.inputLockCertificateId.isEmpty && value.effectCertificateId.isEmpty && value.firstApplicationBlock.isEmpty && value.terminalEvidenceDigest.isEmpty,
            "exactStage.accepted",
          )
        case ExactStageLifecycle.LockCertified =>
          ExactValidation.check(
            value.inputLockCertificateId.nonEmpty && value.effectCertificateId.isEmpty && value.firstApplicationBlock.isEmpty && value.terminalEvidenceDigest.isEmpty,
            "exactStage.lockCertified",
          )
        case ExactStageLifecycle.Reserved =>
          // Durable reservation precedes the first effect vote. A fast source
          // needs its input lock here, and gains effect readiness separately.
          ExactValidation.check(
            value.firstApplicationBlock.isEmpty && value.terminalEvidenceDigest.isEmpty && (value.sourceKind == 1.toByte || value.inputLockCertificateId.nonEmpty),
            "exactStage.reserved",
          )
        case ExactStageLifecycle.Included | ExactStageLifecycle.Finalized |
            ExactStageLifecycle.Materialized =>
          ExactValidation.check(
            value.firstApplicationBlock.nonEmpty && value.terminalEvidenceDigest.nonEmpty && (value.sourceKind == 1.toByte || value.effectCertificateId.nonEmpty),
            "exactStage.applied",
          )
        case ExactStageLifecycle.ExpiredUnapplied =>
          ExactValidation.check(
            value.firstApplicationBlock.isEmpty && value.terminalEvidenceDigest.nonEmpty,
            "exactStage.expiredUnapplied",
          )
        case ExactStageLifecycle.Failed =>
          // Failure grants no release or application inference. An existing
          // complete application remains evidenced if retained during recovery.
          ExactValidation.check(
            value.firstApplicationBlock.isEmpty ||
              (value.terminalEvidenceDigest.nonEmpty &&
                (value.sourceKind == 1.toByte || value.effectCertificateId.nonEmpty)),
            "exactStage.failedEvidence",
          )
    yield ()

  def accepted(
      executionId: ExecutionId,
      sourceKind: Byte,
  ): Either[CoreFailure, ExactStageRecord] =
    val value = ExactStageRecord(
      executionId,
      sourceKind,
      ExactStageLifecycle.Accepted,
      None,
      None,
      None,
      None,
      None,
      None,
    )
    validate(value).map(_ => value)

final case class ExactPipelineRecord(
    schema: Long,
    binding: ExactIdentityBinding,
    request: ExactSubmitRequest,
    stages: Vector[ExactStageRecord],
    acceptedAtMillis: Long,
    admissionJournalSequence: Option[Long],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactPipelineRecord:
  val Domain: Text = Utf8("sigilaris.tx-pipeline.exact.record.v3")
  given ByteEncoder[ExactPipelineRecord] = ByteEncoder.derived
  given ByteDecoder[ExactPipelineRecord] = for
    schema           <- ByteDecoder[Long]
    binding          <- ByteDecoder[ExactIdentityBinding]
    request          <- ByteDecoder[ExactSubmitRequest]
    stages           <- V2Codecs.boundedVectorDecoder[ExactStageRecord](2L)
    acceptedAtMillis <- ByteDecoder[Long]
    admissionJournalSequence <- ByteDecoder[Option[Long]]
  yield ExactPipelineRecord(
    schema,
    binding,
    request,
    stages,
    acceptedAtMillis,
    admissionJournalSequence,
  )
  val codec: CanonicalCodec[ExactPipelineRecord] =
    CanonicalCodec.derived(validate)

  def validate(value: ExactPipelineRecord): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.schema, 3L, "exactRecord.schema")
      _ <- ExactSubmitRequest.validate(value.request)
      _ <- ExactIdentityBinding.verify(value.binding, value.request.signedPlan)
      _ <- ExactValidation.check(
        value.stages.sizeCompare(2) == 0 && value.stages.map(
          _.executionId,
        ) == value.binding.executionIds && value.stages.map(
          _.sourceKind,
        ) == value.request.signedPlan.intent.stages.map(_.sourceKind),
        "exactRecord.stageBinding",
      )
      _ <- V2Validation.all(value.stages.map(ExactStageRecord.validate))
      _ <- ExactValidation.check(
        value.admissionJournalSequence.forall(_ > 0L),
        "exactRecord.admissionSequence",
      )
      _ <- ExactValidation.check(
        value.admissionJournalSequence.nonEmpty || value.stages.forall(
          _.lifecycle == ExactStageLifecycle.Accepted,
        ),
        "exactRecord.unregisteredLifecycle",
      )
      _ <- V2Validation.all(
        value.stages
          .flatMap(_.firstApplicationHeight)
          .map(height =>
            V2Validation.require(
              height.toBigNat.toBigInt <= value.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt,
              FailureCode.InvalidDeadline,
              "exactRecord.applicationDeadline",
            ),
          ),
      )
    yield ()

  def digest(value: ExactPipelineRecord): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

  def accepted(
      nodePipelineId: Text,
      request: ExactSubmitRequest,
      acceptedAtMillis: Long,
  ): Either[CoreFailure, ExactPipelineRecord] =
    for
      _       <- ExactSubmitRequest.validate(request)
      binding <- ExactIdentityBinding.derive(nodePipelineId, request.signedPlan)
      stages  <- ExactValidation.traverse(
        binding.executionIds.zip(request.signedPlan.intent.stages),
      )((execution, stage) =>
        ExactStageRecord.accepted(execution, stage.sourceKind),
      )
      value = ExactPipelineRecord(
        3L,
        binding,
        request,
        stages,
        acceptedAtMillis,
        None,
      )
      _ <- validate(value)
    yield value

  /** Immutable identity and application facts checked independently of
    * evidence. Runtime transition verification still authenticates
    * certificates/finality.
    */
  def validateUpdate(
      previous: ExactPipelineRecord,
      next: ExactPipelineRecord,
  ): Either[CoreFailure, Unit] =
    for
      _ <- validate(previous)
      _ <- validate(next)
      _ <- ExactValidation.check(
        previous.binding == next.binding && previous.request == next.request && previous.acceptedAtMillis == next.acceptedAtMillis,
        "exactRecord.immutableAdmission",
      )
      _ <- ExactValidation.check(
        previous.admissionJournalSequence.forall(sequence =>
          next.admissionJournalSequence.contains(sequence),
        ),
        "exactRecord.immutableSequence",
      )
      _ <- V2Validation.all(
        previous.stages
          .zip(next.stages)
          .map((before, after) =>
            ExactValidation.check(
              before.inputLockCertificateId
                .forall(id => after.inputLockCertificateId.contains(id)) &&
                before.effectCertificateId
                  .forall(id => after.effectCertificateId.contains(id)) &&
                before.firstApplicationBlock
                  .forall(id => after.firstApplicationBlock.contains(id)) &&
                before.firstApplicationHeight.forall(height =>
                  after.firstApplicationHeight.contains(height),
                ) &&
                before.resultDigest
                  .forall(result => after.resultDigest.contains(result)) &&
                (before.lifecycle != ExactStageLifecycle.ExpiredUnapplied ||
                  (after.lifecycle == ExactStageLifecycle.ExpiredUnapplied && before.terminalEvidenceDigest == after.terminalEvidenceDigest)),
              "exactRecord.immutableStageEvidence",
            ),
          ),
      )
    yield ()

final case class IdempotencyOwner(
    key: Text,
    requestDigest: Hash,
    nodePipelineId: Text,
)
object IdempotencyOwner:
  given ByteEncoder[IdempotencyOwner]         = ByteEncoder.derived
  given ByteDecoder[IdempotencyOwner]         = ByteDecoder.derived
  val codec: CanonicalCodec[IdempotencyOwner] = CanonicalCodec.derived(validate)
  def validate(value: IdempotencyOwner): Either[CoreFailure, Unit] = for
    _ <- V2Validation.identifier(value.key, "idempotency.key")
    _ <- V2Validation.identifier(
      value.nodePipelineId,
      "idempotency.nodePipelineId",
    )
  yield ()

final case class StageOwner(
    context: DomainContext,
    stageTxId: Hash,
    bindingDigest: Hash,
)
object StageOwner:
  given ByteEncoder[StageOwner]         = ByteEncoder.derived
  given ByteDecoder[StageOwner]         = ByteDecoder.derived
  val codec: CanonicalCodec[StageOwner] =
    CanonicalCodec.derived(value => DomainContext.validateActive(value.context))
  def key(value: StageOwner): Either[CoreFailure, String] = DomainContext.codec
    .encode(value.context)
    .map(bytes => bytes.toHex + value.stageTxId.bytes.toHex)

final case class OutputOwner(
    context: DomainContext,
    producerTxId: Hash,
    outputSlot: Long,
    bindingDigest: Hash,
)
object OutputOwner:
  given ByteEncoder[OutputOwner]         = ByteEncoder.derived
  given ByteDecoder[OutputOwner]         = ByteDecoder.derived
  val codec: CanonicalCodec[OutputOwner] = CanonicalCodec.derived(validate)
  def validate(value: OutputOwner): Either[CoreFailure, Unit] = for
    _ <- DomainContext.validateActive(value.context)
    _ <- ExactValidation.check(value.outputSlot >= 0L, "outputOwner.outputSlot")
  yield ()
  def key(value: OutputOwner): Either[CoreFailure, String] = validate(value)
    .flatMap(_ => DomainContext.codec.encode(value.context))
    .map(bytes =>
      bytes.toHex + value.producerTxId.bytes.toHex + ByteEncoder[Long]
        .encode(value.outputSlot)
        .toHex,
    )

final case class ExactPipelineSnapshot(
    schema: Long,
    context: DomainContext,
    records: Vector[ExactPipelineRecord],
    idempotency: Vector[IdempotencyOwner],
    stageOwners: Vector[StageOwner],
    outputOwners: Vector[OutputOwner],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactPipelineSnapshot:
  given ByteEncoder[ExactPipelineSnapshot]         = ByteEncoder.derived
  given ByteDecoder[ExactPipelineSnapshot]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExactPipelineSnapshot] =
    CanonicalCodec.derived(validate)

  def ownership(
      records: Vector[ExactPipelineRecord],
  ): Either[CoreFailure, (Vector[StageOwner], Vector[OutputOwner])] =
    for
      rows <- ExactValidation.traverse(records)(record =>
        for
          _      <- ExactPipelineRecord.validate(record)
          digest <- ExactIdentityBinding.digest(record.binding)
          intent = record.request.signedPlan.intent
          producer <- intent.stages.headOption.toRight(
            CoreFailure.at(
              FailureCode.MembershipMismatch,
              "exactSnapshot.producer",
            ),
          )
        yield (
          intent.stages.map(stage =>
            StageOwner(intent.context, stage.txId, digest),
          ),
          OutputOwner(intent.context, producer.txId, intent.outputSlot, digest),
        ),
      )
      stageKeys <- ExactValidation.traverse(rows.flatMap(_._1))(value =>
        StageOwner.key(value).map(_ -> value),
      )
      outputKeys <- ExactValidation.traverse(rows.map(_._2))(value =>
        OutputOwner.key(value).map(_ -> value),
      )
      _ <- V2Validation.sortedUnique(
        stageKeys.map(_._1).sorted,
        "exactSnapshot.stageOwnership",
      )
      _ <- V2Validation.sortedUnique(
        outputKeys.map(_._1).sorted,
        "exactSnapshot.outputOwnership",
      )
    yield (stageKeys.sortBy(_._1).map(_._2), outputKeys.sortBy(_._1).map(_._2))

  def validate(value: ExactPipelineSnapshot): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.schema, 3L, "exactSnapshot.schema")
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.sortedUnique(
        value.records.map(record =>
          V2Validation.textKey(record.binding.nodePipelineId),
        ),
        "exactSnapshot.records",
      )
      _ <- V2Validation.all(value.records.map(ExactPipelineRecord.validate))
      _ <- ExactValidation.check(
        value.records.forall(_.binding.context == value.context),
        "exactSnapshot.context",
      )
      _ <- V2Validation.sortedUnique(
        value.idempotency.map(owner => V2Validation.textKey(owner.key)),
        "exactSnapshot.idempotency",
      )
      _ <- V2Validation.all(value.idempotency.map(IdempotencyOwner.validate))
      expected <- ownership(value.records)
      _        <- ExactValidation.check(
        value.stageOwners == expected._1 && value.outputOwners == expected._2,
        "exactSnapshot.ownershipCoverage",
      )
      keyed <- ExactValidation.traverse(value.records)(record =>
        ExactSubmitRequest
          .digest(record.request)
          .map(digest => record.binding.nodePipelineId -> (record, digest)),
      )
      records = keyed.toMap
      aliases = value.idempotency.map(owner => owner.key -> owner).toMap
      _ <- ExactValidation.check(
        value.idempotency.forall(owner =>
          records.get(owner.nodePipelineId).exists(_._2 == owner.requestDigest),
        ),
        "exactSnapshot.aliasOwner",
      )
      _ <- ExactValidation.check(
        keyed.forall((nodeId, pair) =>
          pair._1.request.idempotencyKey.forall(key =>
            aliases.get(key).contains(IdempotencyOwner(key, pair._2, nodeId)),
          ),
        ),
        "exactSnapshot.aliasCoverage",
      )
    yield ()

  def empty(
      context: DomainContext,
  ): Either[CoreFailure, ExactPipelineSnapshot] =
    val value = ExactPipelineSnapshot(
      3L,
      context,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
    )
    validate(value).map(_ => value)
