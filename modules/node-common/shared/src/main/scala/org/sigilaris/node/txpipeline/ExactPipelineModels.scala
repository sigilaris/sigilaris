package org.sigilaris.node.txpipeline

import java.nio.charset.StandardCharsets

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{ExecutionId, InclusionHeight}
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

final case class ApplicationPipelineId(value: String)

object ApplicationPipelineId:
  def parse(value: String): Either[String, ApplicationPipelineId] =
    ExactPipelineFieldValidation
      .nonBlank("applicationPipelineId", value)
      .map(apply)
  given Encoder[ApplicationPipelineId] = Encoder.encodeString.contramap(_.value)
  given Decoder[ApplicationPipelineId] = Decoder.decodeString.emap(parse)

final case class DependencyProfileId(value: String)

object DependencyProfileId:
  def parse(value: String): Either[String, DependencyProfileId] =
    ExactPipelineFieldValidation.nonBlank("profileId", value).map(apply)
  given Encoder[DependencyProfileId] = Encoder.encodeString.contramap(_.value)
  given Decoder[DependencyProfileId] = Decoder.decodeString.emap(parse)

final case class DependencyProfileVersion(value: Int)

object DependencyProfileVersion:
  def fromInt(value: Int): Either[String, DependencyProfileVersion] =
    Either.cond(
      value > 0,
      DependencyProfileVersion(value),
      "profileVersion must be positive",
    )
  given Encoder[DependencyProfileVersion] = Encoder.encodeInt.contramap(_.value)
  given Decoder[DependencyProfileVersion] = Decoder.decodeInt.emap(fromInt)

final case class VerifierSlot(value: String)

object VerifierSlot:
  def parse(value: String): Either[String, VerifierSlot] =
    ExactPipelineFieldValidation.nonBlank("verifierSlot", value).map(apply)
  given Encoder[VerifierSlot] = Encoder.encodeString.contramap(_.value)
  given Decoder[VerifierSlot] = Decoder.decodeString.emap(parse)

final case class VerifierManifestDigest(value: String)

object VerifierManifestDigest:
  def parse(value: String): Either[String, VerifierManifestDigest] =
    ExactPipelineFieldValidation
      .lowerHex32("verifierManifestDigest", value)
      .map(apply)
  given Encoder[VerifierManifestDigest] =
    Encoder.encodeString.contramap(_.value)
  given Decoder[VerifierManifestDigest] = Decoder.decodeString.emap(parse)

final case class OpaqueDependencyReference(bytes: ByteVector)

object OpaqueDependencyReference:
  def fromBytes(value: ByteVector): Either[String, OpaqueDependencyReference] =
    Either.cond(
      value.nonEmpty,
      OpaqueDependencyReference(value),
      "dependencyReference must be non-empty",
    )

  def parseHex(value: String): Either[String, OpaqueDependencyReference] =
    for
      _ <- ExactPipelineFieldValidation.lowerHexBytes(
        "dependencyReference",
        value,
      )
      bytes     <- ByteVector.fromHexDescriptive(value)
      reference <- fromBytes(bytes)
    yield reference

  given Encoder[OpaqueDependencyReference] =
    Encoder.encodeString.contramap(_.bytes.toHex)
  given Decoder[OpaqueDependencyReference] =
    Decoder.decodeString.emap(parseHex)

final case class OpaqueReferenceCommitment(value: String)

object OpaqueReferenceCommitment:
  def parse(value: String): Either[String, OpaqueReferenceCommitment] =
    ExactPipelineFieldValidation
      .lowerHex32("referenceCommitment", value)
      .map(apply)

  def compute(reference: OpaqueDependencyReference): OpaqueReferenceCommitment =
    OpaqueReferenceCommitment(
      ExactPipelineCanonical.keccakHex(
        ExactPipelineCanonical.referencePreimage(reference.bytes),
      ),
    )

  given Encoder[OpaqueReferenceCommitment] =
    Encoder.encodeString.contramap(_.value)
  given Decoder[OpaqueReferenceCommitment] = Decoder.decodeString.emap(parse)

final case class ExactPipelineSignedPlan(value: String)

object ExactPipelineSignedPlan:
  def parse(value: String): Either[String, ExactPipelineSignedPlan] =
    ExactPipelineFieldValidation
      .nonBlank("signedApplicationPlan", value)
      .map(apply)
  given Encoder[ExactPipelineSignedPlan] =
    Encoder.encodeString.contramap(_.value)
  given Decoder[ExactPipelineSignedPlan] = Decoder.decodeString.emap(parse)

final case class ExactPipelineDigest(value: String)

object ExactPipelineDigest:
  def parse(value: String): Either[String, ExactPipelineDigest] =
    ExactPipelineFieldValidation
      .lowerHex32("exactPipelineDigest", value)
      .map(apply)
  given Encoder[ExactPipelineDigest] = Encoder.encodeString.contramap(_.value)
  given Decoder[ExactPipelineDigest] = Decoder.decodeString.emap(parse)

