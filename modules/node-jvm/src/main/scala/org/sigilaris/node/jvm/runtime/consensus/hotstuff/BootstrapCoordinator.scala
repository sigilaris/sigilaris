package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import cats.effect.kernel.Sync
import cats.effect.Ref
import cats.syntax.all.*

import org.sigilaris.core.application.scheduling.SchedulingClassification
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.datatype.BigNat
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockQuery}
import org.sigilaris.node.gossip.{
  CanonicalRejection,
  ChainId,
  ControlBatch,
  StableArtifactId,
}
import org.sigilaris.node.gossip.tx.TxRuntimePolicy

/** Controls retry timing for bootstrap coordination attempts.
  *
  * @param baseDelay the base delay between retries
  * @param maxDelay the maximum delay cap
  */
final case class BootstrapRetryPolicy(
    baseDelay: Duration,
    maxDelay: Duration,
):
  require(!baseDelay.isNegative, "baseDelay must be non-negative")
  require(!maxDelay.isNegative, "maxDelay must be non-negative")

  /** Computes the next retry instant using linear backoff capped at maxDelay. */
  def nextRetryAt(
      now: Instant,
      attempt: Int,
  ): Instant =
    val cappedAttempt = attempt.max(1).toLong
    val rawMillis     = baseDelay.toMillis * cappedAttempt
    val cappedMillis  = Math.min(rawMillis, maxDelay.toMillis)
    now.plusMillis(cappedMillis)

/** Companion for `BootstrapRetryPolicy`. */
object BootstrapRetryPolicy:
  /** A default retry policy with 5-second base delay and 1-minute max. */
  val boundedDefault: BootstrapRetryPolicy =
    BootstrapRetryPolicy(
      baseDelay = Duration.ofSeconds(5L),
      maxDelay = Duration.ofMinutes(1L),
    )

/** Represents a failure during bootstrap coordination.
  *
  * @param reason a short identifier for the failure
  * @param detail optional human-readable detail
  */
final case class BootstrapCoordinatorFailure(
    reason: String,
    detail: Option[String],
)

/** Companion for `BootstrapCoordinatorFailure`. */
object BootstrapCoordinatorFailure:
  /** Creates a coordinator failure from a snapshot sync failure. */
  def fromSnapshotFailure(
      failure: SnapshotSyncFailure,
  ): BootstrapCoordinatorFailure =
    BootstrapCoordinatorFailure(
      reason = failure.reason,
      detail = failure.detail,
    )

  /** Creates a coordinator failure from a validation failure. */
  def fromValidation(
      failure: HotStuffValidationFailure,
  ): BootstrapCoordinatorFailure =
    BootstrapCoordinatorFailure(
      reason = failure.reason,
      detail = failure.detail,
    )

/** The result of assessing a proposal's readiness for catch-up voting.
  *
  * @param voteReadiness whether the node is ready to vote on this proposal
  * @param controlBatch an optional gossip control batch to request missing data
  */
final case class ProposalCatchUpAssessment(
    voteReadiness: BootstrapVoteReadiness,
    controlBatch: Option[ControlBatch],
)

/** Evaluates whether a proposal can be voted on during forward catch-up. */
trait ProposalCatchUpReadiness[F[_]]:
  /** Assesses the readiness to vote on a given proposal. */
  def assess(
      proposal: Proposal,
  ): F[Either[BootstrapCoordinatorFailure, ProposalCatchUpAssessment]]

