package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.util.UUID

import cats.Applicative
import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.node.gossip.{
  CanonicalRejection,
  ControlBatch,
  ControlIdempotencyKey,
  ControlOp,
  ExactKnownSetScope,
  PeerIdentity,
  StableArtifactId,
}

/** Policy for receiver-side proposal dependency backfill. */
final case class HotStuffProposalDependencyBackfillPolicy(
    maxIdsPerControlBatch: Int,
    maxAttemptsPerDependency: Int,
    maxTrackedAttempts: Int,
):
  require(
    maxIdsPerControlBatch > 0,
    "maxIdsPerControlBatch must be positive",
  )
  require(
    maxAttemptsPerDependency >= 0,
    "maxAttemptsPerDependency must be non-negative",
  )
  require(
    maxTrackedAttempts > 0,
    "maxTrackedAttempts must be positive",
  )

/** Companion defaults for receiver-side proposal dependency backfill. */
object HotStuffProposalDependencyBackfillPolicy:
  val default: HotStuffProposalDependencyBackfillPolicy =
    HotStuffProposalDependencyBackfillPolicy(
      maxIdsPerControlBatch =
        HotStuffPolicy.requestPolicy.maxProposalRequestIds,
      maxAttemptsPerDependency =
        HotStuffPolicy.requestPolicy.maxRetryAttemptsPerWindow + 1,
      maxTrackedAttempts =
        HotStuffPolicy.requestPolicy.maxProposalRequestIds * 1024,
    )

/** Structured receiver-side backfill diagnostic reason. */
enum HotStuffProposalDependencyBackfillDiagnosticReason:
  case Requested
  case ProactiveFanoutOnlySkipped
  case DuplicateSuppressed
  case RetryBudgetExceeded
  case ResolverRejected
  case ResolverFailed
  case ControlBatchRejected

/** Structured receiver-side backfill diagnostic. */
final case class HotStuffProposalDependencyBackfillDiagnostic(
    sourcePeer: PeerIdentity,
    proposalId: ProposalId,
    reason: HotStuffProposalDependencyBackfillDiagnosticReason,
    triggerReason: String,
    dependency: Option[HotStuffProposalApplicationDependency],
    scope: Option[ExactKnownSetScope],
    artifactId: Option[StableArtifactId],
    detail: Option[String],
)

/** Result of preparing receiver-side proposal dependency backfill requests. */
final case class HotStuffProposalDependencyBackfillResult(
    controlBatches: Vector[ControlBatch],
    requested: Vector[HotStuffProposalApplicationDependency],
    proactiveFanoutOnly: Vector[HotStuffProposalApplicationDependency],
    requiredProactiveFanoutOnly: Vector[
      HotStuffProposalApplicationDependency,
    ],
    diagnostics: Vector[HotStuffProposalDependencyBackfillDiagnostic],
)

/** Receiver-side proposal dependency backfill hook.
  *
  * Implementations prepare bounded `RequestByIdExact` control batches and
  * diagnostics. Embedders can deliver the returned batches to the source peer
  * without blocking the current vote-validation attempt on network I/O.
  */
trait HotStuffProposalDependencyBackfill[F[_]]:
  def requestMissingDependencies(
      sourcePeer: PeerIdentity,
      proposal: Proposal,
      dependencies: Vector[HotStuffProposalApplicationDependency],
      reason: String,
  ): F[HotStuffProposalDependencyBackfillResult]

  def resolveAndRequestMissingDependencies(
      sourcePeer: PeerIdentity,
      proposal: Proposal,
      reason: String,
  ): F[HotStuffProposalDependencyBackfillResult]

