package org.sigilaris.core.application.protocol

import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.{UInt256, Utf8}

enum ApplicationFieldRole(val tag: Byte, val wire: String):
  case Immutable    extends ApplicationFieldRole(1.toByte, "immutable")
  case MutableRead  extends ApplicationFieldRole(2.toByte, "mutableRead")
  case MutableWrite extends ApplicationFieldRole(3.toByte, "mutableWrite")
  case Opaque       extends ApplicationFieldRole(4.toByte, "opaque")

object ApplicationFieldRole:
  val all: Vector[ApplicationFieldRole] =
    Vector(Immutable, MutableRead, MutableWrite, Opaque)

  given ByteEncoder[ApplicationFieldRole] =
    ByteEncoder[Byte].contramap(_.tag)

final case class ApplicationFieldManifest(
    fieldId: Utf8,
    role: ApplicationFieldRole,
)

object ApplicationFieldManifest:
  given ByteEncoder[ApplicationFieldManifest] = ByteEncoder.derived

final case class ApplicationFamilyManifest(
    version: ProtocolVersion,
    familyId: ApplicationFamilyId,
    fields: Vector[ApplicationFieldManifest],
)

object ApplicationFamilyManifest:
  given ByteEncoder[ApplicationFamilyManifest] = ByteEncoder.derived

enum ApplicationInputValidationFailure:
  case EmptyManifest
  case DuplicateManifestField(fieldId: String)
  case DuplicateInputField(fieldId: String)
  case FieldShapeMismatch(expected: Vector[String], actual: Vector[String])
  case FieldRoleMismatch(
      fieldId: String,
      expected: ApplicationFieldRole,
      actual: ApplicationFieldRole,
  )
  case StableConflictIdMissing(fieldId: String)
  case StableConflictIdUnexpected(fieldId: String)
  case DuplicateStableConflictId(inputId: ApplicationInputId)
  case CommitmentMismatch(commitment: String)
  case FamilyIdMismatch
  case FamilyVersionMismatch

final case class ApplicationFieldDescriptor(
    fieldId: Utf8,
    role: ApplicationFieldRole,
    value: ByteVector,
    stableConflictId: Option[ApplicationInputId],
)

object ApplicationFieldDescriptor:
  given ByteEncoder[ApplicationFieldDescriptor] = ByteEncoder.derived