/** Companion for `ProposalCatchUpReadiness`, providing static factory methods. */
object ProposalCatchUpReadiness:
  /** Creates readiness that always returns the given static assessment. */
  def static[F[_]: Sync](
      assessment: ProposalCatchUpAssessment,
  ): ProposalCatchUpReadiness[F] =
    new ProposalCatchUpReadiness[F]:
      override def assess(
          proposal: Proposal,
      ): F[Either[BootstrapCoordinatorFailure, ProposalCatchUpAssessment]] =
        assessment.asRight[BootstrapCoordinatorFailure].pure[F]

  /** Creates readiness that always reports as ready to vote. */
  def ready[F[_]: Sync]: ProposalCatchUpReadiness[F] =
    static(
      ProposalCatchUpAssessment(
        voteReadiness = BootstrapVoteReadiness.Ready,
        controlBatch = None,
      ),
    )

  /** Creates readiness that always reports as held for the given reason. */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def held[F[_]: Sync](
      reason: String,
      controlBatch: Option[ControlBatch] = None,
  ): ProposalCatchUpReadiness[F] =
    static(
      ProposalCatchUpAssessment(
        voteReadiness = BootstrapVoteReadiness.Held(reason),
        controlBatch = controlBatch,
      ),
    )

  /** Creates readiness that always fails with the given reason. */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def failure[F[_]: Sync](
      reason: String,
      detail: Option[String] = None,
  ): ProposalCatchUpReadiness[F] =
    new ProposalCatchUpReadiness[F]:
      override def assess(
          proposal: Proposal,
      ): F[Either[BootstrapCoordinatorFailure, ProposalCatchUpAssessment]] =
        BootstrapCoordinatorFailure(reason, detail)
          .asLeft[ProposalCatchUpAssessment]
          .pure[F]

  /** Creates readiness that validates proposals against a block query and requests missing transactions. */
  def fromBlockQuery[
      F[_]: Sync,
      TxRef: ByteEncoder: Hash,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      validatorSet: ValidatorSet,
      knownTxIds: F[Set[StableArtifactId]],
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
      txPolicy: TxRuntimePolicy,
      idempotencyKeyFor: Proposal => String,
  )(
      classifyTx: TxRef => SchedulingClassification,
  ): ProposalCatchUpReadiness[F] =
    new ProposalCatchUpReadiness[F]:
      override def assess(
          proposal: Proposal,
      ): F[Either[BootstrapCoordinatorFailure, ProposalCatchUpAssessment]] =
        knownTxIds.flatMap: known =>
          val missingTxIds =
            HotStuffProposalTxSync.missingTxIds(
              proposal = proposal,
              knownTxIds = known,
            )
          if missingTxIds.nonEmpty then
            HotStuffProposalTxSync
              .controlBatchForProposal(
                proposal = proposal,
                knownTxIds = known,
                idempotencyKey = idempotencyKeyFor(proposal),
                txPolicy = txPolicy,
              ) match
              case Left(rejection) =>
                BootstrapCoordinatorFailure(
                  reason = rejection.reason,
                  detail = rejection.detail,
                ).asLeft[ProposalCatchUpAssessment].pure[F]
              case Right(controlBatch) =>
                ProposalCatchUpAssessment(
                  voteReadiness =
                    BootstrapVoteReadiness.Held("missingTxPayload"),
                  controlBatch = controlBatch,
                ).asRight[BootstrapCoordinatorFailure].pure[F]
          else
            HotStuffRuntimeScheduling
              .validateProposalViewFromBlockQuery(
                proposal = proposal,
                validatorSet = validatorSet,
                blockQuery = blockQuery,
              )(classifyTx)
              .map:
                case Left(failure)
                    if failure.reason === "proposalBlockViewUnavailable" =>
                  ProposalCatchUpAssessment(
                    voteReadiness =
                      BootstrapVoteReadiness.Held("proposalViewUnavailable"),
                    controlBatch = None,
                  ).asRight[BootstrapCoordinatorFailure]
                case Left(failure) =>
                  BootstrapCoordinatorFailure
                    .fromValidation(failure)
                    .asLeft[ProposalCatchUpAssessment]
                case Right(_) =>
                  ProposalCatchUpAssessment(
                    voteReadiness = BootstrapVoteReadiness.Ready,
                    controlBatch = None,
                  ).asRight[BootstrapCoordinatorFailure]

/** The result of a forward catch-up operation, tracking applied and queued proposals. */
final case class ForwardCatchUpResult(
    applied: Vector[Proposal],
    queued: Vector[Proposal],
    controlBatches: Vector[ControlBatch],
    frontierBlockId: BlockId,
    frontierHeight: BlockHeight,
    voteReadiness: BootstrapVoteReadiness,
)

