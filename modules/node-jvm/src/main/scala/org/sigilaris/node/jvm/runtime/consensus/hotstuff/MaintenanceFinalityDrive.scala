package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.{DomainContext, Hash, Height}
import org.sigilaris.node.jvm.runtime.application.v2.Result

/** A local liveness request, never a vote, finality, expiry, or release proof.
  * The drain coordinator selects an inclusive finalized target strictly above
  * every applicable signed deadline/legacy horizon before publishing this id.
  */
final case class HotStuffMaintenanceTarget(
    requestId: Hash,
    context: DomainContext,
    finalizedHeight: Height,
)

final case class HotStuffMaintenanceObservation(
    target: Option[HotStuffMaintenanceTarget],
    finalization: Option[FinalizationTrackerSnapshot],
)

/** Reads operator/drain targets and the actual trusted local finalization
  * tracker. Remote heights, proposal heights, and clock estimates must never be
  * substituted for that tracker. A restart derives the target from the retained
  * drain command and reads finalized proof state before enabling the provider.
  */
trait HotStuffMaintenanceSource[F[_]]:
  def current(
      request: HotStuffProposalInputRequest,
  ): Result[F, HotStuffMaintenanceObservation]

final case class HotStuffMaintenanceAttempt(
    target: HotStuffMaintenanceTarget,
    attempt: Int,
    maxAttempts: Int,
    elapsed: Duration,
)

enum HotStuffMaintenanceDecision:
  case Idle
  case Requested(attempt: HotStuffMaintenanceAttempt)
  case TargetReached(target: HotStuffMaintenanceTarget)
  case Stalled(reason: String)
  case Unavailable(reason: String)

trait HotStuffMaintenanceProgress[F[_]]:
  def next(
      request: HotStuffProposalInputRequest,
      context: DomainContext,
  ): F[HotStuffMaintenanceDecision]

/** The budget controls local work only. Exhaustion keeps the target and every
  * safety claim intact; a genuine finalized tracker advance can still complete
  * it. No transaction-bearing ancestor is required to request another round.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HotStuffMaintenanceProgress:
  private final case class AttemptState(
      target: HotStuffMaintenanceTarget,
      startedAt: Instant,
      attempts: Int,
  )

  def idle[F[_]: Sync]: HotStuffMaintenanceProgress[F] =
    new HotStuffMaintenanceProgress[F]:
      def next(
          request: HotStuffProposalInputRequest,
          context: DomainContext,
      ): F[HotStuffMaintenanceDecision] =
        (HotStuffMaintenanceDecision.Idle: HotStuffMaintenanceDecision).pure[F]

  def bounded[F[_]: Sync](
      source: HotStuffMaintenanceSource[F],
      maxAttempts: Int,
      maxElapsed: Duration,
  ): F[Either[String, HotStuffMaintenanceProgress[F]]] =
    boundedWithCapacity(source, maxAttempts, maxElapsed, 4096)

  /** Retain at most maximumTargets request identities for this process. At
    * capacity, new requests hold instead of evicting an existing attempt budget
    * or forgetting its target binding. Known targets can still reach finality.
    */
  def boundedWithCapacity[F[_]: Sync](
      source: HotStuffMaintenanceSource[F],
      maxAttempts: Int,
      maxElapsed: Duration,
      maximumTargets: Int,
  ): F[Either[String, HotStuffMaintenanceProgress[F]]] =
    if maxAttempts <= 0 || maxElapsed.isNegative || maxElapsed.isZero || maximumTargets <= 0
    then
      Left[String, HotStuffMaintenanceProgress[F]](
        "maintenance progress requires positive local attempt, elapsed and target budgets",
      ).pure[F]
    else
      Ref
        .of[F, Map[Hash, AttemptState]](Map.empty)
        .map(ref =>
          Right[String, HotStuffMaintenanceProgress[F]](
            new HotStuffMaintenanceProgress[F]:
              def next(
                  request: HotStuffProposalInputRequest,
                  context: DomainContext,
              ): F[HotStuffMaintenanceDecision] =
                source.current(request).value.flatMap {
                  case Left(error) =>
                    (HotStuffMaintenanceDecision.Unavailable(
                      error.message,
                    ): HotStuffMaintenanceDecision).pure[F]
                  case Right(observation) =>
                    observation.target match
                      case None =>
                        (HotStuffMaintenanceDecision.Idle: HotStuffMaintenanceDecision)
                          .pure[F]
                      case Some(target) =>
                        val valid = DomainContext
                          .validate(context)
                          .isRight && target.context == context &&
                          target.context.chainId.asString == request.window.chainId.value &&
                          target.context.validatorSetHash == request.window.validatorSetHash.toUInt256
                        val finalized =
                          observation.finalization.flatMap(_.bestFinalized)
                        if !valid then
                          (HotStuffMaintenanceDecision.Unavailable(
                            "maintenance target differs from the current signing context",
                          ): HotStuffMaintenanceDecision).pure[F]
                        else if observation.finalization
                            .exists(_.safetyFaults.nonEmpty)
                        then
                          (HotStuffMaintenanceDecision.Unavailable(
                            "local finalization tracker reports a safety fault",
                          ): HotStuffMaintenanceDecision).pure[F]
                        else if finalized.exists(value =>
                            value.proposal.window.chainId != request.window.chainId,
                          )
                        then
                          (HotStuffMaintenanceDecision.Unavailable(
                            "local finalized proof belongs to another chain",
                          ): HotStuffMaintenanceDecision).pure[F]
                        else
                          ref.modify { previous =>
                            val known   = previous.get(target.requestId)
                            val first   = known.fold(request.now)(_.startedAt)
                            val raw     = Duration.between(first, request.now)
                            val elapsed =
                              if raw.isNegative then Duration.ZERO else raw
                            if known.isEmpty && previous.sizeIs >= maximumTargets
                            then
                              previous -> (HotStuffMaintenanceDecision.Stalled(
                                "maintenance target capacity is exhausted; existing request budgets are retained",
                              ): HotStuffMaintenanceDecision)
                            else if known.exists(_.target != target) then
                              previous -> (HotStuffMaintenanceDecision
                                .Unavailable(
                                  "maintenance request id was rebound to another target",
                                ): HotStuffMaintenanceDecision)
                            else if finalized.exists(
                                _.anchorHeight.toBigNat.toBigInt >= target.finalizedHeight.toBigNat.toBigInt,
                              )
                            then
                              previous.updated(
                                target.requestId,
                                known.getOrElse(AttemptState(target, first, 0)),
                              ) -> (HotStuffMaintenanceDecision
                                .TargetReached(
                                  target,
                                ): HotStuffMaintenanceDecision)
                            else if known.exists(
                                _.attempts >= maxAttempts,
                              ) || elapsed.compareTo(maxElapsed) >= 0
                            then
                              previous -> (HotStuffMaintenanceDecision.Stalled(
                                "maintenance finalized target has not been reached within the local progress budget",
                              ): HotStuffMaintenanceDecision)
                            else
                              val attempt = known.fold(1)(_.attempts + 1)
                              previous.updated(
                                target.requestId,
                                AttemptState(target, first, attempt),
                              ) ->
                                (HotStuffMaintenanceDecision.Requested(
                                  HotStuffMaintenanceAttempt(
                                    target,
                                    attempt,
                                    maxAttempts,
                                    elapsed,
                                  ),
                                ): HotStuffMaintenanceDecision)
                          }
                },
          ),
        )
