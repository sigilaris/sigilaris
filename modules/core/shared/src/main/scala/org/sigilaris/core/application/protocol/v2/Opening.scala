package org.sigilaris.core.application.protocol.v2

import org.sigilaris.core.application.protocol.{
  ApplicationStateRoot,
  NormalizedApplicationResult,
}
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8

import V2Codecs.given

enum OpeningKind(val tag: Byte):
  case Handover         extends OpeningKind(1.toByte)
  case InitialBootstrap extends OpeningKind(2.toByte)

@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
object OpeningKind:
  given ByteEncoder[OpeningKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[OpeningKind] = V2Codecs.enumDecoder(
    "opening kind",
    Vector(
      1.toByte -> OpeningKind.Handover,
      2.toByte -> OpeningKind.InitialBootstrap,
    ),
  )
  val codec: CanonicalCodec[OpeningKind] =
    CanonicalCodec.derived(_ => Right(()))

final case class OpeningEnvelope(
    format: Long,
    kind: OpeningKind,
    sourceDomain: Text,
    sourceCheckpoint: Height,
    sourceRoot: Hash,
    sourceSchemaDigest: Hash,
    targetContext: DomainContext,
    targetManifestDigest: Hash,
    parentId: Hash,
    firstHeight: Height,
    admissionBase: AdmissionBase,
    lastInclusionHeight: Height,
    conversionPayload: Bytes,
    reasonDigest: Hash,
    authorityId: Text,
)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object OpeningEnvelope:
  val Domain: Text                   = Utf8("sigilaris.application.opening.v1")
  given ByteEncoder[OpeningEnvelope] = ByteEncoder.derived
  given ByteDecoder[OpeningEnvelope] = ByteDecoder.derived
  val codec: CanonicalCodec[OpeningEnvelope] = CanonicalCodec.derived(validate)

  def validate(value: OpeningEnvelope): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 1L, "opening.format")
      _ <- DomainContext.validateActive(value.targetContext)
      _ <- V2Validation.identifier(value.sourceDomain, "opening.sourceDomain")
      _ <- V2Validation.identifier(value.authorityId, "opening.authorityId")
      _ <- V2Validation.require(
        value.targetManifestDigest == value.targetContext.configurationDigest,
        FailureCode.ManifestMismatch,
        "opening.targetManifestDigest",
      )
      _ <- V2Validation.require(
        value.firstHeight.toBigNat.toBigInt > AdmissionBase
          .height(value.admissionBase)
          .toBigNat
          .toBigInt &&
          value.firstHeight.toBigNat.toBigInt <= value.lastInclusionHeight.toBigNat.toBigInt,
        FailureCode.InvalidDeadline,
        "opening.firstHeight",
      )
      _ <- (value.kind, value.admissionBase) match
        case (OpeningKind.Handover, AdmissionBase.Finalized(_, _, _)) =>
          Right(())
        case (
              OpeningKind.InitialBootstrap,
              AdmissionBase.InitialAnchor(blockId, height, stateRoot, _),
            ) =>
          V2Validation.require(
            height.toBigNat.toBigInt == 0 && value.firstHeight.toBigNat.toBigInt == 1 &&
              value.parentId == blockId && value.sourceRoot == stateRoot,
            FailureCode.OpeningMismatch,
            "opening.initialAnchor",
          )
        case _ =>
          Left(
            CoreFailure.at(FailureCode.ProofInvalid, "opening.admissionBase"),
          )
    yield ()

  def signingPreimage(value: OpeningEnvelope): Either[CoreFailure, Bytes] =
    codec.encode(value).map(Commitment.preimage(Domain, _))

final case class SignedOpening(envelope: OpeningEnvelope, signature: Bytes)

object SignedOpening:
  given ByteEncoder[SignedOpening]         = ByteEncoder.derived
  given ByteDecoder[SignedOpening]         = ByteDecoder.derived
  val codec: CanonicalCodec[SignedOpening] = CanonicalCodec.derived(validate)

  def validate(value: SignedOpening): Either[CoreFailure, Unit] =
    OpeningEnvelope
      .validate(value.envelope)
      .flatMap(_ =>
        V2Validation.require(
          value.signature.nonEmpty,
          FailureCode.InvalidSignature,
          "opening.signature",
        ),
      )

final case class OpeningExecution[S](
    nextState: S,
    stateRoot: ApplicationStateRoot,
    result: NormalizedApplicationResult,
    actualAccesses: Vector[ActualAccess],
)

/** The configured application executor uses an immutable working state and
  * instruments every actual read, write and absent creation. It does not
  * publish canonical state; publication belongs to the recoverable runtime
  * boundary.
  */
trait OpeningExecutor[S]:
  def execute(
      parentState: S,
      opening: SignedOpening,
  ): Either[CoreFailure, OpeningExecution[S]]

/** Verifies the application's canonical maintenance signature under the
  * verifier selected by the authenticated family manifest, rather than a
  * caller's key.
  */
