package org.sigilaris.core.application.protocol.v2

import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{ExecutionId, ExecutionPlanRoot}
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.failure.DecodeFailure

import V2Codecs.given

enum PlanSource:
  case ConsensusTransaction(
      txId: Hash,
      signedTransaction: Bytes,
      inputLockCertificateId: Option[Hash],
  )
  case CertifiedFastExecution(
      txId: Hash,
      signedTransaction: Bytes,
      inputLockCertificateId: Hash,
      effectCertificateId: Hash,
  )

object PlanSource:
  extension (value: PlanSource)
    def txId: Hash = value match
      case ConsensusTransaction(id, _, _)      => id
      case CertifiedFastExecution(id, _, _, _) => id
    def signedTransaction: Bytes = value match
      case ConsensusTransaction(_, bytes, _)      => bytes
      case CertifiedFastExecution(_, bytes, _, _) => bytes
    def tag: Byte = value match
      case ConsensusTransaction(_, _, _)      => 1.toByte
      case CertifiedFastExecution(_, _, _, _) => 2.toByte

  given ByteEncoder[PlanSource] with
    def encode(value: PlanSource): ByteVector = value match
      case ConsensusTransaction(id, signed, lock) =>
        1.toByte.toBytes ++ id.toBytes ++ signed.toBytes ++ lock.toBytes
      case CertifiedFastExecution(id, signed, lock, effect) =>
        2.toByte.toBytes ++ id.toBytes ++ signed.toBytes ++ lock.toBytes ++ effect.toBytes

  given ByteDecoder[PlanSource] = ByteDecoder[Byte].flatMap:
    case 1 =>
      for
        id     <- ByteDecoder[Hash]
        signed <- ByteDecoder[Bytes]
        lock   <- ByteDecoder[Option[Hash]]
      yield ConsensusTransaction(id, signed, lock)
    case 2 =>
      for
        id     <- ByteDecoder[Hash]
        signed <- ByteDecoder[Bytes]
        lock   <- ByteDecoder[Hash]
        effect <- ByteDecoder[Hash]
      yield CertifiedFastExecution(id, signed, lock, effect)
    case _ => PlanValidation.invalidDecoder("unsupported plan source tag")

  val codec: CanonicalCodec[PlanSource] = CanonicalCodec.derived(validate)

  def validate(value: PlanSource): Either[CoreFailure, Unit] =
    V2Validation.require(
      value.signedTransaction.nonEmpty,
      FailureCode.InvalidLength,
      "signedTransaction",
    )

enum Declaration:
  case Exact(declaredFootprintDigest: Hash)
  case Compatibility(reasonDigest: Hash)

object Declaration:
  given ByteEncoder[Declaration] with
    def encode(value: Declaration): Bytes = value match
      case Exact(digest)         => 1.toByte.toBytes ++ digest.toBytes
      case Compatibility(reason) => 2.toByte.toBytes ++ reason.toBytes

  given ByteDecoder[Declaration] = ByteDecoder[Byte].flatMap:
    case 1 => ByteDecoder[Hash].map(Exact.apply)
    case 2 => ByteDecoder[Hash].map(Compatibility.apply)
    case _ => PlanValidation.invalidDecoder("unsupported declaration tag")

  val codec: CanonicalCodec[Declaration] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

  def digest(value: Declaration): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(Commitment.hash(Utf8("sigilaris.application.declaration.v2"), _))

enum WaveKind(val tag: Byte):
  case ConflictFree           extends WaveKind(1.toByte)
  case Ordered                extends WaveKind(2.toByte)
  case CompatibilitySingleton extends WaveKind(3.toByte)

