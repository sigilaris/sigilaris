package org.sigilaris.core.application.protocol.v2

import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.datatype.Utf8

import V2Codecs.given

final case class DomainContext(
    protocolVersion: Long,
    chainId: Text,
    configurationDigest: Hash,
    epoch: Long,
    validatorSetHash: Hash,
)

object DomainContext:
  given ByteEncoder[DomainContext]         = ByteEncoder.derived
  given ByteDecoder[DomainContext]         = ByteDecoder.derived
  val codec: CanonicalCodec[DomainContext] = CanonicalCodec.derived(validate)

  def validate(value: DomainContext): Either[CoreFailure, Unit] =
    V2Validation.all(
      Vector(
        V2Validation.require(
          value.protocolVersion > 0L,
          FailureCode.UnsupportedFormat,
          "protocolVersion",
        ),
        V2Validation
          .require(value.epoch >= 0L, FailureCode.ManifestMismatch, "epoch"),
        V2Validation.require(
          value.chainId.asString.matches("^[a-z0-9][a-z0-9._-]*$"),
          FailureCode.InvalidIdentifier,
          "chainId",
        ),
      ),
    )

  def validateActive(value: DomainContext): Either[CoreFailure, Unit] =
    validate(value).flatMap(_ =>
      V2Validation.format(value.protocolVersion, 2L, "protocolVersion"),
    )

enum FieldRole(val tag: Byte):
  case Immutable   extends FieldRole(1.toByte)
  case ExactRead   extends FieldRole(2.toByte)
  case ExactMutate extends FieldRole(3.toByte)
  case Opaque      extends FieldRole(4.toByte)

object FieldRole:
  val all: Vector[FieldRole] = Vector(Immutable, ExactRead, ExactMutate, Opaque)
  given ByteEncoder[FieldRole] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[FieldRole] =
    V2Codecs.enumDecoder("field role", all.map(value => value.tag -> value))
  val codec: CanonicalCodec[FieldRole] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))
  def isExisting(value: FieldRole): Boolean = value match
    case ExactRead | ExactMutate => true
    case Immutable | Opaque      => false

enum Authority(val tag: Byte):
  case ConsensusOnly extends Authority(1.toByte)
  case LockEligible  extends Authority(2.toByte)

object Authority:
  val all: Vector[Authority]   = Vector(ConsensusOnly, LockEligible)
  given ByteEncoder[Authority] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[Authority] =
    V2Codecs.enumDecoder("authority", all.map(value => value.tag -> value))
  val codec: CanonicalCodec[Authority] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class FieldManifest(
    fieldId: Text,
    role: FieldRole,
    schemaDigest: Hash,
    authorityPolicyDigest: Option[Hash],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object FieldManifest:
  given ByteEncoder[FieldManifest]         = ByteEncoder.derived
  given ByteDecoder[FieldManifest]         = ByteDecoder.derived
  val codec: CanonicalCodec[FieldManifest] = CanonicalCodec.derived(validate)
  def validate(value: FieldManifest): Either[CoreFailure, Unit] =
    V2Validation
      .identifier(value.fieldId, "fieldId")
      .flatMap(_ =>
        V2Validation.require(
          value.authorityPolicyDigest.nonEmpty == FieldRole
            .isExisting(value.role),
          FailureCode.AuthorityMismatch,
          "authorityPolicyDigest",
        ),
      )

final case class InputManifest(
    format: Long,
    familyId: Text,
    familyVersion: Long,
    fields: Vector[FieldManifest],
    transactionVerifierDigest: Hash,
    authorityVerifierDigest: Hash,
    classificationVerifierDigest: Hash,
    maintenanceVerifierDigest: Option[Hash],
)

object InputManifest:
  given ByteEncoder[InputManifest]         = ByteEncoder.derived
  given ByteDecoder[InputManifest]         = ByteDecoder.derived
  val codec: CanonicalCodec[InputManifest] = CanonicalCodec.derived(validate)
  def validate(value: InputManifest): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 2L, "inputManifest.format")
      _ <- V2Validation.identifier(value.familyId, "familyId")
      _ <- V2Validation.require(
        value.familyVersion > 0L,
        FailureCode.ManifestMismatch,
        "familyVersion",
      )
      _ <- V2Validation.require(
        value.fields.nonEmpty,
        FailureCode.MembershipMismatch,
        "manifest.fields",
      )
      _ <- V2Validation.all(value.fields.map(FieldManifest.validate))
      _ <- V2Validation.sortedUnique(
        value.fields.map(field => V2Validation.textKey(field.fieldId)),
        "manifest.fields",
      )
    yield ()
  def digest(value: InputManifest): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(Commitment.hash(Utf8("sigilaris.application.family-manifest.v2"), _))

