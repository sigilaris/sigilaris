package org.sigilaris.core.application.protocol.v2

import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import V2Codecs.given

/** These codecs freeze shape and commitments. Authority, completeness and
  * active fencing are checked separately by the configured transition verifier.
  */
enum EvidenceKind(val tag: Byte):
  case PresentContent    extends EvidenceKind(1.toByte)
  case HistoricalAbsence extends EvidenceKind(2.toByte)
  case Source            extends EvidenceKind(3.toByte)
  case Rollback          extends EvidenceKind(4.toByte)
  case NonAncestry       extends EvidenceKind(5.toByte)
  case Fence             extends EvidenceKind(6.toByte)
  case Deployment        extends EvidenceKind(7.toByte)
  case KeyUse            extends EvidenceKind(8.toByte)
  case NeverEnabled      extends EvidenceKind(9.toByte)
  case Drain             extends EvidenceKind(10.toByte)
  case AuthorityPolicy   extends EvidenceKind(11.toByte)
object EvidenceKind:
  given ByteEncoder[EvidenceKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[EvidenceKind] = V2Codecs.enumDecoder(
    "EvidenceKind",
    Vector(
      PresentContent,
      HistoricalAbsence,
      Source,
      Rollback,
      NonAncestry,
      Fence,
      Deployment,
      KeyUse,
      NeverEnabled,
      Drain,
      AuthorityPolicy,
    ).map(v => v.tag -> v),
  )

enum TransitionKind(val tag: Byte):
  case Handover         extends TransitionKind(1.toByte)
  case InitialBootstrap extends TransitionKind(2.toByte)
object TransitionKind:
  given ByteEncoder[TransitionKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[TransitionKind] = V2Codecs.enumDecoder(
    "TransitionKind",
    Vector(Handover, InitialBootstrap).map(v => v.tag -> v),
  )

enum IssuanceCapability(val tag: Byte):
  case Unavailable          extends IssuanceCapability(1.toByte)
  case ContinuouslyDisabled extends IssuanceCapability(2.toByte)
  case Enabled              extends IssuanceCapability(3.toByte)
  case Unknown              extends IssuanceCapability(4.toByte)
object IssuanceCapability:
  given ByteEncoder[IssuanceCapability] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[IssuanceCapability] = V2Codecs.enumDecoder(
    "IssuanceCapability",
    Vector(Unavailable, ContinuouslyDisabled, Enabled, Unknown).map(v =>
      v.tag -> v,
    ),
  )

enum DrainKind(val tag: Byte):
  case JournalCovered  extends DrainKind(1.toByte)
  case InferredHorizon extends DrainKind(2.toByte)
  case NeverEnabled    extends DrainKind(3.toByte)
object DrainKind:
  given ByteEncoder[DrainKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[DrainKind] = V2Codecs.enumDecoder(
    "DrainKind",
    Vector(JournalCovered, InferredHorizon, NeverEnabled).map(v => v.tag -> v),
  )

enum SourceAncestry(val tag: Byte):
  case NoPriorDomain             extends SourceAncestry(1.toByte)
  case RetiredNonAncestorDomains extends SourceAncestry(2.toByte)
object SourceAncestry:
  given ByteEncoder[SourceAncestry] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[SourceAncestry] = V2Codecs.enumDecoder(
    "SourceAncestry",
    Vector(NoPriorDomain, RetiredNonAncestorDomains).map(v => v.tag -> v),
  )

final case class EvidenceRef(
    kind: EvidenceKind,
    digest: Hash,
)
object EvidenceRef:
  given ByteEncoder[EvidenceRef]         = ByteEncoder.derived
  given ByteDecoder[EvidenceRef]         = ByteDecoder.derived
  val codec: CanonicalCodec[EvidenceRef] = CanonicalCodec.derived(validate)
  def validate(value: EvidenceRef): Either[CoreFailure, Unit] =
    TransitionShape.ref(value)

final case class TransitionIntent(
    format: Long,
    kind: TransitionKind,
    sourceDomain: Text,
    targetChain: Text,
    targetManifestDigest: Hash,
    targetValidatorSetHash: Hash,
    futureBoundary: Option[Height],
    sourceAuthorityScopeDigest: Hash,
    sourceSchemaDigest: Hash,
    authorityPolicyDigest: Hash,
)
object TransitionIntent:
  given ByteEncoder[TransitionIntent]         = ByteEncoder.derived
  given ByteDecoder[TransitionIntent]         = ByteDecoder.derived
  val codec: CanonicalCodec[TransitionIntent] = CanonicalCodec.derived(validate)
  def validate(value: TransitionIntent): Either[CoreFailure, Unit] =
    TransitionShape.intent(value)
  val Domain: Text = Utf8("sigilaris.application.transition-intent.v1")
  def digest(value: TransitionIntent): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class EvidenceBaseline(
    format: Long,
    transitionIntentDigest: Hash,
    sourceDomain: Text,
    targetChain: Text,
    sourceCheckpoint: Hash,
    sourceStateRoot: Hash,
    authorityPolicyDigest: Hash,
    entries: Vector[EvidenceRef],
)
object EvidenceBaseline:
  given ByteEncoder[EvidenceBaseline]         = ByteEncoder.derived
  given ByteDecoder[EvidenceBaseline]         = ByteDecoder.derived
  val codec: CanonicalCodec[EvidenceBaseline] = CanonicalCodec.derived(validate)
  def validate(value: EvidenceBaseline): Either[CoreFailure, Unit] =
    TransitionShape.baseline(value)
  val Domain: Text = Utf8("sigilaris.application.evidence-inventory.v1")
  def digest(value: EvidenceBaseline): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class PresentArchive(
    format: Long,
    retiredDomain: Text,
    inventoryDigest: Hash,
    sourceBinding: Hash,
    targetChain: Text,
    retirementFenceDigest: Hash,
)
object PresentArchive:
  given ByteEncoder[PresentArchive]         = ByteEncoder.derived
  given ByteDecoder[PresentArchive]         = ByteDecoder.derived
  val codec: CanonicalCodec[PresentArchive] = CanonicalCodec.derived(validate)
  def validate(value: PresentArchive): Either[CoreFailure, Unit] =
    TransitionShape.archive(value)
  val Domain: Text = Utf8("sigilaris.application.archive-inventory.v1")
  def digest(value: PresentArchive): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class ArchiveAbsence(
    format: Long,
    retiredDomain: Text,
    sourceBinding: Hash,
    targetChain: Text,
    missingScopes: Vector[Text],
    knownCircumstances: Text,
    earliestKnownAbsence: Option[Long],
    latestKnownAbsence: Option[Long],
    explicitUnknowns: Vector[Text],
    survivingEvidence: Vector[EvidenceRef],
    rollbackDecisionDigest: Hash,
    nonAncestryProofDigest: Hash,
    neverEnabledDigest: Hash,
    authorityId: Text,
)
object ArchiveAbsence:
  given ByteEncoder[ArchiveAbsence]         = ByteEncoder.derived
  given ByteDecoder[ArchiveAbsence]         = ByteDecoder.derived
  val codec: CanonicalCodec[ArchiveAbsence] = CanonicalCodec.derived(validate)
  def validate(value: ArchiveAbsence): Either[CoreFailure, Unit] =
    TransitionShape.absence(value)
  val Domain: Text = Utf8("sigilaris.application.archive-absence.v1")
  def digest(value: ArchiveAbsence): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))
  def signingPreimage(value: ArchiveAbsence): Either[CoreFailure, Bytes] = codec
    .encode(value)
    .map(bytes => Commitment.preimage(Domain, ByteVector(0.toByte) ++ bytes))

final case class SignedArchiveAbsence(
    record: ArchiveAbsence,
    authentication: SignatureEnvelope,
)
object SignedArchiveAbsence:
  given ByteEncoder[SignedArchiveAbsence]         = ByteEncoder.derived
  given ByteDecoder[SignedArchiveAbsence]         = ByteDecoder.derived
  val codec: CanonicalCodec[SignedArchiveAbsence] =
    CanonicalCodec.derived(validate)
  def validate(value: SignedArchiveAbsence): Either[CoreFailure, Unit] =
    ArchiveAbsence
      .validate(value.record)
      .flatMap(_ =>
        TransitionShape
          .authentication(value.record.authorityId, value.authentication),
      )
  def digest(value: SignedArchiveAbsence): Either[CoreFailure, Hash] = codec
    .encode(value)
    .map(bytes =>
      Commitment.hash(ArchiveAbsence.Domain, ByteVector(1.toByte) ++ bytes),
    )

final case class NeverEnabled(
    format: Long,
    domain: Text,
    lifetimeStart: Long,
    lifetimeEnd: Long,
    authorizedSignerInventory: Hash,
    deployments: Vector[DeploymentInterval],
    keyUses: Vector[KeyUseInterval],
    supportingEvidence: Vector[EvidenceRef],
    authorityId: Text,
)
object NeverEnabled:
  given ByteEncoder[NeverEnabled]         = ByteEncoder.derived
  given ByteDecoder[NeverEnabled]         = ByteDecoder.derived
  val codec: CanonicalCodec[NeverEnabled] = CanonicalCodec.derived(validate)
  def validate(value: NeverEnabled): Either[CoreFailure, Unit] =
    TransitionShape.never(value)
  val Domain: Text = Utf8("sigilaris.application.never-enabled.v1")
  def digest(value: NeverEnabled): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))
  def signingPreimage(value: NeverEnabled): Either[CoreFailure, Bytes] = codec
    .encode(value)
    .map(bytes => Commitment.preimage(Domain, ByteVector(0.toByte) ++ bytes))

