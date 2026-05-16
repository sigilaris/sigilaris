package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockId,
  BlockHeader,
  BlockHeight,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}

/** Local bounds supplied to an application proposal input provider. */
final case class HotStuffProposalInputBounds(
    maxTxIds: Option[Int],
)

/** Companion for `HotStuffProposalInputBounds`. */
object HotStuffProposalInputBounds:
  val unbounded: HotStuffProposalInputBounds =
    HotStuffProposalInputBounds(maxTxIds = None)

/** Application-neutral request for the next leader proposal body. */
final case class HotStuffProposalInputRequest(
    window: HotStuffWindow,
    proposer: ValidatorId,
    parent: Option[BlockId],
    height: BlockHeight,
    justify: QuorumCertificate,
    now: Instant,
    timestamp: BlockTimestamp,
    bounds: HotStuffProposalInputBounds,
)

/** Application-neutral proposal input that Sigilaris can sign as a HotStuff proposal. */
final case class HotStuffProposalInput(
    parent: Option[BlockId],
    height: BlockHeight,
    stateRoot: StateRoot,
    bodyRoot: BodyRoot,
    timestamp: BlockTimestamp,
    txSet: ProposalTxSet,
):
  def blockHeader: BlockHeader =
    BlockHeader(
      parent = parent,
      height = height,
      stateRoot = stateRoot,
      bodyRoot = bodyRoot,
      timestamp = timestamp,
    )

/** Taxonomy for application proposal input lookup results. */
enum HotStuffProposalInputProviderResult:
  case Supplied(input: HotStuffProposalInput)
  case NoWork(reason: String, detail: Option[String])
  case Rejected(reason: String, detail: Option[String])
  case Failed(reason: String, detail: Option[String])

/** Companion for `HotStuffProposalInputProviderResult`. */
object HotStuffProposalInputProviderResult:
  extension (result: HotStuffProposalInputProviderResult)
    def reason: String =
      result match
        case HotStuffProposalInputProviderResult.Supplied(_) =>
          "supplied"
        case HotStuffProposalInputProviderResult.NoWork(reason, _) =>
          reason
        case HotStuffProposalInputProviderResult.Rejected(reason, _) =>
          reason
        case HotStuffProposalInputProviderResult.Failed(reason, _) =>
          reason

    def detail: Option[String] =
      result match
        case HotStuffProposalInputProviderResult.Supplied(_) =>
          None
        case HotStuffProposalInputProviderResult.NoWork(_, detail) =>
          detail
        case HotStuffProposalInputProviderResult.Rejected(_, detail) =>
          detail
        case HotStuffProposalInputProviderResult.Failed(_, detail) =>
          detail

/** Application-owned source of HotStuff proposal input. */
trait HotStuffProposalInputProvider[F[_]]:
  def nextProposalInput(
      request: HotStuffProposalInputRequest,
  ): F[HotStuffProposalInputProviderResult]

/** Explicit policy for preserving or forbidding legacy empty proposals. */
enum HotStuffProposalInputFallbackPolicy:
  case AllowLegacyEmpty
  case RequireProviderInput

/** Runtime wiring for autonomous proposal input. */
final case class HotStuffProposalInputRuntimeConfig[F[_]](
    provider: Option[HotStuffProposalInputProvider[F]],
    fallbackPolicy: HotStuffProposalInputFallbackPolicy,
)

/** Companion for `HotStuffProposalInputRuntimeConfig`. */
object HotStuffProposalInputRuntimeConfig:
  def legacyCompatible[F[_]]: HotStuffProposalInputRuntimeConfig[F] =
    HotStuffProposalInputRuntimeConfig(
      provider = None,
      fallbackPolicy = HotStuffProposalInputFallbackPolicy.AllowLegacyEmpty,
    )

  def withProviderFallback[F[_]](
      provider: HotStuffProposalInputProvider[F],
  ): HotStuffProposalInputRuntimeConfig[F] =
    HotStuffProposalInputRuntimeConfig(
      provider = Some(provider),
      fallbackPolicy = HotStuffProposalInputFallbackPolicy.AllowLegacyEmpty,
    )

  def requireProvider[F[_]](
      provider: HotStuffProposalInputProvider[F],
  ): HotStuffProposalInputRuntimeConfig[F] =
    HotStuffProposalInputRuntimeConfig(
      provider = Some(provider),
      fallbackPolicy = HotStuffProposalInputFallbackPolicy.RequireProviderInput,
    )

  def requireProviderInput[F[_]]: HotStuffProposalInputRuntimeConfig[F] =
    HotStuffProposalInputRuntimeConfig(
      provider = None,
      fallbackPolicy = HotStuffProposalInputFallbackPolicy.RequireProviderInput,
    )

  def validateForAutomaticConsensus[F[_]](
      config: HotStuffProposalInputRuntimeConfig[F],
  ): Either[HotStuffPolicyViolation, Unit] =
    config.fallbackPolicy match
      case HotStuffProposalInputFallbackPolicy.AllowLegacyEmpty =>
        ().asRight[HotStuffPolicyViolation]
      case HotStuffProposalInputFallbackPolicy.RequireProviderInput =>
        config.provider match
          case Some(_) =>
            ().asRight[HotStuffPolicyViolation]
          case None =>
            HotStuffPolicyViolation(
              reason = "proposalInputProviderRequired",
              detail = Some(
                "automatic consensus requires a proposal input provider when legacy empty fallback is disabled",
              ),
            ).asLeft[Unit]