final case class ApplicationInputDescriptor(
    familyId: ApplicationFamilyId,
    familyVersion: ProtocolVersion,
    fields: Vector[ApplicationFieldDescriptor],
    fullInputCommitment: UInt256,
    lockSubsetCommitment: UInt256,
    footprintCommitment: UInt256,
)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ApplicationInputDescriptor:
  private val FullInputDomain  = "sigilaris.application.input.full.v1"
  private val LockSubsetDomain =
    "sigilaris.application.input.lock-subset.v1"
  private val FootprintDomain = "sigilaris.application.input.footprint.v1"

  private final case class FullInputPreimage(
      familyId: ApplicationFamilyId,
      familyVersion: ProtocolVersion,
      fields: Vector[ApplicationFieldDescriptor],
  )
  private object FullInputPreimage:
    given ByteEncoder[FullInputPreimage] = ByteEncoder.derived

  private final case class LockEntry(
      fieldId: Utf8,
      role: ApplicationFieldRole,
      inputId: ApplicationInputId,
  )
  private object LockEntry:
    given ByteEncoder[LockEntry] = ByteEncoder.derived

  private final case class LockSubsetPreimage(
      familyId: ApplicationFamilyId,
      familyVersion: ProtocolVersion,
      entries: Vector[LockEntry],
  )
  private object LockSubsetPreimage:
    given ByteEncoder[LockSubsetPreimage] = ByteEncoder.derived

  private final case class FootprintEntry(
      inputId: ApplicationInputId,
      writes: Boolean,
  )
  private object FootprintEntry:
    given ByteEncoder[FootprintEntry] = ByteEncoder.derived

  private final case class FootprintPreimage(
      familyId: ApplicationFamilyId,
      familyVersion: ProtocolVersion,
      entries: Vector[FootprintEntry],
  )
  private object FootprintPreimage:
    given ByteEncoder[FootprintPreimage] = ByteEncoder.derived

  private def sortedFields(
      fields: Vector[ApplicationFieldDescriptor],
  ): Vector[ApplicationFieldDescriptor] =
    fields.sortBy(_.fieldId.asString)

  private def lockEntries(
      fields: Vector[ApplicationFieldDescriptor],
  ): Vector[LockEntry] =
    fields
      .flatMap: field =>
        field.stableConflictId.map: inputId =>
          LockEntry(field.fieldId, field.role, inputId)
      .sortBy(entry => (entry.inputId.toHex, entry.fieldId.asString))

  private def footprintEntries(
      fields: Vector[ApplicationFieldDescriptor],
  ): Vector[FootprintEntry] =
    fields
      .flatMap: field =>
        field.stableConflictId.map: inputId =>
          FootprintEntry(
            inputId = inputId,
            writes = field.role == ApplicationFieldRole.MutableWrite,
          )
      .sortBy(entry => (entry.inputId.toHex, entry.writes))

  def normalize(
      familyId: ApplicationFamilyId,
      familyVersion: ProtocolVersion,
      fields: Vector[ApplicationFieldDescriptor],
  ): ApplicationInputDescriptor =
    val canonicalFields = sortedFields(fields)
    val fullInput       =
      ApplicationProtocolHash.hash(
        FullInputDomain,
        FullInputPreimage(familyId, familyVersion, canonicalFields).toBytes,
      )
    val lockSubset =
      ApplicationProtocolHash.hash(
        LockSubsetDomain,
        LockSubsetPreimage(
          familyId,
          familyVersion,
          lockEntries(canonicalFields),
        ).toBytes,
      )
    val footprint =
      ApplicationProtocolHash.hash(
        FootprintDomain,
        FootprintPreimage(
          familyId,
          familyVersion,
          footprintEntries(canonicalFields),
        ).toBytes,
      )
    ApplicationInputDescriptor(
      familyId = familyId,
      familyVersion = familyVersion,
      fields = canonicalFields,
      fullInputCommitment = fullInput,
      lockSubsetCommitment = lockSubset,
      footprintCommitment = footprint,
    )

  def validate(
      manifest: ApplicationFamilyManifest,
      descriptor: ApplicationInputDescriptor,
  ): Either[ApplicationInputValidationFailure, Unit] =
    for
      _ <- validateManifest(manifest)
      _ <- Either.cond(
        manifest.familyId.asString == descriptor.familyId.asString,
        (),
        ApplicationInputValidationFailure.FamilyIdMismatch,
      )
      _ <- Either.cond(
        manifest.version == descriptor.familyVersion,
        (),
        ApplicationInputValidationFailure.FamilyVersionMismatch,
      )
      _ <- validateFields(manifest, descriptor)
      _ <- validateStableIds(descriptor.fields)
      expected = normalize(
        descriptor.familyId,
        descriptor.familyVersion,
        descriptor.fields,
      )
      _ <- Either.cond(
        expected.fullInputCommitment == descriptor.fullInputCommitment,
        (),
        ApplicationInputValidationFailure.CommitmentMismatch("fullInput"),
      )
      _ <- Either.cond(
        expected.lockSubsetCommitment == descriptor.lockSubsetCommitment,
        (),
        ApplicationInputValidationFailure.CommitmentMismatch("lockSubset"),
      )
      _ <- Either.cond(
        expected.footprintCommitment == descriptor.footprintCommitment,
        (),
        ApplicationInputValidationFailure.CommitmentMismatch("footprint"),
      )
    yield ()

  private def validateManifest(
      manifest: ApplicationFamilyManifest,
  ): Either[ApplicationInputValidationFailure, Unit] =
    if manifest.fields.isEmpty then
      Left(ApplicationInputValidationFailure.EmptyManifest)
    else
      firstDuplicate(manifest.fields.map(_.fieldId.asString)) match
        case Some(fieldId) =>
          Left(
            ApplicationInputValidationFailure.DuplicateManifestField(fieldId),
          )
        case None => Right(())

  private def validateFields(
      manifest: ApplicationFamilyManifest,
      descriptor: ApplicationInputDescriptor,
  ): Either[ApplicationInputValidationFailure, Unit] =
    firstDuplicate(descriptor.fields.map(_.fieldId.asString)) match
      case Some(fieldId) =>
        Left(ApplicationInputValidationFailure.DuplicateInputField(fieldId))
      case None =>
        val expected = manifest.fields.sortBy(_.fieldId.asString)
        val actual   = descriptor.fields.sortBy(_.fieldId.asString)
        if expected.map(_.fieldId.asString) != actual.map(_.fieldId.asString)
        then
          Left:
            ApplicationInputValidationFailure.FieldShapeMismatch(
              expected.map(_.fieldId.asString),
              actual.map(_.fieldId.asString),
            )
        else
          expected
            .zip(actual)
            .collectFirst:
              case (manifestField, actualField)
                  if manifestField.role != actualField.role =>
                ApplicationInputValidationFailure.FieldRoleMismatch(
                  manifestField.fieldId.asString,
                  manifestField.role,
                  actualField.role,
                )
          match
            case Some(failure) => Left(failure)
            case None          => Right(())

  private def validateStableIds(
      fields: Vector[ApplicationFieldDescriptor],
  ): Either[ApplicationInputValidationFailure, Unit] =
    fields.collectFirst:
      case field
          if (field.role == ApplicationFieldRole.MutableRead ||
            field.role == ApplicationFieldRole.MutableWrite) &&
            field.stableConflictId.isEmpty =>
        ApplicationInputValidationFailure.StableConflictIdMissing(
          field.fieldId.asString,
        )
      case field
          if (field.role == ApplicationFieldRole.Immutable ||
            field.role == ApplicationFieldRole.Opaque) &&
            field.stableConflictId.nonEmpty =>
        ApplicationInputValidationFailure.StableConflictIdUnexpected(
          field.fieldId.asString,
        )
    match
      case Some(failure) => Left(failure)
      case None          =>
        fields
          .flatMap(_.stableConflictId)
          .groupBy(_.toHex)
          .collectFirst:
            case (_, entries) if entries.sizeCompare(1) > 0 =>
              entries.headOption
          .flatten match
          case Some(duplicate) =>
            Left(
              ApplicationInputValidationFailure.DuplicateStableConflictId(
                duplicate,
              ),
            )
          case None => Right(())

  private def firstDuplicate(values: Vector[String]): Option[String] =
    values
      .groupBy(identity)
      .collectFirst:
        case (value, occurrences) if occurrences.sizeCompare(1) > 0 => value