final case class DeploymentInterval(
    deploymentId: Text,
    start: Long,
    end: Long,
    binaryDigest: Hash,
    effectiveConfigDigest: Hash,
    signerKeyIds: Vector[Text],
    lockIssuance: IssuanceCapability,
    effectIssuance: IssuanceCapability,
    pathInventoryDigest: Hash,
)
object DeploymentInterval:
  given ByteEncoder[DeploymentInterval]         = ByteEncoder.derived
  given ByteDecoder[DeploymentInterval]         = ByteDecoder.derived
  val codec: CanonicalCodec[DeploymentInterval] =
    CanonicalCodec.derived(validate)
  def validate(value: DeploymentInterval): Either[CoreFailure, Unit] =
    TransitionShape.deployment(value)

final case class KeyUseInterval(
    signerKeyId: Text,
    start: Long,
    end: Long,
    usePaths: Vector[SigningPath],
    evidenceDigest: Hash,
)
object KeyUseInterval:
  given ByteEncoder[KeyUseInterval]         = ByteEncoder.derived
  given ByteDecoder[KeyUseInterval]         = ByteDecoder.derived
  val codec: CanonicalCodec[KeyUseInterval] = CanonicalCodec.derived(validate)
  def validate(value: KeyUseInterval): Either[CoreFailure, Unit] =
    TransitionShape.keyUse(value)

