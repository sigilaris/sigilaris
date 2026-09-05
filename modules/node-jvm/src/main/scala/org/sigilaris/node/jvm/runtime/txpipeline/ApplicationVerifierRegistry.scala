package org.sigilaris.node.jvm.runtime.txpipeline

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.node.txpipeline.*

final case class ExactPipelineVerificationInput(
    request: ExactPipelineNormalizedRequest,
    profile: DependencyProfileManifest,
    manifest: ApplicationProtocolManifestV1,
)

trait ApplicationExactPipelineVerifier[F[_]]:
  def verify(
      input: ExactPipelineVerificationInput,
  ): F[Either[TxPipelineValidationFailure, VerifiedExactDependencyPlan]]

final case class RegisteredApplicationVerifier[F[_]](
    slot: VerifierSlot,
    manifestDigest: VerifierManifestDigest,
    verifier: ApplicationExactPipelineVerifier[F],
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
final class ApplicationVerifierRegistry[F[_]: Monad] private (
    registrations: Map[
      (VerifierSlot, VerifierManifestDigest),
      ApplicationExactPipelineVerifier[F],
    ],
):
  def validateActivation(
      manifest: ApplicationProtocolManifestV1,
  ): Either[TxPipelineValidationFailure, Unit] =
    for
      _ <- validateManifest(manifest)
      _ <- manifest.profiles.traverse_(profile => resolveVerifier(profile).void)
    yield ()

  def verify(
      manifest: ApplicationProtocolManifestV1,
      request: ExactPipelineNormalizedRequest,
      baseHeight: InclusionHeight,
  ): EitherT[F, TxPipelineValidationFailure, VerifiedExactDependencyPlan] =
    for
      _        <- EitherT.fromEither[F](validateManifest(manifest))
      _        <- EitherT.fromEither[F](validateRequestShape(request))
      profile  <- EitherT.fromEither[F](resolveProfile(manifest, request))
      verifier <- EitherT.fromEither[F](resolveVerifier(profile))
      verified <- EitherT(
        verifier
          .verify(ExactPipelineVerificationInput(request, profile, manifest))
          .map(_.leftMap(normalizeVerifierFailure)),
      )
      validated <- EitherT.fromEither[F](
        validateVerifiedPlan(
          request,
          profile,
          manifest,
          baseHeight,
          verified,
        ),
      )
    yield validated

  private def validateManifest(
      manifest: ApplicationProtocolManifestV1,
  ): Either[TxPipelineValidationFailure, Unit] =
    ApplicationProtocolManifestV1
      .validate(manifest)
      .bimap(
        value => failure(value.reason, "application manifest is invalid"),
        _ => (),
      )

  private def validateRequestShape(
      request: ExactPipelineNormalizedRequest,
  ): Either[TxPipelineValidationFailure, Unit] =
    Either.cond(
      request.transactions.sizeCompare(2) == 0,
      (),
      failure(
        "exactPipelineStageCountMismatch",
        "M1 exact pipelines require exactly two ordered transactions",
      ),
    )

  private def resolveProfile(
      manifest: ApplicationProtocolManifestV1,
      request: ExactPipelineNormalizedRequest,
  ): Either[TxPipelineValidationFailure, DependencyProfileManifest] =
    manifest.profiles.find(_.profileId == request.profileId) match
      case None =>
        Left(
          failure(
            "unknownDependencyProfile",
            s"profile ${request.profileId.value} is not registered",
          ),
        )
      case Some(profile) if profile.profileVersion != request.profileVersion =>
        Left(
          failure(
            "inactiveDependencyProfile",
            s"profile ${request.profileId.value} version ${request.profileVersion.value} is not active",
          ),
        )
      case Some(profile) => Right(profile)

  private def resolveVerifier(
      profile: DependencyProfileManifest,
  ): Either[
    TxPipelineValidationFailure,
    ApplicationExactPipelineVerifier[F],
  ] =
    registrations.get(
      profile.verifierSlot -> profile.verifierManifestDigest,
    ) match
      case Some(verifier) => Right(verifier)
      case None           =>
        val slotInstalled =
          registrations.keysIterator.exists(_._1 == profile.verifierSlot)
        if slotInstalled then
          Left(
            failure(
              "verifierManifestMismatch",
              s"verifier slot ${profile.verifierSlot.value} has no implementation for manifest ${profile.verifierManifestDigest.value}",
            ),
          )
        else
          Left(
            failure(
              "unknownVerifierSlot",
              s"verifier slot ${profile.verifierSlot.value} is unavailable",
            ),
          )

  private def validateVerifiedPlan(
      request: ExactPipelineNormalizedRequest,
      profile: DependencyProfileManifest,
      manifest: ApplicationProtocolManifestV1,
      baseHeight: InclusionHeight,
      plan: VerifiedExactDependencyPlan,
  ): Either[TxPipelineValidationFailure, VerifiedExactDependencyPlan] =
    for
      _ <- Either.cond(
        plan.profileId == request.profileId &&
          plan.profileVersion == request.profileVersion,
        (),
        failure(
          "inactiveDependencyProfile",
          "verified plan profile does not match the submitted profile",
        ),
      )
      _ <- Either.cond(
        plan.verifierSlot == profile.verifierSlot,
        (),
        failure(
          "unknownVerifierSlot",
          "verified plan changed the manifest verifier slot",
        ),
      )
      _ <- Either.cond(
        plan.verifierManifestDigest == profile.verifierManifestDigest,
        (),
        failure(
          "verifierManifestMismatch",
          "verified plan changed the verifier manifest digest",
        ),
      )
      _ <- Either.cond(
        plan.orderedExecutionIds.sizeCompare(2) == 0 &&
          plan.orderedExecutionIds.distinct.sizeCompare(2) == 0,
        (),
        failure(
          "executionOrderMismatch",
          "verified plan must contain two distinct ordered execution ids",
        ),
      )
      _ <- Either.cond(
        plan.producerPosition == 0 && plan.consumerPosition == 1,
        (),
        failure(
          "exactPipelineEdgeCountMismatch",
          "M1 requires exactly the edge at producer 0 -> consumer 1",
        ),
      )
      _ <- VerifiedExactDependencyPlan
        .validateCommitments(plan)
        .leftMap(detail => failure("dependencyReferenceMismatch", detail))
      _ <- Either.cond(
        plan.signedPlanDigest == ExactPipelineCanonical.signedPlanDigest(
          request.signedApplicationPlan,
        ),
        (),
        failure(
          "exactPipelineBindingMismatch",
          "signed plan digest does not bind the submitted signed plan bytes",
        ),
      )
      _ <- validateDeadline(
        baseHeight,
        plan.lastInclusionHeight,
        manifest.maxLockLifetimeBlocks,
      )
    yield plan

  private def validateDeadline(
      baseHeight: InclusionHeight,
      deadline: InclusionHeight,
      maximumLifetime: Long,
  ): Either[TxPipelineValidationFailure, Unit] =
    val base     = baseHeight.toBigNat.toBigInt
    val boundary = deadline.toBigNat.toBigInt
    if boundary <= base then
      Left(
        failure(
          "deadlineNotAfterBase",
          "lastInclusionHeight must be greater than the base height",
        ),
      )
    else if boundary > base + BigInt(maximumLifetime) then
      Left(
        failure(
          "deadlineExceedsMaximum",
          "lastInclusionHeight exceeds the committed maximum lifetime",
        ),
      )
    else Right(())

  private def normalizeVerifierFailure(
      value: TxPipelineValidationFailure,
  ): TxPipelineValidationFailure =
    if ApplicationVerifierRegistry.AllowedVerifierReasons.contains(value.reason)
    then value
    else
      failure(
        "applicationVerificationFailed",
        value.detail.getOrElse(
          "registered application verifier rejected the request",
        ),
      )

  private def failure(
      reason: String,
      detail: String,
  ): TxPipelineValidationFailure =
    TxPipelineValidationFailure(reason, detail)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Nothing",
  ),
)
object ApplicationVerifierRegistry:
  private val AllowedVerifierReasons: Set[String] = Set(
    "applicationVerificationFailed",
    "dependencyReferenceMismatch",
    "outsidePipelineConsumer",
    "crossPipelineDependencyReuse",
    "implicitDependencyLookupUnsupported",
    "missingLastInclusionHeight",
    "deadlineMismatch",
    "executionOrderMismatch",
    "exactPipelineEdgeCountMismatch",
    "exactPipelineBindingMismatch",
  )

  def create[F[_]: Monad](
      registrations: Vector[RegisteredApplicationVerifier[F]],
  ): Either[TxPipelineValidationFailure, ApplicationVerifierRegistry[F]] =
    val duplicateBinding = registrations
      .groupBy(value => value.slot -> value.manifestDigest)
      .collectFirst:
        case (binding, entries) if entries.sizeCompare(1) > 0 => binding
    duplicateBinding match
      case Some((slot, digest)) =>
        Left(
          TxPipelineValidationFailure(
            "verifierManifestMismatch",
            s"duplicate verifier registration ${slot.value}/${digest.value}",
          ),
        )
      case None =>
        Right(
          new ApplicationVerifierRegistry(
            registrations
              .map(value =>
                (value.slot -> value.manifestDigest) -> value.verifier,
              )
              .toMap,
          ),
        )

  def signedPlanDigest(
      plan: ExactPipelineSignedPlan,
  ): ExactPipelineDigest =
    ExactPipelineCanonical.signedPlanDigest(plan)