/** A local decision made from a provider result and fallback policy. */
enum HotStuffProposalInputDecision:
  case UseProviderInput(input: HotStuffProposalInput)
  case UseLegacyEmpty(reason: String, detail: Option[String])
  case Suppress(reason: String, detail: Option[String])

/** Companion for `HotStuffProposalInputDecision`. */
object HotStuffProposalInputDecision:
  def fromProviderResult(
      result: HotStuffProposalInputProviderResult,
      fallbackPolicy: HotStuffProposalInputFallbackPolicy,
  ): HotStuffProposalInputDecision =
    result match
      case HotStuffProposalInputProviderResult.Supplied(input) =>
        HotStuffProposalInputDecision.UseProviderInput(input)
      case nonSupplied =>
        fallbackPolicy match
          case HotStuffProposalInputFallbackPolicy.AllowLegacyEmpty =>
            HotStuffProposalInputDecision.UseLegacyEmpty(
              reason = nonSupplied.reason,
              detail = nonSupplied.detail,
            )
          case HotStuffProposalInputFallbackPolicy.RequireProviderInput =>
            HotStuffProposalInputDecision.Suppress(
              reason = nonSupplied.reason,
              detail = nonSupplied.detail,
            )

/** Validation helpers for provider-supplied proposal input. */
object HotStuffProposalInputValidator:
  def validate(
      request: HotStuffProposalInputRequest,
      input: HotStuffProposalInput,
  ): Either[HotStuffValidationFailure, HotStuffProposalInput] =
    for
      _ <- ensure(
        input.parent === request.parent,
        "proposalInputParentMismatch",
        request.parent.map(_.toHexLower),
      )
      _ <- ensure(
        input.height === request.height,
        "proposalInputHeightMismatch",
        Some(
          ss"expected=${request.height.render} actual=${input.height.render}",
        ),
      )
      _ <- ensure(
        input.height.toBigNat === request.window.height.toBigNat,
        "proposalInputWindowHeightMismatch",
        Some(
          ss"window=${request.window.height.render} input=${input.height.render}",
        ),
      )
      _ <- ensure(
        input.timestamp === request.timestamp,
        "proposalInputTimestampMismatch",
        Some(
          ss"expected=${request.timestamp.toEpochMillis.toString} actual=${input.timestamp.toEpochMillis.toString}",
        ),
      )
      _ <- ensure(
        ProposalTxSet.isCanonical(input.txSet),
        "proposalInputTxSetNotCanonical",
        None,
      )
      _ <- ProposalTxSet
        .firstUnsupportedTxId(input.txSet)
        .fold(().asRight[HotStuffValidationFailure]): txId =>
          HotStuffValidationFailure(
            reason = "proposalInputTxIdUnsupported",
            detail = Some(txId.toHexLower),
          ).asLeft[Unit]
      _ <- request.bounds.maxTxIds.traverse: maxTxIds =>
        ensure(
          input.txSet.txIds.sizeCompare(maxTxIds) <= 0,
          "proposalInputTxLimitExceeded",
          Some(
            ss"max=${maxTxIds.toString} actual=${input.txSet.txIds.length.toString}",
          ),
        )
    yield input

  private def ensure(
      condition: Boolean,
      reason: String,
      detail: Option[String],
  ): Either[HotStuffValidationFailure, Unit] =
    Either.cond(
      condition,
      (),
      HotStuffValidationFailure(reason = reason, detail = detail),
    )

/** Legacy provider that emits the previous autonomous empty proposal body. */
final class LegacyEmptyHotStuffProposalInputProvider[F[_]: Sync](
    stateRootFor: HotStuffProposalInputRequest => F[StateRoot],
    bodyRootFor: HotStuffProposalInputRequest => BodyRoot,
) extends HotStuffProposalInputProvider[F]:
  override def nextProposalInput(
      request: HotStuffProposalInputRequest,
  ): F[HotStuffProposalInputProviderResult] =
    stateRootFor(request).map: stateRoot =>
      HotStuffProposalInputProviderResult.Supplied:
        HotStuffProposalInput(
          parent = request.parent,
          height = request.height,
          stateRoot = stateRoot,
          bodyRoot = bodyRootFor(request),
          timestamp = request.timestamp,
          txSet = ProposalTxSet.empty,
        )

/** Companion for `LegacyEmptyHotStuffProposalInputProvider`. */
object LegacyEmptyHotStuffProposalInputProvider:
  def const[F[_]: Sync](
      stateRoot: StateRoot,
      bodyRoot: BodyRoot,
  ): HotStuffProposalInputProvider[F] =
    new LegacyEmptyHotStuffProposalInputProvider[F](
      stateRootFor = _ => stateRoot.pure[F],
      bodyRootFor = _ => bodyRoot,
    )