final case class SigningPath(
    pathId: Text,
    binaryDigest: Hash,
    configurationDigest: Hash,
    lockIssuance: IssuanceCapability,
    effectIssuance: IssuanceCapability,
)
object SigningPath:
  given ByteEncoder[SigningPath]         = ByteEncoder.derived
  given ByteDecoder[SigningPath]         = ByteDecoder.derived
  val codec: CanonicalCodec[SigningPath] = CanonicalCodec.derived(validate)
  def validate(value: SigningPath): Either[CoreFailure, Unit] =
    V2Validation.identifier(value.pathId, "path.id")

final case class SignedNeverEnabled(
    record: NeverEnabled,
    authentication: SignatureEnvelope,
)
object SignedNeverEnabled:
  given ByteEncoder[SignedNeverEnabled]         = ByteEncoder.derived
  given ByteDecoder[SignedNeverEnabled]         = ByteDecoder.derived
  val codec: CanonicalCodec[SignedNeverEnabled] =
    CanonicalCodec.derived(validate)
  def validate(value: SignedNeverEnabled): Either[CoreFailure, Unit] =
    NeverEnabled
      .validate(value.record)
      .flatMap(_ =>
        TransitionShape
          .authentication(value.record.authorityId, value.authentication),
      )
  def digest(value: SignedNeverEnabled): Either[CoreFailure, Hash] = codec
    .encode(value)
    .map(bytes =>
      Commitment.hash(NeverEnabled.Domain, ByteVector(1.toByte) ++ bytes),
    )