trait MaintenanceAuthorizationVerifier:
  def verify(
      manifest: InputManifest,
      opening: SignedOpening,
      preimage: Bytes,
  ): Either[CoreFailure, Unit]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object OpeningValidation:
  /** Checks signed opening and admission-reference bindings. Runtime validation
    * additionally authenticates the actual parent/state/base, executes
    * conversion and verifies any required certificate through
    * AdmissionValidation.
    */
  def validate(
      opening: SignedOpening,
      entry: PlanEntry,
      input: InputDescriptor,
      manifest: ProtocolManifest,
      family: InputManifest,
      authorization: MaintenanceAuthorizationVerifier,
  ): Either[CoreFailure, Unit] =
    val envelope = opening.envelope
    for
      _            <- SignedOpening.validate(opening)
      _            <- ProtocolManifest.validate(manifest)
      context      <- ProtocolManifest.context(manifest)
      familyDigest <- InputManifest.digest(family)
      activeFamily <- CertificationChecks.family(familyDigest, manifest)
      _            <- V2Validation.require(
        activeFamily == family && family.maintenanceVerifierDigest.nonEmpty,
        FailureCode.ManifestMismatch,
        "opening.family",
      )
      _ <- V2Validation.require(
        envelope.targetContext == context && envelope.targetManifestDigest == context.configurationDigest &&
          entry.manifestDigest == familyDigest && input.manifestDigest == familyDigest,
        FailureCode.ManifestMismatch,
        "opening.manifest",
      )
      _ <- CertificationChecks.deadline(
        envelope.admissionBase,
        envelope.lastInclusionHeight,
        manifest.maxLockLifetimeBlocks,
      )
      _ <- V2Validation.require(
        entry.lastInclusionHeight == envelope.lastInclusionHeight,
        FailureCode.InvalidDeadline,
        "opening.lastInclusionHeight",
      )
      encoded       <- SignedOpening.codec.encode(opening)
      lockReference <- entry.source match
        case PlanSource.ConsensusTransaction(_, signedTransaction, lockId) =>
          V2Validation
            .require(
              signedTransaction == encoded,
              FailureCode.OpeningMismatch,
              "opening.signedTransaction",
            )
            .map(_ => lockId)
        case _ =>
          Left(CoreFailure.at(FailureCode.OpeningMismatch, "opening.source"))
      _ <- V2Validation.require(
        entry.declaration match
          case Declaration.Compatibility(reason) =>
            reason == envelope.reasonDigest
          case _ => false,
        FailureCode.ClassificationMismatch,
        "opening.declaration",
      )
      declarationDigest <- Declaration.digest(entry.declaration)
      _                 <- V2Validation.require(
        declarationDigest == entry.declarationDigest,
        FailureCode.CommitmentMismatch,
        "opening.declarationDigest",
      )
      lockInputs <- InputDerivation.lockInputs(family, input)
      _          <- V2Validation.require(
        input.fullInputCommitment == entry.fullInputCommitment && input.lockSubsetCommitment == entry.lockSubsetCommitment,
        FailureCode.CommitmentMismatch,
        "opening.inputs",
      )
      _ <- V2Validation.require(
        lockReference.nonEmpty == lockInputs.nonEmpty,
        FailureCode.CertificateMismatch,
        "opening.inputLockCertificateId",
      )
      _ <- envelope.kind match
        case OpeningKind.InitialBootstrap =>
          V2Validation.require(
            lockInputs.isEmpty && lockReference.isEmpty && entry.entryPreStateRoot == envelope.sourceRoot,
            FailureCode.OpeningMismatch,
            "opening.bootstrapLockSubset",
          )
        case OpeningKind.Handover => Right(())
      identity <- ExecutionIdentity.compute(
        ExecutionIdentityInput(
          context,
          entry.manifestDigest,
          entry.source.txId,
          encoded,
          entry.fullInputCommitment,
          entry.lockSubsetCommitment,
          entry.declarationDigest,
          entry.dependencyPlanDigest,
          entry.lastInclusionHeight,
        ),
      )
      _ <- V2Validation.require(
        identity == entry.executionId,
        FailureCode.CommitmentMismatch,
        "opening.executionId",
      )
      preimage <- OpeningEnvelope.signingPreimage(envelope)
      _        <- authorization.verify(family, opening, preimage)
    yield ()

  /** Runs a verified signed conversion against private working state and checks
    * result normalization and complete actual-access commitments. This is a
    * pure preparation helper; it does not reserve, vote, expire or commit
    * state.
    */
  def executeValidated[S](
      parentState: S,
      opening: SignedOpening,
      entry: PlanEntry,
      input: InputDescriptor,
      manifest: ProtocolManifest,
      family: InputManifest,
      authorization: MaintenanceAuthorizationVerifier,
      executor: OpeningExecutor[S],
  ): Either[CoreFailure, OpeningExecution[S]] =
    for
      _ <- validate(opening, entry, input, manifest, family, authorization)
      execution <- executor.execute(parentState, opening)
      _         <- NormalizedApplicationResult
        .validate(execution.result)
        .left
        .map(_ =>
          CoreFailure.at(FailureCode.CommitmentMismatch, "opening.result"),
        )
      actual <- InputDerivation.validateActual(
        input,
        None,
        execution.actualAccesses,
      )
      commitment <- Footprint.actualCommitment(actual)
      _          <- V2Validation.require(
        commitment == entry.actualFootprintCommitment,
        FailureCode.CommitmentMismatch,
        "opening.actualFootprintCommitment",
      )
    yield execution