/** Plans forward catch-up by applying replayed and live proposals in chain order. */
object HotStuffForwardCatchUp:

  /** Plans a forward catch-up from an anchor, applying replayed and live proposals sequentially. */
  def plan[F[_]: Sync](
      anchor: FinalizedAnchorSuggestion,
      replayed: Vector[Proposal],
      live: Vector[Proposal],
      readiness: ProposalCatchUpReadiness[F],
  ): F[Either[BootstrapCoordinatorFailure, ForwardCatchUpResult]] =
    val ordered =
      normalizeReplayWindow(
        anchorBlockId = anchor.anchorBlockId,
        anchorHeight = anchor.anchorHeight,
        proposals = replayed ++ live,
      )

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def loop(
        frontierBlockId: BlockId,
        frontierHeight: BlockHeight,
        remaining: Vector[Proposal],
        applied: Vector[Proposal],
        controlBatches: Vector[ControlBatch],
    ): F[Either[BootstrapCoordinatorFailure, ForwardCatchUpResult]] =
      val expectedHeight = increment(frontierHeight)
      remaining.find(proposal =>
        proposal.block.parent.contains(frontierBlockId) &&
          proposal.block.height.toBigNat === expectedHeight.toBigNat,
      ) match
        case None =>
          val readinessState =
            if remaining.isEmpty then BootstrapVoteReadiness.Ready
            else BootstrapVoteReadiness.Held("proposalReplayGap")
          ForwardCatchUpResult(
            applied = applied,
            queued = remaining.sortBy(proposal =>
              (proposal.block.height, proposal.proposalId.toHexLower),
            ),
            controlBatches = controlBatches,
            frontierBlockId = frontierBlockId,
            frontierHeight = frontierHeight,
            voteReadiness = readinessState,
          ).asRight[BootstrapCoordinatorFailure].pure[F]
        case Some(nextProposal) =>
          readiness
            .assess(nextProposal)
            .flatMap:
              case Left(error) =>
                error.asLeft[ForwardCatchUpResult].pure[F]
              case Right(assessment) =>
                val nextRemaining =
                  remaining.filterNot(_.proposalId === nextProposal.proposalId)
                val nextBatches =
                  assessment.controlBatch.fold(controlBatches)(
                    controlBatches :+ _,
                  )
                assessment.voteReadiness match
                  case held @ BootstrapVoteReadiness.Held(_) =>
                    ForwardCatchUpResult(
                      applied = applied,
                      queued = (nextProposal +: nextRemaining)
                        .sortBy(proposal =>
                          (
                            proposal.block.height,
                            proposal.proposalId.toHexLower,
                          ),
                        ),
                      controlBatches = nextBatches,
                      frontierBlockId = frontierBlockId,
                      frontierHeight = frontierHeight,
                      voteReadiness = held,
                    ).asRight[BootstrapCoordinatorFailure].pure[F]
                  case BootstrapVoteReadiness.Ready =>
                    loop(
                      frontierBlockId = nextProposal.targetBlockId,
                      frontierHeight = nextProposal.block.height,
                      remaining = nextRemaining,
                      applied = applied :+ nextProposal,
                      controlBatches = nextBatches,
                    )

    loop(
      frontierBlockId = anchor.anchorBlockId,
      frontierHeight = anchor.anchorHeight,
      remaining = ordered,
      applied = Vector.empty[Proposal],
      controlBatches = Vector.empty[ControlBatch],
    )

  private def increment(
      height: BlockHeight,
  ): BlockHeight =
    BlockHeight(BigNat.add(height.toBigNat, BigNat.One))

  private def dedupeByProposalId(
      proposals: Vector[Proposal],
  ): Vector[Proposal] =
    proposals
      .foldLeft(Map.empty[ProposalId, Proposal]): (acc, proposal) =>
        acc.updatedWith(proposal.proposalId):
          case current @ Some(_) => current
          case None              => Some(proposal)
      .values
      .toVector

  private def normalizeReplayWindow(
      anchorBlockId: BlockId,
      anchorHeight: BlockHeight,
      proposals: Vector[Proposal],
  ): Vector[Proposal] =
    val deduped =
      dedupeByProposalId(proposals)
        .sortBy(proposal =>
          (
            proposal.block.height,
            proposal.window.view,
            proposal.proposalId.toHexLower,
          ),
        )
    val byBlockId =
      deduped.iterator
        .map(proposal => proposal.targetBlockId -> proposal)
        .toMap

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def pathToAnchor(
        proposal: Proposal,
        acc: Vector[Proposal],
    ): Option[Vector[Proposal]] =
      if proposal.block.parent.contains(anchorBlockId) &&
        proposal.block.height.toBigNat === increment(anchorHeight).toBigNat
      then (proposal +: acc).some
      else
        proposal.block.parent.flatMap(byBlockId.get) match
          case Some(parent)
              if proposal.block.height.toBigNat === increment(
                parent.block.height,
              ).toBigNat =>
            pathToAnchor(parent, proposal +: acc)
          case _ =>
            none[Vector[Proposal]]

    val canonicalChain =
      deduped
        .flatMap(proposal => pathToAnchor(proposal, Vector.empty))
        .maxByOption(chain =>
          chain.lastOption.fold(
            (anchorHeight, HotStuffView.unsafeFromLong(0L), ""),
          ): leaf =>
            (
              leaf.block.height,
              leaf.window.view,
              leaf.proposalId.toHexLower,
            ),
        )
        .getOrElse(Vector.empty[Proposal])

    val canonicalIds =
      canonicalChain.iterator.map(_.proposalId).toSet
    val canonicalFrontierHeight =
      canonicalChain.lastOption.fold(anchorHeight)(_.block.height)
    val unresolvedHigher =
      deduped.filter(proposal =>
        !canonicalIds.contains(proposal.proposalId) &&
          Ordering[BlockHeight]
            .gt(proposal.block.height, canonicalFrontierHeight),
      )

    canonicalChain ++ unresolvedHigher

