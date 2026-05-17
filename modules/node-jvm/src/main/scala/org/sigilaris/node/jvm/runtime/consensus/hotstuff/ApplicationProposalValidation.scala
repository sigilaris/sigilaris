package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.kernel.Sync
import cats.syntax.all.*

/** Application-neutral request for validating a received proposal before a local vote is signed. */
final case class HotStuffProposalValidationRequest(
    proposal: Proposal,
    localVoter: ValidatorId,
    now: Instant,
    validatorSet: ValidatorSet,
)

/** Taxonomy for application proposal validation provider results. */
enum HotStuffProposalValidationProviderResult:
  case Accepted
  case Rejected(reason: String, detail: Option[String])
  case Unavailable(reason: String, detail: Option[String])
  case Failed(reason: String, detail: Option[String])

/** Companion for `HotStuffProposalValidationProviderResult`. */
object HotStuffProposalValidationProviderResult:
  extension (result: HotStuffProposalValidationProviderResult)
    def reason: String =
      result match
        case HotStuffProposalValidationProviderResult.Accepted =>
          "accepted"
        case HotStuffProposalValidationProviderResult.Rejected(reason, _) =>
          reason
        case HotStuffProposalValidationProviderResult.Unavailable(reason, _) =>
          reason
        case HotStuffProposalValidationProviderResult.Failed(reason, _) =>
          reason

    def detail: Option[String] =
      result match
        case HotStuffProposalValidationProviderResult.Accepted =>
          None
        case HotStuffProposalValidationProviderResult.Rejected(_, detail) =>
          detail
        case HotStuffProposalValidationProviderResult.Unavailable(_, detail) =>
          detail
        case HotStuffProposalValidationProviderResult.Failed(_, detail) =>
          detail

/** Application-owned proposal validation hook for received proposal vote paths. */
trait HotStuffProposalValidationProvider[F[_]]:
  def validateProposal(
      request: HotStuffProposalValidationRequest,
  ): F[HotStuffProposalValidationProviderResult]

/** Explicit policy for handling a missing proposal validation provider. */
enum HotStuffProposalValidationMissingProviderPolicy:
  case AllowAll
  case RequireProvider

/** Runtime wiring for application proposal validation. */
final case class HotStuffProposalValidationRuntimeConfig[F[_]](
    provider: Option[HotStuffProposalValidationProvider[F]],
    missingProviderPolicy: HotStuffProposalValidationMissingProviderPolicy,
)

/** Companion for `HotStuffProposalValidationRuntimeConfig`. */
object HotStuffProposalValidationRuntimeConfig:
  def legacyCompatible[F[_]]: HotStuffProposalValidationRuntimeConfig[F] =
    HotStuffProposalValidationRuntimeConfig(
      provider = None,
      missingProviderPolicy =
        HotStuffProposalValidationMissingProviderPolicy.AllowAll,
    )

  def withProvider[F[_]](
      provider: HotStuffProposalValidationProvider[F],
  ): HotStuffProposalValidationRuntimeConfig[F] =
    HotStuffProposalValidationRuntimeConfig(
      provider = Some(provider),
      missingProviderPolicy =
        HotStuffProposalValidationMissingProviderPolicy.AllowAll,
    )

  def requireProvider[F[_]](
      provider: HotStuffProposalValidationProvider[F],
  ): HotStuffProposalValidationRuntimeConfig[F] =
    HotStuffProposalValidationRuntimeConfig(
      provider = Some(provider),
      missingProviderPolicy =
        HotStuffProposalValidationMissingProviderPolicy.RequireProvider,
    )

  def requireValidationProvider[F[_]]
      : HotStuffProposalValidationRuntimeConfig[F] =
    HotStuffProposalValidationRuntimeConfig(
      provider = None,
      missingProviderPolicy =
        HotStuffProposalValidationMissingProviderPolicy.RequireProvider,
    )

  def validateForAutomaticConsensus[F[_]](
      config: HotStuffProposalValidationRuntimeConfig[F],
  ): Either[HotStuffPolicyViolation, Unit] =
    config.missingProviderPolicy match
      case HotStuffProposalValidationMissingProviderPolicy.AllowAll =>
        ().asRight[HotStuffPolicyViolation]
      case HotStuffProposalValidationMissingProviderPolicy.RequireProvider =>
        config.provider match
          case Some(_) =>
            ().asRight[HotStuffPolicyViolation]
          case None =>
            HotStuffPolicyViolation(
              reason = "proposalValidationProviderRequired",
              detail = Some(
                "automatic consensus requires a proposal validation provider when validation is required",
              ),
            ).asLeft[Unit]

/** Diagnostic-level outcome for proposal validation decisions. */
enum HotStuffProposalValidationOutcome:
  case Accepted, Rejected, Unavailable, Failed, MissingProvider