final case class DrainEvidence(
    format: Long,
    transitionIntentDigest: Hash,
    domain: DomainContext,
    kind: DrainKind,
    fenceDigest: Hash,
    reconciledInventoryDigest: Option[Hash],
    greatestRecordedDeadline: Option[Height],
    baseUpperBound: Option[Height],
    oldMaximumLifetime: Long,
    coverageProofDigest: Hash,
    finalizedEvidenceDigest: Option[Hash],
    zeroLiveEvidenceDigest: Option[Hash],
)
object DrainEvidence:
  given ByteEncoder[DrainEvidence]         = ByteEncoder.derived
  given ByteDecoder[DrainEvidence]         = ByteDecoder.derived
  val codec: CanonicalCodec[DrainEvidence] = CanonicalCodec.derived(validate)
  def validate(value: DrainEvidence): Either[CoreFailure, Unit] =
    TransitionShape.drain(value)
  val Domain: Text = Utf8("sigilaris.application.drain-evidence.v1")
  def digest(value: DrainEvidence): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class SourceBinding(
    format: Long,
    domain: Text,
    checkpointId: Hash,
    checkpointHeight: Height,
    stateRoot: Hash,
    stateSchemaDigest: Hash,
    dataInventoryDigest: Hash,
    provenanceDigest: Hash,
    replayPolicyDigest: Hash,
)
object SourceBinding:
  given ByteEncoder[SourceBinding]         = ByteEncoder.derived
  given ByteDecoder[SourceBinding]         = ByteDecoder.derived
  val codec: CanonicalCodec[SourceBinding] = CanonicalCodec.derived(validate)
  def validate(value: SourceBinding): Either[CoreFailure, Unit] =
    TransitionShape.source(value)
  val Domain: Text = Utf8("sigilaris.application.source-binding.v1")
  def digest(value: SourceBinding): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class InitialValidator(
    validatorId: Text,
    publicKey: Bytes,
)
object InitialValidator:
  given ByteEncoder[InitialValidator]         = ByteEncoder.derived
  given ByteDecoder[InitialValidator]         = ByteDecoder.derived
  val codec: CanonicalCodec[InitialValidator] = CanonicalCodec.derived(validate)
  def validate(value: InitialValidator): Either[CoreFailure, Unit] =
    TransitionShape.validator(value)

final case class BootstrapBundle(
    format: Long,
    transitionIntent: TransitionIntent,
    source: SourceBinding,
    target: DomainContext,
    validators: Vector[InitialValidator],
    targetManifestDigest: Hash,
    genesisTimestampMillis: Long,
    sourceWriteFenceDigest: Hash,
    ancestryKind: SourceAncestry,
    retiredEvidence: Vector[EvidenceRef],
    evidenceBaselineDigest: Hash,
    authorityPolicyDigest: Hash,
)
object BootstrapBundle:
  given ByteEncoder[BootstrapBundle]         = ByteEncoder.derived
  given ByteDecoder[BootstrapBundle]         = ByteDecoder.derived
  val codec: CanonicalCodec[BootstrapBundle] = CanonicalCodec.derived(validate)
  def validate(value: BootstrapBundle): Either[CoreFailure, Unit] =
    TransitionShape.bundle(value)
  val Domain: Text = Utf8("sigilaris.application.bootstrap.v1")
  def digest(value: BootstrapBundle): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))
  def signingPreimage(value: BootstrapBundle): Either[CoreFailure, Bytes] =
    codec
      .encode(value)
      .map(bytes => Commitment.preimage(Domain, ByteVector(0.toByte) ++ bytes))

final case class SignedBootstrapBundle(
    bundle: BootstrapBundle,
    authentication: SignatureEnvelope,
)
object SignedBootstrapBundle:
  given ByteEncoder[SignedBootstrapBundle]         = ByteEncoder.derived
  given ByteDecoder[SignedBootstrapBundle]         = ByteDecoder.derived
  val codec: CanonicalCodec[SignedBootstrapBundle] =
    CanonicalCodec.derived(validate)
  def validate(value: SignedBootstrapBundle): Either[CoreFailure, Unit] =
    BootstrapBundle
      .validate(value.bundle)
      .flatMap(_ => SignatureEnvelope.validate(value.authentication))
  def digest(value: SignedBootstrapBundle): Either[CoreFailure, Hash] = codec
    .encode(value)
    .map(bytes =>
      Commitment.hash(BootstrapBundle.Domain, ByteVector(1.toByte) ++ bytes),
    )