enum ExactExecutionMode(val tag: Int, val wire: String):
  case OrderedAtomic     extends ExactExecutionMode(1, "orderedAtomic")
  case CertifiedAncestor extends ExactExecutionMode(2, "certifiedAncestor")

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals"),
)
object ExactExecutionMode:
  val all: Vector[ExactExecutionMode] = Vector(OrderedAtomic, CertifiedAncestor)

  def parse(value: String): Either[String, ExactExecutionMode] =
    all
      .find(_.wire == value)
      .toRight(s"unsupported exact execution mode: $value")

  given Encoder[ExactExecutionMode] = Encoder.encodeString.contramap(_.wire)
  given Decoder[ExactExecutionMode] = Decoder.decodeString.emap(parse)

enum ExactPipelineLifecycle(val wire: String):
  case Accepted         extends ExactPipelineLifecycle("accepted")
  case LockCertified    extends ExactPipelineLifecycle("lockCertified")
  case EffectCertified  extends ExactPipelineLifecycle("effectCertified")
  case Included         extends ExactPipelineLifecycle("included")
  case Finalized        extends ExactPipelineLifecycle("finalized")
  case Materialized     extends ExactPipelineLifecycle("materialized")
  case ExpiredUnapplied extends ExactPipelineLifecycle("expiredUnapplied")
  case Failed           extends ExactPipelineLifecycle("failed")

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals"),
)
object ExactPipelineLifecycle:
  val all: Vector[ExactPipelineLifecycle] = Vector(
    Accepted,
    LockCertified,
    EffectCertified,
    Included,
    Finalized,
    Materialized,
    ExpiredUnapplied,
    Failed,
  )

  def parse(value: String): Either[String, ExactPipelineLifecycle] =
    all
      .find(_.wire == value)
      .toRight(s"unsupported exact pipeline lifecycle: $value")

  def isTerminal(value: ExactPipelineLifecycle): Boolean =
    value match
      case Finalized | Materialized | ExpiredUnapplied | Failed => true
      case _                                                    => false

  given Encoder[ExactPipelineLifecycle] = Encoder.encodeString.contramap(_.wire)
  given Decoder[ExactPipelineLifecycle] = Decoder.decodeString.emap(parse)

final case class ExactPipelineSubmitRequest(
    transactions: Vector[TxPipelineTransactionPayload],
    signedApplicationPlan: ExactPipelineSignedPlan,
    profileId: DependencyProfileId,
    profileVersion: DependencyProfileVersion,
    waitFor: TxPipelineWaitMode,
)

object ExactPipelineSubmitRequest:
  given Encoder[ExactPipelineSubmitRequest] = deriveEncoder
  given Decoder[ExactPipelineSubmitRequest] = deriveDecoder

final case class ExactPipelineNormalizedRequest(
    transactions: Vector[TxPipelineSubmittedTransaction],
    signedApplicationPlan: ExactPipelineSignedPlan,
    profileId: DependencyProfileId,
    profileVersion: DependencyProfileVersion,
    waitFor: TxPipelineWaitMode,
)

object ExactPipelineNormalizedRequest:
  def fromSubmit(
      request: ExactPipelineSubmitRequest,
  ): Either[String, ExactPipelineNormalizedRequest] =
    for
      _ <- Either.cond(
        request.transactions.sizeCompare(2) == 0,
        (),
        "exact pipelines require exactly two ordered transactions",
      )
      signedPlan <- ExactPipelineSignedPlan.parse(
        request.signedApplicationPlan.value,
      )
      profileId      <- DependencyProfileId.parse(request.profileId.value)
      profileVersion <- DependencyProfileVersion.fromInt(
        request.profileVersion.value,
      )
    yield ExactPipelineNormalizedRequest(
      transactions = request.transactions.zipWithIndex.map:
        case (payload, index) =>
          TxPipelineSubmittedTransaction(
            stageIndex = 0,
            transactionIndex = index,
            payload = payload,
          )
      ,
      signedApplicationPlan = signedPlan,
      profileId = profileId,
      profileVersion = profileVersion,
      waitFor = request.waitFor,
    )

  given Encoder[ExactPipelineNormalizedRequest] = deriveEncoder
  given Decoder[ExactPipelineNormalizedRequest] = deriveDecoder