/** Runtime decision made from provider output and missing-provider policy. */
enum HotStuffProposalValidationDecision:
  case Accept(acceptReason: String, acceptDetail: Option[String])
  case Suppress(
      suppressOutcome: HotStuffProposalValidationOutcome,
      suppressReason: String,
      suppressDetail: Option[String],
  )

  def outcome: HotStuffProposalValidationOutcome =
    this match
      case HotStuffProposalValidationDecision.Accept(_, _) =>
        HotStuffProposalValidationOutcome.Accepted
      case HotStuffProposalValidationDecision.Suppress(suppressOutcome, _, _) =>
        suppressOutcome

  def reason: String =
    this match
      case HotStuffProposalValidationDecision.Accept(acceptReason, _) =>
        acceptReason
      case HotStuffProposalValidationDecision.Suppress(_, suppressReason, _) =>
        suppressReason

  def detail: Option[String] =
    this match
      case HotStuffProposalValidationDecision.Accept(_, acceptDetail) =>
        acceptDetail
      case HotStuffProposalValidationDecision.Suppress(_, _, suppressDetail) =>
        suppressDetail

  def voteSuppressed: Boolean =
    this match
      case HotStuffProposalValidationDecision.Accept(_, _) => false
      case HotStuffProposalValidationDecision.Suppress(_, _, _) => true

/** Companion for `HotStuffProposalValidationDecision`. */
object HotStuffProposalValidationDecision:
  def fromProviderResult(
      result: HotStuffProposalValidationProviderResult,
  ): HotStuffProposalValidationDecision =
    result match
      case HotStuffProposalValidationProviderResult.Accepted =>
        HotStuffProposalValidationDecision.Accept(
          acceptReason = result.reason,
          acceptDetail = result.detail,
        )
      case HotStuffProposalValidationProviderResult.Rejected(reason, detail) =>
        HotStuffProposalValidationDecision.Suppress(
          HotStuffProposalValidationOutcome.Rejected,
          reason,
          detail,
        )
      case HotStuffProposalValidationProviderResult.Unavailable(
            reason,
            detail,
          ) =>
        HotStuffProposalValidationDecision.Suppress(
          HotStuffProposalValidationOutcome.Unavailable,
          reason,
          detail,
        )
      case HotStuffProposalValidationProviderResult.Failed(reason, detail) =>
        HotStuffProposalValidationDecision.Suppress(
          HotStuffProposalValidationOutcome.Failed,
          reason,
          detail,
        )

  def fromMissingProviderPolicy(
      policy: HotStuffProposalValidationMissingProviderPolicy,
  ): HotStuffProposalValidationDecision =
    policy match
      case HotStuffProposalValidationMissingProviderPolicy.AllowAll =>
        HotStuffProposalValidationDecision.Accept(
          acceptReason = "proposalValidationAllowAll",
          acceptDetail = None,
        )
      case HotStuffProposalValidationMissingProviderPolicy.RequireProvider =>
        HotStuffProposalValidationDecision.Suppress(
          HotStuffProposalValidationOutcome.MissingProvider,
          suppressReason = "proposalValidationProviderRequired",
          suppressDetail = Some("proposal validation provider is required"),
        )

  def fromProviderFailure(
      error: Throwable,
  ): HotStuffProposalValidationDecision =
    HotStuffProposalValidationDecision.Suppress(
      HotStuffProposalValidationOutcome.Failed,
      suppressReason = "proposalValidationProviderFailed",
      suppressDetail = Some(error.getClass.getName),
    )

  def evaluate[F[_]: Sync](
      config: HotStuffProposalValidationRuntimeConfig[F],
      request: HotStuffProposalValidationRequest,
  ): F[HotStuffProposalValidationDecision] =
    config.provider match
      case Some(provider) =>
        provider
          .validateProposal(request)
          .attempt
          .map:
            case Right(result) => fromProviderResult(result)
            case Left(error)   => fromProviderFailure(error)
      case None =>
        fromMissingProviderPolicy(config.missingProviderPolicy).pure[F]

  def evaluateForLocalVote[F[_]: Sync](
      config: HotStuffProposalValidationRuntimeConfig[F],
      proposal: Proposal,
      localVoter: ValidatorId,
      now: Instant,
      validatorSet: ValidatorSet,
  ): F[HotStuffProposalValidationDecision] =
    evaluate(
      config,
      HotStuffProposalValidationRequest(
        proposal = proposal,
        localVoter = localVoter,
        now = now,
        validatorSet = validatorSet,
      ),
    )

/** Compatibility provider that accepts all proposal vote requests. */
final class AllowAllHotStuffProposalValidationProvider[F[_]: Sync]
    extends HotStuffProposalValidationProvider[F]:
  override def validateProposal(
      request: HotStuffProposalValidationRequest,
  ): F[HotStuffProposalValidationProviderResult] =
    HotStuffProposalValidationProviderResult.Accepted.pure[F]
