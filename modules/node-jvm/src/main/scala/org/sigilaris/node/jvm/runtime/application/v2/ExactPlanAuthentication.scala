package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.txpipeline.v2.{
  ExactPlanIntent,
  ExactMode,
  SignedExactPlan,
  StageIntent,
}

/** Decoded from the actual signed application transaction under its configured
  * signature/replay contract. The root must be authenticated retained state or
  * the result of deterministic authenticated preceding execution, never an
  * arbitrary future root copied from a request. Authentication also verifies
  * that admissionBase and the descriptor/deadline are bound by those signed
  * transaction bytes under the selected family's source contract.
  */
final case class ExactStageMaterial(
    txId: Hash,
    descriptor: InputDescriptor,
    entryPreStateRoot: Hash,
    admissionBase: AdmissionBase,
    proofs: Vector[ResolutionEvidence],
    declaration: Declaration,
    lastInclusionHeight: Height,
)
trait ExactStageAuthentication:
  def authenticate(
      context: DomainContext,
      family: InputManifest,
      signedTransaction: Bytes,
  ): Either[CoreFailure, ExactStageMaterial]

/** A successful decode may describe deterministic reducer rejection. The full
  * actual execution must already be authenticated before this outcome can
  * preserve reservations. Malformed output/reference evidence is a separate
  * outer Left and never creates an execution capability.
  */
final case class ExactOutputEvaluation(
    output: Bytes,
    outcome: Either[V2RuntimeFailure, Unit],
)
trait ExactProfileAuthentication:
  def verifyAuthorization(
      signed: SignedExactPlan,
      manifest: ProtocolManifest,
      signingPreimage: Bytes,
  ): Either[CoreFailure, Unit]
  def deriveReference(
      signed: SignedExactPlan,
      producer: ExactStageMaterial,
      consumer: ExactStageMaterial,
  ): Either[CoreFailure, Bytes]
  def producerOutput(
      signed: SignedExactPlan,
      normalizedResult: Bytes,
  ): Either[CoreFailure, ExactOutputEvaluation]
  def consumerAcceptance(
      signed: SignedExactPlan,
      producerOutput: Bytes,
      normalizedConsumerResult: Bytes,
  ): Either[CoreFailure, Either[V2RuntimeFailure, Unit]]