object WaveKind:
  given ByteEncoder[WaveKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[WaveKind] = ByteDecoder[Byte].flatMap:
    case 1 => PlanValidation.pureDecoder(ConflictFree)
    case 2 => PlanValidation.pureDecoder(Ordered)
    case 3 => PlanValidation.pureDecoder(CompatibilitySingleton)
    case _ => PlanValidation.invalidDecoder("unsupported wave kind")
  val codec: CanonicalCodec[WaveKind] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class ReferencedValue(
    identity: InputId,
    precondition: ExactPrecondition,
)

object ReferencedValue:
  given ByteEncoder[ReferencedValue]         = ByteEncoder.derived
  given ByteDecoder[ReferencedValue]         = ByteDecoder.derived
  val codec: CanonicalCodec[ReferencedValue] = CanonicalCodec.derived: value =>
    for
      _ <- V2Validation.inputId(value.identity, "referencedValue.identity")
      _ <- ExactPrecondition.codec.encode(value.precondition).map(_ => ())
    yield ()

enum ClassificationPurpose(val tag: Byte):
  case Admission          extends ClassificationPurpose(1.toByte)
  case ExecutionClass     extends ClassificationPurpose(2.toByte)
  case Footprint          extends ClassificationPurpose(3.toByte)
  case MaintenanceOpening extends ClassificationPurpose(4.toByte)

object ClassificationPurpose:
  given ByteEncoder[ClassificationPurpose] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ClassificationPurpose] = ByteDecoder[Byte].flatMap:
    case 1 => PlanValidation.pureDecoder(Admission)
    case 2 => PlanValidation.pureDecoder(ExecutionClass)
    case 3 => PlanValidation.pureDecoder(Footprint)
    case 4 => PlanValidation.pureDecoder(MaintenanceOpening)
    case _ =>
      PlanValidation.invalidDecoder("unsupported classification purpose")
  val codec: CanonicalCodec[ClassificationPurpose] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class ClassificationStatement(
    format: Long,
    manifestDigest: Hash,
    txId: Hash,
    sourceKind: Byte,
    entryPreStateRoot: Hash,
    declarationDigest: Hash,
    referencedValues: Vector[ReferencedValue],
    purposes: Vector[ClassificationPurpose],
)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ClassificationStatement:
  given ByteEncoder[ClassificationStatement]         = ByteEncoder.derived
  given ByteDecoder[ClassificationStatement]         = ByteDecoder.derived
  val codec: CanonicalCodec[ClassificationStatement] =
    CanonicalCodec.derived(validate)

  def validate(value: ClassificationStatement): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.require(
        value.format == 2L,
        FailureCode.UnsupportedFormat,
        "classification.format",
      )
      _ <- V2Validation.require(
        value.sourceKind == 1.toByte || value.sourceKind == 2.toByte,
        FailureCode.UnsupportedFormat,
        "classification.sourceKind",
      )
      _ <- V2Validation.require(
        value.purposes.nonEmpty && value.purposes == value.purposes
          .sortBy(_.tag)
          .distinct,
        FailureCode.NonCanonicalEncoding,
        "classification.purposes",
      )
      ids = value.referencedValues.map(_.identity.toHex)
      _ <- V2Validation.require(
        ids == ids.sorted.distinct,
        FailureCode.NonCanonicalEncoding,
        "classification.references",
      )
      _ <- V2Validation.all(
        value.referencedValues.map(ReferencedValue.codec.encode(_).map(_ => ())),
      )
    yield ()

  def commitment(value: ClassificationStatement): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(Commitment.hash(Utf8("sigilaris.application.classification.v2"), _))

final case class ClassificationProof(statementCommitment: Hash, artifact: Bytes)

object ClassificationProof:
  given ByteEncoder[ClassificationProof]         = ByteEncoder.derived
  given ByteDecoder[ClassificationProof]         = ByteDecoder.derived
  val codec: CanonicalCodec[ClassificationProof] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class PlanEntry(
    executionId: ExecutionId,
    source: PlanSource,
    manifestDigest: Hash,
    declaration: Declaration,
    declarationDigest: Hash,
    fullInputCommitment: Hash,
    lockSubsetCommitment: Hash,
    actualFootprintCommitment: Hash,
    dependencyPlanDigest: Hash,
    lastInclusionHeight: Height,
    entryPreStateRoot: Hash,
    classificationStatementCommitment: Option[Hash],
)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object PlanEntry:
  given ByteEncoder[PlanEntry]         = ByteEncoder.derived
  given ByteDecoder[PlanEntry]         = ByteDecoder.derived
  val codec: CanonicalCodec[PlanEntry] = CanonicalCodec.derived(validate)

  def validate(value: PlanEntry): Either[CoreFailure, Unit] =
    for
      _        <- PlanSource.validate(value.source)
      expected <- Declaration.digest(value.declaration)
      _        <- V2Validation.require(
        expected == value.declarationDigest,
        FailureCode.CommitmentMismatch,
        "declarationDigest",
      )
      _ <- V2Validation.require(
        value.lastInclusionHeight.toBigNat.toBigInt > 0,
        FailureCode.InvalidDeadline,
        "lastInclusionHeight",
      )
    yield ()