final case class VerifiedExactDependencyPlan(
    applicationPipelineId: ApplicationPipelineId,
    profileId: DependencyProfileId,
    profileVersion: DependencyProfileVersion,
    verifierSlot: VerifierSlot,
    verifierManifestDigest: VerifierManifestDigest,
    orderedExecutionIds: Vector[ExecutionId],
    mode: ExactExecutionMode,
    producerPosition: Int,
    consumerPosition: Int,
    dependencyReference: OpaqueDependencyReference,
    referenceCommitment: OpaqueReferenceCommitment,
    lastInclusionHeight: InclusionHeight,
    signedPlanDigest: ExactPipelineDigest,
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object VerifiedExactDependencyPlan:
  import ExactPipelineJsonCodecs.given

  def validateCommitments(
      plan: VerifiedExactDependencyPlan,
  ): Either[String, VerifiedExactDependencyPlan] =
    for
      _ <- ApplicationPipelineId.parse(plan.applicationPipelineId.value)
      _ <- DependencyProfileId.parse(plan.profileId.value)
      _ <- DependencyProfileVersion.fromInt(plan.profileVersion.value)
      _ <- VerifierSlot.parse(plan.verifierSlot.value)
      _ <- VerifierManifestDigest.parse(plan.verifierManifestDigest.value)
      _ <- OpaqueDependencyReference.fromBytes(plan.dependencyReference.bytes)
      _ <- OpaqueReferenceCommitment.parse(plan.referenceCommitment.value)
      _ <- ExactPipelineDigest.parse(plan.signedPlanDigest.value)
      _ <- Either.cond(
        plan.orderedExecutionIds.sizeCompare(2) == 0 &&
          plan.orderedExecutionIds.distinct.sizeCompare(2) == 0,
        (),
        "orderedExecutionIds must contain two distinct executions",
      )
      _ <- Either.cond(
        plan.producerPosition == 0 && plan.consumerPosition == 1,
        (),
        "exact dependency positions must be producer 0 and consumer 1",
      )
      _ <- Either.cond(
        plan.referenceCommitment.value.equals(
          OpaqueReferenceCommitment.compute(plan.dependencyReference).value,
        ),
        (),
        "referenceCommitment does not match dependencyReference",
      )
    yield plan

  given Encoder[VerifiedExactDependencyPlan] = deriveEncoder
  given Decoder[VerifiedExactDependencyPlan] =
    deriveDecoder[VerifiedExactDependencyPlan].emap(validateCommitments)

final case class ExactPipelineIdentityBinding(
    nodePipelineId: TxPipelineId,
    applicationPipelineId: ApplicationPipelineId,
    identityStrategy: String,
    verifiedPlanDigest: ExactPipelineDigest,
    bindingDigest: ExactPipelineDigest,
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactPipelineIdentityBinding:
  def create(
      nodePipelineId: TxPipelineId,
      applicationPipelineId: ApplicationPipelineId,
      identityStrategy: String,
      verifiedPlanDigest: String,
  ): Either[String, ExactPipelineIdentityBinding] =
    for
      strategy <- ExactPipelineFieldValidation.nonBlank(
        "identityStrategy",
        identityStrategy,
      )
      planDigest <- ExactPipelineFieldValidation.lowerHex32(
        "verifiedPlanDigest",
        verifiedPlanDigest,
      )
    yield
      val provisional = ExactPipelineIdentityBinding(
        nodePipelineId,
        applicationPipelineId,
        strategy,
        ExactPipelineDigest(planDigest),
        bindingDigest = ExactPipelineDigest("0" * 64),
      )
      provisional.copy(
        bindingDigest = ExactPipelineDigest(
          ExactPipelineCanonical.keccakHex(
            ExactPipelineCanonical.identityBindingPreimage(provisional),
          ),
        ),
      )

  def validate(
      binding: ExactPipelineIdentityBinding,
  ): Either[String, ExactPipelineIdentityBinding] =
    for
      _ <- ExactPipelineFieldValidation.nonBlank(
        "identityStrategy",
        binding.identityStrategy,
      )
      expected <- create(
        binding.nodePipelineId,
        binding.applicationPipelineId,
        binding.identityStrategy,
        binding.verifiedPlanDigest.value,
      )
      _ <- Either.cond(
        binding.bindingDigest.value.equals(expected.bindingDigest.value),
        (),
        "bindingDigest does not match exact pipeline identity fields",
      )
    yield binding

  given Encoder[ExactPipelineIdentityBinding] = deriveEncoder
  given Decoder[ExactPipelineIdentityBinding] =
    deriveDecoder[ExactPipelineIdentityBinding].emap(validate)

final case class ExactTxPipelineRecord(
    schemaVersion: Int,
    genericProjection: TxPipelineRecord,
    verifiedPlan: VerifiedExactDependencyPlan,
    identityBinding: ExactPipelineIdentityBinding,
    lifecycle: ExactPipelineLifecycle,
    terminalFailure: Option[TxPipelineValidationFailure],
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ExactTxPipelineRecord:
  val SchemaVersion: Int = 2

  def validate(
      value: ExactTxPipelineRecord,
  ): Either[String, ExactTxPipelineRecord] =
    if value.schemaVersion != SchemaVersion then
      Left(s"unsupported exact pipeline record schema: ${value.schemaVersion}")
    else
      for
        _ <- VerifiedExactDependencyPlan.validateCommitments(value.verifiedPlan)
        _ <- ExactPipelineIdentityBinding.validate(value.identityBinding)
        _ <- Either.cond(
          value.genericProjection.pipelineId ==
            value.identityBinding.nodePipelineId,
          (),
          "exactPipelineBindingMismatch: node pipeline identity",
        )
        _ <- Either.cond(
          value.verifiedPlan.applicationPipelineId ==
            value.identityBinding.applicationPipelineId,
          (),
          "exactPipelineBindingMismatch: application pipeline identity",
        )
        _ <- Either.cond(
          ExactPipelineCanonical.verifiedPlanDigest(value.verifiedPlan) ==
            value.identityBinding.verifiedPlanDigest.value,
          (),
          "exactPipelineBindingMismatch: verified plan digest",
        )
        _ <- Either.cond(
          terminalFailureMatches(value.lifecycle, value.terminalFailure),
          (),
          "terminalFailure must be present exactly when lifecycle is failed",
        )
      yield value

  private def terminalFailureMatches(
      lifecycle: ExactPipelineLifecycle,
      terminalFailure: Option[TxPipelineValidationFailure],
  ): Boolean =
    (lifecycle == ExactPipelineLifecycle.Failed) == terminalFailure.nonEmpty

  given Encoder[ExactTxPipelineRecord] = deriveEncoder
  given Decoder[ExactTxPipelineRecord] =
    deriveDecoder[ExactTxPipelineRecord].emap(validate)

final case class ExactTxPipelineSnapshot(
    schemaVersion: Int,
    genericProjection: TxPipelineSnapshot,
    verifiedPlan: VerifiedExactDependencyPlan,
    identityBinding: ExactPipelineIdentityBinding,
    lifecycle: ExactPipelineLifecycle,
    terminalFailure: Option[TxPipelineValidationFailure],
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ExactTxPipelineSnapshot:
  val SchemaVersion: Int = 2

  def fromRecord(record: ExactTxPipelineRecord): ExactTxPipelineSnapshot =
    ExactTxPipelineSnapshot(
      schemaVersion = SchemaVersion,
      genericProjection = record.genericProjection.snapshot,
      verifiedPlan = record.verifiedPlan,
      identityBinding = record.identityBinding,
      lifecycle = record.lifecycle,
      terminalFailure = record.terminalFailure,
    )

  def validate(
      value: ExactTxPipelineSnapshot,
  ): Either[String, ExactTxPipelineSnapshot] =
    if value.schemaVersion != SchemaVersion then
      Left(
        s"unsupported exact pipeline snapshot schema: ${value.schemaVersion}",
      )
    else
      for
        _ <- VerifiedExactDependencyPlan.validateCommitments(value.verifiedPlan)
        _ <- ExactPipelineIdentityBinding.validate(value.identityBinding)
        _ <- Either.cond(
          value.genericProjection.pipelineId ==
            value.identityBinding.nodePipelineId,
          (),
          "exactPipelineBindingMismatch: node pipeline identity",
        )
        _ <- Either.cond(
          value.verifiedPlan.applicationPipelineId ==
            value.identityBinding.applicationPipelineId,
          (),
          "exactPipelineBindingMismatch: application pipeline identity",
        )
        _ <- Either.cond(
          ExactPipelineCanonical.verifiedPlanDigest(value.verifiedPlan) ==
            value.identityBinding.verifiedPlanDigest.value,
          (),
          "exactPipelineBindingMismatch: verified plan digest",
        )
        _ <- Either.cond(
          (value.lifecycle == ExactPipelineLifecycle.Failed) ==
            value.terminalFailure.nonEmpty,
          (),
          "terminalFailure must be present exactly when lifecycle is failed",
        )
      yield value

  given Encoder[ExactTxPipelineSnapshot] = deriveEncoder
  given Decoder[ExactTxPipelineSnapshot] =
    deriveDecoder[ExactTxPipelineSnapshot].emap(validate)

enum TxPipelineSubmitEnvelope:
  case GenericV1(request: TxPipelineSubmitRequest)
  case ExactV2(request: ExactPipelineSubmitRequest)

object TxPipelineSubmitEnvelope:
  given Encoder[TxPipelineSubmitEnvelope] = Encoder.instance:
    case TxPipelineSubmitEnvelope.GenericV1(request) => request.asJson
    case TxPipelineSubmitEnvelope.ExactV2(request)   =>
      Json.obj(
        "kind"    -> Json.fromString("exactV2"),
        "request" -> request.asJson,
      )

  given Decoder[TxPipelineSubmitEnvelope] = Decoder.instance: cursor =>
    decodeVariant(cursor, "request")(
      generic => TxPipelineSubmitEnvelope.GenericV1(generic),
      exact => TxPipelineSubmitEnvelope.ExactV2(exact),
    )

enum TxPipelineRecordEnvelope:
  case GenericV1(record: TxPipelineRecord)
  case ExactV2(record: ExactTxPipelineRecord)

object TxPipelineRecordEnvelope:
  given Encoder[TxPipelineRecordEnvelope] = Encoder.instance:
    case TxPipelineRecordEnvelope.GenericV1(record) => record.asJson
    case TxPipelineRecordEnvelope.ExactV2(record)   =>
      Json.obj("kind" -> Json.fromString("exactV2"), "record" -> record.asJson)

  given Decoder[TxPipelineRecordEnvelope] = Decoder.instance: cursor =>
    decodeVariant(cursor, "record")(
      generic => TxPipelineRecordEnvelope.GenericV1(generic),
      exact => TxPipelineRecordEnvelope.ExactV2(exact),
    )

enum TxPipelineSnapshotEnvelope:
  case GenericV1(snapshot: TxPipelineSnapshot)
  case ExactV2(snapshot: ExactTxPipelineSnapshot)

object TxPipelineSnapshotEnvelope:
  given Encoder[TxPipelineSnapshotEnvelope] = Encoder.instance:
    case TxPipelineSnapshotEnvelope.GenericV1(snapshot) => snapshot.asJson
    case TxPipelineSnapshotEnvelope.ExactV2(snapshot)   =>
      Json.obj(
        "kind"     -> Json.fromString("exactV2"),
        "snapshot" -> snapshot.asJson,
      )

  given Decoder[TxPipelineSnapshotEnvelope] = Decoder.instance: cursor =>
    decodeVariant(cursor, "snapshot")(
      generic => TxPipelineSnapshotEnvelope.GenericV1(generic),
      exact => TxPipelineSnapshotEnvelope.ExactV2(exact),
    )

final case class DependencyProfileManifest(
    profileId: DependencyProfileId,
    profileVersion: DependencyProfileVersion,
    verifierSlot: VerifierSlot,
    verifierManifestDigest: VerifierManifestDigest,
)

object DependencyProfileManifest:
  given Encoder[DependencyProfileManifest] = deriveEncoder
  given Decoder[DependencyProfileManifest] = deriveDecoder

final case class ApplicationSubstrateVersions(
    blockHeaderVersion: Int,
    executionPlanVersion: Int,
    lockCodecVersion: Int,
    effectCodecVersion: Int,
    exactPipelineCodecVersion: Int,
    applicationJournalVersion: Int,
)

object ApplicationSubstrateVersions:
  val M1: ApplicationSubstrateVersions =
    ApplicationSubstrateVersions(2, 1, 1, 1, 2, 1)

  given Encoder[ApplicationSubstrateVersions] = deriveEncoder
  given Decoder[ApplicationSubstrateVersions] = deriveDecoder

final case class ApplicationProtocolManifestV1(
    version: Int,
    epoch: Long,
    configurationDigest: String,
    validatorSetHash: String,
    maxLockLifetimeBlocks: Long,
    substrate: ApplicationSubstrateVersions,
    profiles: Vector[DependencyProfileManifest],
)

enum ApplicationProtocolManifestFailure(val reason: String):
  case UnsupportedVersion
      extends ApplicationProtocolManifestFailure("unsupportedProtocolVersion")
  case NegativeEpoch extends ApplicationProtocolManifestFailure("negativeEpoch")
  case InvalidConfigurationDigest
      extends ApplicationProtocolManifestFailure("invalidConfigurationDigest")
  case ConfigurationDigestMismatch
      extends ApplicationProtocolManifestFailure("configurationDigestMismatch")
  case InvalidValidatorSetHash
      extends ApplicationProtocolManifestFailure("invalidValidatorSetHash")
  case InvalidVerifierManifestDigest
      extends ApplicationProtocolManifestFailure(
        "invalidVerifierManifestDigest",
      )
  case InvalidMaximumLifetime
      extends ApplicationProtocolManifestFailure("invalidMaxLockLifetimeBlocks")
  case EmptyProfiles
      extends ApplicationProtocolManifestFailure("emptyDependencyProfiles")
  case DuplicateProfile
      extends ApplicationProtocolManifestFailure("duplicateDependencyProfile")
  case DuplicateVerifierBinding
      extends ApplicationProtocolManifestFailure("duplicateVerifierBinding")
  case IncompleteApplicationSubstrate
      extends ApplicationProtocolManifestFailure(
        "incompleteApplicationSubstrate",
      )

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ApplicationProtocolManifestV1:
  val Version: Int = 1

  def withComputedDigest(
      epoch: Long,
      validatorSetHash: String,
      maxLockLifetimeBlocks: Long,
      substrate: ApplicationSubstrateVersions,
      profiles: Vector[DependencyProfileManifest],
  ): Either[
    ApplicationProtocolManifestFailure,
    ApplicationProtocolManifestV1,
  ] =
    val provisional = ApplicationProtocolManifestV1(
      Version,
      epoch,
      configurationDigest = "",
      validatorSetHash,
      maxLockLifetimeBlocks,
      substrate,
      profiles,
    )
    for
      digest <- computeConfigurationDigest(provisional)
      manifest = provisional.copy(configurationDigest = digest)
      validated <- validate(manifest)
    yield validated

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeWithComputedDigest(
      epoch: Long,
      validatorSetHash: String,
      maxLockLifetimeBlocks: Long,
      substrate: ApplicationSubstrateVersions,
      profiles: Vector[DependencyProfileManifest],
  ): ApplicationProtocolManifestV1 =
    withComputedDigest(
      epoch,
      validatorSetHash,
      maxLockLifetimeBlocks,
      substrate,
      profiles,
    ) match
      case Right(manifest) => manifest
      case Left(failure)   =>
        throw new IllegalArgumentException(failure.reason)

  def computeConfigurationDigest(
      manifest: ApplicationProtocolManifestV1,
  ): Either[ApplicationProtocolManifestFailure, String] =
    validateCanonicalDigestInputs(manifest).map: _ =>
      ExactPipelineCanonical.keccakHex(
        ExactPipelineCanonical.manifestPreimage(manifest),
      )

  def validate(
      manifest: ApplicationProtocolManifestV1,
  ): Either[ApplicationProtocolManifestFailure, ApplicationProtocolManifestV1] =
    for
      _        <- validateStructure(manifest)
      computed <- computeConfigurationDigest(manifest)
      _        <- Either.cond(
        computed == manifest.configurationDigest,
        (),
        ApplicationProtocolManifestFailure.ConfigurationDigestMismatch,
      )
    yield manifest

  private def validateStructure(
      manifest: ApplicationProtocolManifestV1,
  ): Either[ApplicationProtocolManifestFailure, Unit] =
    if manifest.version != Version then
      Left(ApplicationProtocolManifestFailure.UnsupportedVersion)
    else if manifest.epoch < 0L then
      Left(ApplicationProtocolManifestFailure.NegativeEpoch)
    else if !ExactPipelineFieldValidation.isLowerHex32(
        manifest.configurationDigest,
      )
    then Left(ApplicationProtocolManifestFailure.InvalidConfigurationDigest)
    else if manifest.maxLockLifetimeBlocks <= 0L then
      Left(ApplicationProtocolManifestFailure.InvalidMaximumLifetime)
    else if manifest.substrate != ApplicationSubstrateVersions.M1 then
      Left(ApplicationProtocolManifestFailure.IncompleteApplicationSubstrate)
    else if manifest.profiles.isEmpty then
      Left(ApplicationProtocolManifestFailure.EmptyProfiles)
    else if hasDuplicateProfiles(manifest.profiles) then
      Left(ApplicationProtocolManifestFailure.DuplicateProfile)
    else if hasDuplicateVerifierBindings(manifest.profiles) then
      Left(ApplicationProtocolManifestFailure.DuplicateVerifierBinding)
    else validateCanonicalDigestInputs(manifest)

  private def validateCanonicalDigestInputs(
      manifest: ApplicationProtocolManifestV1,
  ): Either[ApplicationProtocolManifestFailure, Unit] =
    if !ExactPipelineFieldValidation.isLowerHex32(manifest.validatorSetHash)
    then Left(ApplicationProtocolManifestFailure.InvalidValidatorSetHash)
    else if manifest.profiles.exists(profile =>
        !ExactPipelineFieldValidation.isLowerHex32(
          profile.verifierManifestDigest.value,
        ),
      )
    then Left(ApplicationProtocolManifestFailure.InvalidVerifierManifestDigest)
    else Right(())

  private def hasDuplicateProfiles(
      profiles: Vector[DependencyProfileManifest],
  ): Boolean =
    profiles
      .groupBy(_.profileId.value)
      .exists(_._2.sizeCompare(1) > 0)

  private def hasDuplicateVerifierBindings(
      profiles: Vector[DependencyProfileManifest],
  ): Boolean =
    profiles
      .groupBy(profile =>
        profile.verifierSlot.value -> profile.verifierManifestDigest.value,
      )
      .exists(_._2.sizeCompare(1) > 0)

  given Encoder[ApplicationProtocolManifestV1] = Encoder.instance: value =>
    Json.obj(
      "version"               -> Json.fromInt(value.version),
      "epoch"                 -> Json.fromLong(value.epoch),
      "configurationDigest"   -> Json.fromString(value.configurationDigest),
      "validatorSetHash"      -> Json.fromString(value.validatorSetHash),
      "maxLockLifetimeBlocks" -> Json.fromLong(value.maxLockLifetimeBlocks),
      "blockHeaderVersion" -> Json.fromInt(value.substrate.blockHeaderVersion),
      "executionPlanVersion" -> Json.fromInt(
        value.substrate.executionPlanVersion,
      ),
      "lockCodecVersion"   -> Json.fromInt(value.substrate.lockCodecVersion),
      "effectCodecVersion" -> Json.fromInt(value.substrate.effectCodecVersion),
      "exactPipelineCodecVersion" -> Json.fromInt(
        value.substrate.exactPipelineCodecVersion,
      ),
      "applicationJournalVersion" -> Json.fromInt(
        value.substrate.applicationJournalVersion,
      ),
      "profiles" -> value.profiles.asJson,
    )

  given Decoder[ApplicationProtocolManifestV1] = Decoder.instance: cursor =>
    val decoded = for
      version                   <- cursor.get[Int]("version")
      epoch                     <- cursor.get[Long]("epoch")
      configurationDigest       <- cursor.get[String]("configurationDigest")
      validatorSetHash          <- cursor.get[String]("validatorSetHash")
      maxLockLifetimeBlocks     <- cursor.get[Long]("maxLockLifetimeBlocks")
      blockHeaderVersion        <- cursor.get[Int]("blockHeaderVersion")
      executionPlanVersion      <- cursor.get[Int]("executionPlanVersion")
      lockCodecVersion          <- cursor.get[Int]("lockCodecVersion")
      effectCodecVersion        <- cursor.get[Int]("effectCodecVersion")
      exactPipelineCodecVersion <- cursor.get[Int]("exactPipelineCodecVersion")
      applicationJournalVersion <- cursor.get[Int]("applicationJournalVersion")
      profiles <- cursor.get[Vector[DependencyProfileManifest]]("profiles")
    yield ApplicationProtocolManifestV1(
      version = version,
      epoch = epoch,
      configurationDigest = configurationDigest,
      validatorSetHash = validatorSetHash,
      maxLockLifetimeBlocks = maxLockLifetimeBlocks,
      substrate = ApplicationSubstrateVersions(
        blockHeaderVersion,
        executionPlanVersion,
        lockCodecVersion,
        effectCodecVersion,
        exactPipelineCodecVersion,
        applicationJournalVersion,
      ),
      profiles = profiles,
    )
    decoded.flatMap(value =>
      validate(value).left
        .map(failure => DecodingFailure(failure.reason, cursor.history)),
    )

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Throw"),
)
object ExactPipelineCanonical:
  private val PlanDomain    = "sigilaris.tx-pipeline.exact.plan.v1"
  private val BindingDomain = "sigilaris.tx-pipeline.exact.identity-binding.v1"
  private val ReferenceDomain = "sigilaris.tx-pipeline.exact.reference.v1"
  private val ManifestDomain  = "sigilaris.application.manifest.v1"

  private final case class VerifiedPlanPreimage(
      domain: Utf8,
      applicationPipelineId: Utf8,
      profileId: Utf8,
      profileVersion: Long,
      verifierSlot: Utf8,
      verifierManifestDigest: UInt256,
      orderedExecutionIds: List[ExecutionId],
      mode: Byte,
      producerPosition: Long,
      consumerPosition: Long,
      dependencyReference: List[Byte],
      referenceCommitment: UInt256,
      lastInclusionHeight: BigNat,
      signedPlanDigest: UInt256,
  )

  private object VerifiedPlanPreimage:
    given ByteEncoder[VerifiedPlanPreimage] = ByteEncoder.derived

  private final case class IdentityBindingPreimage(
      domain: Utf8,
      nodePipelineId: Utf8,
      applicationPipelineId: Utf8,
      identityStrategy: Utf8,
      verifiedPlanDigest: UInt256,
  )

  private object IdentityBindingPreimage:
    given ByteEncoder[IdentityBindingPreimage] = ByteEncoder.derived

  private final case class ReferencePreimage(
      domain: Utf8,
      dependencyReference: List[Byte],
  )

  private object ReferencePreimage:
    given ByteEncoder[ReferencePreimage] = ByteEncoder.derived

  private final case class ManifestProfilePreimage(
      profileId: Utf8,
      profileVersion: Long,
      verifierSlot: Utf8,
      verifierManifestDigest: UInt256,
  )

  private object ManifestProfilePreimage:
    given ByteEncoder[ManifestProfilePreimage] = ByteEncoder.derived

  private final case class ManifestPreimage(
      domain: Utf8,
      version: Long,
      epoch: Long,
      validatorSetHash: UInt256,
      maxLockLifetimeBlocks: Long,
      blockHeaderVersion: Long,
      executionPlanVersion: Long,
      lockCodecVersion: Long,
      effectCodecVersion: Long,
      exactPipelineCodecVersion: Long,
      applicationJournalVersion: Long,
      profiles: List[ManifestProfilePreimage],
  )

  private object ManifestPreimage:
    given ByteEncoder[ManifestPreimage] = ByteEncoder.derived

  def verifiedPlanPreimage(plan: VerifiedExactDependencyPlan): ByteVector =
    VerifiedPlanPreimage(
      domain = Utf8(PlanDomain),
      applicationPipelineId = Utf8(plan.applicationPipelineId.value),
      profileId = Utf8(plan.profileId.value),
      profileVersion = plan.profileVersion.value.toLong,
      verifierSlot = Utf8(plan.verifierSlot.value),
      verifierManifestDigest = uint256(plan.verifierManifestDigest.value),
      orderedExecutionIds = plan.orderedExecutionIds.toList,
      mode = plan.mode.tag.toByte,
      producerPosition = plan.producerPosition.toLong,
      consumerPosition = plan.consumerPosition.toLong,
      dependencyReference = plan.dependencyReference.bytes.toArray.toList,
      referenceCommitment = uint256(plan.referenceCommitment.value),
      lastInclusionHeight = plan.lastInclusionHeight.toBigNat,
      signedPlanDigest = uint256(plan.signedPlanDigest.value),
    ).toBytes

  def verifiedPlanDigest(plan: VerifiedExactDependencyPlan): String =
    keccakHex(verifiedPlanPreimage(plan))

  def signedPlanDigest(plan: ExactPipelineSignedPlan): ExactPipelineDigest =
    ExactPipelineDigest(
      keccakHex(
        ByteVector.view(plan.value.getBytes(StandardCharsets.UTF_8)),
      ),
    )

  def identityBindingPreimage(
      binding: ExactPipelineIdentityBinding,
  ): ByteVector =
    IdentityBindingPreimage(
      domain = Utf8(BindingDomain),
      nodePipelineId = Utf8(binding.nodePipelineId.value),
      applicationPipelineId = Utf8(binding.applicationPipelineId.value),
      identityStrategy = Utf8(binding.identityStrategy),
      verifiedPlanDigest = uint256(binding.verifiedPlanDigest.value),
    ).toBytes

  def referencePreimage(value: ByteVector): ByteVector =
    ReferencePreimage(Utf8(ReferenceDomain), value.toArray.toList).toBytes

  def manifestPreimage(manifest: ApplicationProtocolManifestV1): ByteVector =
    val profiles = manifest.profiles
      .sortBy(profile =>
        (profile.profileId.value, profile.profileVersion.value),
      )
      .map: profile =>
        ManifestProfilePreimage(
          profileId = Utf8(profile.profileId.value),
          profileVersion = profile.profileVersion.value.toLong,
          verifierSlot = Utf8(profile.verifierSlot.value),
          verifierManifestDigest = uint256(
            profile.verifierManifestDigest.value,
          ),
        )
      .toList
    ManifestPreimage(
      domain = Utf8(ManifestDomain),
      version = manifest.version.toLong,
      epoch = manifest.epoch,
      validatorSetHash = uint256(manifest.validatorSetHash),
      maxLockLifetimeBlocks = manifest.maxLockLifetimeBlocks,
      blockHeaderVersion = manifest.substrate.blockHeaderVersion.toLong,
      executionPlanVersion = manifest.substrate.executionPlanVersion.toLong,
      lockCodecVersion = manifest.substrate.lockCodecVersion.toLong,
      effectCodecVersion = manifest.substrate.effectCodecVersion.toLong,
      exactPipelineCodecVersion =
        manifest.substrate.exactPipelineCodecVersion.toLong,
      applicationJournalVersion =
        manifest.substrate.applicationJournalVersion.toLong,
      profiles = profiles,
    ).toBytes

  private[txpipeline] def keccakHex(value: ByteVector): String =
    ByteVector.view(CryptoOps.keccak256(value.toArray)).toHex

  private def uint256(value: String): UInt256 =
    UInt256.fromHex(value) match
      case Right(digest) => digest
      case Left(error)   => throw new IllegalArgumentException(error.toString)

private object ExactPipelineJsonCodecs:
  given Encoder[ExecutionId] = Encoder.encodeString.contramap(_.toHexLower)
  given Decoder[ExecutionId] = Decoder.decodeString.emap: value =>
    for
      _       <- ExactPipelineFieldValidation.lowerHex32("executionId", value)
      decoded <- UInt256.fromHex(value).left.map(_.toString)
    yield ExecutionId(decoded)

  given Encoder[InclusionHeight] = Encoder.instance: value =>
    Json.fromBigInt(value.toBigNat.toBigInt)
  given Decoder[InclusionHeight] = Decoder.decodeBigInt.emap: value =>
    BigNat.fromBigInt(value).map(InclusionHeight(_))

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"),
)
private def decodeVariant[G: Decoder, E: Decoder, A](
    cursor: HCursor,
    fieldName: String,
)(
    generic: G => A,
    exact: E => A,
): Decoder.Result[A] =
  cursor.downField("kind").focus match
    case None        => cursor.as[G].map(generic)
    case Some(value) =>
      value.asString match
        case Some("genericV1") => cursor.downField(fieldName).as[G].map(generic)
        case Some("exactV2")   => cursor.downField(fieldName).as[E].map(exact)
        case Some(other)       =>
          Left(
            DecodingFailure(
              s"unsupported pipeline discriminator: $other",
              cursor.history,
            ),
          )
        case None =>
          Left(
            DecodingFailure(
              "pipeline discriminator must be a string",
              cursor.history,
            ),
          )

@SuppressWarnings(Array("org.wartremover.warts.Any"))
private object ExactPipelineFieldValidation:
  private val LowerHex32    = "[0-9a-f]{64}".r
  private val LowerHexBytes = "(?:[0-9a-f]{2})+".r

  def nonBlank(field: String, value: String): Either[String, String] =
    Either.cond(value.trim.nonEmpty, value, s"$field must be non-empty")

  def lowerHex32(field: String, value: String): Either[String, String] =
    Either.cond(
      isLowerHex32(value),
      value,
      s"$field must be 32-byte lowercase hex",
    )

  def isLowerHex32(value: String): Boolean =
    LowerHex32.matches(value)

  def lowerHexBytes(field: String, value: String): Either[String, String] =
    Either.cond(
      LowerHexBytes.matches(value),
      value,
      s"$field must be non-empty lowercase hexadecimal bytes",
    )