type VerifiedExactPlan = ExactPlanAuthentication.VerifiedExactPlan
trait ExactPlanAuthentication:
  def verify(
      signed: SignedExactPlan,
      manifest: ProtocolManifest,
  ): Either[CoreFailure, VerifiedExactPlan]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ExactPlanAuthentication:
  final class VerifiedExactPlan private[ExactPlanAuthentication] (
      val signedPlan: SignedExactPlan,
      val signedPlanDigest: Hash,
      val stages: Vector[ExactStageMaterial],
      private val profile: ExactProfileAuthentication,
  ):
    def producerOutput(
        normalizedResult: Bytes,
    ): Either[CoreFailure, ExactOutputEvaluation] =
      profile.producerOutput(signedPlan, normalizedResult)
    def consumerAcceptance(
        producerOutput: Bytes,
        normalizedResult: Bytes,
    ): Either[CoreFailure, Either[V2RuntimeFailure, Unit]] =
      profile.consumerAcceptance(signedPlan, producerOutput, normalizedResult)

  def authenticated(
      stageAuthentication: ExactStageAuthentication,
      inputAuthentication: InputAuthentication,
      declarations: DeclaredFootprintAuthentication,
      creations: DeclaredCreationAuthentication,
      profile: ExactProfileAuthentication,
      artifactAuthentication: ArtifactAuthentication,
  ): ExactPlanAuthentication = new ExactPlanAuthentication:
    private def check(
        value: Boolean,
        field: String,
    ): Either[CoreFailure, Unit] =
      V2Validation.require(value, FailureCode.MembershipMismatch, field)
    private def family(
        manifest: ProtocolManifest,
        digest: Hash,
    ): Either[CoreFailure, InputManifest] =
      manifest.families
        .find(value => InputManifest.digest(value).toOption.contains(digest))
        .toRight(
          CoreFailure.at(FailureCode.ManifestMismatch, "exact.stageManifest"),
        )
    private def stage(
        signed: SignedExactPlan,
        manifest: ProtocolManifest,
        selected: InputManifest,
        intent: StageIntent,
    ): Either[CoreFailure, ExactStageMaterial] =
      for
        material <- stageAuthentication.authenticate(
          signed.intent.context,
          selected,
          intent.signedTransaction,
        )
        _ <- artifactAuthentication.verifyFinalizedBase(
          signed.intent.context,
          material.admissionBase,
        )
        _ <- V2Validation.require(
          (material.admissionBase match
            case AdmissionBase.Finalized(_, _, _) => true
            case _                                => false
          ),
          FailureCode.ProofInvalid,
          "exact.finalizedAdmission",
        )
        baseHeight = AdmissionBase
          .height(material.admissionBase)
          .toBigNat
          .toBigInt
        deadline = material.lastInclusionHeight.toBigNat.toBigInt
        _ <- V2Validation.require(
          deadline > baseHeight && deadline <= baseHeight + BigInt(
            manifest.maxLockLifetimeBlocks,
          ),
          FailureCode.InvalidDeadline,
          "exact.signedAdmissionDeadline",
        )
        _ <- InputDerivation.validate(
          selected,
          intent.signedTransaction,
          material.entryPreStateRoot,
          material.descriptor,
          material.proofs,
          inputAuthentication,
        )
        locks <- InputDerivation.lockInputs(selected, material.descriptor)
        _     <- V2Validation.require(
          intent.sourceKind != 2.toByte || locks.nonEmpty,
          FailureCode.InvalidLength,
          "exact.fastSourceRequiresEligibleLocks",
        )
        _ <- V2Validation.require(
          signed.intent.mode != ExactMode.OrderedAtomic || (intent.declaration match
            case Declaration.Exact(_)         => true
            case Declaration.Compatibility(_) => false),
          FailureCode.ClassificationMismatch,
          "exact.orderedPairCannotUseSingletonCompatibility",
        )
        declared <- declarations.derive(
          selected,
          intent.signedTransaction,
          material.entryPreStateRoot,
          material.descriptor,
        )
        creationProofs <- creations.derive(
          selected,
          intent.signedTransaction,
          material.entryPreStateRoot,
          material.descriptor,
        )
        _ <- V2Validation.sortedUnique(
          creationProofs.map(_._1.toHex),
          "exact.declaredCreations",
        )
        _ <- V2Validation.all(
          creationProofs.map((identity, _) =>
            V2Validation.inputId(identity, "exact.creationIdentity"),
          ),
        )
        existing = material.descriptor.fields.flatMap(_.stableId).toSet
        _ <- check(
          creationProofs.forall((identity, _) =>
            !existing.contains(identity) && declared.forall(
              _.writes.contains(identity),
            ),
          ),
          "exact.creationCoverage",
        )
        _ <- V2Validation.all(
          creationProofs.map((identity, proof) =>
            inputAuthentication.verifyAbsentCreation(
              selected,
              intent.signedTransaction,
              material.entryPreStateRoot,
              identity,
              proof,
            ),
          ),
        )
        declaredDigest <- declared match
          case Some(footprint) =>
            Footprint.declaredCommitment(footprint).map(Declaration.Exact(_))
          case None => Right[CoreFailure, Declaration](material.declaration)
        _ <- check(
          material.txId == intent.txId && material.descriptor.manifestDigest == intent.manifestDigest &&
            material.descriptor.fullInputCommitment == intent.fullInputCommitment &&
            material.descriptor.lockSubsetCommitment == intent.lockSubsetCommitment && material.declaration == intent.declaration &&
            declaredDigest == intent.declaration && material.lastInclusionHeight == signed.intent.lastInclusionHeight,
          "exact.signedStageBinding",
        )
        _ <- intent.declaration match
          case Declaration.Exact(_) =>
            check(declared.nonEmpty, "exact.declarationShape")
          case Declaration.Compatibility(_) =>
            check(declared.isEmpty, "exact.declarationShape")
      yield material

    def verify(
        signed: SignedExactPlan,
        manifest: ProtocolManifest,
    ): Either[CoreFailure, VerifiedExactPlan] =
      for
        _       <- SignedExactPlan.validate(signed)
        context <- ProtocolManifest.context(manifest)
        _       <- check(
          signed.intent.context == context && manifest.profiles.contains(
            signed.intent.profile,
          ),
          "exact.activeProfile",
        )
        _ <- V2Validation.require(
          signed.authorization.nonEmpty,
          FailureCode.InvalidSignature,
          "exact.outerAuthorization",
        )
        preimage  <- ExactPlanIntent.signingBytes(signed.intent)
        _         <- profile.verifyAuthorization(signed, manifest, preimage)
        materials <- signed.intent.stages
          .foldLeft[Either[CoreFailure, Vector[ExactStageMaterial]]](
            Right(Vector.empty),
          ) { (previous, intent) =>
            for
              all      <- previous
              selected <- family(manifest, intent.manifestDigest)
              verified <- stage(signed, manifest, selected, intent)
            yield all :+ verified
          }
        producer <- materials.headOption.toRight(
          CoreFailure.at(FailureCode.MembershipMismatch, "exact.producer"),
        )
        consumer <- materials
          .lift(1)
          .toRight(
            CoreFailure.at(FailureCode.MembershipMismatch, "exact.consumer"),
          )
        reference      <- profile.deriveReference(signed, producer, consumer)
        consumerFamily <- family(manifest, consumer.descriptor.manifestDigest)
        field          <- consumer.descriptor.fields
          .find(_.fieldId == signed.intent.consumerFieldId)
          .toRight(
            CoreFailure.at(
              FailureCode.MembershipMismatch,
              "exact.consumerReferenceField",
            ),
          )
        definition <- consumerFamily.fields
          .find(_.fieldId == signed.intent.consumerFieldId)
          .toRight(
            CoreFailure.at(
              FailureCode.MembershipMismatch,
              "exact.consumerReferenceManifest",
            ),
          )
        _ <- check(
          reference == signed.intent.consumerReference && field.value == reference &&
            field.role == FieldRole.Opaque && definition.role == FieldRole.Opaque &&
            definition.schemaDigest == signed.intent.referenceSchemaDigest,
          "exact.outputReferenceBinding",
        )
        digest <- SignedExactPlan.digest(signed)
      yield new VerifiedExactPlan(signed, digest, materials, profile)