final case class ExecutionWave(kind: WaveKind, entries: Vector[PlanEntry])

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ExecutionWave:
  given ByteEncoder[ExecutionWave]         = ByteEncoder.derived
  given ByteDecoder[ExecutionWave]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExecutionWave] = CanonicalCodec.derived(validate)

  def validate(value: ExecutionWave): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.require(
        value.entries.nonEmpty,
        FailureCode.MembershipMismatch,
        "emptyWave",
      )
      _ <- V2Validation.all(value.entries.map(PlanEntry.validate))
      _ <- value.kind match
        case WaveKind.CompatibilitySingleton =>
          V2Validation.require(
            value.entries.sizeCompare(1) == 0 && value.entries.forall: entry =>
              (entry.source, entry.declaration) match
                case (
                      PlanSource.ConsensusTransaction(_, _, _),
                      Declaration.Compatibility(_),
                    ) =>
                  true
                case _ => false
            ,
            FailureCode.MembershipMismatch,
            "compatibilitySingleton",
          )
        case kind =>
          val ids = value.entries.map(_.source.txId.toBytes.toHex)
          for
            _ <- V2Validation.require(
              value.entries.forall(_.declaration match
                case Declaration.Exact(_) => true
                case _                    => false),
              FailureCode.ClassificationMismatch,
              "exactWaveDeclaration",
            )
            _ <- V2Validation.require(
              kind != WaveKind.ConflictFree || ids == ids.sorted,
              FailureCode.NonCanonicalEncoding,
              "conflictFreeSourceOrder",
            )
          yield ()
    yield ()

final case class ExecutionPlan(
    format: Long,
    waves: Vector[ExecutionWave],
    statements: Vector[ClassificationStatement],
)

final case class ExecutionIdentityInput(
    context: DomainContext,
    manifestDigest: Hash,
    txId: Hash,
    signedTransaction: Bytes,
    fullInputCommitment: Hash,
    lockSubsetCommitment: Hash,
    declarationDigest: Hash,
    dependencyPlanDigest: Hash,
    lastInclusionHeight: Height,
)

object ExecutionIdentityInput:
  given ByteEncoder[ExecutionIdentityInput]         = ByteEncoder.derived
  given ByteDecoder[ExecutionIdentityInput]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExecutionIdentityInput] = CanonicalCodec.derived:
    value =>
      for
        _ <- DomainContext.validateActive(value.context)
        _ <- V2Validation.require(
          value.signedTransaction.nonEmpty,
          FailureCode.InvalidLength,
          "signedTransaction",
        )
        _ <- V2Validation.require(
          value.lastInclusionHeight.toBigNat.toBigInt > 0,
          FailureCode.InvalidDeadline,
          "lastInclusionHeight",
        )
      yield ()

object ExecutionIdentity:
  def compute(value: ExecutionIdentityInput): Either[CoreFailure, ExecutionId] =
    ExecutionIdentityInput.codec
      .encode(value)
      .map: bytes =>
        ExecutionId(
          Commitment.hash(Utf8("sigilaris.application.execution.id.v2"), bytes),
        )

final case class BodyMember(txId: Hash, executionId: ExecutionId)

object BodyMember:
  given ByteEncoder[BodyMember]         = ByteEncoder.derived
  given ByteDecoder[BodyMember]         = ByteDecoder.derived
  val codec: CanonicalCodec[BodyMember] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class VerifiedEntry(
    entry: PlanEntry,
    declaredFootprint: Option[Footprint],
    actualFootprint: Footprint,
)

/** Trusted manifest-bound adapter; the runtime supplies authenticated execution
  * and proof lookup, while core independently checks the returned commitments.
  */