final case class BootstrapSubject(
    format: Long,
    bundleDigest: Hash,
    genesisBlockId: Hash,
)
object BootstrapSubject:
  given ByteEncoder[BootstrapSubject]         = ByteEncoder.derived
  given ByteDecoder[BootstrapSubject]         = ByteDecoder.derived
  val codec: CanonicalCodec[BootstrapSubject] = CanonicalCodec.derived(validate)
  def validate(value: BootstrapSubject): Either[CoreFailure, Unit] =
    V2Validation.format(value.format, 1L, "bootstrapSubject.format")
  val Domain: Text = Utf8("sigilaris.application.bootstrap.subject.v1")
  def digest(value: BootstrapSubject): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class BootstrapCertificate(
    format: Long,
    bundleDigest: Hash,
    genesisBlockId: Hash,
    quorum: Bytes,
)
object BootstrapCertificate:
  given ByteEncoder[BootstrapCertificate]         = ByteEncoder.derived
  given ByteDecoder[BootstrapCertificate]         = ByteDecoder.derived
  val codec: CanonicalCodec[BootstrapCertificate] =
    CanonicalCodec.derived(validate)
  def validate(value: BootstrapCertificate): Either[CoreFailure, Unit] =
    V2Validation
      .format(value.format, 1L, "bootstrapCertificate.format")
      .flatMap(_ =>
        RecordValidation
          .check(value.quorum.nonEmpty, "bootstrapCertificate.quorum"),
      )

final case class CertifiedSuffixEntry(
    height: Height,
    blockId: Hash,
    parentBlockId: Hash,
    profileDigest: Hash,
    proposalDigest: Hash,
    quorumDigest: Hash,
    resultInventoryDigest: Hash,
)
object CertifiedSuffixEntry:
  given ByteEncoder[CertifiedSuffixEntry]         = ByteEncoder.derived
  given ByteDecoder[CertifiedSuffixEntry]         = ByteDecoder.derived
  val codec: CanonicalCodec[CertifiedSuffixEntry] =
    CanonicalCodec.derived(validate)
  def validate(
      @scala.annotation.unused value: CertifiedSuffixEntry,
  ): Either[CoreFailure, Unit] =
    Right[CoreFailure, Unit](())

final case class ConsensusSafetyBinding(
    signerId: Text,
    highQcDigest: Hash,
    lockedQcDigest: Hash,
    voterWatermarkDigest: Hash,
    journalInventoryDigest: Hash,
)
object ConsensusSafetyBinding:
  given ByteEncoder[ConsensusSafetyBinding]         = ByteEncoder.derived
  given ByteDecoder[ConsensusSafetyBinding]         = ByteDecoder.derived
  val codec: CanonicalCodec[ConsensusSafetyBinding] =
    CanonicalCodec.derived(validate)
  def validate(value: ConsensusSafetyBinding): Either[CoreFailure, Unit] =
    V2Validation.identifier(value.signerId, "safety.signer")