/** Coordinates the full bootstrap process: discovery, snapshot sync, and forward catch-up. */
trait BootstrapCoordinator[F[_]] extends BootstrapDiagnosticsSource[F]:
  /** Discovers finalized anchor suggestions from available peer sessions. */
  def discover(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      now: Instant,
  ): F[Either[BootstrapCoordinatorFailure, Option[FinalizedAnchorSuggestion]]]

  /** Runs the full bootstrap sequence for a given chain. */
  def bootstrap(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
      liveProposals: Vector[Proposal],
  ): F[Either[BootstrapCoordinatorFailure, BootstrapCoordinatorResult]]

/** The successful result of a full bootstrap coordination run. */
final case class BootstrapCoordinatorResult(
    anchor: FinalizedAnchorSuggestion,
    snapshot: SnapshotSyncResult,
    forwardCatchUp: ForwardCatchUpResult,
    diagnostics: BootstrapDiagnostics,
)

/** Companion for `BootstrapCoordinator`. */
object BootstrapCoordinator:
  /** Creates a bootstrap coordinator without historical backfill. */
  def create[F[_]: Sync](
      retryPolicy: BootstrapRetryPolicy,
      validatorSetLookup: ValidatorSetLookup[F],
      finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
      snapshotCoordinator: SnapshotCoordinator[F],
      proposalReplay: ProposalReplayService[F],
      readiness: ProposalCatchUpReadiness[F],
      forwardStore: ForwardCatchUpStore[F],
  ): F[BootstrapCoordinator[F]] =
    createWithBackfill(
      retryPolicy = retryPolicy,
      validatorSetLookup = validatorSetLookup,
      finalizedAnchorSuggestions = finalizedAnchorSuggestions,
      snapshotCoordinator = snapshotCoordinator,
      proposalReplay = proposalReplay,
      readiness = readiness,
      forwardStore = forwardStore,
      historicalBackfill = HistoricalBackfillWorker.disabled[F],
    )

  /** Creates a bootstrap coordinator with historical backfill support. */
  def createWithBackfill[F[_]: Sync](
      retryPolicy: BootstrapRetryPolicy,
      validatorSetLookup: ValidatorSetLookup[F],
      finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
      snapshotCoordinator: SnapshotCoordinator[F],
      proposalReplay: ProposalReplayService[F],
      readiness: ProposalCatchUpReadiness[F],
      forwardStore: ForwardCatchUpStore[F],
      historicalBackfill: HistoricalBackfillWorker[F],
  ): F[BootstrapCoordinator[F]] =
    Ref
      .of[F, BootstrapCoordinatorState](BootstrapCoordinatorState.empty)
      .map: stateRef =>
        new InMemoryBootstrapCoordinator[F](
          retryPolicy = retryPolicy,
          validatorSetLookup = validatorSetLookup,
          finalizedAnchorSuggestions = finalizedAnchorSuggestions,
          snapshotCoordinator = snapshotCoordinator,
          proposalReplay = proposalReplay,
          readiness = readiness,
          forwardStore = forwardStore,
          historicalBackfill = historicalBackfill,
          ref = stateRef,
        )