final case class SubstrateVersions(
    input: Long,
    header: Long,
    plan: Long,
    manifest: Long,
    lock: Long,
    effect: Long,
    exactPipeline: Long,
    journal: Long,
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object SubstrateVersions:
  val V2: SubstrateVersions = SubstrateVersions(2L, 2L, 2L, 2L, 2L, 2L, 3L, 2L)
  given ByteEncoder[SubstrateVersions]         = ByteEncoder.derived
  given ByteDecoder[SubstrateVersions]         = ByteDecoder.derived
  val codec: CanonicalCodec[SubstrateVersions] =
    CanonicalCodec.derived(validate)
  def validate(value: SubstrateVersions): Either[CoreFailure, Unit] =
    V2Validation.require(value == V2, FailureCode.UnsupportedTuple, "substrate")

final case class ProtocolLimits(
    maxIdentities: Long,
    maxWitnessBytes: Long,
    maxChunks: Long,
    chunkBytes: Long,
    maxIdentityBytes: Long,
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ProtocolLimits:
  val V2: ProtocolLimits =
    ProtocolLimits(100000L, 16777216L, 256L, 65536L, 256L)
  given ByteEncoder[ProtocolLimits]         = ByteEncoder.derived
  given ByteDecoder[ProtocolLimits]         = ByteDecoder.derived
  val codec: CanonicalCodec[ProtocolLimits] = CanonicalCodec.derived(validate)
  def validate(value: ProtocolLimits): Either[CoreFailure, Unit] =
    V2Validation.require(value == V2, FailureCode.UnsupportedTuple, "limits")

final case class DependencyProfileBinding(
    profileId: Text,
    profileVersion: Long,
    verifierSlot: Text,
    verifierManifestDigest: Hash,
)

object DependencyProfileBinding:
  given ByteEncoder[DependencyProfileBinding]         = ByteEncoder.derived
  given ByteDecoder[DependencyProfileBinding]         = ByteDecoder.derived
  val codec: CanonicalCodec[DependencyProfileBinding] =
    CanonicalCodec.derived(validate)
  def validate(value: DependencyProfileBinding): Either[CoreFailure, Unit] =
    V2Validation.all(
      Vector(
        V2Validation.identifier(value.profileId, "profileId"),
        V2Validation.identifier(value.verifierSlot, "verifierSlot"),
        V2Validation.require(
          value.profileVersion > 0L,
          FailureCode.ManifestMismatch,
          "profileVersion",
        ),
      ),
    )

final case class ProtocolManifest(
    format: Long,
    protocolVersion: Long,
    chainId: Text,
    epoch: Long,
    validatorSetHash: Hash,
    maxLockLifetimeBlocks: Long,
    substrate: SubstrateVersions,
    limits: ProtocolLimits,
    families: Vector[InputManifest],
    profiles: Vector[DependencyProfileBinding],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ProtocolManifest:
  given ByteEncoder[ProtocolManifest]         = ByteEncoder.derived
  given ByteDecoder[ProtocolManifest]         = ByteDecoder.derived
  val codec: CanonicalCodec[ProtocolManifest] = CanonicalCodec.derived(validate)
  def validate(value: ProtocolManifest): Either[CoreFailure, Unit] =
    val familyKeys = value.families.map(family =>
      V2Validation.textKey(family.familyId) -> family.familyVersion,
    )
    val bindings = value.profiles.map(profile =>
      V2Validation.textKey(
        profile.verifierSlot,
      ) -> profile.verifierManifestDigest,
    )
    for
      _ <- V2Validation.format(value.format, 2L, "protocolManifest.format")
      _ <- V2Validation.format(value.protocolVersion, 2L, "protocolVersion")
      _ <- V2Validation.require(
        value.chainId.asString.matches("^[a-z0-9][a-z0-9._-]*$"),
        FailureCode.InvalidIdentifier,
        "chainId",
      )
      _ <- V2Validation.require(
        value.epoch >= 0L && value.maxLockLifetimeBlocks > 0L,
        FailureCode.ManifestMismatch,
        "epoch/lifetime",
      )
      _ <- SubstrateVersions.validate(value.substrate)
      _ <- ProtocolLimits.validate(value.limits)
      _ <- V2Validation.require(
        value.families.nonEmpty,
        FailureCode.MembershipMismatch,
        "families",
      )
      _ <- V2Validation.all(value.families.map(InputManifest.validate))
      _ <- V2Validation.require(
        familyKeys.distinct.sizeCompare(familyKeys.size) == 0,
        FailureCode.DuplicateIdentity,
        "families",
      )
      _ <- V2Validation.require(
        familyKeys == familyKeys.sorted,
        FailureCode.NonCanonicalEncoding,
        "families",
      )
      _ <- V2Validation.all(
        value.profiles.map(DependencyProfileBinding.validate),
      )
      _ <- V2Validation.sortedUnique(
        value.profiles.map(profile => V2Validation.textKey(profile.profileId)),
        "profiles",
      )
      _ <- V2Validation.require(
        bindings.distinct.sizeCompare(bindings.size) == 0,
        FailureCode.DuplicateIdentity,
        "verifierBindings",
      )
    yield ()
  def configurationDigest(value: ProtocolManifest): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(Commitment.hash(Utf8("sigilaris.application.manifest.v2"), _))
  def context(value: ProtocolManifest): Either[CoreFailure, DomainContext] =
    configurationDigest(value).map(digest =>
      DomainContext(
        value.protocolVersion,
        value.chainId,
        digest,
        value.epoch,
        value.validatorSetHash,
      ),
    )

final case class ExactPrecondition(schemaDigest: Hash, bytes: Bytes)

object ExactPrecondition:
  given ByteEncoder[ExactPrecondition]         = ByteEncoder.derived
  given ByteDecoder[ExactPrecondition]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExactPrecondition] =
    CanonicalCodec.derived(validate)
  def validate(value: ExactPrecondition): Either[CoreFailure, Unit] =
    V2Validation.require(
      value.bytes.nonEmpty,
      FailureCode.PreconditionMismatch,
      "precondition",
    )

final case class ResolvedField(
    fieldId: Text,
    role: FieldRole,
    value: Bytes,
    stableId: Option[InputId],
    precondition: Option[ExactPrecondition],
    authority: Option[Authority],
)

object ResolvedField:
  given ByteEncoder[ResolvedField]         = ByteEncoder.derived
  given ByteDecoder[ResolvedField]         = ByteDecoder.derived
  val codec: CanonicalCodec[ResolvedField] = CanonicalCodec.derived(validate)
  def validate(value: ResolvedField): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.identifier(value.fieldId, "fieldId")
      _ <-
        if FieldRole.isExisting(value.role) then
          V2Validation.require(
            value.stableId.nonEmpty && value.precondition.nonEmpty && value.authority.nonEmpty,
            FailureCode.PreconditionMismatch,
            "existingField",
          )
        else
          V2Validation.require(
            value.stableId.isEmpty && value.precondition.isEmpty && value.authority.isEmpty,
            FailureCode.RoleMismatch,
            "nonStateField",
          )
      _ <- V2Validation.all(
        value.stableId.toList.toVector.map(V2Validation.inputId(_, "stableId")),
      )
      _ <- V2Validation.all(
        value.precondition.toList.toVector.map(ExactPrecondition.validate),
      )
    yield ()

final case class LockInput(
    fieldId: Text,
    stableId: InputId,
    precondition: ExactPrecondition,
    authority: Authority,
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object LockInput:
  given ByteEncoder[LockInput]         = ByteEncoder.derived
  given ByteDecoder[LockInput]         = ByteDecoder.derived
  val codec: CanonicalCodec[LockInput] = CanonicalCodec.derived(validate)
  def validate(value: LockInput): Either[CoreFailure, Unit] =
    V2Validation.all(
      Vector(
        V2Validation.identifier(value.fieldId, "fieldId"),
        V2Validation.inputId(value.stableId, "stableId"),
        ExactPrecondition.validate(value.precondition),
        V2Validation.require(
          value.authority == Authority.LockEligible,
          FailureCode.AuthorityMismatch,
          "authority",
        ),
      ),
    )

final case class Footprint(
    format: Long,
    reads: Vector[InputId],
    writes: Vector[InputId],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object Footprint:
  given ByteEncoder[Footprint] = ByteEncoder.derived
  given ByteDecoder[Footprint] = bytes =>
    for
      format <- ByteDecoder[Long].decode(bytes)
      reads  <- V2Codecs
        .boundedVectorDecoder[InputId](ProtocolLimits.V2.maxIdentities)
        .decode(format.remainder)
      writes <- V2Codecs
        .boundedVectorDecoder[InputId](
          ProtocolLimits.V2.maxIdentities - reads.value.size.toLong,
        )
        .decode(reads.remainder)
    yield DecodeResult(
      Footprint(format.value, reads.value, writes.value),
      writes.remainder,
    )
  val codec: CanonicalCodec[Footprint] = CanonicalCodec.derived(validate)
  val empty: Footprint = Footprint(2L, Vector.empty, Vector.empty)
  def validate(value: Footprint): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 2L, "footprint.format")
      _ <- V2Validation.require(
        value.reads.size.toLong + value.writes.size.toLong <= ProtocolLimits.V2.maxIdentities,
        FailureCode.ProtocolLimitExceeded,
        "footprint.identities",
      )
      _ <- V2Validation.all(
        (value.reads ++ value.writes).map(
          V2Validation.inputId(_, "footprint.identity"),
        ),
      )
      _ <- V2Validation.sortedUnique(
        value.reads.map(_.toHex),
        "footprint.reads",
      )
      _ <- V2Validation.sortedUnique(
        value.writes.map(_.toHex),
        "footprint.writes",
      )
      _ <- V2Validation.require(
        !value.reads.exists(value.writes.toSet.contains),
        FailureCode.NonCanonicalEncoding,
        "footprint.overlap",
      )
    yield ()

  def canonical(
      reads: Vector[InputId],
      writes: Vector[InputId],
  ): Either[CoreFailure, Footprint] =
    for
      _ <- V2Validation.require(
        reads.distinct.sizeCompare(reads.size) == 0 && writes.distinct
          .sizeCompare(writes.size) == 0,
        FailureCode.DuplicateIdentity,
        "footprint",
      )
      writeSet = writes.toSet
      result   = Footprint(
        2L,
        reads.filterNot(writeSet.contains).sortBy(_.toHex),
        writes.sortBy(_.toHex),
      )
      _ <- validate(result)
    yield result

  def declaredCommitment(value: Footprint): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment
          .hash(Utf8("sigilaris.application.input.footprint.declared.v2"), _),
      )
  def actualCommitment(value: Footprint): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment
          .hash(Utf8("sigilaris.application.input.footprint.actual.v2"), _),
      )
  def conflicts(left: Footprint, right: Footprint): Boolean =
    val rightWrites = right.writes.toSet
    val rightAll    = rightWrites ++ right.reads
    left.writes.exists(rightAll.contains) || left.reads.exists(
      rightWrites.contains,
    )

final case class InputDescriptor(
    format: Long,
    manifestDigest: Hash,
    fields: Vector[ResolvedField],
    fullInputCommitment: Hash,
    lockSubsetCommitment: Hash,
)

object InputDescriptor:
  given ByteEncoder[InputDescriptor]         = ByteEncoder.derived
  given ByteDecoder[InputDescriptor]         = ByteDecoder.derived
  val codec: CanonicalCodec[InputDescriptor] =
    CanonicalCodec.derived(InputDerivation.validateDescriptor)

enum AccessKind(val tag: Byte):
  case ReadExisting   extends AccessKind(1.toByte)
  case MutateExisting extends AccessKind(2.toByte)
  case CreateAbsent   extends AccessKind(3.toByte)

object AccessKind:
  val all: Vector[AccessKind] =
    Vector(ReadExisting, MutateExisting, CreateAbsent)
  given ByteEncoder[AccessKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[AccessKind] =
    V2Codecs.enumDecoder("access kind", all.map(value => value.tag -> value))
  val codec: CanonicalCodec[AccessKind] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class ActualAccess(
    identity: InputId,
    kind: AccessKind,
    resolvedAuthority: Option[Authority],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ActualAccess:
  given ByteEncoder[ActualAccess]         = ByteEncoder.derived
  given ByteDecoder[ActualAccess]         = ByteDecoder.derived
  val codec: CanonicalCodec[ActualAccess] = CanonicalCodec.derived(validate)
  def validate(value: ActualAccess): Either[CoreFailure, Unit] =
    V2Validation
      .inputId(value.identity, "actual.identity")
      .flatMap(_ =>
        V2Validation.require(
          value.resolvedAuthority.nonEmpty == (value.kind != AccessKind.CreateAbsent),
          FailureCode.AuthorityMismatch,
          "actual.authority",
        ),
      )

final case class ResolutionEvidence(fieldId: Text, artifact: Bytes)

object ResolutionEvidence:
  given ByteEncoder[ResolutionEvidence]         = ByteEncoder.derived
  given ByteDecoder[ResolutionEvidence]         = ByteDecoder.derived
  val codec: CanonicalCodec[ResolutionEvidence] = CanonicalCodec.derived(
    value => V2Validation.identifier(value.fieldId, "evidence.fieldId"),
  )

trait InputAuthentication:
  def verify(
      manifest: InputManifest,
      signedTransaction: Bytes,
      entryPreStateRoot: Hash,
      field: ResolvedField,
      evidence: ResolutionEvidence,
  ): Either[CoreFailure, Unit]
  def verifyAbsentCreation(
      manifest: InputManifest,
      signedTransaction: Bytes,
      entryPreStateRoot: Hash,
      identity: InputId,
      evidence: Bytes,
  ): Either[CoreFailure, Unit]

trait DeclaredFootprintAuthentication:
  def derive(
      manifest: InputManifest,
      signedTransaction: Bytes,
      entryPreStateRoot: Hash,
      descriptor: InputDescriptor,
  ): Either[CoreFailure, Option[Footprint]]

/** Pure derivation never substitutes for the configured authentication
  * boundary.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object InputDerivation:
  private final case class FullPreimage(
      format: Long,
      manifestDigest: Hash,
      fields: Vector[ResolvedField],
  )
  private object FullPreimage:
    given ByteEncoder[FullPreimage] = ByteEncoder.derived
  private final case class LockPreimage(
      format: Long,
      manifestDigest: Hash,
      inputs: Vector[LockInput],
  )
  private object LockPreimage:
    given ByteEncoder[LockPreimage] = ByteEncoder.derived

  private def selectedLocks(fields: Vector[ResolvedField]): Vector[LockInput] =
    fields
      .flatMap: field =>
        if field.role == FieldRole.ExactMutate && field.authority.contains(
            Authority.LockEligible,
          )
        then
          for
            identity  <- field.stableId.toList
            condition <- field.precondition.toList
          yield LockInput(
            field.fieldId,
            identity,
            condition,
            Authority.LockEligible,
          )
        else Vector.empty[LockInput]
      .sortBy(_.stableId.toHex)

  private def normalized(
      manifestDigest: Hash,
      fields: Vector[ResolvedField],
  ): InputDescriptor =
    InputDescriptor(
      2L,
      manifestDigest,
      fields,
      Commitment.hash(
        Utf8("sigilaris.application.input.full.v2"),
        ByteEncoder[FullPreimage].encode(
          FullPreimage(2L, manifestDigest, fields),
        ),
      ),
      Commitment.hash(
        Utf8("sigilaris.application.input.lock-subset.v2"),
        ByteEncoder[LockPreimage].encode(
          LockPreimage(2L, manifestDigest, selectedLocks(fields)),
        ),
      ),
    )

  private def validateFields(
      fields: Vector[ResolvedField],
  ): Either[CoreFailure, Unit] =
    val ids = fields.flatMap(_.stableId).map(_.toHex)
    for
      _ <- V2Validation.require(
        fields.nonEmpty,
        FailureCode.MembershipMismatch,
        "fields",
      )
      _ <- V2Validation.all(fields.map(ResolvedField.validate))
      _ <- V2Validation.sortedUnique(
        fields.map(field => V2Validation.textKey(field.fieldId)),
        "fields",
      )
      _ <- V2Validation.require(
        ids.distinct.sizeCompare(ids.size) == 0,
        FailureCode.DuplicateIdentity,
        "stableIds",
      )
    yield ()

  def validateDescriptor(value: InputDescriptor): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 2L, "descriptor.format")
      _ <- validateFields(value.fields)
      expected = normalized(value.manifestDigest, value.fields)
      _ <- V2Validation.require(
        expected.fullInputCommitment == value.fullInputCommitment,
        FailureCode.CommitmentMismatch,
        "fullInput",
      )
      _ <- V2Validation.require(
        expected.lockSubsetCommitment == value.lockSubsetCommitment,
        FailureCode.CommitmentMismatch,
        "lockSubset",
      )
    yield ()

  private def validateManifestFields(
      manifest: InputManifest,
      fields: Vector[ResolvedField],
  ): Either[CoreFailure, Unit] =
    for
      _ <- InputManifest.validate(manifest)
      _ <- validateFields(fields)
      _ <- V2Validation.require(
        manifest.fields.map(_.fieldId) == fields.map(_.fieldId),
        FailureCode.MembershipMismatch,
        "fields",
      )
      _ <- V2Validation.all(
        manifest.fields
          .zip(fields)
          .map: (expected, actual) =>
            for
              _ <- V2Validation.require(
                expected.role == actual.role,
                FailureCode.RoleMismatch,
                actual.fieldId.asString,
              )
              _ <- V2Validation.require(
                actual.precondition
                  .forall(_.schemaDigest == expected.schemaDigest),
                FailureCode.PreconditionMismatch,
                actual.fieldId.asString,
              )
            yield (),
      )
    yield ()

  def derive(
      manifest: InputManifest,
      fields: Vector[ResolvedField],
  ): Either[CoreFailure, InputDescriptor] =
    val canonical = fields.sortBy(field => V2Validation.textKey(field.fieldId))
    for
      _      <- validateManifestFields(manifest, canonical)
      digest <- InputManifest.digest(manifest)
    yield normalized(digest, canonical)

  def lockInputs(
      manifest: InputManifest,
      descriptor: InputDescriptor,
  ): Either[CoreFailure, Vector[LockInput]] =
    for
      _      <- validateManifestFields(manifest, descriptor.fields)
      _      <- validateDescriptor(descriptor)
      digest <- InputManifest.digest(manifest)
      _      <- V2Validation.require(
        digest == descriptor.manifestDigest,
        FailureCode.ManifestMismatch,
        "manifestDigest",
      )
    yield selectedLocks(descriptor.fields)

  def validate(
      manifest: InputManifest,
      signedTransaction: Bytes,
      entryPreStateRoot: Hash,
      descriptor: InputDescriptor,
      evidence: Vector[ResolutionEvidence],
      authentication: InputAuthentication,
  ): Either[CoreFailure, Unit] =
    val existing =
      descriptor.fields.filter(field => FieldRole.isExisting(field.role))
    for
      _ <- lockInputs(manifest, descriptor)
      _ <- V2Validation.require(
        signedTransaction.nonEmpty,
        FailureCode.InvalidSignature,
        "signedTransaction",
      )
      _ <- V2Validation.sortedUnique(
        evidence.map(value => V2Validation.textKey(value.fieldId)),
        "evidence",
      )
      _ <- V2Validation.require(
        evidence.map(_.fieldId) == existing.map(_.fieldId),
        FailureCode.MembershipMismatch,
        "evidence",
      )
      proofs = evidence.map(value => value.fieldId -> value).toMap
      _ <- V2Validation.all(descriptor.fields.map: field =>
        val proof = proofs.getOrElse(
          field.fieldId,
          ResolutionEvidence(field.fieldId, ByteVector.empty),
        )
        authentication
          .verify(manifest, signedTransaction, entryPreStateRoot, field, proof))
    yield ()

  def validateActual(
      descriptor: InputDescriptor,
      declared: Option[Footprint],
      actual: Vector[ActualAccess],
  ): Either[CoreFailure, Footprint] =
    val byIdentity = actual.groupBy(_.identity)
    val eligible   = selectedLocks(descriptor.fields).map(_.stableId).toSet
    val reads      =
      actual.filter(_.kind == AccessKind.ReadExisting).map(_.identity).distinct
    val writes =
      actual.filter(_.kind != AccessKind.ReadExisting).map(_.identity).distinct
    for
      _ <- validateDescriptor(descriptor)
      _ <- V2Validation.all(actual.map(ActualAccess.validate))
      _ <- V2Validation.require(
        !byIdentity.valuesIterator.exists(values =>
          values.exists(_.kind == AccessKind.CreateAbsent) && values.exists(
            _.kind != AccessKind.CreateAbsent,
          ),
        ),
        FailureCode.PreconditionMismatch,
        "actual.creation",
      )
      _ <- V2Validation.require(
        !byIdentity.valuesIterator.exists(values =>
          values.flatMap(_.resolvedAuthority).distinct.sizeCompare(1) > 0,
        ),
        FailureCode.AuthorityMismatch,
        "actual.authority",
      )
      fieldsById = descriptor.fields
        .flatMap(field => field.stableId.map(_ -> field))
        .toMap
      _ <- V2Validation.require(
        actual.forall(access =>
          fieldsById
            .get(access.identity)
            .forall(field =>
              access.kind != AccessKind.CreateAbsent && field.authority == access.resolvedAuthority &&
                (access.kind != AccessKind.MutateExisting || field.role == FieldRole.ExactMutate),
            ),
        ),
        FailureCode.AuthorityMismatch,
        "actual.declaredInput",
      )
      _ <- V2Validation.require(
        actual.forall(access =>
          access.kind != AccessKind.MutateExisting ||
            !access.resolvedAuthority.contains(
              Authority.LockEligible,
            ) || eligible.contains(access.identity),
        ),
        FailureCode.AccessOmission,
        "actual.eligibleMutation",
      )
      footprint <- Footprint.canonical(reads, writes)
      actualIdentities = (footprint.reads ++ footprint.writes).toSet
      _ <- V2Validation.require(
        descriptor.fields.forall(_.stableId.forall(actualIdentities.contains)),
        FailureCode.AccessOmission,
        "actual.preconditionReads",
      )
      _ <- declared match
        case None        => Right[CoreFailure, Unit](())
        case Some(value) =>
          val declaredWrites = value.writes.toSet
          val declaredAll    = declaredWrites ++ value.reads
          for
            _ <- Footprint.validate(value)
            _ <- V2Validation.require(
              footprint.reads.forall(declaredAll.contains) && footprint.writes
                .forall(declaredWrites.contains),
              FailureCode.AccessOmission,
              "actual.footprint",
            )
            _ <- V2Validation.require(
              descriptor.fields.forall(field =>
                field.stableId.forall(identity =>
                  if field.role == FieldRole.ExactMutate then
                    declaredWrites.contains(identity)
                  else declaredAll.contains(identity),
                ),
              ),
              FailureCode.AccessOmission,
              "declared.inputs",
            )
          yield ()
    yield footprint