final case class HandoverEvidence(
    format: Long,
    transitionIntent: TransitionIntent,
    source: DomainContext,
    target: DomainContext,
    drainCheckpointId: Hash,
    drainCheckpointHeight: Height,
    drainStateRoot: Hash,
    drainEvidenceDigest: Hash,
    boundaryHeight: Height,
    continuationParentId: Hash,
    continuationParentRoot: Hash,
    retainedSuffix: Vector[CertifiedSuffixEntry],
    safety: Vector[ConsensusSafetyBinding],
    fencePromises: Vector[SignedFencePromise],
    continuationProofDigest: Hash,
    targetManifestDigest: Hash,
    evidenceBaselineDigest: Hash,
)
object HandoverEvidence:
  given ByteEncoder[HandoverEvidence]         = ByteEncoder.derived
  given ByteDecoder[HandoverEvidence]         = ByteDecoder.derived
  val codec: CanonicalCodec[HandoverEvidence] = CanonicalCodec.derived(validate)
  def validate(value: HandoverEvidence): Either[CoreFailure, Unit] =
    TransitionShape.handover(value)
  val Domain: Text = Utf8("sigilaris.application.handover.v1")
  def digest(value: HandoverEvidence): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class RestoreEvidence(
    format: Long,
    sourceGroupDigest: Hash,
    currentSafetyInventoryDigest: Hash,
    fenceEvidenceDigest: Hash,
    preservedPromiseInventoryDigest: Hash,
    preservedCanonicalWriteInventoryDigest: Hash,
    authorityId: Text,
)
object RestoreEvidence:
  given ByteEncoder[RestoreEvidence]         = ByteEncoder.derived
  given ByteDecoder[RestoreEvidence]         = ByteDecoder.derived
  val codec: CanonicalCodec[RestoreEvidence] = CanonicalCodec.derived(validate)
  def validate(value: RestoreEvidence): Either[CoreFailure, Unit] = V2Validation
    .format(value.format, 1L, "restore.format")
    .flatMap(_ =>
      V2Validation.identifier(value.authorityId, "restore.authority"),
    )
  val Domain: Text = Utf8("sigilaris.application.restore.v1")
  def digest(value: RestoreEvidence): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))
  def signingPreimage(value: RestoreEvidence): Either[CoreFailure, Bytes] =
    codec
      .encode(value)
      .map(bytes => Commitment.preimage(Domain, ByteVector(0.toByte) ++ bytes))

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
private[v2] object TransitionShape:
  private def check(value: Boolean, field: String): Either[CoreFailure, Unit] =
    RecordValidation.check(value, field)
  private def format(value: Long): Either[CoreFailure, Unit] =
    V2Validation.format(value, 1L, "transition.format")
  private def names(
      values: Vector[Text],
      field: String,
  ): Either[CoreFailure, Unit] =
    values
      .traverse_(V2Validation.identifier(_, field))
      .flatMap(_ =>
        V2Validation.sortedUnique(values.map(V2Validation.textKey), field),
      )
  private def refs(values: Vector[EvidenceRef]): Either[CoreFailure, Unit] =
    values
      .traverse_(EvidenceRef.validate)
      .flatMap(_ =>
        check(
          values == values.sortBy(v =>
            (v.kind.tag, v.digest.bytes.toHex),
          ) && values.distinct.sizeCompare(values.size) == 0,
          "evidence.references",
        ),
      )
  private def interval(start: Long, end: Long): Either[CoreFailure, Unit] =
    check(start < end, "evidence.interval")
  def ref(
      @scala.annotation.unused value: EvidenceRef,
  ): Either[CoreFailure, Unit] =
    Right[CoreFailure, Unit](())
  def authentication(
      authority: Text,
      signature: SignatureEnvelope,
  ): Either[CoreFailure, Unit] =
    SignatureEnvelope
      .validate(signature)
      .flatMap(_ =>
        check(authority == signature.authorityId, "evidence.authority"),
      )
  def intent(value: TransitionIntent): Either[CoreFailure, Unit] =
    for
      _ <- format(value.format)
      _ <- V2Validation.identifier(value.sourceDomain, "intent.sourceDomain")
      _ <- V2Validation.identifier(value.targetChain, "intent.targetChain")
      _ <- check(
        (value.kind == TransitionKind.Handover) == value.futureBoundary.nonEmpty,
        "intent.boundary",
      )
      _ <- check(
        value.kind != TransitionKind.InitialBootstrap || value.sourceDomain != value.targetChain,
        "intent.freshChain",
      )
    yield ()
  def baseline(value: EvidenceBaseline): Either[CoreFailure, Unit] =
    for
      _ <- format(value.format)
      _ <- V2Validation.identifier(value.sourceDomain, "baseline.sourceDomain")
      _ <- V2Validation.identifier(value.targetChain, "baseline.targetChain")
      _ <- refs(value.entries)
    yield ()
  def archive(value: PresentArchive): Either[CoreFailure, Unit] =
    for
      _ <- format(value.format)
      _ <- V2Validation.identifier(value.retiredDomain, "archive.domain")
      _ <- V2Validation.identifier(value.targetChain, "archive.target")
    yield ()
  def absence(value: ArchiveAbsence): Either[CoreFailure, Unit] =
    for
      _ <- format(value.format)
      _ <- V2Validation.identifier(value.retiredDomain, "absence.domain")
      _ <- V2Validation.identifier(value.targetChain, "absence.target")
      _ <- V2Validation.identifier(value.authorityId, "absence.authority")
      _ <- names(value.missingScopes, "absence.missingScopes")
      _ <- check(
        value.missingScopes.nonEmpty && value.knownCircumstances.asString.nonEmpty,
        "absence.description",
      )
      _ <- names(value.explicitUnknowns, "absence.unknowns")
      _ <- refs(value.survivingEvidence)
      _ <- check(
        value.earliestKnownAbsence.forall(a =>
          value.latestKnownAbsence.forall(a <= _),
        ),
        "absence.interval",
      )
    yield ()
  def deployment(value: DeploymentInterval): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.identifier(value.deploymentId, "deployment.id")
      _ <- interval(value.start, value.end)
      _ <- names(value.signerKeyIds, "deployment.signers")
    yield ()
  def keyUse(value: KeyUseInterval): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.identifier(value.signerKeyId, "keyUse.signer")
      _ <- interval(value.start, value.end)
      _ <- value.usePaths.traverse_(SigningPath.validate)
      _ <- names(value.usePaths.map(_.pathId), "keyUse.paths")
    yield ()
  def never(value: NeverEnabled): Either[CoreFailure, Unit] =
    for
      _ <- format(value.format)
      _ <- V2Validation.identifier(value.domain, "never.domain")
      _ <- V2Validation.identifier(value.authorityId, "never.authority")
      _ <- interval(value.lifetimeStart, value.lifetimeEnd)
      _ <- value.deployments.traverse_(DeploymentInterval.validate)
      _ <- value.keyUses.traverse_(KeyUseInterval.validate)
      _ <- refs(value.supportingEvidence)
      ds = value.deployments.map(v =>
        (V2Validation.textKey(v.deploymentId), v.start),
      )
      ks = value.keyUses.map(v =>
        (V2Validation.textKey(v.signerKeyId), v.start),
      )
      _ <- check(
        ds == ds.sorted && ds.distinct.sizeCompare(ds.size) == 0,
        "never.deployments",
      )
      _ <- check(
        ks == ks.sorted && ks.distinct.sizeCompare(ks.size) == 0,
        "never.keyUses",
      )
      _ <- check(
        value.deployments.forall(v =>
          v.start >= value.lifetimeStart && v.end <= value.lifetimeEnd,
        ) && value.keyUses.forall(v =>
          v.start >= value.lifetimeStart && v.end <= value.lifetimeEnd,
        ),
        "never.lifetime",
      )
    yield ()
  def drain(value: DrainEvidence): Either[CoreFailure, Unit] =
    for
      _ <- format(value.format)
      _ <- DomainContext.validate(value.domain)
      _ <- check(value.oldMaximumLifetime >= 0L, "drain.maximumLifetime")
      _ <- value.kind match
        case DrainKind.JournalCovered =>
          check(
            value.baseUpperBound.isEmpty && value.reconciledInventoryDigest.nonEmpty && value.finalizedEvidenceDigest.nonEmpty && value.zeroLiveEvidenceDigest.nonEmpty,
            "drain.journal",
          )
        case DrainKind.InferredHorizon =>
          check(
            value.baseUpperBound.nonEmpty && value.oldMaximumLifetime > 0L && value.finalizedEvidenceDigest.nonEmpty && value.zeroLiveEvidenceDigest.nonEmpty,
            "drain.inferred",
          )
        case DrainKind.NeverEnabled =>
          check(
            value.baseUpperBound.isEmpty && value.greatestRecordedDeadline.isEmpty && value.finalizedEvidenceDigest.isEmpty,
            "drain.neverEnabled",
          )
    yield ()
  def source(value: SourceBinding): Either[CoreFailure, Unit] =
    format(value.format).flatMap(_ =>
      V2Validation.identifier(value.domain, "source.domain"),
    )
  def validator(value: InitialValidator): Either[CoreFailure, Unit] =
    val prime = BigInt(
      "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f",
      16,
    )
    val x = BigInt(1, value.publicKey.take(32L).toArray)
    val y = BigInt(1, value.publicKey.drop(32L).toArray)
    for
      _ <- V2Validation.identifier(value.validatorId, "validator.id")
      _ <- check(
        value.publicKey.size == 64L && x < prime && y < prime && ((y * y - x * x * x - 7) mod prime) == 0,
        "validator.publicKey",
      )
    yield ()
  def bundle(value: BootstrapBundle): Either[CoreFailure, Unit] =
    for
      _ <- format(value.format)
      _ <- check(value.genesisTimestampMillis >= 0L, "bootstrap.timestamp")
      _ <- TransitionIntent.validate(value.transitionIntent)
      _ <- SourceBinding.validate(value.source)
      _ <- DomainContext.validateActive(value.target)
      _ <- value.validators.traverse_(InitialValidator.validate)
      _ <- check(
        value.validators.nonEmpty && value.validators
          .map(_.validatorId)
          .distinct
          .sizeCompare(value.validators.size) == 0 && value.validators
          .map(_.publicKey)
          .distinct
          .sizeCompare(value.validators.size) == 0,
        "bootstrap.validators",
      )
      _ <- refs(value.retiredEvidence)
      i = value.transitionIntent
      _ <- check(
        i.kind == TransitionKind.InitialBootstrap && i.sourceDomain == value.source.domain && i.targetChain == value.target.chainId && i.targetManifestDigest == value.targetManifestDigest && i.targetManifestDigest == value.target.configurationDigest && i.targetValidatorSetHash == value.target.validatorSetHash && i.sourceSchemaDigest == value.source.stateSchemaDigest && i.authorityPolicyDigest == value.authorityPolicyDigest,
        "bootstrap.intent",
      )
      _ <- check(
        (value.ancestryKind == SourceAncestry.NoPriorDomain) == value.retiredEvidence.isEmpty,
        "bootstrap.ancestry",
      )
    yield ()
  def handover(value: HandoverEvidence): Either[CoreFailure, Unit] =
    for
      _ <- format(value.format)
      _ <- TransitionIntent.validate(value.transitionIntent)
      _ <- DomainContext.validate(value.source)
      _ <- DomainContext.validateActive(value.target)
      _ <- value.safety.traverse_(ConsensusSafetyBinding.validate)
      _ <- names(value.safety.map(_.signerId), "handover.safety")
      _ <- value.fencePromises.traverse_(SignedFencePromise.validate)
      i = value.transitionIntent
      _ <- check(
        i.kind == TransitionKind.Handover && i.sourceDomain == value.source.chainId && value.source.chainId == value.target.chainId && i.targetChain == value.target.chainId && i.futureBoundary
          .contains(
            value.boundaryHeight,
          ) && i.targetManifestDigest == value.targetManifestDigest && i.targetManifestDigest == value.target.configurationDigest && i.targetValidatorSetHash == value.target.validatorSetHash,
        "handover.intent",
      )
      _ <- value.retainedSuffix.zip(value.retainedSuffix.drop(1)).traverse_ {
        case (a, b) =>
          check(
            b.height.toBigNat.toBigInt == a.height.toBigNat.toBigInt + 1 && b.parentBlockId == a.blockId,
            "handover.suffix",
          )
      }
      _ <- check(
        value.retainedSuffix.headOption.forall(v =>
          v.parentBlockId == value.drainCheckpointId && v.height.toBigNat.toBigInt == value.drainCheckpointHeight.toBigNat.toBigInt + 1,
        ),
        "handover.suffixStart",
      )
      parent = value.retainedSuffix.lastOption.fold(value.drainCheckpointId)(
        _.blockId,
      )
      height = value.retainedSuffix.lastOption.fold(
        value.drainCheckpointHeight,
      )(_.height)
      _ <- check(
        value.continuationParentId == parent && value.boundaryHeight.toBigNat.toBigInt == height.toBigNat.toBigInt + 1,
        "handover.parent",
      )
      _ <- check(
        value.retainedSuffix.nonEmpty || value.continuationParentRoot == value.drainStateRoot,
        "handover.emptySuffixRoot",
      )
    yield ()