final case class NormalizedApplicationResult(
    bytes: ByteVector,
    digest: ApplicationResultDigest,
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object NormalizedApplicationResult:
  private val Domain = "sigilaris.application.result.normalized.v1"

  def fromBytes(bytes: ByteVector): NormalizedApplicationResult =
    NormalizedApplicationResult(
      bytes = bytes,
      digest = ApplicationResultDigest(
        ApplicationProtocolHash.hash(Domain, bytes),
      ),
    )

  def validate(
      result: NormalizedApplicationResult,
  ): Either[ApplicationInputValidationFailure, Unit] =
    Either.cond(
      fromBytes(result.bytes).digest == result.digest,
      (),
      ApplicationInputValidationFailure.CommitmentMismatch("result"),
    )

final case class ExactApplicationInput(
    inputId: ApplicationInputId,
    expectedValueDigest: UInt256,
)

enum ExactInputFreshnessFailure:
  case DuplicateExpectedInput(inputId: ApplicationInputId)
  case MissingCurrentInput(inputId: ApplicationInputId)
  case UnexpectedCurrentInput(inputId: ApplicationInputId)
  case ValueDigestMismatch(inputId: ApplicationInputId)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ExactInputFreshness:
  def validate(
      expected: Vector[ExactApplicationInput],
      current: Map[ApplicationInputId, UInt256],
  ): Either[ExactInputFreshnessFailure, Unit] =
    expected
      .groupBy(_.inputId.toHex)
      .collectFirst:
        case (_, entries) if entries.sizeCompare(1) > 0 => entries.headOption
      .flatten match
      case Some(duplicate) =>
        Left(
          ExactInputFreshnessFailure.DuplicateExpectedInput(duplicate.inputId),
        )
      case None =>
        expected.collectFirst:
          case entry if !current.contains(entry.inputId) =>
            ExactInputFreshnessFailure.MissingCurrentInput(entry.inputId)
          case entry
              if current
                .get(entry.inputId)
                .exists(_ != entry.expectedValueDigest) =>
            ExactInputFreshnessFailure.ValueDigestMismatch(entry.inputId)
        match
          case Some(failure) => Left(failure)
          case None          =>
            val expectedIds = expected.iterator.map(_.inputId).toSet
            current.keysIterator.find(inputId =>
              !expectedIds.contains(inputId),
            ) match
              case Some(inputId) =>
                Left(ExactInputFreshnessFailure.UnexpectedCurrentInput(inputId))
              case None => Right(())

final case class ExecutionIdentityInput(
    protocolVersion: ProtocolVersion,
    configurationDigest: ApplicationConfigurationDigest,
    familyId: ApplicationFamilyId,
    normalizedTransactionBytes: ByteVector,
    fullInputCommitment: UInt256,
    lockSubsetCommitment: UInt256,
    footprintCommitment: UInt256,
    dependencyPlanDigest: DependencyPlanDigest,
    lastInclusionHeight: InclusionHeight,
)

object ExecutionIdentity:
  private val Domain = "sigilaris.application.execution.id.v1"

  private given ByteEncoder[ExecutionIdentityInput] = ByteEncoder.derived

  def compute(input: ExecutionIdentityInput): ExecutionId =
    ExecutionId(ApplicationProtocolHash.hash(Domain, input.toBytes))