private final case class BootstrapCoordinatorChainState(
    bestFinalized: Option[SnapshotAnchor],
    selectedAnchor: Option[SnapshotAnchor],
    pinnedAnchor: Option[SnapshotAnchor],
    pinnedSuggestion: Option[FinalizedAnchorSuggestion],
    voteReadiness: BootstrapVoteReadiness,
    finalizationSafetyFaults: Vector[FinalizedAnchorSafetyFault],
)

private object BootstrapCoordinatorChainState:
  val empty: BootstrapCoordinatorChainState =
    BootstrapCoordinatorChainState(
      bestFinalized = None,
      selectedAnchor = None,
      pinnedAnchor = None,
      pinnedSuggestion = None,
      voteReadiness = BootstrapVoteReadiness.Held("snapshotPending"),
      finalizationSafetyFaults = Vector.empty[FinalizedAnchorSafetyFault],
    )

private final case class BootstrapCoordinatorState(
    phase: BootstrapPhase,
    chains: Map[ChainId, BootstrapCoordinatorChainState],
    retryAttempts: Int,
    nextRetryAt: Option[Instant],
    lastFailure: Option[String],
)

private object BootstrapCoordinatorState:
  val empty: BootstrapCoordinatorState =
    BootstrapCoordinatorState(
      phase = BootstrapPhase.Discovery,
      chains = Map.empty[ChainId, BootstrapCoordinatorChainState],
      retryAttempts = 0,
      nextRetryAt = None,
      lastFailure = None,
    )