/** Companion for receiver-side proposal dependency backfill wiring. */
object HotStuffProposalDependencyBackfill:
  def disabled[F[_]: Applicative]: HotStuffProposalDependencyBackfill[F] =
    new HotStuffProposalDependencyBackfill[F]:
      override def requestMissingDependencies(
          sourcePeer: PeerIdentity,
          proposal: Proposal,
          dependencies: Vector[HotStuffProposalApplicationDependency],
          reason: String,
      ): F[HotStuffProposalDependencyBackfillResult] =
        HotStuffProposalDependencyBackfillResult(
          controlBatches = Vector.empty[ControlBatch],
          requested = Vector.empty[HotStuffProposalApplicationDependency],
          proactiveFanoutOnly =
            Vector.empty[HotStuffProposalApplicationDependency],
          requiredProactiveFanoutOnly =
            Vector.empty[HotStuffProposalApplicationDependency],
          diagnostics =
            Vector.empty[HotStuffProposalDependencyBackfillDiagnostic],
        ).pure[F]

      override def resolveAndRequestMissingDependencies(
          sourcePeer: PeerIdentity,
          proposal: Proposal,
          reason: String,
      ): F[HotStuffProposalDependencyBackfillResult] =
        requestMissingDependencies(
          sourcePeer = sourcePeer,
          proposal = proposal,
          dependencies = Vector.empty[HotStuffProposalApplicationDependency],
          reason = reason,
        )

  def create[F[_]: Sync](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      policy: HotStuffProposalDependencyBackfillPolicy,
  ): F[HotStuffProposalDependencyBackfill[F]] =
    createWithRequestLimits(
      config = config,
      policy = policy,
      requestByIdLimitForScope = _ =>
        policy.maxIdsPerControlBatch
          .asRight[CanonicalRejection.ControlBatchRejected],
    )

  def createWithRequestLimits[F[_]: Sync](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      policy: HotStuffProposalDependencyBackfillPolicy,
      requestByIdLimitForScope: ExactKnownSetScope => Either[
        CanonicalRejection.ControlBatchRejected,
        Int,
      ],
  ): F[HotStuffProposalDependencyBackfill[F]] =
    createWithIdempotencyKeysAndRequestLimits(
      config = config,
      policy = policy,
      nextIdempotencyKey = Sync[F].delay(UUID.randomUUID().toString),
      requestByIdLimitForScope = requestByIdLimitForScope,
    )

  private[hotstuff] def createWithIdempotencyKeys[F[_]: Sync](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      policy: HotStuffProposalDependencyBackfillPolicy,
      nextIdempotencyKey: F[String],
  ): F[HotStuffProposalDependencyBackfill[F]] =
    createWithIdempotencyKeysAndRequestLimits(
      config = config,
      policy = policy,
      nextIdempotencyKey = nextIdempotencyKey,
      requestByIdLimitForScope = _ =>
        policy.maxIdsPerControlBatch
          .asRight[CanonicalRejection.ControlBatchRejected],
    )

  private[hotstuff] def createWithIdempotencyKeysAndRequestLimits[F[_]: Sync](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      policy: HotStuffProposalDependencyBackfillPolicy,
      nextIdempotencyKey: F[String],
      requestByIdLimitForScope: ExactKnownSetScope => Either[
        CanonicalRejection.ControlBatchRejected,
        Int,
      ],
  ): F[HotStuffProposalDependencyBackfill[F]] =
    Ref
      .of[F, BackfillAttemptState](BackfillAttemptState.empty)
      .map(attempts =>
        new DefaultBackfill(
          config,
          policy,
          attempts,
          nextIdempotencyKey,
          requestByIdLimitForScope,
        ),
      )

  private final case class BackfillAttemptKey(
      sourcePeer: PeerIdentity,
      proposalId: ProposalId,
      scope: ExactKnownSetScope,
      artifactId: StableArtifactId,
  )

  private final case class PreparedBatchInput(
      scope: ExactKnownSetScope,
      dependencies: Vector[HotStuffProposalApplicationDependency],
      idempotencyKey: ControlIdempotencyKey,
  )

  private final case class BackfillAttemptState(
      counts: Map[BackfillAttemptKey, Int],
      insertionOrder: Vector[BackfillAttemptKey],
  )

  private object BackfillAttemptState:
    val empty: BackfillAttemptState =
      BackfillAttemptState(
        counts = Map.empty[BackfillAttemptKey, Int],
        insertionOrder = Vector.empty[BackfillAttemptKey],
      )

  private final class DefaultBackfill[F[_]: Sync](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      policy: HotStuffProposalDependencyBackfillPolicy,
      attempts: Ref[F, BackfillAttemptState],
      nextIdempotencyKey: F[String],
      requestByIdLimitForScope: ExactKnownSetScope => Either[
        CanonicalRejection.ControlBatchRejected,
        Int,
      ],
  ) extends HotStuffProposalDependencyBackfill[F]:

    override def requestMissingDependencies(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        dependencies: Vector[HotStuffProposalApplicationDependency],
        reason: String,
    ): F[HotStuffProposalDependencyBackfillResult] =
      val filtered =
        HotStuffProposalApplicationDependency.filterForGenericReceiverBackfill(
          dependencies,
        )
      val proactiveDiagnostics =
        filtered.proactiveFanoutOnly.map(dependency =>
          diagnostic(
            sourcePeer = sourcePeer,
            proposal = proposal,
            reason =
              HotStuffProposalDependencyBackfillDiagnosticReason.ProactiveFanoutOnlySkipped,
            triggerReason = reason,
            dependency = Some(dependency),
            detail = Some(renderCriticality(dependency)),
            scope = None,
            artifactId = None,
          ),
        )
      val (uniqueDependencies, duplicateDiagnostics) =
        suppressDuplicates(
          sourcePeer = sourcePeer,
          proposal = proposal,
          dependencies = filtered.requestByIdBackfillable,
          reason = reason,
        )

      prepareBatchInputs(
        sourcePeer = sourcePeer,
        proposal = proposal,
        dependencies = uniqueDependencies,
        reason = reason,
      ).flatMap: (preparedInputs, batchDiagnostics) =>
        markAttempts(
          sourcePeer = sourcePeer,
          proposal = proposal,
          dependencies = preparedInputs.flatMap(_.dependencies),
          reason = reason,
        ).map: (requestable, retryDiagnostics) =>
          val requestableKeys =
            requestable
              .map(dependency => keyFor(sourcePeer, proposal, dependency))
              .toSet
          val successful =
            preparedInputs.flatMap: input =>
              val dependencies =
                input.dependencies.filter(dependency =>
                  requestableKeys.contains(
                    keyFor(sourcePeer, proposal, dependency),
                  ),
                )
              if dependencies.isEmpty then
                Vector.empty[
                  (
                      ControlBatch,
                      Vector[HotStuffProposalApplicationDependency],
                  ),
                ]
              else
                Vector(
                  ControlBatch(
                    idempotencyKey = input.idempotencyKey,
                    ops = Vector(
                      ControlOp.RequestByIdExact(
                        input.scope,
                        dependencies.map(_.artifactId),
                      ),
                    ),
                  ) -> dependencies,
                )
          val requested = successful.flatMap(_._2)
          HotStuffProposalDependencyBackfillResult(
            controlBatches = successful.map(_._1),
            requested = requested,
            proactiveFanoutOnly = filtered.proactiveFanoutOnly,
            requiredProactiveFanoutOnly = filtered.requiredProactiveFanoutOnly,
            diagnostics = proactiveDiagnostics ++ duplicateDiagnostics ++
              retryDiagnostics ++ batchDiagnostics,
          )

    override def resolveAndRequestMissingDependencies(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        reason: String,
    ): F[HotStuffProposalDependencyBackfillResult] =
      HotStuffProposalApplicationDependencyRuntimeConfig
        .resolveForReceiver(config, proposal)
        .flatMap:
          case HotStuffProposalApplicationDependencyResolution.Resolved(
                dependencies,
              ) =>
            requestMissingDependencies(
              sourcePeer = sourcePeer,
              proposal = proposal,
              dependencies = dependencies,
              reason = reason,
            )
          case HotStuffProposalApplicationDependencyResolution.ResolverRejected(
                _,
                rejectionReason,
                detail,
              ) =>
            emptyWithDiagnostic(
              sourcePeer = sourcePeer,
              proposal = proposal,
              reason =
                HotStuffProposalDependencyBackfillDiagnosticReason.ResolverRejected,
              triggerReason = reason,
              detail = Some(renderResolverDetail(rejectionReason, detail)),
            ).pure[F]
          case HotStuffProposalApplicationDependencyResolution.ResolverFailed(
                _,
                detail,
              ) =>
            emptyWithDiagnostic(
              sourcePeer = sourcePeer,
              proposal = proposal,
              reason =
                HotStuffProposalDependencyBackfillDiagnosticReason.ResolverFailed,
              triggerReason = reason,
              detail = detail,
            ).pure[F]

    private def suppressDuplicates(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        dependencies: Vector[HotStuffProposalApplicationDependency],
        reason: String,
    ): (
        Vector[HotStuffProposalApplicationDependency],
        Vector[HotStuffProposalDependencyBackfillDiagnostic],
    ) =
      val initial = (
        Set.empty[BackfillAttemptKey],
        Vector.empty[HotStuffProposalApplicationDependency],
        Vector.empty[HotStuffProposalDependencyBackfillDiagnostic],
      )
      val (_, uniqueDependencies, diagnostics) =
        dependencies.foldLeft(initial):
          case ((seen, unique, duplicateDiagnostics), dependency) =>
            val key = keyFor(sourcePeer, proposal, dependency)
            if seen.contains(key) then
              (
                seen,
                unique,
                duplicateDiagnostics :+ diagnostic(
                  sourcePeer = sourcePeer,
                  proposal = proposal,
                  reason =
                    HotStuffProposalDependencyBackfillDiagnosticReason.DuplicateSuppressed,
                  triggerReason = reason,
                  dependency = Some(dependency),
                  detail = None,
                  scope = None,
                  artifactId = None,
                ),
              )
            else (seen + key, unique :+ dependency, duplicateDiagnostics)
      uniqueDependencies -> diagnostics

    private def markAttempts(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        dependencies: Vector[HotStuffProposalApplicationDependency],
        reason: String,
    ): F[
      (
          Vector[HotStuffProposalApplicationDependency],
          Vector[HotStuffProposalDependencyBackfillDiagnostic],
      ),
    ] =
      attempts.modify: state =>
        val initial = (
          state,
          Vector.empty[HotStuffProposalApplicationDependency],
          Vector.empty[HotStuffProposalDependencyBackfillDiagnostic],
        )
        val (updatedState, requestable, diagnostics) =
          dependencies.foldLeft(initial):
            case ((currentState, allowed, attemptDiagnostics), dependency) =>
              val key     = keyFor(sourcePeer, proposal, dependency)
              val current = currentState.counts.getOrElse(key, 0)
              if current >= policy.maxAttemptsPerDependency then
                (
                  currentState,
                  allowed,
                  attemptDiagnostics :+ diagnostic(
                    sourcePeer = sourcePeer,
                    proposal = proposal,
                    reason =
                      HotStuffProposalDependencyBackfillDiagnosticReason.RetryBudgetExceeded,
                    triggerReason = reason,
                    dependency = Some(dependency),
                    detail = Some(
                      "max=" + policy.maxAttemptsPerDependency.toString +
                        " actual=" + (current + 1).toString,
                    ),
                    scope = None,
                    artifactId = None,
                  ),
                )
              else
                val nextState =
                  recordAttempt(
                    currentState = currentState,
                    key = key,
                    nextCount = current + 1,
                  )
                (
                  nextState,
                  allowed :+ dependency,
                  attemptDiagnostics :+ diagnostic(
                    sourcePeer = sourcePeer,
                    proposal = proposal,
                    reason =
                      HotStuffProposalDependencyBackfillDiagnosticReason.Requested,
                    triggerReason = reason,
                    dependency = Some(dependency),
                    detail = Some("attempt=" + (current + 1).toString),
                    scope = None,
                    artifactId = None,
                  ),
                )
        trimAttempts(updatedState) -> (requestable -> diagnostics)

    private def recordAttempt(
        currentState: BackfillAttemptState,
        key: BackfillAttemptKey,
        nextCount: Int,
    ): BackfillAttemptState =
      val known = currentState.counts.contains(key)
      val updated =
        currentState.copy(
          counts = currentState.counts.updated(key, nextCount),
          insertionOrder =
            if known then currentState.insertionOrder
            else currentState.insertionOrder :+ key,
        )
      updated

    private def trimAttempts(
        state: BackfillAttemptState,
    ): BackfillAttemptState =
      val overflow = state.counts.size - policy.maxTrackedAttempts
      if overflow <= 0 then state
      else
        val evicted = state.insertionOrder.take(overflow).toSet
        BackfillAttemptState(
          counts = state.counts -- evicted,
          insertionOrder = state.insertionOrder.drop(overflow),
        )

    private def prepareBatchInputs(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        dependencies: Vector[HotStuffProposalApplicationDependency],
        reason: String,
    ): F[
      (
          Vector[PreparedBatchInput],
          Vector[HotStuffProposalDependencyBackfillDiagnostic],
      ),
    ] =
      val (batchInputs, limitDiagnostics) =
        chunkByScopeLimits(
          sourcePeer = sourcePeer,
          proposal = proposal,
          dependencies = dependencies,
          reason = reason,
        )
      batchInputs
        .traverse: (scope, ids) =>
          nextIdempotencyKey.map: idempotencyKey =>
            ControlIdempotencyKey
              .parse(idempotencyKey)
              .bimap(
                rejection =>
                  ids -> diagnostic(
                    sourcePeer = sourcePeer,
                    proposal = proposal,
                    reason =
                      HotStuffProposalDependencyBackfillDiagnosticReason.ControlBatchRejected,
                    triggerReason = reason,
                    dependency = None,
                    scope = Some(scope),
                    artifactId = None,
                    detail = Some(
                      rejection.reason +
                        rejection.detail.fold("")(value => ":" + value),
                    ),
                  ),
                key =>
                  PreparedBatchInput(
                    scope = scope,
                    dependencies = ids,
                    idempotencyKey = key,
                  ),
              )
        .map: results =>
          val (inputs, diagnostics) =
            results.foldLeft(
              (
                Vector.empty[PreparedBatchInput],
                Vector.empty[HotStuffProposalDependencyBackfillDiagnostic],
              ),
            ):
              case ((inputs, diagnostics), Right(input)) =>
                (inputs :+ input, diagnostics)
              case (
                    (inputs, diagnostics),
                    Left((_, diagnostic)),
                  ) =>
                (inputs, diagnostics :+ diagnostic)
          (
            inputs,
            limitDiagnostics ++ diagnostics,
          )

    private def chunkByScopeLimits(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        dependencies: Vector[HotStuffProposalApplicationDependency],
        reason: String,
    ): (
        Vector[
          (
              ExactKnownSetScope,
              Vector[HotStuffProposalApplicationDependency],
          ),
        ],
        Vector[HotStuffProposalDependencyBackfillDiagnostic],
    ) =
      groupByScope(dependencies).foldLeft(
        (
          Vector.empty[
            (
                ExactKnownSetScope,
                Vector[HotStuffProposalApplicationDependency],
            ),
          ],
          Vector.empty[HotStuffProposalDependencyBackfillDiagnostic],
        ),
      ):
        case ((inputs, diagnostics), (scope, scopedDependencies)) =>
          requestByIdLimitForScope(scope) match
            case Left(rejection) =>
              inputs -> (diagnostics :+ diagnostic(
                sourcePeer = sourcePeer,
                proposal = proposal,
                reason =
                  HotStuffProposalDependencyBackfillDiagnosticReason.ControlBatchRejected,
                triggerReason = reason,
                dependency = None,
                scope = Some(scope),
                artifactId = None,
                detail = Some(renderControlRejection(rejection)),
              ))
            case Right(limit) if limit <= 0 =>
              inputs -> (diagnostics :+ diagnostic(
                sourcePeer = sourcePeer,
                proposal = proposal,
                reason =
                  HotStuffProposalDependencyBackfillDiagnosticReason.ControlBatchRejected,
                triggerReason = reason,
                dependency = None,
                scope = Some(scope),
                artifactId = None,
                detail = Some(
                  "invalidRequestByIdLimit:scope=" + scope.topic.value +
                    " limit=" + limit.toString,
                ),
              ))
            case Right(limit) =>
              val effectiveLimit = policy.maxIdsPerControlBatch.min(limit)
              val chunks =
                scopedDependencies
                  .grouped(effectiveLimit)
                  .toVector
                  .map(chunk => scope -> chunk)
              (inputs ++ chunks) -> diagnostics

    private def groupByScope(
        dependencies: Vector[HotStuffProposalApplicationDependency],
    ): Vector[
      (
          ExactKnownSetScope,
          Vector[HotStuffProposalApplicationDependency],
      ),
    ] =
      dependencies.foldLeft(
        Vector.empty[
          (
              ExactKnownSetScope,
              Vector[HotStuffProposalApplicationDependency],
          ),
        ],
      ):
        case (acc, dependency) =>
          val index = acc.indexWhere(_._1 === dependency.exactScope)
          if index < 0 then acc :+ (dependency.exactScope -> Vector(dependency))
          else
            val (scope, dependencies) = acc(index)
            acc.updated(index, scope -> (dependencies :+ dependency))

    private def keyFor(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        dependency: HotStuffProposalApplicationDependency,
    ): BackfillAttemptKey =
      BackfillAttemptKey(
        sourcePeer = sourcePeer,
        proposalId = proposal.proposalId,
        scope = dependency.exactScope,
        artifactId = dependency.artifactId,
      )

    private def diagnostic(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        reason: HotStuffProposalDependencyBackfillDiagnosticReason,
        triggerReason: String,
        dependency: Option[HotStuffProposalApplicationDependency],
        detail: Option[String],
        scope: Option[ExactKnownSetScope],
        artifactId: Option[StableArtifactId],
    ): HotStuffProposalDependencyBackfillDiagnostic =
      HotStuffProposalDependencyBackfillDiagnostic(
        sourcePeer = sourcePeer,
        proposalId = proposal.proposalId,
        reason = reason,
        triggerReason = triggerReason,
        dependency = dependency,
        scope = scope.orElse(dependency.map(_.exactScope)),
        artifactId = artifactId.orElse(dependency.map(_.artifactId)),
        detail = detail,
      )

    private def emptyWithDiagnostic(
        sourcePeer: PeerIdentity,
        proposal: Proposal,
        reason: HotStuffProposalDependencyBackfillDiagnosticReason,
        triggerReason: String,
        detail: Option[String],
    ): HotStuffProposalDependencyBackfillResult =
      HotStuffProposalDependencyBackfillResult(
        controlBatches = Vector.empty[ControlBatch],
        requested = Vector.empty[HotStuffProposalApplicationDependency],
        proactiveFanoutOnly =
          Vector.empty[HotStuffProposalApplicationDependency],
        requiredProactiveFanoutOnly =
          Vector.empty[HotStuffProposalApplicationDependency],
        diagnostics = Vector(
          diagnostic(
            sourcePeer = sourcePeer,
            proposal = proposal,
            reason = reason,
            triggerReason = triggerReason,
            dependency = None,
            detail = detail,
            scope = None,
            artifactId = None,
          ),
        ),
      )

    private def renderCriticality(
        dependency: HotStuffProposalApplicationDependency,
    ): String =
      dependency.criticality match
        case HotStuffProposalDependencyCriticality.RequiredForVote =>
          "requiredForVote"
        case HotStuffProposalDependencyCriticality.HelpfulForMaterialization =>
          "helpfulForMaterialization"

    private def renderResolverDetail(
        reason: String,
        detail: Option[String],
    ): String =
      detail.fold(reason)(value => reason + ":" + value)

    private def renderControlRejection(
        rejection: CanonicalRejection.ControlBatchRejected,
    ): String =
      rejection.detail.fold(rejection.reason)(value =>
        rejection.reason + ":" + value,
      )