trait EntryAuthentication:
  def verify(
      entry: PlanEntry,
      statement: Option[ClassificationStatement],
  ): Either[CoreFailure, VerifiedEntry]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ExecutionPlan:
  given ByteEncoder[ExecutionPlan]         = ByteEncoder.derived
  given ByteDecoder[ExecutionPlan]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExecutionPlan] =
    CanonicalCodec.derived(validateShape)

  val empty: ExecutionPlan = ExecutionPlan(2L, Vector.empty, Vector.empty)

  def computeRoot(plan: ExecutionPlan): Either[CoreFailure, ExecutionPlanRoot] =
    codec
      .encode(plan)
      .map: bytes =>
        ExecutionPlanRoot(
          Commitment
            .hash(Utf8("sigilaris.application.execution-plan.root.v2"), bytes),
        )

  def validateShape(plan: ExecutionPlan): Either[CoreFailure, Unit] =
    val entries      = plan.waves.flatMap(_.entries)
    val txIds        = entries.map(_.source.txId.toBytes.toHex)
    val executionIds = entries.map(_.executionId.toHexLower)
    val fastIds      = entries.flatMap(_.source match
      case PlanSource.CertifiedFastExecution(_, _, _, effect) =>
        Some(effect.toBytes.toHex)
      case _ => None)
    for
      _ <- V2Validation.require(
        plan.format == 2L,
        FailureCode.UnsupportedFormat,
        "plan.format",
      )
      _ <- V2Validation.all(plan.waves.map(ExecutionWave.validate))
      _ <- V2Validation.require(
        !plan.waves.exists(
          _.kind == WaveKind.CompatibilitySingleton,
        ) || plan.waves.sizeCompare(1) == 0,
        FailureCode.MembershipMismatch,
        "compatibilityIsolation",
      )
      _ <- V2Validation.require(
        txIds.distinct.sizeCompare(txIds.size) == 0 && executionIds.distinct
          .sizeCompare(executionIds.size) == 0 &&
          fastIds.distinct.sizeCompare(fastIds.size) == 0,
        FailureCode.DuplicateIdentity,
        "plan.members",
      )
      statementHashes <- PlanValidation.traverse(plan.statements)(
        ClassificationStatement.commitment,
      )
      _ <- V2Validation.require(
        statementHashes.map(_.toBytes.toHex) == statementHashes
          .map(_.toBytes.toHex)
          .sorted
          .distinct,
        FailureCode.NonCanonicalEncoding,
        "plan.statements",
      )
      references = entries.flatMap(_.classificationStatementCommitment)
      _ <- V2Validation.require(
        references.distinct.sizeCompare(
          references.size,
        ) == 0 && references.toSet == statementHashes.toSet,
        FailureCode.ClassificationMismatch,
        "classificationMembership",
      )
      lookup = statementHashes.zip(plan.statements).toMap
      _ <- V2Validation.all(
        entries.map(entry =>
          validateStatement(
            entry,
            entry.classificationStatementCommitment.flatMap(lookup.get),
          ),
        ),
      )
    yield ()

  def validate(
      plan: ExecutionPlan,
      body: Vector[BodyMember],
      authentication: EntryAuthentication,
  ): Either[CoreFailure, Vector[VerifiedEntry]] =
    val entries  = plan.waves.flatMap(_.entries)
    val expected =
      entries.map(entry => BodyMember(entry.source.txId, entry.executionId))
    for
      _ <- validateShape(plan)
      _ <- V2Validation.require(
        body.sizeCompare(expected.size) == 0 && body.toSet == expected.toSet,
        FailureCode.MembershipMismatch,
        "body.members",
      )
      hashes <- PlanValidation.traverse(plan.statements)(
        ClassificationStatement.commitment,
      )
      lookup = hashes.zip(plan.statements).toMap
      verified <- PlanValidation.traverse(entries): entry =>
        for
          result <- authentication.verify(
            entry,
            entry.classificationStatementCommitment.flatMap(lookup.get),
          )
          _ <- validateVerified(entry, result)
        yield result
      kinds = plan.waves.flatMap(wave => wave.entries.map(_ => wave.kind))
      _ <- validateConflicts(verified.zip(kinds))
    yield verified

  private def validateStatement(
      entry: PlanEntry,
      statement: Option[ClassificationStatement],
  ): Either[CoreFailure, Unit] =
    statement match
      case None        => Right(())
      case Some(value) =>
        V2Validation.require(
          value.manifestDigest == entry.manifestDigest && value.txId == entry.source.txId &&
            value.sourceKind == entry.source.tag && value.entryPreStateRoot == entry.entryPreStateRoot &&
            value.declarationDigest == entry.declarationDigest,
          FailureCode.ClassificationMismatch,
          "classificationBinding",
        )

  private def validateVerified(
      entry: PlanEntry,
      verified: VerifiedEntry,
  ): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.require(
        entry == verified.entry,
        FailureCode.CommitmentMismatch,
        "authenticatedEntry",
      )
      actual <- Footprint.actualCommitment(verified.actualFootprint)
      _      <- V2Validation.require(
        actual == entry.actualFootprintCommitment,
        FailureCode.CommitmentMismatch,
        "actualFootprint",
      )
      _ <- (entry.declaration, verified.declaredFootprint) match
        case (Declaration.Compatibility(_), None)          => Right(())
        case (Declaration.Exact(expected), Some(declared)) =>
          for
            digest <- Footprint.declaredCommitment(declared)
            _      <- V2Validation.require(
              digest == expected,
              FailureCode.CommitmentMismatch,
              "declaredFootprint",
            )
            _ <- V2Validation.require(
              verified.actualFootprint.reads.toSet
                .subsetOf(declared.reads.toSet ++ declared.writes.toSet) &&
                verified.actualFootprint.writes.toSet
                  .subsetOf(declared.writes.toSet),
              FailureCode.AccessOmission,
              "actualFootprintCoverage",
            )
          yield ()
        case _ =>
          Left(
            CoreFailure
              .at(FailureCode.ClassificationMismatch, "declarationFootprint"),
          )
    yield ()

  private def validateConflicts(
      entries: Vector[(VerifiedEntry, WaveKind)],
  ): Either[CoreFailure, Unit] =
    entries
      .foldLeft[Either[CoreFailure, ConflictCoverage]](
        Right(ConflictCoverage.empty),
      ):
        case (acc, (entry, kind)) =>
          acc.flatMap: previous =>
            entry.declaredFootprint match
              case None            => Right(previous)
              case Some(footprint) =>
                val reads          = footprint.reads.toSet
                val writes         = footprint.writes.toSet
                val conflictFree   = kind == WaveKind.ConflictFree
                val forbiddenReads = if conflictFree then previous.writes
                else previous.conflictFreeWrites
                val forbiddenWrites = if conflictFree then
                  previous.reads ++ previous.writes
                else previous.conflictFreeReads ++ previous.conflictFreeWrites
                V2Validation
                  .require(
                    !reads.exists(forbiddenReads.contains) && !writes
                      .exists(forbiddenWrites.contains),
                    FailureCode.ForbiddenOverlap,
                    "crossWaveConflict",
                  )
                  .map: _ =>
                    ConflictCoverage(
                      previous.reads ++ reads,
                      previous.writes ++ writes,
                      if conflictFree then previous.conflictFreeReads ++ reads
                      else previous.conflictFreeReads,
                      if conflictFree then previous.conflictFreeWrites ++ writes
                      else previous.conflictFreeWrites,
                    )
      .map(_ => ())

  private final case class ConflictCoverage(
      reads: Set[InputId],
      writes: Set[InputId],
      conflictFreeReads: Set[InputId],
      conflictFreeWrites: Set[InputId],
  )
  private object ConflictCoverage:
    val empty: ConflictCoverage =
      ConflictCoverage(Set.empty, Set.empty, Set.empty, Set.empty)

private[v2] object PlanValidation:
  def invalidDecoder[A](message: String): ByteDecoder[A] = _ =>
    Left[DecodeFailure, org.sigilaris.core.codec.byte.DecodeResult[A]](
      DecodeFailure(message),
    )
  def pureDecoder[A](value: A): ByteDecoder[A] = bytes =>
    Right[DecodeFailure, org.sigilaris.core.codec.byte.DecodeResult[A]](
      org.sigilaris.core.codec.byte.DecodeResult(value, bytes),
    )

  def traverse[A, B](
      values: Vector[A],
  )(f: A => Either[CoreFailure, B]): Either[CoreFailure, Vector[B]] =
    values.foldLeft[Either[CoreFailure, Vector[B]]](
      Right[CoreFailure, Vector[B]](Vector.empty),
    ):
      case (result, value) =>
        for
          previous <- result
          next     <- f(value)
        yield previous :+ next