private final class InMemoryBootstrapCoordinator[F[_]: Sync](
    retryPolicy: BootstrapRetryPolicy,
    validatorSetLookup: ValidatorSetLookup[F],
    finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
    snapshotCoordinator: SnapshotCoordinator[F],
    proposalReplay: ProposalReplayService[F],
    readiness: ProposalCatchUpReadiness[F],
    forwardStore: ForwardCatchUpStore[F],
    historicalBackfill: HistoricalBackfillWorker[F],
    ref: Ref[F, BootstrapCoordinatorState],
) extends BootstrapCoordinator[F]:

  override def current: F[BootstrapDiagnostics] =
    (ref.get, historicalBackfill.current).mapN(renderDiagnostics)

  override def discover(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      now: Instant,
  ): F[Either[BootstrapCoordinatorFailure, Option[FinalizedAnchorSuggestion]]] =
    fetchVerified(chainId, sessions).flatMap:
      case Left(error) =>
        updateFailure(chainId, now, error.reason, error.detail, Vector.empty) *>
          error.asLeft[Option[FinalizedAnchorSuggestion]].pure[F]
      case Right(None) =>
        updateNoCandidate(chainId, now) *>
          Option
            .empty[FinalizedAnchorSuggestion]
            .asRight[BootstrapCoordinatorFailure]
            .pure[F]
      case Right(Some((suggestion, faults))) =>
        ref
          .update: state =>
            val chainState = state.chains.getOrElse(
              chainId,
              BootstrapCoordinatorChainState.empty,
            )
            state.copy(
              chains = state.chains.updated(
                chainId,
                chainState.copy(
                  bestFinalized = Some(suggestion.snapshotAnchor),
                  finalizationSafetyFaults = faults,
                ),
              ),
              retryAttempts = 0,
              nextRetryAt = None,
              lastFailure = None,
            )
          *> suggestion.some
            .asRight[BootstrapCoordinatorFailure]
            .pure[F]

  override def bootstrap(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
      liveProposals: Vector[Proposal],
  ): F[Either[BootstrapCoordinatorFailure, BootstrapCoordinatorResult]] =
    pinnedSuggestion(chainId).flatMap:
      case Some(anchor) =>
        runPinnedBootstrap(chainId, anchor, sessions, startedAt, liveProposals)
      case None =>
        discover(chainId, sessions, startedAt).flatMap:
          case Left(error) =>
            error.asLeft[BootstrapCoordinatorResult].pure[F]
          case Right(None) =>
            BootstrapCoordinatorFailure(
              reason = "noVerifiableFinalizedAnchor",
              detail = None,
            ).asLeft[BootstrapCoordinatorResult].pure[F]
          case Right(Some(anchor)) =>
            pinAnchor(chainId, anchor) *>
              runPinnedBootstrap(
                chainId,
                anchor,
                sessions,
                startedAt,
                liveProposals,
              )

  private def runPinnedBootstrap(
      chainId: ChainId,
      anchor: FinalizedAnchorSuggestion,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
      liveProposals: Vector[Proposal],
  ): F[Either[BootstrapCoordinatorFailure, BootstrapCoordinatorResult]] =
    updatePhase(chainId, BootstrapPhase.SnapshotSync, None) *>
      snapshotCoordinator
        .sync(anchor, sessions, startedAt)
        .flatMap:
          case Left(failure) =>
            val error = BootstrapCoordinatorFailure.fromSnapshotFailure(failure)
            updateFailure(
              chainId,
              startedAt,
              error.reason,
              error.detail,
              Vector.empty,
            ) *>
              error.asLeft[BootstrapCoordinatorResult].pure[F]
          case Right(snapshotResult) =>
            readReplay(chainId, anchor, sessions).flatMap:
              case Left(error) =>
                updateFailure(
                  chainId,
                  startedAt,
                  error.reason,
                  error.detail,
                  Vector.empty,
                ) *>
                  error.asLeft[BootstrapCoordinatorResult].pure[F]
              case Right(replayBatch) =>
                HotStuffForwardCatchUp
                  .plan(
                    anchor = anchor,
                    replayed = replayBatch,
                    live = liveProposals,
                    readiness = readiness,
                  )
                  .flatMap:
                    case Left(error) =>
                      updateFailure(
                        chainId,
                        startedAt,
                        error.reason,
                        error.detail,
                        Vector.empty,
                      ) *> error.asLeft[BootstrapCoordinatorResult].pure[F]
                    case Right(forward) =>
                      val phase =
                        forward.voteReadiness match
                          case BootstrapVoteReadiness.Ready
                              if forward.queued.isEmpty =>
                            BootstrapPhase.Ready
                          case _ =>
                            BootstrapPhase.ForwardCatchUp
                      forwardStore.put(
                        ForwardCatchUpMaterialization(
                          chainId = chainId,
                          anchor = anchor.snapshotAnchor,
                          applied = forward.applied,
                          queued = forward.queued,
                          controlBatches = forward.controlBatches,
                          frontierBlockId = forward.frontierBlockId,
                          frontierHeight = forward.frontierHeight,
                          voteReadiness = forward.voteReadiness,
                          lastUpdatedAt = startedAt,
                        ),
                      ) *>
                        updateForwardState(
                          chainId = chainId,
                          phase = phase,
                          voteReadiness = forward.voteReadiness,
                        ) *>
                        historicalBackfill.start(
                          chainId = chainId,
                          sessions = sessions,
                          anchor = anchor.snapshotAnchor,
                          now = startedAt,
                        ) *>
                        current.map: diagnostics =>
                          BootstrapCoordinatorResult(
                            anchor = anchor,
                            snapshot = snapshotResult,
                            forwardCatchUp = forward,
                            diagnostics = diagnostics,
                          ).asRight[BootstrapCoordinatorFailure]

  private def fetchVerified(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
  ): F[Either[
    BootstrapCoordinatorFailure,
    Option[(FinalizedAnchorSuggestion, Vector[FinalizedAnchorSafetyFault])],
  ]] =
    sessions
      .traverse(finalizedAnchorSuggestions.bestFinalized(_, chainId))
      .flatMap: responses =>
        val candidateSuggestions =
          responses.collect { case Right(Some(suggestion)) => suggestion }
        val surfacedFaults =
          responses.collect {
            case Left(CanonicalRejection.BackfillUnavailable(reason, detail))
                if reason === "conflictingFinalizedSuggestion" =>
              FinalizedAnchorSafetyFault(
                chainId = chainId,
                height = BlockHeight.Genesis,
                conflictingAnchors = Vector.empty[SnapshotAnchor],
              )
          }
        HotStuffFinalizedAnchorVerifier
          .selectHighestVerified(candidateSuggestions, validatorSetLookup)
          .map:
            case Left(fault) =>
              BootstrapCoordinatorFailure(
                reason = fault.reason,
                detail = fault.detail,
              ).asLeft[Option[
                (
                    FinalizedAnchorSuggestion,
                    Vector[
                      FinalizedAnchorSafetyFault,
                    ],
                ),
              ]]
            case Right(None) =>
              Option
                .empty[
                  (
                      FinalizedAnchorSuggestion,
                      Vector[
                        FinalizedAnchorSafetyFault,
                      ],
                  ),
                ]
                .asRight[BootstrapCoordinatorFailure]
            case Right(Some(suggestion)) =>
              (suggestion, surfacedFaults).some
                .asRight[BootstrapCoordinatorFailure]

  private def readReplay(
      chainId: ChainId,
      anchor: FinalizedAnchorSuggestion,
      sessions: Vector[BootstrapSessionBinding],
  ): F[Either[BootstrapCoordinatorFailure, Vector[Proposal]]] =
    val replayPageSize = 256
    val replayMaxLimit = replayPageSize * 16
    val replayStartHeight =
      BlockHeight(BigNat.add(anchor.anchorHeight.toBigNat, BigNat.One))

    def dedupeReplayedProposals(
        proposals: Vector[Proposal],
    ): Vector[Proposal] =
      proposals
        .foldLeft(Map.empty[ProposalId, Proposal]): (acc, proposal) =>
          acc.updatedWith(proposal.proposalId):
            case current @ Some(_) => current
            case None              => Some(proposal)
        .values
        .toVector

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def loop(
        limit: Int,
    ): F[Either[BootstrapCoordinatorFailure, Vector[Proposal]]] =
      sessions
        .traverse: session =>
          proposalReplay.readNext(
            session = session,
            chainId = chainId,
            anchorBlockId = anchor.anchorBlockId,
            nextHeight = replayStartHeight,
            limit = limit,
          )
        .flatMap: batches =>
          val successfulBatches =
            batches.collect { case Right(proposals) =>
              dedupeReplayedProposals(proposals)
                .filter(proposal =>
                  Ordering[BlockHeight]
                    .gteq(proposal.block.height, replayStartHeight),
                )
                .sortBy(proposal =>
                  (proposal.block.height, proposal.proposalId.toHexLower),
                )
            }
          val successful =
            dedupeReplayedProposals(successfulBatches.flatten)
              .sortBy(proposal =>
                (proposal.block.height, proposal.proposalId.toHexLower),
              )
          val highestHeight =
            successful.iterator
              .map(_.block.height)
              .maxOption(using Ordering[BlockHeight])
          val saturated =
            successfulBatches.exists(_.sizeCompare(limit) >= 0)

          // `readNext` is keyed only by `nextHeight`, so paging by advancing a
          // shared height cursor can skip proposals from peers that returned a
          // shorter prefix. Re-read from the anchor with a larger limit instead.
          if saturated && limit < replayMaxLimit then
            loop(Math.min(limit * 2, replayMaxLimit))
          else if saturated then
            BootstrapCoordinatorFailure(
              reason = "proposalReplayPageBudgetExceeded",
              detail = Some(
                highestHeight.getOrElse(replayStartHeight).render,
              ),
            ).asLeft[Vector[Proposal]].pure[F]
          else if batches.isEmpty || batches.exists {
              case Right(_) => true
              case Left(_)  => false
            }
          then successful.asRight[BootstrapCoordinatorFailure].pure[F]
          else
            val firstFailure =
              batches.collectFirst { case Left(rejection) =>
                BootstrapCoordinatorFailure(rejection.reason, rejection.detail)
              }
            firstFailure
              .getOrElse(
                BootstrapCoordinatorFailure("proposalReplayUnavailable", None),
              )
              .asLeft[Vector[Proposal]]
              .pure[F]

    loop(limit = replayPageSize)

  private def pinAnchor(
      chainId: ChainId,
      anchor: FinalizedAnchorSuggestion,
  ): F[Unit] =
    ref.update: state =>
      val chainState =
        state.chains.getOrElse(chainId, BootstrapCoordinatorChainState.empty)
      state.copy(
        chains = state.chains.updated(
          chainId,
          chainState.copy(
            bestFinalized = Some(anchor.snapshotAnchor),
            selectedAnchor = Some(anchor.snapshotAnchor),
            pinnedAnchor = Some(anchor.snapshotAnchor),
            pinnedSuggestion = Some(anchor),
            voteReadiness = BootstrapVoteReadiness.Held("snapshotPending"),
          ),
        ),
      )

  private def pinnedSuggestion(
      chainId: ChainId,
  ): F[Option[FinalizedAnchorSuggestion]] =
    ref.get.map(_.chains.get(chainId).flatMap(_.pinnedSuggestion))

  private def updatePhase(
      chainId: ChainId,
      phase: BootstrapPhase,
      lastFailure: Option[String],
  ): F[Unit] =
    ref.update: state =>
      val chainState =
        state.chains.getOrElse(chainId, BootstrapCoordinatorChainState.empty)
      state.copy(
        phase = phase,
        chains = state.chains.updated(chainId, chainState),
        lastFailure = lastFailure.orElse(state.lastFailure),
      )

  private def updateForwardState(
      chainId: ChainId,
      phase: BootstrapPhase,
      voteReadiness: BootstrapVoteReadiness,
  ): F[Unit] =
    ref.update: state =>
      val chainState =
        state.chains.getOrElse(chainId, BootstrapCoordinatorChainState.empty)
      state.copy(
        phase = phase,
        chains = state.chains.updated(
          chainId,
          chainState.copy(voteReadiness = voteReadiness),
        ),
        retryAttempts = 0,
        nextRetryAt = None,
        lastFailure = None,
      )

  private def updateNoCandidate(
      chainId: ChainId,
      now: Instant,
  ): F[Unit] =
    ref.update: state =>
      val attempts  = state.retryAttempts + 1
      val nextRetry = Some(retryPolicy.nextRetryAt(now, attempts))
      val chainState =
        state.chains.getOrElse(chainId, BootstrapCoordinatorChainState.empty)
      state.copy(
        phase = BootstrapPhase.Discovery,
        chains = state.chains.updated(
          chainId,
          chainState.copy(bestFinalized = None),
        ),
        retryAttempts = attempts,
        nextRetryAt = nextRetry,
        lastFailure = Some("noVerifiableFinalizedAnchor"),
      )

  private def updateFailure(
      chainId: ChainId,
      now: Instant,
      reason: String,
      detail: Option[String],
      faults: Vector[FinalizedAnchorSafetyFault],
  ): F[Unit] =
    ref.update: state =>
      val attempts  = state.retryAttempts + 1
      val nextRetry = Some(retryPolicy.nextRetryAt(now, attempts))
      val chainState =
        state.chains.getOrElse(chainId, BootstrapCoordinatorChainState.empty)
      state.copy(
        phase = BootstrapPhase.Discovery,
        chains = state.chains.updated(
          chainId,
          chainState.copy(finalizationSafetyFaults = faults),
        ),
        retryAttempts = attempts,
        nextRetryAt = nextRetry,
        lastFailure = Some(detail.fold(reason)(value => reason + ":" + value)),
      )

  private def renderDiagnostics(
      state: BootstrapCoordinatorState,
      historicalBackfillStatus: HistoricalBackfillStatus,
  ): BootstrapDiagnostics =
    BootstrapDiagnostics(
      phase = state.phase,
      chains = state.chains.view
        .mapValues: chainState =>
          BootstrapChainDiagnostics(
            bestFinalized = chainState.bestFinalized,
            selectedAnchor = chainState.selectedAnchor,
            pinnedAnchor = chainState.pinnedAnchor,
            voteReadiness = chainState.voteReadiness,
            finalizationSafetyFaults = chainState.finalizationSafetyFaults,
          )
        .toMap,
      retryAttempts = state.retryAttempts,
      nextRetryAt = state.nextRetryAt,
      lastFailure = state.lastFailure,
      historicalBackfill = historicalBackfillStatus,
    )
